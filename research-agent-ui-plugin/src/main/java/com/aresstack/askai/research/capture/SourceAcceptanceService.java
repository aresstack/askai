package com.aresstack.askai.research.capture;

import com.aresstack.askai.research.knowledge.processing.KnowledgeProcessingScheduler;
import com.aresstack.askai.research.sources.ResearchSourceRecord;
import com.aresstack.askai.research.sources.SourceQuery;
import com.aresstack.askai.research.sources.SourceRelevance;
import com.aresstack.askai.research.sources.SourceReliability;
import com.aresstack.askai.research.sources.SourceStatus;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The ONLY path from a visited capture to a persistent source. Acceptance is atomic (the source record is
 * fully built, then committed, THEN indexed — an index failure never loses the source, it just marks the
 * index STALE) and idempotent (re-accepting the same capture returns ALREADY_ACCEPTED with the existing id;
 * no second source, no second index entry).
 *
 * <p>Dedup rules (canonical URL + content hash, no near-duplicate/embedding search in the MVP): same
 * canonical URL + same hash → identical → ALREADY_ACCEPTED; different URL + same hash → content duplicate →
 * accepted but flagged DUPLICATE; same URL + different hash → a new revision of the page → a fresh source.
 * The result stays compact for the model: status, source id, title, passage count, duplicate flag — never
 * HTML or the full extracted text.</p>
 */
public final class SourceAcceptanceService {

    /** Commits a fully-built record to the source store (in-memory or file-backed). */
    public interface SourceCreator {
        void create(ResearchSourceRecord record);
    }

    public enum Status { ACCEPTED, ALREADY_ACCEPTED, UNKNOWN_CAPTURE }

    public static final class Result {
        public final Status status;
        public final String sourceId;
        public final String title;
        public final int passageCount;
        public final boolean duplicate;
        public final boolean indexStale;

        Result(Status status, String sourceId, String title, int passageCount,
               boolean duplicate, boolean indexStale) {
            this.status = status;
            this.sourceId = sourceId;
            this.title = title;
            this.passageCount = passageCount;
            this.duplicate = duplicate;
            this.indexStale = indexStale;
        }

        /** Compact tool-facing rendering — no HTML, no full text. */
        public String render() {
            return "status=" + status + " source_id=" + (sourceId == null ? "-" : sourceId)
                    + " title=\"" + title + "\" passage_count=" + passageCount
                    + " duplicate=" + duplicate + (indexStale ? " index=STALE" : "");
        }
    }

    private final CaptureStore captures;
    private final com.aresstack.askai.research.sources.ResearchSourceRepository repository;
    private final SourceCreator creator;
    private final ResearchSearchIndex index;
    private final AtomicLong sourceIds = new AtomicLong();
    /** captureId → sourceId of a completed acceptance (idempotency). */
    private final Map<String, String> acceptedByCapture = new HashMap<String, String>();
    /**
     * The knowledge-pipeline acceptance hook (§3): the neutral scheduler port enqueued once per accepted
     * capture, AFTER the commit and INDEPENDENT of the source-level index (an index failure must not stop it).
     * Never fired for ALREADY_ACCEPTED / parked / unknown captures, so a duplicate acceptance never enqueues
     * twice. The plugin holds ONLY this port — no queue/worker/NLP/embedding logic lives here.
     */
    private volatile KnowledgeProcessingScheduler knowledgeScheduler = KnowledgeProcessingScheduler.NONE;

    /**
     * Plan PR 14: names the phase and the search plan entry of the search a source came from. The host resolves
     * both from the {@code search_request_id} the manual search port already sends; a new source carries them.
     */
    public interface SourceTagger {
        /** The phase the search ran in ("" when unknown). */
        String phaseFor(String searchRequestId);

        /** The search plan entry the search belongs to ("" for a typed search). */
        String planEntryFor(String searchRequestId);

        SourceTagger NONE = new SourceTagger() {
            public String phaseFor(String searchRequestId) {
                return "";
            }

            public String planEntryFor(String searchRequestId) {
                return "";
            }
        };
    }

    private volatile SourceTagger tagger = SourceTagger.NONE;

