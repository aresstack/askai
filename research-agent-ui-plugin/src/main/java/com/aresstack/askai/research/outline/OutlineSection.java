package com.aresstack.askai.research.outline;

/** One section of a chapter (D6); immutable. */
public final class OutlineSection extends OutlineEntry {

    public OutlineSection(String id, String parentId, int order, String title, OutlineBrief brief,
                          OutlineProvenance provenance, boolean userEdited, String staleReason) {
        super(id, parentId, order, title, brief, provenance, userEdited, staleReason);
    }

    /** A new section without an id; the store assigns id, parent and order when it is saved. */
    public static OutlineSection draft(String title, OutlineBrief brief, OutlineProvenance provenance) {
        return new OutlineSection("", "", 0, title, brief, provenance, false, "");
    }

    public OutlineSection withTitle(String newTitle) {
        return new OutlineSection(getId(), getParentId(), getOrder(), newTitle, getBrief(), getProvenance(),
                isUserEdited(), getStaleReason());
    }

    public OutlineSection withUserEdited(boolean edited) {
        return new OutlineSection(getId(), getParentId(), getOrder(), getTitle(), getBrief(), getProvenance(),
                edited, getStaleReason());
    }

    public OutlineSection withStaleReason(String reason) {
        return new OutlineSection(getId(), getParentId(), getOrder(), getTitle(), getBrief(), getProvenance(),
                isUserEdited(), reason);
    }

    OutlineSection placed(String id, String parentId, int order) {
        return new OutlineSection(id, parentId, order, getTitle(), getBrief(), getProvenance(), isUserEdited(),
                getStaleReason());
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof OutlineSection && sameFields((OutlineSection) other);
    }

    @Override
    public int hashCode() {
        return fieldsHash();
    }
}
