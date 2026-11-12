package com.aresstack.askai.research.knowledge.topic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Phase 3: one sub-topic inside a topic: a group of the merge tree's sub-topic cut with enough passages, or, in a
 * topic carrying the material of several independent concept areas, the passages the coverage assigned to one
 * concept node (a concept part, {@link #getConceptAnchorId()}). Its id is derived from its sorted members, so the
 * same material gives the same id. Immutable.
 */
public final class Subtopic {

    private final String subtopicId;
    private final String title;
    private final String representativePassageId;
    private final List<String> passageIds;
    private final int distinctPassageCount;
    private final int sourceCount;
    private final String conceptAnchorId;

    public Subtopic(String subtopicId, String title, String representativePassageId, List<String> passageIds,
                    int distinctPassageCount, int sourceCount, String conceptAnchorId) {
        this.subtopicId = subtopicId == null ? "" : subtopicId;
        this.title = title == null ? "" : title;
        this.representativePassageId = representativePassageId == null ? "" : representativePassageId;
        this.passageIds = Collections.unmodifiableList(new ArrayList<String>(
                passageIds == null ? Collections.<String>emptyList() : passageIds));
        this.distinctPassageCount = distinctPassageCount;
        this.sourceCount = sourceCount;
        this.conceptAnchorId = conceptAnchorId == null ? "" : conceptAnchorId;
    }

    public String getSubtopicId() {
        return subtopicId;
    }

    public String getTitle() {
        return title;
    }

    public String getRepresentativePassageId() {
        return representativePassageId;
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

    /** The concept node whose material this concept part is; "" for a part of the sub-topic cut. */
    public String getConceptAnchorId() {
        return conceptAnchorId;
    }
}
