package com.aresstack.askai.research.knowledge.topic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Phase 2/3: how much evidence one concept node has. Its passages are those whose nearest node (above threshold and
 * margin) is this node or one below it. Three things are judged apart and reported apart:
 * <ul>
 * <li><b>evidence quality</b>: enough DISTINCT passages (twenty copies of one statement count once,
 * {@link #isMaterialCovered()}) confirmed by enough sources from enough domains ({@link #isSourceConfirmed()});</li>
 * <li><b>breadth</b> (Mengenabdeckung, nodes with children): enough of its children are covered
 * ({@link #isBroad()});</li>
 * <li><b>balance</b> (Vielfalt): no single child holds most of its distinct material ({@link #isBalanced()}); for a
 * leaf, {@link #getTopicCount()} and {@link #getDominantTopicShare()} show how the material spreads over the research
 * topics.</li>
 * </ul>
 * Thirty sources on wearables of which twenty-five cover only smartwatches are therefore not COVERED. A node without
 * passages is a GAP, one that passes every check COVERED, any other THIN. Immutable.
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
    private final int domainCount;
    private final List<String> topicIds;
    private final boolean materialCovered;
    private final boolean sourceConfirmed;
    private final int childCount;
    private final int coveredChildCount;
    private final double dominantChildShare;
    private final boolean broad;
    private final boolean balanced;
    private final int topicCount;
    private final double dominantTopicShare;
    private final Status status;

    public ConceptCoverage(String anchorId, String label, String path, int depth, String parentId,
                           List<String> passageIds, int distinctPassageCount, int sourceCount, int domainCount,
                           List<String> topicIds, boolean materialCovered, boolean sourceConfirmed, int childCount,
                           int coveredChildCount, double dominantChildShare, boolean broad, boolean balanced,
                           int topicCount, double dominantTopicShare) {
        this.anchorId = anchorId == null ? "" : anchorId;
        this.label = label == null ? "" : label;
        this.path = path == null ? "" : path;
        this.depth = depth;
        this.parentId = parentId == null ? "" : parentId;
        this.passageIds = copy(passageIds);
        this.distinctPassageCount = distinctPassageCount;
        this.sourceCount = sourceCount;
        this.domainCount = domainCount;
        this.topicIds = copy(topicIds);
        this.materialCovered = materialCovered;
        this.sourceConfirmed = sourceConfirmed;
        this.childCount = childCount;
        this.coveredChildCount = coveredChildCount;
        this.dominantChildShare = dominantChildShare;
        this.broad = broad;
        this.balanced = balanced;
        this.topicCount = topicCount;
        this.dominantTopicShare = dominantTopicShare;
        this.status = this.passageIds.isEmpty() ? Status.GAP
                : materialCovered && sourceConfirmed && broad && balanced ? Status.COVERED : Status.THIN;
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

    public int getDomainCount() {
        return domainCount;
    }

    /** Significant topics whose centroid aligns with this node or one below it. */
    public List<String> getTopicIds() {
        return topicIds;
    }

    /** Evidence quality, material: enough DISTINCT passages. */
    public boolean isMaterialCovered() {
        return materialCovered;
    }

    /** Evidence quality, confirmation: enough sources from enough domains. */
    public boolean isSourceConfirmed() {
        return sourceConfirmed;
    }

    /** Concept children of this node (0 for a leaf). */
    public int getChildCount() {
        return childCount;
    }

    /** Children with status COVERED. */
    public int getCoveredChildCount() {
        return coveredChildCount;
    }

    /** The largest share of this node's distinct material that one child holds (0 for a leaf). */
    public double getDominantChildShare() {
        return dominantChildShare;
    }

    /** Breadth: enough children covered (always true for a leaf). */
    public boolean isBroad() {
        return broad;
    }

    /** Balance: no child dominates the material (always true for a leaf). */
    public boolean isBalanced() {
        return balanced;
    }

    /** Research topics its passages fall into. */
    public int getTopicCount() {
        return topicCount;
    }

    /** The largest share of its distinct material inside one research topic. */
    public double getDominantTopicShare() {
        return dominantTopicShare;
    }

    public Status getStatus() {
        return status;
    }
}
