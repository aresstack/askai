package com.aresstack.askai.research.knowledge.processing.live;

import com.aresstack.askai.research.domain.Passage;
import com.aresstack.askai.research.knowledge.ClusteringMonitor;
import com.aresstack.askai.research.knowledge.TopicDendrogram;
import com.aresstack.askai.research.knowledge.live.LiveOutlineProjection;
import com.aresstack.askai.research.knowledge.live.LiveOutlineProjectionBuilder;
import com.aresstack.askai.research.knowledge.live.LiveTopicProjection;
import com.aresstack.askai.research.knowledge.live.LiveTopicTree;
import com.aresstack.askai.research.knowledge.topic.AnalysisPassage;
import com.aresstack.askai.research.knowledge.topic.EvidenceReport;
import com.aresstack.askai.research.knowledge.topic.TopicAnalysis;
import com.aresstack.askai.research.knowledge.topic.TopicAnalysisSettings;
import com.aresstack.askai.research.knowledge.topic.TopicAnalyzer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * #42 — the ONE phase-neutral topic-discovery use case: read the active corpus, run the
 * EXISTING {@link LiveOutlineProjectionBuilder#discoverTopics} (the established HAC/cosine
 * math — deliberately no second clustering implementation, no concept-specific variant) and
 * persist the result as a fingerprint-pinned snapshot. Phase 1 consumes the snapshot for
 * concept ideas in the chat; the outline build consumes the SAME snapshot for phase 3 — when
 * the discovery improves later, both consumers profit automatically.
 *
 * <p>D6: it is also the ONE place of the shared topic analysis of Phase 2 and Phase 3
 * ({@link #analyze}): the same clustering run with its merge tree, measured by the
 * {@link TopicAnalyzer} against the concept and the search fence, published as an immutable,
 * persisted {@link TopicAnalysisSnapshot}. Phase 2 reads {@link #latestAnalysis()} to steer the
 * research; Phase 3 takes {@link #analysisFor} and builds the outline only on it. Background
 * runs come from the session's analysis runner ({@link #analyzeIfDue()}), never per page.</p>
 *
 * <p>Discovery is PURE CPU over already-persisted vectors — it never calls the embedding
 * endpoint, so a background refresh cannot compete with the foreground GPU/LLM path by
 * construction (the resource guard of the issue).</p>
 */
public final class SharedTopicDiscovery {

    /** The corpus seam (reader + source filter bound by the session factory). */
    public interface CorpusSource {
        ActiveKnowledgeCorpusReader.Corpus read();
    }

    private final CorpusSource corpus;
    private final LiveOutlineProjectionBuilder builder;
    private final FileTopicSnapshotStore store;
    private final String embeddingFingerprint;
    private final int corpusLimit;

    /** PR 20: the default corpus limit, about 144 MB as a full float matrix of 768-dimensional vectors. */
    public static final int DEFAULT_CORPUS_LIMIT = 6000;

    public SharedTopicDiscovery(CorpusSource corpus, LiveOutlineProjectionBuilder builder,
                                FileTopicSnapshotStore store, String embeddingFingerprint) {
        this(corpus, builder, store, embeddingFingerprint, DEFAULT_CORPUS_LIMIT);
    }

    public SharedTopicDiscovery(CorpusSource corpus, LiveOutlineProjectionBuilder builder,
                                FileTopicSnapshotStore store, String embeddingFingerprint,
                                int corpusLimit) {
        if (corpusLimit < 2) {
            throw new IllegalArgumentException("corpus limit must be at least 2: " + corpusLimit);
        }
        this.corpusLimit = corpusLimit;
        this.corpus = corpus;
        this.builder = builder;
        this.store = store;
        this.embeddingFingerprint = embeddingFingerprint == null ? "" : embeddingFingerprint;
    }

    /** The atomically pinned pair a combining consumer builds from — never two reads. */
    public static final class Pinned {
        public final ActiveKnowledgeCorpusReader.Corpus corpus;
        public final FileTopicSnapshotStore.TopicSnapshot snapshot;
        /** PR 20: passages of the active corpus left out because it exceeded the corpus limit. */
        public final int omittedPassages;

        Pinned(ActiveKnowledgeCorpusReader.Corpus corpus,
               FileTopicSnapshotStore.TopicSnapshot snapshot, int omittedPassages) {
            this.corpus = corpus;
            this.snapshot = snapshot;
            this.omittedPassages = omittedPassages;
        }
    }

    /**
     * ONE deterministic discovery run over the CURRENT corpus: cluster (existing math),
     * fingerprint, persist, return. Topics only — never an outline, never a concept
     * mutation, never a phase change.
     */
    public FileTopicSnapshotStore.TopicSnapshot refresh(long nowMillis) {
        return refreshPinned(nowMillis).snapshot;
    }

    /**
     * The corpus read, the clustering AND the snapshot write happen under ONE lock, and the
     * corpus used is returned WITH the topics: a consumer that combines both (the outline
     * build: passages + topics) gets one coherent pair, and two concurrent refreshes can
     * never write an older corpus's snapshot over a newer one (reads outside the lock did
     * exactly that).
     */
    public Pinned refreshPinned(long nowMillis) {
        return refreshPinned(nowMillis, ClusteringMonitor.NONE);
    }

    /**
     * PR 20: the same run with progress and cancel. A cancelled run throws
     * {@link ClusteringMonitor.ClusteringCancelledException} and persists nothing. Above the corpus limit the
     * best passages per source are kept ({@link CorpusLimit}) and {@link Pinned#omittedPassages} says how many
     * were left out; the snapshot's corpus fingerprint stays the one of the whole corpus, so a limited run is
     * not reported stale.
     */
    public synchronized Pinned refreshPinned(long nowMillis, ClusteringMonitor monitor) {
        ActiveKnowledgeCorpusReader.Corpus whole = corpus.read();
        ActiveKnowledgeCorpusReader.Corpus current = CorpusLimit.apply(whole, corpusLimit);
        int omitted = whole.getPassages().size() - current.getPassages().size();
        TopicAnalysisRequest request = currentRequest();
        List<LiveTopicProjection> topics = clustering(request == null ? null : request.getSettings())
                .discoverTopics(current.getPassages(), current.getVectors(), monitor, splitRule(request, current));
        if (monitor != null && monitor.isCancelled()) {
            throw new ClusteringMonitor.ClusteringCancelledException(); // never overwrite on a late cancel
        }
        FileTopicSnapshotStore.TopicSnapshot previous = store.load();
        FileTopicSnapshotStore.TopicSnapshot snapshot = new FileTopicSnapshotStore.TopicSnapshot(
                (previous == null ? 0L : previous.revision) + 1L,
                corpusFingerprintOf(whole), embeddingFingerprint, nowMillis, topics);
        store.save(snapshot);
        return new Pinned(current, snapshot, omitted);
    }

    /** The persisted snapshot, or {@code null} when none exists yet. Pure read. */
    public FileTopicSnapshotStore.TopicSnapshot current() {
        return store.load();
    }

    /**
     * Whether the persisted snapshot still describes the CURRENT corpus + embedding world.
     * {@code true} also when no snapshot exists but the corpus has content. Pure read —
     * never triggers a rebuild.
     */
    public boolean isStale() {
        ActiveKnowledgeCorpusReader.Corpus current = corpus.read();
        FileTopicSnapshotStore.TopicSnapshot persisted = store.load();
        if (persisted == null) {
            return !current.getPassages().isEmpty();
        }
        return !corpusFingerprintOf(current).equals(persisted.corpusFingerprint)
                || !embeddingFingerprint.equals(persisted.embeddingFingerprint);
    }

    // ------------------------------------------------------------------ D6: the shared topic analysis

    /** Bumped when the analysis code changes its results; part of every snapshot id. */
    public static final int ANALYSIS_VERSION = 1;

    /** The host's side of a run: concept, fence, eligibility, pending sources and settings. */
    public interface AnalysisInputs {
        /** The inputs of a run now, or {@code null} while there is nothing to analyse against (no approved concept). */
        TopicAnalysisRequest request() throws Exception;
    }

    private volatile AnalysisInputs analysisInputs;
    private volatile KnowledgeProjectionInvalidator analysisTrigger = KnowledgeProjectionInvalidator.NONE;
    private volatile TopicAnalysisSnapshot latestAnalysis;
    private volatile boolean cycleBoundaryRequested;
    private final CopyOnWriteArrayList<Consumer<TopicAnalysisSnapshot>> analysisListeners =
            new CopyOnWriteArrayList<Consumer<TopicAnalysisSnapshot>>();

    public void setAnalysisInputs(AnalysisInputs inputs) {
        this.analysisInputs = inputs;
    }

    /** The session's debounced, single-flight analysis runner (it calls {@link #analyzeIfDue()}). */
    public void setAnalysisTrigger(KnowledgeProjectionInvalidator trigger) {
        this.analysisTrigger = trigger == null ? KnowledgeProjectionInvalidator.NONE : trigger;
    }

    public void addAnalysisListener(Consumer<TopicAnalysisSnapshot> listener) {
        if (listener != null) {
            analysisListeners.addIfAbsent(listener);
        }
    }

    public void removeAnalysisListener(Consumer<TopicAnalysisSnapshot> listener) {
        analysisListeners.remove(listener);
    }

    /** The current analysis (Phase 2 reads this), or {@code null} before the first run of this session. */
    public TopicAnalysisSnapshot latestAnalysis() {
        return latestAnalysis;
    }

    /** Every persisted analysis of the project, oldest first (why an area was thin and later covered). */
    public List<FileTopicSnapshotStore.AnalysisRecord> analysisHistory() {
        return store.analysisHistory();
    }

    /**
     * A research cycle ended: the runner analyses once the debounce window is quiet, if the eligible corpus changed
     * since the latest snapshot (and has the minimum size). Never blocks the caller.
     */
    public void requestAtCycleBoundary() {
        cycleBoundaryRequested = true;
        analysisTrigger.knowledgeChanged();
    }

    /**
     * One run now on the calling thread, under the discovery lock (corpus read, clustering and snapshot are one
     * coherent state), persisted and published.
     * @throws IllegalStateException when there is nothing to analyse against yet
     * @throws ClusteringMonitor.ClusteringCancelledException when the monitor cancels (nothing is published)
     */
    public synchronized TopicAnalysisSnapshot analyze(ClusteringMonitor monitor) throws Exception {
        TopicAnalysisRequest request = request();
        return run(request, read(request), monitor == null ? ClusteringMonitor.NONE : monitor);
    }

    /**
     * Phase 3: one consistent snapshot for the outline: the latest one when it still describes the current state
     * (same id), otherwise a fresh run. The caller works only on the returned snapshot.
     */
    public synchronized TopicAnalysisSnapshot analysisFor(ClusteringMonitor monitor) throws Exception {
        TopicAnalysisRequest request = request();
        ActiveKnowledgeCorpusReader.Corpus corpus = read(request);
        TopicAnalysisSnapshot current = latestAnalysis;
        if (current != null && current.getId().equals(snapshotId(corpusFingerprintOf(corpus), request))) {
            return current;
        }
        return run(request, corpus, monitor == null ? ClusteringMonitor.NONE : monitor);
    }

    /**
     * The analysis runner's step: runs when the eligible corpus has the minimum size and grew by the configured
     * number of passages since the latest snapshot, or at a requested cycle boundary when it changed at all.
     * @return the new snapshot, or {@code null} when no run was due
     */
    /**
     * The background run's monitor: a closed session interrupts the runner thread ({@code shutdownNow}), and the
     * clustering and the analysis stop at their next check instead of running on against torn-down resources.
     */
    static final ClusteringMonitor INTERRUPTIBLE = new ClusteringMonitor() {
        public void progress(String stage, long done, long total) {
        }

        public boolean isCancelled() {
            return Thread.currentThread().isInterrupted();
        }
    };

    public synchronized TopicAnalysisSnapshot analyzeIfDue() throws Exception {
        boolean boundary = cycleBoundaryRequested;
        cycleBoundaryRequested = false;
        AnalysisInputs inputs = analysisInputs;
        TopicAnalysisRequest request = inputs == null ? null : inputs.request();
        if (request == null) {
            return null;
        }
        ActiveKnowledgeCorpusReader.Corpus corpus = read(request);
        int passages = corpus.getPassages().size();
        if (passages < request.getMinimumCorpusPassages()) {
            return null;
        }
        TopicAnalysisSnapshot current = latestAnalysis;
        boolean due = current == null
                || passages - current.getPassages() >= request.getEveryNewPassages()
                || (boundary && !current.getId().equals(snapshotId(corpusFingerprintOf(corpus), request)));
        return due ? run(request, corpus, INTERRUPTIBLE) : null;
    }

    private TopicAnalysisRequest request() throws Exception {
        AnalysisInputs inputs = analysisInputs;
        TopicAnalysisRequest request = inputs == null ? null : inputs.request();
        if (request == null) {
            throw new IllegalStateException("There is no approved concept to analyse the research against.");
        }
        return request;
    }

    /** The corpus of the request: the discovery's corpus, narrowed to the eligible sources (D4). */
    private ActiveKnowledgeCorpusReader.Corpus read(TopicAnalysisRequest request) {
        ActiveKnowledgeCorpusReader.Corpus whole = corpus.read();
        Set<String> eligible = request.getEligibleSourceIds();
        if (eligible == null) {
            return whole;
        }
        List<Passage> passages = new ArrayList<Passage>();
        Map<String, com.aresstack.askai.research.knowledge.EmbeddingPort.EmbeddingVector> vectors =
                new LinkedHashMap<String, com.aresstack.askai.research.knowledge.EmbeddingPort.EmbeddingVector>();
        Map<String, String> sources = new HashMap<String, String>();
        Set<String> kept = new java.util.HashSet<String>();
        for (Passage passage : whole.getPassages()) {
            String sourceId = whole.sourceIdOf(passage.getPassageId());
            if (eligible.contains(sourceId)) {
                passages.add(passage);
                kept.add(passage.getPassageId());
                sources.put(passage.getPassageId(), sourceId);
                vectors.put(passage.getPassageId(), whole.getVectors().get(passage.getPassageId()));
            }
        }
        List<String> relevant = new ArrayList<String>();
        for (String id : whole.getUserRelevantPassageIds()) {
            if (kept.contains(id)) {
                relevant.add(id);
            }
        }
        return new ActiveKnowledgeCorpusReader.Corpus(passages, vectors, relevant, sources);
    }

    /** The builder with the clustering values of the analysis settings: both consumers cut the same topics. */
    private LiveOutlineProjectionBuilder clustering(TopicAnalysisSettings settings) {
        return settings == null ? builder : builder.with(settings.getTopicDistance(), settings.getRepresentatives());
    }

    /** The concept split of the request (no split without a request or without concept nodes). */
    private static TopicDendrogram.SplitRule splitRule(TopicAnalysisRequest request,
                                                       ActiveKnowledgeCorpusReader.Corpus corpus) {
        return request == null ? TopicDendrogram.SplitRule.NONE
                : new TopicAnalyzer(request.getSettings()).conceptSplit(corpus.getVectors(), request.getAnchors());
    }

    /** The request of the current inputs, or {@code null} without inputs (the builder's own values, no split). */
    private TopicAnalysisRequest currentRequest() {
        AnalysisInputs inputs = analysisInputs;
        if (inputs == null) {
            return null;
        }
        try {
            return inputs.request();
        } catch (Exception unavailable) {
            return null;
        }
    }

    private TopicAnalysisSnapshot run(TopicAnalysisRequest request, ActiveKnowledgeCorpusReader.Corpus whole,
                                      ClusteringMonitor monitor) {
        ActiveKnowledgeCorpusReader.Corpus limited = CorpusLimit.apply(whole, request.getCorpusLimit());
        LiveTopicTree tree = clustering(request.getSettings())
                .discoverTopicTree(limited.getPassages(), limited.getVectors(), monitor, splitRule(request, limited));
        List<AnalysisPassage> passages = new ArrayList<AnalysisPassage>();
        Map<String, String> texts = new LinkedHashMap<String, String>();
        for (Passage passage : whole.getPassages()) {
            String sourceId = whole.sourceIdOf(passage.getPassageId());
            passages.add(new AnalysisPassage(passage.getPassageId(), sourceId, request.domainOf(sourceId),
                    passage.getText()));
            texts.put(passage.getPassageId(), passage.getText());
        }
        TopicAnalysis analysis = new TopicAnalyzer(request.getSettings()).analyze(passages, whole.getVectors(), tree,
                request.getAnchors(), request.getFence(), monitor);
        if (monitor.isCancelled()) {
            throw new ClusteringMonitor.ClusteringCancelledException();
        }
        EvidenceReport report = EvidenceReport.of(analysis, texts, request.getKeyTerms());
        String fingerprint = corpusFingerprintOf(whole);
        TopicAnalysisSnapshot snapshot = new TopicAnalysisSnapshot(snapshotId(fingerprint, request),
                ANALYSIS_VERSION, System.currentTimeMillis(), fingerprint, embeddingFingerprint,
                request.getConceptRevision(), request.getFence() == null ? -1L : request.getFence().getRevision(),
                request.getBookTitle(), request.hasBookFrame(), whole.getPassages().size(),
                whole.getPassages().size() - limited.getPassages().size(), request.getPendingSourceIds(), analysis,
                report, texts);
        store.saveAnalysis(snapshot);
        latestAnalysis = snapshot;
        System.err.println("[topic-analysis] " + snapshot.getId() + " passages=" + snapshot.getPassages()
                + " topics=" + analysis.getTopics().size() + " novel=" + snapshot.novelAreas().size()
                + " pendingSources=" + snapshot.getPendingSourceIds().size());
        for (Consumer<TopicAnalysisSnapshot> listener : analysisListeners) {
            try {
                listener.accept(snapshot);
            } catch (RuntimeException ignored) {
                // a broken observer never stops the analysis
            }
        }
        return snapshot;
    }

    private String snapshotId(String corpusFingerprint, TopicAnalysisRequest request) {
        String key = ANALYSIS_VERSION + "|" + corpusFingerprint + "|" + embeddingFingerprint + "|"
                + request.fingerprint();
        return "analysis-" + sha256(key).substring(0, 16);
    }

    private static String sha256(String value) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    /** The SAME corpus-identity math the outline staleness uses — one fingerprint truth. */
    private static String corpusFingerprintOf(ActiveKnowledgeCorpusReader.Corpus corpus) {
        List<String> ids = new ArrayList<String>();
        for (Passage passage : corpus.getPassages()) {
            ids.add(passage.getPassageId());
        }
        return LiveOutlineProjection.corpusFingerprintOf(ids);
    }
}
