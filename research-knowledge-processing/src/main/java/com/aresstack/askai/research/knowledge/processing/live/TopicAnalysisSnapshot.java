package com.aresstack.askai.research.knowledge.processing.live;

import com.aresstack.askai.research.knowledge.topic.EvidenceReport;
import com.aresstack.askai.research.knowledge.topic.TopicAnalysis;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * D6: one completed run of the shared topic analysis, immutable. Phase 2 reads the latest one to steer the research;
 * Phase 3 takes one and builds the outline only on it (its provenance names {@link #getId()}). A later run never
 * changes an earlier snapshot; the history of snapshots stays on disk ({@link FileTopicSnapshotStore}).
 * <p>
 * The id is derived from everything that decides the result: analysis version, corpus, embedding world, concept
 * revision and nodes, scope revision, eligible sources and settings. The same state gives the same id.
 */
public final class TopicAnalysisSnapshot {

    private final String id;
    private final int analysisVersion;
    private final long createdAt;
    private final String corpusFingerprint;
    private final String embeddingFingerprint;
    private final long conceptRevision;
    private final long scopeRevision;
    private final String bookTitle;
    private final boolean bookFrame;
    private final int passages;
    private final int omittedPassages;
    private final Set<String> pendingSourceIds;
    private final TopicAnalysis analysis;
    private final EvidenceReport report;
    private final Map<String, String> texts;

    TopicAnalysisSnapshot(String id, int analysisVersion, long createdAt, String corpusFingerprint,
                          String embeddingFingerprint, long conceptRevision, long scopeRevision, String bookTitle,
                          boolean bookFrame, int passages, int omittedPassages, Set<String> pendingSourceIds,
                          TopicAnalysis analysis, EvidenceReport report, Map<String, String> texts) {
        this.id = id;
        this.analysisVersion = analysisVersion;
        this.createdAt = createdAt;
        this.corpusFingerprint = corpusFingerprint;
        this.embeddingFingerprint = embeddingFingerprint;
        this.conceptRevision = conceptRevision;
        this.scopeRevision = scopeRevision;
        this.bookTitle = bookTitle;
        this.bookFrame = bookFrame;
        this.passages = passages;
        this.omittedPassages = omittedPassages;
        this.pendingSourceIds = Collections.unmodifiableSet(new TreeSet<String>(pendingSourceIds));
        this.analysis = analysis;
        this.report = report;
        this.texts = Collections.unmodifiableMap(texts);
    }

    /** {@code analysis-<16 hex>}. */
    public String getId() {
        return id;
    }

    /** {@link SharedTopicDiscovery#ANALYSIS_VERSION} of the code that made it. */
    public int getAnalysisVersion() {
        return analysisVersion;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public String getCorpusFingerprint() {
        return corpusFingerprint;
    }

    public String getEmbeddingFingerprint() {
        return embeddingFingerprint;
    }

    public long getConceptRevision() {
        return conceptRevision;
    }

    /** The scope revision of the fence it was read against; -1 without a fence. */
    public long getScopeRevision() {
        return scopeRevision;
    }

    public String getBookTitle() {
        return bookTitle;
    }

    public boolean hasBookFrame() {
        return bookFrame;
    }

    /** Eligible passages analysed (the concept coverage counts all of them). */
    public int getPassages() {
        return passages;
    }

    /** Passages the corpus limit kept out of the clustering. */
    public int getOmittedPassages() {
        return omittedPassages;
    }

    /** Accepted sources still in the processing queue when the run started: their passages are not in yet. */
    public Set<String> getPendingSourceIds() {
        return pendingSourceIds;
    }

    /** No accepted source is still being processed: missing material is a real gap, not a pending one. */
    public boolean isSettled() {
        return pendingSourceIds.isEmpty();
    }

    /** Topics, groups, relations, concept coverage and scope readings. */
    public TopicAnalysis analysis() {
        return analysis;
    }

    public EvidenceReport report() {
        return report;
    }

    /** Per concept node: MISSING / REDUNDANT / THIN / UNCONFIRMED / ONE_SIDED / OK with key terms. */
    public java.util.List<EvidenceReport.NodeEvidence> nodes() {
        return report.getNodes();
    }

    /** Significant research areas without a concept node, with their fence reading and key terms. */
    public java.util.List<EvidenceReport.ResearchArea> novelAreas() {
        return report.getNovelAreas();
    }

    /** The text of an analysed passage ("" when unknown). */
    public String text(String passageId) {
        String text = texts.get(passageId);
        return text == null ? "" : text;
    }
}