    /** Bind the phase/plan-entry tagger (plan PR 14); {@code null} restores the no-op. */
    public void setSourceTagger(SourceTagger tagger) {
        this.tagger = tagger == null ? SourceTagger.NONE : tagger;
    }

    private String phaseTag(String searchRequestId) {
        try {
            return nz(tagger.phaseFor(searchRequestId == null ? "" : searchRequestId.trim()));
        } catch (RuntimeException unknown) {
            return "";
        }
    }

    private String planEntryTag(String searchRequestId) {
        try {
            return nz(tagger.planEntryFor(searchRequestId == null ? "" : searchRequestId.trim()));
        } catch (RuntimeException unknown) {
            return "";
        }
    }

    private static String nz(String value) {
        return value == null ? "" : value.trim();
    }

    public SourceAcceptanceService(CaptureStore captures,
                                   com.aresstack.askai.research.sources.ResearchSourceRepository repository,
                                   SourceCreator creator, ResearchSearchIndex index,
                                   long sourceIdSeed) {
        this(captures, repository, creator, index);
        // Restore-safe ids: continue AFTER the highest persisted source number so a resumed
        // project never reissues an existing source id.
        this.sourceIds.set(sourceIdSeed);
    }

    public SourceAcceptanceService(CaptureStore captures,
                                   com.aresstack.askai.research.sources.ResearchSourceRepository repository,
                                   SourceCreator creator, ResearchSearchIndex index) {
        this.captures = captures;
        this.repository = repository;
        this.creator = creator;
        this.index = index;
    }

    public synchronized Result accept(String captureId) {
        return accept(captureId, "");
    }

    /**
     * As {@link #accept(String)} but records the USER web-search query that found the source, so the host can
     * later know (across restarts) which queries were already searched. Agent acceptance passes "".
     *
     * <p>When a PARKED record (a reranked candidate written before the visit, see {@link #park}) already
     * exists for this page's canonical URL, the visit ENRICHES it in place — its empty full text is filled
     * and it is promoted PARKED→NEW — instead of creating a second source. The parked reranker score is kept.
     */
    public synchronized Result accept(String captureId, String searchQuery) {
        return accept(captureId, searchQuery, false);
    }

    /** As {@link #accept(String, String)} but also records the HUD ⭐ (the user marked this page relevant). */
    public synchronized Result accept(String captureId, String searchQuery, boolean userRelevant) {
        return accept(captureId, searchQuery, userRelevant, "");
    }

    /**
     * As {@link #accept(String, String, boolean)} but with the AUTHORITATIVE language snapshot of the search
     * that found this capture ("en"/"de") - persisted on the knowledge-processing job so the sentence-model
     * world stays unambiguous even after a restart. Empty = no snapshot (agent path / legacy): the scheduler's
     * composition root substitutes the session language.
     */
    public synchronized Result accept(String captureId, String searchQuery, boolean userRelevant,
                                      String languageCode) {
        return accept(captureId, searchQuery, userRelevant, languageCode, "");
    }

