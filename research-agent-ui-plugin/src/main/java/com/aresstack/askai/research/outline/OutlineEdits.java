package com.aresstack.askai.research.outline;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The editor's gestures on the outline (plan PR 23): rename, add, delete, move, indent and outdent. Each returns a
 * draft for {@link OutlineWorkspace#save}; ids are never invented here (new entries carry an empty id, the store
 * issues one). {@link #markEdits} is the one place that decides the user-edited flag and keeps the provenance of
 * known ids, for tree gestures and raw JSON alike. Pure and immutable.
 */
public final class OutlineEdits {

    private OutlineEdits() {
    }

    /** Rename the chapter or section {@code id}. */
    public static Outline rename(Outline outline, String id, String title) {
        requireTitle(title);
        List<OutlineChapter> chapters = new ArrayList<OutlineChapter>();
        boolean found = false;
        for (OutlineChapter chapter : outline.getChapters()) {
            if (chapter.getId().equals(id)) {
                chapters.add(chapter.withTitle(title.trim()));
                found = true;
                continue;
            }
            List<OutlineSection> sections = new ArrayList<OutlineSection>();
            for (OutlineSection section : chapter.getSections()) {
                if (section.getId().equals(id)) {
                    sections.add(section.withTitle(title.trim()));
                    found = true;
                } else {
                    sections.add(section);
                }
            }
            chapters.add(chapter.withSections(sections));
        }
        return requireFound(found, id, outline.withChapters(chapters));
    }

    /** Add a chapter after chapter {@code afterId} ({@code null} or unknown: at the end). */
    public static Outline addChapter(Outline outline, String afterId, String title) {
        requireTitle(title);
        List<OutlineChapter> chapters = new ArrayList<OutlineChapter>(outline.getChapters());
        int index = indexOfChapter(chapters, afterId);
        OutlineChapter added = OutlineChapter.draft(title.trim(), null, null, null);
        chapters.add(index < 0 ? chapters.size() : index + 1, added);
        return outline.withChapters(chapters);
    }

    /** Add a section at the end of chapter {@code chapterId}. */
    public static Outline addSection(Outline outline, String chapterId, String title) {
        requireTitle(title);
        List<OutlineChapter> chapters = new ArrayList<OutlineChapter>(outline.getChapters());
        int index = indexOfChapter(chapters, chapterId);
        if (index < 0) {
            throw new IllegalArgumentException("No chapter with id \"" + chapterId + "\".");
        }
        OutlineChapter chapter = chapters.get(index);
        List<OutlineSection> sections = new ArrayList<OutlineSection>(chapter.getSections());
        sections.add(OutlineSection.draft(title.trim(), null, null));
        chapters.set(index, chapter.withSections(sections));
        return outline.withChapters(chapters);
    }

    /** Delete the chapter (with its sections) or section {@code id}. */
    public static Outline delete(Outline outline, String id) {
        List<OutlineChapter> chapters = new ArrayList<OutlineChapter>();
        boolean found = false;
        for (OutlineChapter chapter : outline.getChapters()) {
            if (chapter.getId().equals(id)) {
                found = true;
                continue;
            }
            List<OutlineSection> sections = new ArrayList<OutlineSection>();
            for (OutlineSection section : chapter.getSections()) {
                if (section.getId().equals(id)) {
                    found = true;
                } else {
                    sections.add(section);
                }
            }
            chapters.add(sections.size() == chapter.getSections().size() ? chapter : chapter.withSections(sections));
        }
        return requireFound(found, id, outline.withChapters(chapters));
    }

    /**
     * Move the entry {@code id} one step up ({@code delta} -1) or down (+1) among its siblings; a section at the
     * edge of its chapter moves into the neighbouring chapter. Unchanged when it cannot move.
     */
    public static Outline move(Outline outline, String id, int delta) {
        if (delta != -1 && delta != 1) {
            throw new IllegalArgumentException("Move by one step.");
        }
        List<OutlineChapter> chapters = new ArrayList<OutlineChapter>(outline.getChapters());
        int chapterIndex = indexOfChapter(chapters, id);
        if (chapterIndex >= 0) {
            int target = chapterIndex + delta;
            if (target < 0 || target >= chapters.size()) {
                return outline;
            }
            chapters.add(target, chapters.remove(chapterIndex));
            return outline.withChapters(chapters);
        }
        for (int c = 0; c < chapters.size(); c++) {
            List<OutlineSection> sections = new ArrayList<OutlineSection>(chapters.get(c).getSections());
            int s = indexOfSection(sections, id);
            if (s < 0) {
                continue;
            }
            int target = s + delta;
            if (target >= 0 && target < sections.size()) {
                sections.add(target, sections.remove(s));
                chapters.set(c, chapters.get(c).withSections(sections));
                return outline.withChapters(chapters);
            }
            int neighbour = c + delta;
            if (neighbour < 0 || neighbour >= chapters.size()) {
                return outline;
            }
            OutlineSection moved = sections.remove(s);
            chapters.set(c, chapters.get(c).withSections(sections));
            List<OutlineSection> other = new ArrayList<OutlineSection>(chapters.get(neighbour).getSections());
            other.add(delta < 0 ? other.size() : 0, moved);
            chapters.set(neighbour, chapters.get(neighbour).withSections(other));
            return outline.withChapters(chapters);
        }
        throw new IllegalArgumentException("No chapter or section with id \"" + id + "\".");
    }

    /**
     * Indent chapter {@code id}: it becomes the last section of the chapter before it, its sections follow it as
     * sections of that chapter. The entry changes its kind, so it gets a fresh id; title, brief and provenance stay.
     */
    public static Outline indent(Outline outline, String id) {
        List<OutlineChapter> chapters = new ArrayList<OutlineChapter>(outline.getChapters());
        int index = indexOfChapter(chapters, id);
        if (index < 0) {
            throw new IllegalArgumentException("Only a chapter can be indented.");
        }
        if (index == 0) {
            throw new IllegalArgumentException("The first chapter has no chapter above it to move into.");
        }
        OutlineChapter chapter = chapters.remove(index);
        OutlineChapter parent = chapters.get(index - 1);
        List<OutlineSection> sections = new ArrayList<OutlineSection>(parent.getSections());
        sections.add(new OutlineSection("", "", 0, chapter.getTitle(), chapter.getBrief(), chapter.getProvenance(),
                true, ""));
        sections.addAll(chapter.getSections());
        chapters.set(index - 1, parent.withSections(sections));
        return outline.withChapters(chapters);
    }

    /**
     * Outdent section {@code id}: it becomes a chapter right after its chapter and takes the sections that
     * followed it. Fresh id for the changed kind; title, brief and provenance stay.
     */
    public static Outline outdent(Outline outline, String id) {
        List<OutlineChapter> chapters = new ArrayList<OutlineChapter>(outline.getChapters());
        for (int c = 0; c < chapters.size(); c++) {
            List<OutlineSection> sections = chapters.get(c).getSections();
            int s = indexOfSection(sections, id);
            if (s < 0) {
                continue;
            }
            OutlineSection section = sections.get(s);
            chapters.set(c, chapters.get(c).withSections(new ArrayList<OutlineSection>(sections.subList(0, s))));
            chapters.add(c + 1, new OutlineChapter("", 0, section.getTitle(), section.getBrief(),
                    section.getProvenance(), true, "",
                    new ArrayList<OutlineSection>(sections.subList(s + 1, sections.size()))));
            return outline.withChapters(chapters);
        }
        throw new IllegalArgumentException("Only a section can be outdented.");
    }

    /**
     * The draft as the editor saves it, measured against the stored outline: an entry with a known id keeps its
     * stored provenance and stale mark; it is flagged user-edited when its title, brief or place (parent, position)
     * changed, and keeps its stored flag otherwise. New entries are user-edited.
     */
    public static Outline markEdits(Outline draft, Outline stored) {
        Map<String, OutlineEntry> known = new HashMap<String, OutlineEntry>();
        Map<String, String> place = new HashMap<String, String>();
        int chapterOrder = 0;
        for (OutlineChapter chapter : stored.getChapters()) {
            chapterOrder++;
            known.put(chapter.getId(), chapter);
            place.put(chapter.getId(), "|" + chapterOrder);
            int sectionOrder = 0;
            for (OutlineSection section : chapter.getSections()) {
                sectionOrder++;
                known.put(section.getId(), section);
                place.put(section.getId(), chapter.getId() + "|" + sectionOrder);
            }
        }
        List<OutlineChapter> chapters = new ArrayList<OutlineChapter>();
        chapterOrder = 0;
        for (OutlineChapter chapter : draft.getChapters()) {
            chapterOrder++;
            List<OutlineSection> sections = new ArrayList<OutlineSection>();
            int sectionOrder = 0;
            for (OutlineSection section : chapter.getSections()) {
                sectionOrder++;
                OutlineEntry before = known.get(section.getId());
                boolean edited = !(before instanceof OutlineSection) || changed(section, before)
                        || !place.get(section.getId()).equals(chapter.getId() + "|" + sectionOrder);
                sections.add(new OutlineSection(section.getId(), section.getParentId(), section.getOrder(),
                        section.getTitle().trim(), section.getBrief(),
                        before instanceof OutlineSection ? before.getProvenance() : section.getProvenance(),
                        edited || before.isUserEdited(),
                        before instanceof OutlineSection ? before.getStaleReason() : ""));
            }
            OutlineEntry before = known.get(chapter.getId());
            boolean edited = !(before instanceof OutlineChapter) || changed(chapter, before)
                    || !place.get(chapter.getId()).equals("|" + chapterOrder);
            chapters.add(new OutlineChapter(chapter.getId(), chapter.getOrder(), chapter.getTitle().trim(),
                    chapter.getBrief(),
                    before instanceof OutlineChapter ? before.getProvenance() : chapter.getProvenance(),
                    edited || before.isUserEdited(),
                    before instanceof OutlineChapter ? before.getStaleReason() : "", sections));
        }
        return draft.withChapters(chapters);
    }

    /**
     * Strict checks of a raw draft before it is placed: duplicate ids and empty titles are named with a reason
     * (the store refuses unknown ids itself).
     * @throws IllegalArgumentException with the reason
     */
    public static void validate(Outline draft) {
        Set<String> seen = new HashSet<String>();
        int chapterNumber = 0;
        for (OutlineChapter chapter : draft.getChapters()) {
            chapterNumber++;
            check(chapter, "Chapter " + chapterNumber, seen);
            int sectionNumber = 0;
            for (OutlineSection section : chapter.getSections()) {
                sectionNumber++;
                check(section, "Section " + chapterNumber + "." + sectionNumber, seen);
            }
        }
    }

    private static void check(OutlineEntry entry, String where, Set<String> seen) {
        if (entry.getTitle().trim().isEmpty()) {
            throw new IllegalArgumentException(where + " has an empty title.");
        }
        if (!entry.getId().isEmpty() && !seen.add(entry.getId())) {
            throw new IllegalArgumentException(where + " repeats the id \"" + entry.getId() + "\".");
        }
    }

    private static boolean changed(OutlineEntry now, OutlineEntry before) {
        return !now.getTitle().trim().equals(before.getTitle()) || !now.getBrief().equals(before.getBrief());
    }

    private static int indexOfChapter(List<OutlineChapter> chapters, String id) {
        for (int i = 0; i < chapters.size(); i++) {
            if (chapters.get(i).getId().equals(id)) {
                return i;
            }
        }
        return -1;
    }

    private static int indexOfSection(List<OutlineSection> sections, String id) {
        for (int i = 0; i < sections.size(); i++) {
            if (sections.get(i).getId().equals(id)) {
                return i;
            }
        }
        return -1;
    }

    private static void requireTitle(String title) {
        if (title == null || title.trim().isEmpty()) {
            throw new IllegalArgumentException("A title must not be empty.");
        }
    }

    private static Outline requireFound(boolean found, String id, Outline result) {
        if (!found) {
            throw new IllegalArgumentException("No chapter or section with id \"" + id + "\".");
        }
        return result;
    }
}
