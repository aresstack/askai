package com.aresstack.askai.research.outline;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Plan PR 22: a leftover source cluster that meets the topic minimums but matched no concept node. Candidates
 * are listed with the outline, never inserted as chapters; the user (PR 23) or the label worker (PR 24) decides.
 */
public final class OutlineCandidate {

    private final String clusterId;
    private final String suggestedTitle;
    private final List<String> passageIds;
    private final List<String> representativePassageIds;
    private final int sourceCount;

    public OutlineCandidate(String clusterId, String suggestedTitle, List<String> passageIds,
                            List<String> representativePassageIds, int sourceCount) {
        this.clusterId = clusterId == null ? "" : clusterId;
        this.suggestedTitle = suggestedTitle == null ? "" : suggestedTitle;
        this.passageIds = copy(passageIds);
        this.representativePassageIds = copy(representativePassageIds);
        this.sourceCount = sourceCount;
    }

    private static List<String> copy(List<String> values) {
        return Collections.unmodifiableList(new ArrayList<String>(
                values == null ? Collections.<String>emptyList() : values));
    }

    public String getClusterId() {
        return clusterId;
    }

    public String getSuggestedTitle() {
        return suggestedTitle;
    }

    public List<String> getPassageIds() {
        return passageIds;
    }

    public List<String> getRepresentativePassageIds() {
        return representativePassageIds;
    }

    public int getSourceCount() {
        return sourceCount;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof OutlineCandidate)) {
            return false;
        }
        OutlineCandidate that = (OutlineCandidate) other;
        return clusterId.equals(that.clusterId) && suggestedTitle.equals(that.suggestedTitle)
                && passageIds.equals(that.passageIds)
                && representativePassageIds.equals(that.representativePassageIds) && sourceCount == that.sourceCount;
    }

    @Override
    public int hashCode() {
        int hash = clusterId.hashCode();
        hash = hash * 31 + suggestedTitle.hashCode();
        hash = hash * 31 + passageIds.hashCode();
        hash = hash * 31 + representativePassageIds.hashCode();
        return hash * 31 + sourceCount;
    }
}