    /**
     * As above but with the manual-search REQUEST id that found this capture — persisted on the
     * source so a review can be scoped to exactly ONE search instead of a time window.
     */
    public synchronized Result accept(String captureId, String searchQuery, boolean userRelevant,
                                      String languageCode, String searchRequestId) {
        // Idempotency first: a completed acceptance always returns the same source id.
        String existing = acceptedByCapture.get(captureId);
        if (existing != null) {
            ResearchSourceRecord record = repository.get(existing);
            return new Result(Status.ALREADY_ACCEPTED, existing,
                    record == null ? "" : record.getTitle(), 0,
                    record != null && record.getStatus() == SourceStatus.DUPLICATE, false);
        }
        VisitedCapture capture = captures.get(captureId);
        if (capture == null) {
            return new Result(Status.UNKNOWN_CAPTURE, null, "", 0, false, false);
        }

        // Dedup against existing sources; a parked record for the same URL is the enrich target.
        boolean contentDuplicate = false;
        ResearchSourceRecord parkedMatch = null;
        for (ResearchSourceRecord source : repository.find(SourceQuery.all())) {
            boolean sameUrl = capture.getCanonicalUrl().equals(
                    CaptureStore.canonicalize(source.getUrl()));
            if (sameUrl && source.isParked()) {
                parkedMatch = source; // a parked candidate we are now visiting: enrich it below
                continue;
            }
            boolean sameHash = capture.getContentHash().equals(source.getChecksum());
            if (sameUrl && sameHash) {
                // Identical page already accepted: idempotent outcome against the existing source.
                acceptedByCapture.put(captureId, source.getSourceId());
                return new Result(Status.ALREADY_ACCEPTED, source.getSourceId(),
                        source.getTitle(), 0, source.getStatus() == SourceStatus.DUPLICATE, false);
            }
            if (sameHash) {
                contentDuplicate = true; // different URL, same content
            }
            // sameUrl && !sameHash → a changed page: falls through to a fresh source (new revision).
        }

        // Extract (already-clean text; the chain only normalizes residues and counts passages).
        DocumentExtractor.ExtractedDocument doc = DocumentExtractor.Chain.extract(
                new DocumentExtractor.DocumentInput("text/plain", "", capture.getText()));
        String title = capture.getTitle().isEmpty() ? doc.getTitle() : capture.getTitle();

        // Build the record (enrich the parked candidate in place, or create a fresh source), then commit
        // atomically; the capture's assessment travels along. The full page text is stored on the record.
        final String sourceId;
        final ResearchSourceRecord record;
        if (parkedMatch != null) {
            sourceId = parkedMatch.getSourceId();
            record = parkedMatch.toBuilder()
                    .title(parkedMatch.getTitle().isEmpty() ? title : parkedMatch.getTitle())
                    .sourceType(capture.getSourceType() == null ? parkedMatch.getSourceType()
                            : capture.getSourceType())
                    .capturedAt(capture.getCapturedAt())
                    .fullText(capture.getText())
                    .status(contentDuplicate ? SourceStatus.DUPLICATE : SourceStatus.NEW)
                    .checksum(capture.getContentHash())
                    .revision(parkedMatch.getRevision() + 1L)
                    .searchQuery(parkedMatch.getSearchQuery().isEmpty()
                            ? (searchQuery == null ? "" : searchQuery.trim()) : parkedMatch.getSearchQuery())
                    .searchRequestId(parkedMatch.getSearchRequestId().isEmpty()
                            ? (searchRequestId == null ? "" : searchRequestId.trim())
                            : parkedMatch.getSearchRequestId())
                    .userRelevant(userRelevant || parkedMatch.isUserRelevant()) // never clobber a prior ⭐
                    .phaseTag(parkedMatch.getPhaseTag().isEmpty() ? phaseTag(searchRequestId)
                            : parkedMatch.getPhaseTag())
                    .planEntryId(parkedMatch.getPlanEntryId().isEmpty() ? planEntryTag(searchRequestId)
                            : parkedMatch.getPlanEntryId())
                    .build();
        } else {
            sourceId = "source-" + sourceIds.incrementAndGet();
            record = ResearchSourceRecord.builder(sourceId)
                    .title(title)
                    .origin(hostOf(capture.getCanonicalUrl()))
                    .url(capture.getUrl())
                    .sourceType(capture.getSourceType() == null ? "web" : capture.getSourceType())
                    .capturedAt(capture.getCapturedAt())
                    .comment(capture.getAssessmentNote() == null ? "" : capture.getAssessmentNote())
                    .relevance(parseRelevance(capture.getRelevance()))
                    .reliability(SourceReliability.UNKNOWN)
                    .status(contentDuplicate ? SourceStatus.DUPLICATE : SourceStatus.NEW)
                    .snapshotReference("")
                    .fullText(capture.getText())
                    .checksum(capture.getContentHash())
                    .revision(1L)
                    .searchQuery(searchQuery == null ? "" : searchQuery.trim())
                    .searchRequestId(searchRequestId == null ? "" : searchRequestId.trim())
                    .userRelevant(userRelevant)
                    .phaseTag(phaseTag(searchRequestId))
                    .planEntryId(planEntryTag(searchRequestId))
                    .build();
        }
        creator.create(record);
        acceptedByCapture.put(captureId, sourceId);

        // Index AFTER the commit: a failure marks the index stale but never loses the source.
        boolean stale = false;
        try {
            index.index(new ResearchSearchIndex.ResearchSourceDocument(sourceId, title, doc.getText()));
        } catch (RuntimeException ex) {
            stale = true;
        }
        // Enqueue for knowledge processing LAST and independent of the index outcome (§3): a Lucene failure
        // above already only marked the index stale; the accepted capture must still be enqueued. Best-effort.
        scheduleKnowledgeProcessing(captureId, sourceId, languageCode);
        return new Result(Status.ACCEPTED, sourceId, title, doc.getPassageCount(), contentDuplicate, stale);
    }

