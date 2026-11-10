package com.aresstack.askai.research.knowledge.topic;

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
    /** ... and only with this lead over the second-best node (plan PR 22's provisional value). */
    public static final double DEFAULT_CONCEPT_MARGIN = 0.02;
    /** A topic or sub-topic needs this many passages to count. */
    public static final int DEFAULT_MINIMUM_PASSAGES = 3;
    /** A topic needs this many distinct sources to count. */
    public static final int DEFAULT_MINIMUM_SOURCES = 2;
    /** A significant topic holding at least this share of the distinct material is a major topic. */
    public static final double DEFAULT_MAJOR_TOPIC_SHARE = 0.15;
    /** Representative passages listed per topic. */
    public static final int DEFAULT_REPRESENTATIVES = 2;

    private final double duplicateDistance;
    private final double subtopicDistance;
    private final double topicGroupDistance;
    private final double conceptThreshold;
    private final double conceptMargin;
    private final int minimumPassages;
    private final int minimumSources;
    private final double majorTopicShare;
    private final int representatives;

    public TopicAnalysisSettings(double duplicateDistance, double subtopicDistance, double topicGroupDistance,
                                 double conceptThreshold, double conceptMargin, int minimumPassages,
                                 int minimumSources, double majorTopicShare, int representatives) {
        if (!(duplicateDistance >= 0 && duplicateDistance < subtopicDistance && subtopicDistance < topicGroupDistance)) {
            throw new IllegalArgumentException("The distances must grow: duplicates " + duplicateDistance
                    + " < sub-topics " + subtopicDistance + " < topic groups " + topicGroupDistance + ".");
        }
        if (!(majorTopicShare > 0 && majorTopicShare <= 1)) {
            throw new IllegalArgumentException("The major topic share must lie in (0, 1]: " + majorTopicShare);
        }
        this.duplicateDistance = duplicateDistance;
        this.subtopicDistance = subtopicDistance;
        this.topicGroupDistance = topicGroupDistance;
        this.conceptThreshold = conceptThreshold;
        this.conceptMargin = Math.max(0, conceptMargin);
        this.minimumPassages = Math.max(1, minimumPassages);
        this.minimumSources = Math.max(1, minimumSources);
        this.majorTopicShare = majorTopicShare;
        this.representatives = Math.max(1, representatives);
    }

    /** THE single default origin. */
    public static TopicAnalysisSettings defaults() {
        return new TopicAnalysisSettings(DEFAULT_DUPLICATE_DISTANCE, DEFAULT_SUBTOPIC_DISTANCE,
                DEFAULT_TOPIC_GROUP_DISTANCE, DEFAULT_CONCEPT_THRESHOLD, DEFAULT_CONCEPT_MARGIN,
                DEFAULT_MINIMUM_PASSAGES, DEFAULT_MINIMUM_SOURCES, DEFAULT_MAJOR_TOPIC_SHARE,
                DEFAULT_REPRESENTATIVES);
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

    public int getMinimumPassages() {
        return minimumPassages;
    }

    public int getMinimumSources() {
        return minimumSources;
    }

    public double getMajorTopicShare() {
        return majorTopicShare;
    }

    public int getRepresentatives() {
        return representatives;
    }
}
