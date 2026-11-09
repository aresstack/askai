package com.aresstack.askai.research.outline;

/**
 * What chapters and sections share (D6): a store-owned id ("" until the store assigns one), the parent id
 * ("" for a chapter), the 1-based order inside the parent, title, brief, provenance, the user-edited flag and the
 * stale mark ("" while not stale, otherwise its reason).
 */
public abstract class OutlineEntry {

    private final String id;
    private final String parentId;
    private final int order;
    private final String title;
    private final OutlineBrief brief;
    private final OutlineProvenance provenance;
    private final boolean userEdited;
    private final String staleReason;

    OutlineEntry(String id, String parentId, int order, String title, OutlineBrief brief,
                 OutlineProvenance provenance, boolean userEdited, String staleReason) {
        this.id = id == null ? "" : id.trim();
        this.parentId = parentId == null ? "" : parentId.trim();
        this.order = order;
        this.title = title == null ? "" : title;
        this.brief = brief == null ? OutlineBrief.EMPTY : brief;
        this.provenance = provenance == null ? OutlineProvenance.NONE : provenance;
        this.userEdited = userEdited;
        this.staleReason = staleReason == null ? "" : staleReason.trim();
    }

    public String getId() {
        return id;
    }

    public String getParentId() {
        return parentId;
    }

    public int getOrder() {
        return order;
    }

    public String getTitle() {
        return title;
    }

    public OutlineBrief getBrief() {
        return brief;
    }

    public OutlineProvenance getProvenance() {
        return provenance;
    }

    public boolean isUserEdited() {
        return userEdited;
    }

    public boolean isStale() {
        return !staleReason.isEmpty();
    }

    /** Why the entry is stale; "" while it is not. */
    public String getStaleReason() {
        return staleReason;
    }

    boolean sameFields(OutlineEntry that) {
        return id.equals(that.id) && parentId.equals(that.parentId) && order == that.order
                && title.equals(that.title) && brief.equals(that.brief) && provenance.equals(that.provenance)
                && userEdited == that.userEdited && staleReason.equals(that.staleReason);
    }

    int fieldsHash() {
        int hash = id.hashCode();
        hash = hash * 31 + parentId.hashCode();
        hash = hash * 31 + order;
        hash = hash * 31 + title.hashCode();
        hash = hash * 31 + brief.hashCode();
        hash = hash * 31 + provenance.hashCode();
        hash = hash * 31 + (userEdited ? 1 : 0);
        return hash * 31 + staleReason.hashCode();
    }
}
