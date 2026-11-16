package com.aresstack.askai.research.outline;

/**
 * One section of a chapter (D6); immutable. Plan PR 22 adds two generation flags: {@code untitled} (a section
 * made from a sub-cluster, titled "&lt;chapter title&gt;, part n" until PR 24 labels it) and {@code thin} (fewer
 * than two distinct sources).
 */
public final class OutlineSection extends OutlineEntry {

    private final boolean untitled;
    private final boolean thin;

    public OutlineSection(String id, String parentId, int order, String title, OutlineBrief brief,
                          OutlineProvenance provenance, boolean userEdited, String staleReason) {
        this(id, parentId, order, title, brief, provenance, userEdited, staleReason, false, false);
    }

    public OutlineSection(String id, String parentId, int order, String title, OutlineBrief brief,
                          OutlineProvenance provenance, boolean userEdited, String staleReason,
                          boolean untitled, boolean thin) {
        super(id, parentId, order, title, brief, provenance, userEdited, staleReason);
        this.untitled = untitled;
        this.thin = thin;
    }

    /** A new section without an id; the store assigns id, parent and order when it is saved. */
    public static OutlineSection draft(String title, OutlineBrief brief, OutlineProvenance provenance) {
        return new OutlineSection("", "", 0, title, brief, provenance, false, "");
    }

    /** Whether the title is a generated placeholder ("&lt;chapter&gt;, part n") awaiting a label. */
    public boolean isUntitled() {
        return untitled;
    }

    /** Whether fewer than two distinct sources back this section. */
    public boolean isThin() {
        return thin;
    }

    public OutlineSection withTitle(String newTitle) {
        return new OutlineSection(getId(), getParentId(), getOrder(), newTitle, getBrief(), getProvenance(),
                isUserEdited(), getStaleReason(), untitled, thin);
    }

    public OutlineSection withUserEdited(boolean edited) {
        return new OutlineSection(getId(), getParentId(), getOrder(), getTitle(), getBrief(), getProvenance(),
                edited, getStaleReason(), untitled, thin);
    }

    public OutlineSection withStaleReason(String reason) {
        return new OutlineSection(getId(), getParentId(), getOrder(), getTitle(), getBrief(), getProvenance(),
                isUserEdited(), reason, untitled, thin);
    }

    public OutlineSection withUntitled(boolean value) {
        return new OutlineSection(getId(), getParentId(), getOrder(), getTitle(), getBrief(), getProvenance(),
                isUserEdited(), getStaleReason(), value, thin);
    }

    public OutlineSection withThin(boolean value) {
        return new OutlineSection(getId(), getParentId(), getOrder(), getTitle(), getBrief(), getProvenance(),
                isUserEdited(), getStaleReason(), untitled, value);
    }

    public OutlineSection withBrief(OutlineBrief value) {
        return new OutlineSection(getId(), getParentId(), getOrder(), getTitle(), value, getProvenance(),
                isUserEdited(), getStaleReason(), untitled, thin);
    }

    public OutlineSection withProvenance(OutlineProvenance value) {
        return new OutlineSection(getId(), getParentId(), getOrder(), getTitle(), getBrief(), value,
                isUserEdited(), getStaleReason(), untitled, thin);
    }

    OutlineSection placed(String id, String parentId, int order) {
        return new OutlineSection(id, parentId, order, getTitle(), getBrief(), getProvenance(), isUserEdited(),
                getStaleReason(), untitled, thin);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof OutlineSection && sameFields((OutlineSection) other)
                && untitled == ((OutlineSection) other).untitled && thin == ((OutlineSection) other).thin;
    }

    @Override
    public int hashCode() {
        return (fieldsHash() * 31 + (untitled ? 1 : 0)) * 31 + (thin ? 1 : 0);
    }
}
