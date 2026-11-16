package com.aresstack.askai.research.knowledge.processing;

/**
 * All knowledge-processing knobs in one place — NO magic numbers in the worker/scheduler. Immutable; the
 * single default origin is {@link #defaults()}. The segmentation fields mirror the canonical
 * {@code research-knowledge-pipeline} {@code PassageSegmentation} constructor (window/threshold/min/max), so
 * the worker configures that algorithm from here instead of duplicating it. Topic/labeling/debounce fields
 * are carried for the later slices (C5–C7) and unused here.
 */
public final class KnowledgeProcessingSettings {

    // --- Semantic passage segmentation (feeds PassageSegmentation) ---
    /** Sentence-window width on each side of a candidate boundary. */
    public final int windowSize;
    /** Below this LEFT-window vs RIGHT-window cosine similarity, a passage boundary is placed. */
    public final double boundaryThreshold;
    public final int minPassageSentences;
    public final int maxPassageSentences;

    // --- Topic projection (C5, carried) ---
    public final double topicAssignmentThreshold;
    public final double topicMergeThreshold;
    public final int minimumPassagesPerTopic;
    public final int minimumDistinctSourcesPerTopic;

    // --- Projection debounce (C7, carried) ---
    public final long projectionDebounceMillis;

    // --- Topic labeling (C6, carried) ---
    public final int topicLabelRepresentativePassageCount;

    // --- Processing pipeline provenance + retry ---
    /**
     * Part of a job's idempotency key (a bump forces reprocessing). The OTHER half of that key — the embedding
     * world fingerprint — is NOT a setting: it is injected per session from the host's
     * {@code EmbeddingEndpointDescriptor}, so a job always names the vector world it was created for (§4.3).
     */
    public final String segmentationPipelineVersion;
    /** Maximum processing attempts before a retryable failure is treated as permanent. */
    public final int maxProcessingAttempts;

    public KnowledgeProcessingSettings(int windowSize, double boundaryThreshold, int minPassageSentences,
                                       int maxPassageSentences, double topicAssignmentThreshold,
                                       double topicMergeThreshold, int minimumPassagesPerTopic,
                                       int minimumDistinctSourcesPerTopic, long projectionDebounceMillis,
                                       int topicLabelRepresentativePassageCount,
                                       String segmentationPipelineVersion,
                                       int maxProcessingAttempts) {
        this.windowSize = windowSize;
        this.boundaryThreshold = boundaryThreshold;
        this.minPassageSentences = minPassageSentences;
        this.maxPassageSentences = maxPassageSentences;
        this.topicAssignmentThreshold = topicAssignmentThreshold;
        this.topicMergeThreshold = topicMergeThreshold;
        this.minimumPassagesPerTopic = minimumPassagesPerTopic;
        this.minimumDistinctSourcesPerTopic = minimumDistinctSourcesPerTopic;
        this.projectionDebounceMillis = projectionDebounceMillis;
        this.topicLabelRepresentativePassageCount = topicLabelRepresentativePassageCount;
        this.segmentationPipelineVersion = segmentationPipelineVersion == null
                ? "" : segmentationPipelineVersion;
        this.maxProcessingAttempts = maxProcessingAttempts;
    }

    /** THE single default origin. Segmentation defaults mirror PassageSegmentation's own defaults. */
    public static KnowledgeProcessingSettings defaults() {
        return new KnowledgeProcessingSettings(
                3,       // windowSize
                0.35,    // boundaryThreshold
                2,       // minPassageSentences
                8,       // maxPassageSentences
                0.72,    // topicAssignmentThreshold (C5)
                0.86,    // topicMergeThreshold (C5)
                2,       // minimumPassagesPerTopic (C5)
                1,       // minimumDistinctSourcesPerTopic (C5)
                750L,    // projectionDebounceMillis (C7)
                6,       // topicLabelRepresentativePassageCount (C6)
                "seg-v2",   // segmentationPipelineVersion (PR 20: passages split to the embedding budget) (the embedding world fingerprint is session-injected)
                3);      // maxProcessingAttempts
    }
}
