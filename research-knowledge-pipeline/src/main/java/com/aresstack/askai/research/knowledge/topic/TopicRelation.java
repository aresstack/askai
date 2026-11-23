package com.aresstack.askai.research.knowledge.topic;

/**
 * Phase 3: how close another topic of the same topic group lies: the merge height that joins both (cophenetic
 * distance) and the cosine similarity of their centroids. Immutable.
 */
public final class TopicRelation {

    private final String topicId;
    private final double joinDistance;
    private final double centroidSimilarity;

    public TopicRelation(String topicId, double joinDistance, double centroidSimilarity) {
        this.topicId = topicId == null ? "" : topicId;
        this.joinDistance = joinDistance;
        this.centroidSimilarity = centroidSimilarity;
    }

    public String getTopicId() {
        return topicId;
    }

    public double getJoinDistance() {
        return joinDistance;
    }

    public double getCentroidSimilarity() {
        return centroidSimilarity;
    }
}