    /** Bind the knowledge-processing scheduler (§3). No-op default keeps acceptance decoupled in tests. */
    public void setKnowledgeProcessingScheduler(KnowledgeProcessingScheduler scheduler) {
        this.knowledgeScheduler = scheduler == null ? KnowledgeProcessingScheduler.NONE : scheduler;
    }

    private void scheduleKnowledgeProcessing(String captureId, String sourceId, String languageCode) {
        try {
            knowledgeScheduler.enqueue(captureId, sourceId, languageCode);
        } catch (RuntimeException ex) {
            // The scheduler is a downstream reaction: its failure must never fail (or roll back) acceptance.
        }
    }

    /** Outcome of {@link #park}: the source id and whether a new parked record was actually created. */
    public static final class ParkResult {
        public final String sourceId;
        public final boolean created;

        ParkResult(String sourceId, boolean created) {
            this.sourceId = sourceId;
            this.created = created;
        }

        public String render() {
            return "status=" + (created ? "PARKED" : "ALREADY_PRESENT") + " source_id=" + sourceId;
        }
    }

    /**
     * Park a reranked search candidate in the store BEFORE the page is visited: a record carrying the search
     * excerpt and the reranker score, with an empty full text (status {@link SourceStatus#PARKED}). Visiting
     * the page later enriches it (see {@link #accept}). Idempotent per canonical URL: a URL already present
     * (parked or accepted) is not parked again.
     */
    public synchronized ParkResult park(String url, String title, String excerpt, double rerankScore,
                                        String searchQuery) {
        return park(url, title, excerpt, rerankScore, searchQuery, "");
    }

    /** As above but with the manual-search REQUEST id that produced this candidate. */
    public synchronized ParkResult park(String url, String title, String excerpt, double rerankScore,
                                        String searchQuery, String searchRequestId) {
        String canonical = CaptureStore.canonicalize(url);
        for (ResearchSourceRecord source : repository.find(SourceQuery.all())) {
            if (canonical.equals(CaptureStore.canonicalize(source.getUrl()))) {
                return new ParkResult(source.getSourceId(), false); // already parked or accepted
            }
        }
        String sourceId = "source-" + sourceIds.incrementAndGet();
        ResearchSourceRecord record = ResearchSourceRecord.builder(sourceId)
                .title(title == null ? "" : title)
                .origin(hostOf(canonical))
                .url(url)
                .sourceType("web")
                .excerpt(excerpt == null ? "" : excerpt)
                .fullText("") // parked: filled only when the page is successfully visited
                .rerankScore(rerankScore)
                .relevance(SourceRelevance.UNKNOWN)
                .reliability(SourceReliability.UNKNOWN)
                .status(SourceStatus.PARKED)
                .checksum("")
                .revision(1L)
                .searchQuery(searchQuery == null ? "" : searchQuery.trim())
                .searchRequestId(searchRequestId == null ? "" : searchRequestId.trim())
                .phaseTag(phaseTag(searchRequestId))
                .planEntryId(planEntryTag(searchRequestId))
                .build();
        creator.create(record);
        return new ParkResult(sourceId, true);
    }

    private static SourceRelevance parseRelevance(String v) {
        if (v == null) {
            return SourceRelevance.UNKNOWN;
        }
        try {
            return SourceRelevance.valueOf(v.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return SourceRelevance.UNKNOWN;
        }
    }

    private static String hostOf(String canonicalUrl) {
        int i = canonicalUrl.indexOf("://");
        if (i < 0) {
            return canonicalUrl;
        }
        String rest = canonicalUrl.substring(i + 3);
        int slash = rest.indexOf('/');
        return slash < 0 ? rest : rest.substring(0, slash);
    }
}
