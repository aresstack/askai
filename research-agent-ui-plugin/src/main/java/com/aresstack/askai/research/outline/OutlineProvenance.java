package com.aresstack.askai.research.outline;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Where an outline entry comes from (D6): the concept node id (from the concept's identity sidecar, "" for an
 * entry without one), the clusters and the passages it was built from.
 */
public final class OutlineProvenance {

    public static final OutlineProvenance NONE = new OutlineProvenance("",
            Collections.<String>emptyList(), Collections.<String>emptyList());

    private final String conceptNodeId;
    private final List<String> clusterIds;
    private final List<String> passageIds;

    public OutlineProvenance(String conceptNodeId, List<String> clusterIds, List<String> passageIds) {
        this.conceptNodeId = conceptNodeId == null ? "" : conceptNodeId;
        this.clusterIds = copy(clusterIds);
        this.passageIds = copy(passageIds);
    }

    private static List<String> copy(List<String> values) {
        return Collections.unmodifiableList(new ArrayList<String>(
                values == null ? Collections.<String>emptyList() : values));
    }

    public String getConceptNodeId() {
        return conceptNodeId;
    }

    public List<String> getClusterIds() {
        return clusterIds;
    }

    public List<String> getPassageIds() {
        return passageIds;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof OutlineProvenance)) {
            return false;
        }
        OutlineProvenance that = (OutlineProvenance) other;
        return conceptNodeId.equals(that.conceptNodeId) && clusterIds.equals(that.clusterIds)
                && passageIds.equals(that.passageIds);
    }

    @Override
    public int hashCode() {
        return (conceptNodeId.hashCode() * 31 + clusterIds.hashCode()) * 31 + passageIds.hashCode();
    }
}
