package com.aresstack.askai.research.drafting;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The gaps the writer left, as the next research cycle of the Sources work (live run 3, M2 "Lücken selbst
 * nachrecherchieren"): every {@code GAP:} line of a section's newest revision, and every section the writer could
 * not write for lack of evidence (its failure names one of the evidence words). Each gap becomes one "Research this
 * gap" entry ({@link GapResearch}) of the existing search plan, so the search, the planner, the source preference
 * and the Weidezaun are the ones of the plan, and the entry id stays the same for the same gap: a gap is searched
 * once, however often the writer reports it.
 */
public final class WriterGaps {

    /** One gap of one section. */
    public static final class Gap {
        private final String sectionId;
        private final String sectionTitle;
        private final String text;

        public Gap(String sectionId, String sectionTitle, String text) {
            this.sectionId = sectionId == null ? "" : sectionId;
            this.sectionTitle = sectionTitle == null ? "" : sectionTitle;
            this.text = text == null ? "" : text.trim();
        }

        public String getSectionId() {
            return sectionId;
        }

        public String getSectionTitle() {
            return sectionTitle;
        }

        public String getText() {
            return text;
        }
    }

    /** What the writer left in one section. */
    public static final class Section {
        final String id;
        final String title;
        final boolean failed;
        final String failure;
        final List<String> gapLines;

        /**
         * @param failed   whether the section has no text because the writer failed
         * @param failure  the writer's failure reason ("" when none)
         * @param gapLines the {@code GAP:} lines of the newest revision (empty when none)
         */
        public Section(String id, String title, boolean failed, String failure, List<String> gapLines) {
            this.id = id == null ? "" : id;
            this.title = title == null ? "" : title;
            this.failed = failed;
            this.failure = failure == null ? "" : failure.trim();
            this.gapLines = gapLines == null ? Collections.<String>emptyList() : new ArrayList<String>(gapLines);
        }
    }

    private WriterGaps() {
    }

    /**
     * The gaps in outline order, at most {@code max} (0 = none): first each failed section whose failure names an
     * evidence word (the gap is the section title itself, the failure says nothing to search for), then the
     * {@code GAP:} lines. Duplicates (same section, same text) count once.
     */
    public static List<Gap> of(List<Section> sections, List<String> evidenceWords, int max) {
        List<Gap> out = new ArrayList<Gap>();
        if (sections == null || max <= 0) {
            return out;
        }
        Set<String> seen = new LinkedHashSet<String>();
        for (Section s : sections) {
            if (s.failed && namesEvidence(s.failure, evidenceWords)) {
                add(out, seen, new Gap(s.id, s.title, s.title), max);
            }
        }
        for (Section s : sections) {
            for (String line : s.gapLines) {
                if (line != null && !line.trim().isEmpty()) {
                    add(out, seen, new Gap(s.id, s.title, line), max);
                }
            }
        }
        return out;
    }

    static boolean namesEvidence(String failure, List<String> words) {
        if (failure == null || failure.isEmpty() || words == null) {
            return false;
        }
        String lower = failure.toLowerCase(Locale.ROOT);
        for (String w : words) {
            if (w != null && !w.trim().isEmpty() && lower.contains(w.trim().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static void add(List<Gap> out, Set<String> seen, Gap gap, int max) {
        if (out.size() < max && !gap.getText().isEmpty()
                && seen.add(gap.getSectionId() + "\n" + gap.getText().toLowerCase(Locale.ROOT))) {
            out.add(gap);
        }
    }
}
