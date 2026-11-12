package com.aresstack.askai.research.knowledge.topic;

import com.aresstack.askai.research.knowledge.TopicClusterer;

/**
 * Phase 3: every knob of the topic analysis, in one immutable value with one default origin
 * ({@link #defaults()}). The host turns each into a setting of the Outline settings; nothing here is a hidden
 * constant. Distances are cosine distances on the clusterer's merge tree (average linkage): the near-duplicate cut
 * lies below the sub-topic cut, which lies below the clusterer's topic cutoff, which lies below the topic-group cut.
 */
public final class TopicAnalysisSettings {

    /** Passages joined at or below this distance say the same thing (cosine similarity 0.92 and more). */
    public static final double DEFAULT_DUPLICATE_DISTANCE = 0.08;
    /** Inside a topic, groups joined at or below this distance are its sub-topics. */
    public static final double DEFAULT_SUBTOPIC_DISTANCE = 0.40;
    /** Topics joined at or below this distance form a topic group (a candidate parent chapter). */
    public static final double DEFAULT_TOPIC_GROUP_DISTANCE = 0.75;
    /** A passage or topic belongs to a concept node from this cosine similarity on (plan PR 22's provisional value). */
    public static final double DEFAULT_CONCEPT_THRESHOLD = 0.35;
    /**
     * ... and only with this lead over the best node of ANOTHER lineage (a node, its ancestors and its descendants
     * are one lineage). Live run 3 (2026-11-10): with the book-title prefix the median lead was about 0.01, and 0.02
     * rejected three of four passages although none fell below the threshold.
     */
    public static final double DEFAULT_CONCEPT_MARGIN = 0.01;
    /**
     * ... and, when the book frame (the prefix every anchor path shares) is known, only when it points this far in
     * the direction the node adds to the frame: the passage's cosine with the unit vector from the frame to the node.
     * Unrelated passages scatter around 0; the bare frame is no evidence. Live outline acceptance 2026-11-12 (bge-m3):
     * all 24 passages of four other areas lay above the threshold 0.35 for "Training apprentices", their raw lead over
     * the frame stayed at or below 0.061.
     */
    public static final double DEFAULT_CONCEPT_FRAME_LEAD = 0.15;
    /** Material: a topic, sub-topic or concept node needs this many DISTINCT passages (near duplicates count once). */
    public static final int DEFAULT_MINIMUM_PASSAGES = 3;
    /** Confirmation: a topic or concept node needs this many distinct sources ... */
    public static final int DEFAULT_MINIMUM_SOURCES = 2;
    /** ... from this many distinct domains (pages of one site confirm each other only once). */
    public static final int DEFAULT_MINIMUM_DOMAINS = 2;
    /** Breadth: a concept node with children needs at least this share of its children covered ... */
    public static final double DEFAULT_MINIMUM_CHILD_COVERAGE = 0.5;
    /** ... and no single child may hold more than this share of its distinct material (one-sided evidence). */
    public static final double DEFAULT_MAXIMUM_DOMINANT_SHARE = 0.75;
    /** A significant topic holding at least this share of the distinct material is a major topic. */
    public static final double DEFAULT_MAJOR_TOPIC_SHARE = 0.15;
    /** Representative passages listed per topic. */
    public static final int DEFAULT_REPRESENTATIVES = TopicClusterer.DEFAULT_REPRESENTATIVES;
    /**
     * Passages joined at or below this distance form one topic (the clusterer's topic cutoff). It depends on the
     * embedding model: live run 4 (2026-11-12, nomic-embed-text) merged the whole corpus below 0.55 into one topic.
     */
    public static final double DEFAULT_TOPIC_DISTANCE = TopicClusterer.DEFAULT_TOPIC_DISTANCE;

    private final double duplicateDistance;
    private final double subtopicDistance;
    private final double topicGroupDistance;
    private final double conceptThreshold;
    private final double conceptMargin;
    private final double conceptFrameLead;
    private final int minimumPassages;
    private final int minimumSources;
    private final int minimumDomains;
    private final double minimumChildCoverage;
    private final double maximumDominantShare;
    private final double majorTopicShare;
    private final int representatives;
    private final double topicDistance;

