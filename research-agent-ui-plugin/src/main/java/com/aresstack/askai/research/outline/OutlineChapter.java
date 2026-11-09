package com.aresstack.askai.research.outline;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** One chapter of the outline with its sections in order (D6); immutable. */
public final class OutlineChapter extends OutlineEntry {

    private final List<OutlineSection> sections;

    public OutlineChapter(String id, int order, String title, OutlineBrief brief, OutlineProvenance provenance,
                          boolean userEdited, String staleReason, List<OutlineSection> sections) {
        super(id, "", order, title, brief, provenance, userEdited, staleReason);
        this.sections = Collections.unmodifiableList(new ArrayList<OutlineSection>(
                sections == null ? Collections.<OutlineSection>emptyList() : sections));
    }

    /** A new chapter without an id; the store assigns ids and order when it is saved. */
    public static OutlineChapter draft(String title, OutlineBrief brief, OutlineProvenance provenance,
                                       List<OutlineSection> sections) {
        return new OutlineChapter("", 0, title, brief, provenance, false, "", sections);
    }

    public List<OutlineSection> getSections() {
        return sections;
    }

    public OutlineChapter withTitle(String newTitle) {
        return new OutlineChapter(getId(), getOrder(), newTitle, getBrief(), getProvenance(), isUserEdited(),
                getStaleReason(), sections);
    }

    public OutlineChapter withUserEdited(boolean edited) {
        return new OutlineChapter(getId(), getOrder(), getTitle(), getBrief(), getProvenance(), edited,
                getStaleReason(), sections);
    }

    public OutlineChapter withStaleReason(String reason) {
        return new OutlineChapter(getId(), getOrder(), getTitle(), getBrief(), getProvenance(), isUserEdited(),
                reason, sections);
    }

    public OutlineChapter withSections(List<OutlineSection> newSections) {
        return new OutlineChapter(getId(), getOrder(), getTitle(), getBrief(), getProvenance(), isUserEdited(),
                getStaleReason(), newSections);
    }

    OutlineChapter placed(String id, int order, List<OutlineSection> placedSections) {
        return new OutlineChapter(id, order, getTitle(), getBrief(), getProvenance(), isUserEdited(),
                getStaleReason(), placedSections);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof OutlineChapter && sameFields((OutlineChapter) other)
                && sections.equals(((OutlineChapter) other).sections);
    }

    @Override
    public int hashCode() {
        return fieldsHash() * 31 + sections.hashCode();
    }
}
