package com.aresstack.askai.research.knowledge.topic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Phase 3: how much evidence one concept node has: the passages whose nearest node (above threshold and margin) is
 * this node or one below it, their sources, and the significant topics aligned with it. A node without passages
 * is a GAP, one below the topic minimums is THIN. Immutable.
 */
public final class ConceptCoverage {

    public enum Status { COVERED, THIN, GAP }

    private final String anchorId;
    private final String label;
    private final String path;
    private final int depth;
    private final String parentId;
    private final List<String> passageIds;
    private final int distinctPassageCount;
    private final int sourceCount;
    private final List<String> topicIds;
    private final Status status;

    public ConceptCoverage(String anchorId, String label, String path, int depth, String parentId,
                           List<String> passageIds, int distinctPassageCount, int sourceCount,
                           List<String> topicIds, Status status) {
        this.anchorId = anchorId == null ? "" : anchorId;
        this.label = label == null ? "" : label;
        this.path = path == null ? "" : path;
        this.depth = depth;
        this.parentId = parentId == null ? "" : parentId;
        this.passageIds = copy(passageIds);
        this.distinctPassageCount = distinctPassageCount;
        this.sourceCount = sourceCount;
        this.topicIds = copy(topicIds);
        this.status = status == null ? Status.GAP : status;
    }

    private static List<String> copy(List<String> values) {
        return Collections.unmodifiableList(new ArrayList<String>(
                values == null ? Collections.<String>emptyList() : values));
    }

    public String getAnchorId() {
        return anchorId;
    }

    public String getLabel() {
        return label;
    }

    public String getPath() {
        return path;
    }

    public int getDepth() {
        return depth;
    }

    public String getParentId() {
        return parentId;
    }

    public List<String> getPassageIds() {
        return passageIds;
    }

    public int getDistinctPassageCount() {
        return distinctPassageCount;
    }

    public int getSourceCount() {
        return sourceCount;
    }

    /** Significant topics whose centroid aligns with this node or one below it. */
    public List<String> getTopicIds() {
        return topicIds;
    }

    public Status getStatus() {
        return status;
    }
}