    public TopicAnalysisSettings(double duplicateDistance, double subtopicDistance, double topicGroupDistance,
                                 double conceptThreshold, double conceptMargin, double conceptFrameLead,
                                 int minimumPassages,
                                 int minimumSources, int minimumDomains, double minimumChildCoverage,
                                 double maximumDominantShare, double majorTopicShare, int representatives,
                                 double topicDistance) {
        if (!(duplicateDistance >= 0 && duplicateDistance < subtopicDistance && subtopicDistance < topicGroupDistance)) {
            throw new IllegalArgumentException("The distances must grow: duplicates " + duplicateDistance
                    + " < sub-topics " + subtopicDistance + " < topic groups " + topicGroupDistance + ".");
        }
        if (!(topicDistance > duplicateDistance && topicDistance <= topicGroupDistance)) {
            throw new IllegalArgumentException("The topic distance must lie above the duplicates " + duplicateDistance
                    + " and at most at the topic groups " + topicGroupDistance + ": " + topicDistance);
        }
        if (!(majorTopicShare > 0 && majorTopicShare <= 1)) {
            throw new IllegalArgumentException("The major topic share must lie in (0, 1]: " + majorTopicShare);
        }
        this.duplicateDistance = duplicateDistance;
        this.subtopicDistance = subtopicDistance;
        this.topicGroupDistance = topicGroupDistance;
        this.conceptThreshold = conceptThreshold;
        this.conceptMargin = Math.max(0, conceptMargin);
        this.conceptFrameLead = conceptFrameLead;
        this.minimumPassages = Math.max(1, minimumPassages);
        this.minimumSources = Math.max(1, minimumSources);
        this.minimumDomains = Math.max(1, minimumDomains);
        if (!(minimumChildCoverage >= 0 && minimumChildCoverage <= 1)) {
            throw new IllegalArgumentException("The minimum child coverage must lie in [0, 1]: "
                    + minimumChildCoverage);
        }
        if (!(maximumDominantShare > 0 && maximumDominantShare <= 1)) {
            throw new IllegalArgumentException("The maximum dominant share must lie in (0, 1]: "
                    + maximumDominantShare);
        }
        this.minimumChildCoverage = minimumChildCoverage;
        this.maximumDominantShare = maximumDominantShare;
        this.majorTopicShare = majorTopicShare;
        this.representatives = Math.max(1, representatives);
        this.topicDistance = topicDistance;
    }

    /** THE single default origin. */
    public static TopicAnalysisSettings defaults() {
        return new TopicAnalysisSettings(DEFAULT_DUPLICATE_DISTANCE, DEFAULT_SUBTOPIC_DISTANCE,
                DEFAULT_TOPIC_GROUP_DISTANCE, DEFAULT_CONCEPT_THRESHOLD, DEFAULT_CONCEPT_MARGIN,
                DEFAULT_CONCEPT_FRAME_LEAD, DEFAULT_MINIMUM_PASSAGES, DEFAULT_MINIMUM_SOURCES, DEFAULT_MINIMUM_DOMAINS,
                DEFAULT_MINIMUM_CHILD_COVERAGE, DEFAULT_MAXIMUM_DOMINANT_SHARE, DEFAULT_MAJOR_TOPIC_SHARE,
                DEFAULT_REPRESENTATIVES, DEFAULT_TOPIC_DISTANCE);
    }

    public double getDuplicateDistance() {
        return duplicateDistance;
    }

    public double getSubtopicDistance() {
        return subtopicDistance;
    }

    public double getTopicGroupDistance() {
        return topicGroupDistance;
    }

    public double getConceptThreshold() {
        return conceptThreshold;
    }

    public double getConceptMargin() {
        return conceptMargin;
    }

    /** The minimum projection on the direction from the book frame to the node (see the default). */
    public double getConceptFrameLead() {
        return conceptFrameLead;
    }

    public int getMinimumPassages() {
        return minimumPassages;
    }

    public int getMinimumSources() {
        return minimumSources;
    }

    public int getMinimumDomains() {
        return minimumDomains;
    }

    /** Material coverage: enough DISTINCT passages. */
    public boolean isMaterialCovered(int distinctPassages) {
        return distinctPassages >= minimumPassages;
    }

    /** Source confirmation: enough distinct sources from enough distinct domains. */
    public boolean isSourceConfirmed(int sources, int domains) {
        return sources >= minimumSources && domains >= minimumDomains;
    }

    public double getMinimumChildCoverage() {
        return minimumChildCoverage;
    }

    public double getMaximumDominantShare() {
        return maximumDominantShare;
    }

    public double getMajorTopicShare() {
        return majorTopicShare;
    }

    public int getRepresentatives() {
        return representatives;
    }

    /** The clusterer's topic cutoff. */
    public double getTopicDistance() {
        return topicDistance;
    }
}
