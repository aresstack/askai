package com.aresstack.askai.research.outline;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The user-owned outline (D6): chapters with their sections, in order. Immutable. Ids are store-owned:
 * {@link #placedAgainst} gives every new entry (empty id) a fresh id, refuses ids the stored outline does not
 * know, and sets parent and order from the array position, so an id never changes once it was issued.
 */
public final class Outline {

    public static final Outline EMPTY = new Outline(Collections.<OutlineChapter>emptyList());

    /** Issues new entry ids. */
    public interface IdSource {
        String next(String prefix);
    }

    /** Random, short ids such as {@code ch-1a2b3c4d5e6f}. */
    public static final IdSource RANDOM_IDS = new IdSource() {
        public String next(String prefix) {
            return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        }
    };

    private final List<OutlineChapter> chapters;

    public Outline(List<OutlineChapter> chapters) {
        this.chapters = Collections.unmodifiableList(new ArrayList<OutlineChapter>(
                chapters == null ? Collections.<OutlineChapter>emptyList() : chapters));
    }

    public List<OutlineChapter> getChapters() {
        return chapters;
    }

    public boolean isEmpty() {
        return chapters.isEmpty();
    }

    /** Whether at least one chapter has a section (the minimum of {@code finish-outline}, Q24). */
    public boolean hasChapterWithSection() {
        for (OutlineChapter chapter : chapters) {
            if (!chapter.getSections().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /** The chapter with {@code id}, or {@code null}. */
    public OutlineChapter chapter(String id) {
        for (OutlineChapter chapter : chapters) {
            if (chapter.getId().equals(id)) {
                return chapter;
            }
        }
        return null;
    }

    /** Every id of this outline, chapters and sections, in document order. */
    public List<String> allIds() {
        List<String> ids = new ArrayList<String>();
        for (OutlineChapter chapter : chapters) {
            ids.add(chapter.getId());
            for (OutlineSection section : chapter.getSections()) {
                ids.add(section.getId());
            }
        }
        return ids;
    }

    /** A copy with the chapters replaced. */
    public Outline withChapters(List<OutlineChapter> newChapters) {
        return new Outline(newChapters);
    }

    /** A copy with stale reasons set on the chapters named in {@code reasons} (chapter id -> reason). */
    public Outline withChapterStaleReasons(Map<String, String> reasons) {
        List<OutlineChapter> next = new ArrayList<OutlineChapter>();
        for (OutlineChapter chapter : chapters) {
            String reason = reasons.get(chapter.getId());
            next.add(reason == null ? chapter : chapter.withStaleReason(reason));
        }
        return new Outline(next);
    }

    /**
     * This outline as the store keeps it, measured against the stored one: new entries get ids from
     * {@code ids}, sections get their chapter as parent, orders follow the array position.
     * @throws IllegalArgumentException for an id the stored outline does not know, a duplicate id, a section id
     *                                  used as a chapter id (or the reverse) or an empty title
     */
    public Outline placedAgainst(Outline stored, IdSource ids) {
        Set<String> knownChapters = new HashSet<String>();
        Set<String> knownSections = new HashSet<String>();
        for (OutlineChapter chapter : stored.chapters) {
            knownChapters.add(chapter.getId());
            for (OutlineSection section : chapter.getSections()) {
                knownSections.add(section.getId());
            }
        }
        Set<String> used = new HashSet<String>(stored.allIds());
        Set<String> seen = new HashSet<String>();
        List<OutlineChapter> placed = new ArrayList<OutlineChapter>();
        int chapterOrder = 0;
        for (OutlineChapter chapter : chapters) {
            chapterOrder++;
            requireTitle(chapter);
            String chapterId = idFor(chapter.getId(), "ch-", knownChapters, "chapter", used, seen, ids);
            List<OutlineSection> sections = new ArrayList<OutlineSection>();
            int sectionOrder = 0;
            for (OutlineSection section : chapter.getSections()) {
                sectionOrder++;
                requireTitle(section);
                String sectionId = idFor(section.getId(), "sec-", knownSections, "section", used, seen, ids);
                sections.add(section.placed(sectionId, chapterId, sectionOrder));
            }
            placed.add(chapter.placed(chapterId, chapterOrder, sections));
        }
        return new Outline(placed);
    }

    private static void requireTitle(OutlineEntry entry) {
        if (entry.getTitle().trim().isEmpty()) {
            throw new IllegalArgumentException("Every chapter and section needs a title.");
        }
    }

    private static String idFor(String given, String prefix, Set<String> known, String kind, Set<String> used,
                                Set<String> seen, IdSource ids) {
        String id = given;
        if (id.isEmpty()) {
            do {
                id = ids.next(prefix);
            } while (used.contains(id) || seen.contains(id));
            used.add(id);
        } else if (!known.contains(id)) {
            throw new IllegalArgumentException("Unknown " + kind + " id \"" + id
                    + "\". Ids are assigned by the outline store; leave the id empty for a new " + kind + ".");
        }
        if (!seen.add(id)) {
            throw new IllegalArgumentException("The id \"" + id + "\" is used twice.");
        }
        return id;
    }

    /** Chapters by id, in order. */
    public Map<String, OutlineChapter> chaptersById() {
        Map<String, OutlineChapter> byId = new LinkedHashMap<String, OutlineChapter>();
        for (OutlineChapter chapter : chapters) {
            byId.put(chapter.getId(), chapter);
        }
        return byId;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Outline && chapters.equals(((Outline) other).chapters);
    }

    @Override
    public int hashCode() {
        return chapters.hashCode();
    }
}
