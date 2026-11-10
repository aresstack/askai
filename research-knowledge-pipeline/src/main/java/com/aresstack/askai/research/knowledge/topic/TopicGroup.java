package com.aresstack.askai.research.knowledge.topic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Phase 3: topics the merge tree joins at or below the topic-group distance: related areas that may share a
 * parent chapter. Topics of different groups are independent areas. Immutable.
 */
public final class TopicGroup {

    private final String groupId;
    private final List<String> topicIds;
    private final int distinctPassageCount;

    public TopicGroup(String groupId, List<String> topicIds, int distinctPassageCount) {
        this.groupId = groupId == null ? "" : groupId;
        this.topicIds = Collections.unmodifiableList(new ArrayList<String>(
                topicIds == null ? Collections.<String>emptyList() : topicIds));
        this.distinctPassageCount = distinctPassageCount;
    }

    public String getGroupId() {
        return groupId;
    }

    /** Its topics, largest first. */
    public List<String> getTopicIds() {
        return topicIds;
    }

    public int getDistinctPassageCount() {
        return distinctPassageCount;
    }
}
