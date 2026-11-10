package com.aresstack.askai.research.agent;

import com.aresstack.askai.research.outline.Outline;
import com.aresstack.askai.research.outline.OutlineBrief;
import com.aresstack.askai.research.outline.OutlineChapter;
import com.aresstack.askai.research.outline.OutlineEntry;
import com.aresstack.askai.research.outline.OutlineSection;
import com.aresstack.askai.research.tabs.RowSignal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * One line of the Outline tab's list: a chapter or a section with its number in the book ("2", "2.3") and
 * where it stands. The rows keep the order of the book; the colour says what still needs attention:
 * grey = no brief yet, green = ready, yellow = stale or thin, red = its brief could not be generated.
 */
final class OutlineRow {

    private final OutlineEntry entry;
    private final String number;
    private final boolean briefFailed;

    private OutlineRow(OutlineEntry entry, String number, boolean briefFailed) {
        this.entry = entry;
        this.number = number;
        this.briefFailed = briefFailed;
    }

    /**
     * The rows of an outline in book order, reduced to the titles that contain {@code filter} (a chapter stays
     * when one of its sections matches, so a hit never loses its place in the book).
     *
     * @param briefFailures the ids of the chapters whose last brief call failed
     */
    static List<OutlineRow> rows(Outline outline, Set<String> briefFailures, String filter) {
        String needle = filter == null ? "" : filter.trim().toLowerCase(Locale.ROOT);
        Set<String> failures = briefFailures == null ? Collections.<String>emptySet() : briefFailures;
        List<OutlineRow> rows = new ArrayList<OutlineRow>();
        int c = 0;
        for (OutlineChapter chapter : outline.getChapters()) {
            c++;
            List<OutlineRow> sections = new ArrayList<OutlineRow>();
            int s = 0;
            for (OutlineSection section : chapter.getSections()) {
                s++;
                if (matches(section, needle)) {
                    sections.add(new OutlineRow(section, c + "." + s, false));
                }
            }
            if (matches(chapter, needle) || !sections.isEmpty()) {
                rows.add(new OutlineRow(chapter, String.valueOf(c), failures.contains(chapter.getId())));
                rows.addAll(sections);
            }
        }
        return rows;
    }

    private static boolean matches(OutlineEntry entry, String needle) {
        return needle.isEmpty() || entry.getTitle().toLowerCase(Locale.ROOT).contains(needle);
    }

    OutlineEntry entry() {
        return entry;
    }

    String number() {
        return number;
    }

    String title() {
        return entry.getTitle().trim();
    }

    boolean isChapter() {
        return entry instanceof OutlineChapter;
    }

    private boolean thin() {
        return entry instanceof OutlineSection && ((OutlineSection) entry).isThin();
    }

    private boolean hasBrief() {
        OutlineBrief brief = entry.getBrief();
        return !brief.getPurpose().trim().isEmpty() || !brief.getKeyPoints().isEmpty();
    }

    RowSignal signal() {
        if (briefFailed) {
            return RowSignal.FAILED;
        }
        if (entry.isStale() || thin()) {
            return RowSignal.CAUTION;
        }
        return hasBrief() ? RowSignal.DONE : RowSignal.PENDING;
    }

    /** The word in the Status column. */
    String status() {
        if (briefFailed) {
            return "Brief failed";
        }
        if (entry.isStale()) {
            return "Stale";
        }
        if (thin()) {
            return "Thin";
        }
        if (!hasBrief()) {
            return "No brief";
        }
        return entry.isUserEdited() ? "Edited" : "Ready";
    }

    /** What the row's tooltip adds to the columns: why it is marked and what the entry is for. */
    String note() {
        StringBuilder note = new StringBuilder();
        if (briefFailed) {
            note.append("Brief not generated. Retry it with the button below.");
        } else if (entry.isStale()) {
            note.append("Stale: ").append(entry.getStaleReason());
        } else if (thin()) {
            note.append("Thin: fewer than two sources back this section.");
        }
        String purpose = entry.getBrief().getPurpose().trim();
        if (!purpose.isEmpty()) {
            if (note.length() > 0) {
                note.append('\n');
            }
            note.append(purpose);
        }
        return note.toString();
    }
}
