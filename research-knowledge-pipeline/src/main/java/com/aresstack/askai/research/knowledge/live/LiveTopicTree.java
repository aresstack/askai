package com.aresstack.askai.research.knowledge.live;

import com.aresstack.askai.research.knowledge.TopicDendrogram;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Phase 3: the live topics of one clustering run together with the complete merge tree they were cut from. The
 * topics are exactly those of {@link LiveOutlineProjectionBuilder#discoverTopics}; the tree carries what the flat
 * cut drops (near duplicates, sub-topics, topic groups) for the topic analysis. Immutable.
 */
public final class LiveTopicTree {

    private final List<LiveTopicProjection> topics;
    private final TopicDendrogram dendrogram;

    public LiveTopicTree(List<LiveTopicProjection> topics, TopicDendrogram dendrogram) {
        this.topics = Collections.unmodifiableList(new ArrayList<LiveTopicProjection>(
                topics == null ? Collections.<LiveTopicProjection>emptyList() : topics));
        this.dendrogram = dendrogram;
    }

    /** Largest topic first, like {@link LiveOutlineProjectionBuilder#discoverTopics}. */
    public List<LiveTopicProjection> getTopics() {
        return topics;
    }

    public TopicDendrogram getDendrogram() {
        return dendrogram;
    }
}
