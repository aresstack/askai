package com.aresstack.askai.research.document;

import com.aresstack.askai.research.manuscript.ManuscriptSection;
import com.aresstack.askai.research.manuscript.SectionStatus;
import com.aresstack.askai.research.outline.Outline;
import com.aresstack.askai.research.outline.OutlineChapter;
import com.aresstack.askai.research.outline.OutlineSection;
import com.aresstack.askai.research.tabs.RowSignal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One line of the Document tab's list: a chapter of the approved outline (a heading with the count of its
 * written sections) or a section with its writing state. The rows keep the order of the book; the colour
 * says where a section stands: grey = not written yet, green = written, yellow = stale, red = failed or
 * orphaned; a skipped section carries no colour.
 */
final class DocumentRow {

    private final String number;
    private final String title;
    /** The section this row opens, or {@code null} for a chapter heading. */
    private final SectionEditor.Item item;
    private final SectionStatus sectionStatus;
    private final String reason;
    private final int written;
    private final int sections;

    private DocumentRow(String number, String title, SectionEditor.Item item, SectionStatus sectionStatus,
                        String reason, int written, int sections) {
        this.number = number;
        this.title = title;
        this.item = item;
        this.sectionStatus = sectionStatus;
        this.reason = reason == null ? "" : reason;
        this.written = written;
        this.sections = sections;
    }

    /**
     * The rows of the book in order, reduced to the titles that contain {@code filter} (a chapter stays when
     * one of its sections matches).
     *
     * @param stored the manuscript's sections; a section without one is still to be written
     */
    static List<DocumentRow> rows(Outline approved, List<ManuscriptSection> stored, String filter) {
        List<DocumentRow> rows = new ArrayList<DocumentRow>();
        if (approved == null) {
            return rows;
        }
        String needle = filter == null ? "" : filter.trim().toLowerCase(Locale.ROOT);
        Map<String, ManuscriptSection> states = new HashMap<String, ManuscriptSection>();
        if (stored != null) {
            for (ManuscriptSection section : stored) {
                states.put(section.getSectionId(), section);
            }
        }
        Map<String, SectionEditor.Item> items = new HashMap<String, SectionEditor.Item>();
        for (SectionEditor.Item item : SectionEditor.items(approved)) {
            items.put(item.sectionId, item);
        }
        int c = 0;
        for (OutlineChapter chapter : approved.getChapters()) {
            c++;
            List<DocumentRow> sectionRows = new ArrayList<DocumentRow>();
            int written = 0;
            int total = 0;
            int s = 0;
            for (OutlineSection section : chapter.getSections()) {
                s++;
                SectionEditor.Item item = items.get(section.getId());
                if (item == null) {
                    continue; // not a section the manuscript can hold
                }
                total++;
                ManuscriptSection state = states.get(section.getId());
                SectionStatus status = state == null ? SectionStatus.PENDING : state.getStatus();
                if (status == SectionStatus.WRITTEN || status == SectionStatus.STALE) {
                    written++;
                }
                if (matches(section.getTitle(), needle)) {
                    sectionRows.add(new DocumentRow(c + "." + s, section.getTitle().trim(), item, status,
                            state == null ? "" : state.getReason(), 0, 0));
                }
            }
            if (matches(chapter.getTitle(), needle) || !sectionRows.isEmpty()) {
                rows.add(new DocumentRow(String.valueOf(c), chapter.getTitle().trim(), null, null, "", written,
                        total));
                rows.addAll(sectionRows);
            }
        }
        return rows;
    }

    private static boolean matches(String title, String needle) {
        return needle.isEmpty() || title.toLowerCase(Locale.ROOT).contains(needle);
    }

    String number() {
        return number;
    }

    String title() {
        return title;
    }

    boolean isChapter() {
        return item == null;
    }

    /** The section to open, or {@code null} for a chapter heading. */
    SectionEditor.Item item() {
        return item;
    }

    SectionStatus sectionStatus() {
        return sectionStatus;
    }

    RowSignal signal() {
        if (sectionStatus == null) {
            return RowSignal.NONE;
        }
        switch (sectionStatus) {
            case PENDING:
                return RowSignal.PENDING;
            case WRITTEN:
                return RowSignal.DONE;
            case STALE:
                return RowSignal.CAUTION;
            case FAILED:
            case ORPHAN:
                return RowSignal.FAILED;
            default:
                return RowSignal.NONE;
        }
    }

    /** A skipped section is outside the work: its text steps back. */
    boolean muted() {
        return sectionStatus == SectionStatus.SKIPPED;
    }

    /** The word in the Status column: a section's state, a chapter's written sections ("2 of 3"). */
    String status() {
        return sectionStatus == null ? written + " of " + sections : sectionStatus.label();
    }

    /** What the row's tooltip adds: why a section failed or is stale. */
    String note() {
        return reason;
    }

    /** "8 of 12 sections written · 1 stale · 1 failed · 1 skipped" over the rows of the whole book. */
    static String summary(List<DocumentRow> rows) {
        int total = 0;
        int written = 0;
        int stale = 0;
        int failed = 0;
        int skipped = 0;
        for (DocumentRow row : rows) {
            if (row.isChapter()) {
                continue;
            }
            total++;
            switch (row.sectionStatus) {
                case WRITTEN:
                    written++;
                    break;
                case STALE:
                    written++;
                    stale++;
                    break;
                case FAILED:
                case ORPHAN:
                    failed++;
                    break;
                case SKIPPED:
                    skipped++;
                    break;
                default:
                    break;
            }
        }
        if (total == 0) {
            return "No approved outline yet";
        }
        StringBuilder text = new StringBuilder().append(written).append(" of ").append(total)
                .append(total == 1 ? " section written" : " sections written");
        if (stale > 0) {
            text.append(" · ").append(stale).append(" stale");
        }
        if (failed > 0) {
            text.append(" · ").append(failed).append(" failed");
        }
        if (skipped > 0) {
            text.append(" · ").append(skipped).append(" skipped");
        }
        return text.toString();
    }
}
