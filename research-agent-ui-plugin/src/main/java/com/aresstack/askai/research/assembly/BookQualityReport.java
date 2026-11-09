package com.aresstack.askai.research.assembly;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The deterministic quality report of one assembly (plan PR 30, D8). The book is {@link Status#COMPLETE} when no
 * section is missing, skipped or stale, every paragraph cites a passage, every citation resolves, and no unsupported
 * number or quote, near-copy or open gap remains; otherwise it is an {@link Status#INCOMPLETE_DRAFT} with the counts.
 * A book without any written word (no outline, no sections or nothing written yet) is {@link Status#NOT_STARTED}.
 * The report only informs: the user may approve the book either way (Q27).
 */
public final class BookQualityReport {

    public enum Status {
        COMPLETE("complete"),
        INCOMPLETE_DRAFT("incomplete draft"),
        NOT_STARTED("not started");

        private final String label;

        Status(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** A kind of finding; {@code blocking} kinds make the book an incomplete draft. */
    public enum Kind {
        NO_OUTLINE("outline without sections", true),
        MISSING_SECTION("section without text", true),
        SKIPPED_SECTION("skipped section", true),
        STALE_SECTION("stale section", true),
        THIN_SECTION("thin section", false),
        UNCITED_SENTENCE("uncited sentence", false),
        UNCITED_PARAGRAPH("uncited paragraph", true),
        UNSUPPORTED_NUMBER("unsupported number", true),
        UNSUPPORTED_QUOTE("unsupported quote", true),
        UNRESOLVED_CITATION("unresolved citation", true),
        OPEN_GAP("open gap", true),
        UNCOVERED_CONCEPT_NODE("concept node uncovered at the Sources approval", false),
        LATE_SOURCE("source accepted after the Sources approval", false),
        NEAR_COPY("near-copy of a cited passage", true),
        REPEATED_PASSAGE("passage repeated across sections", false),
        UNUSED_GLOSSARY_TERM("glossary term never used", false),
        ACRONYM_WITHOUT_LONG_FORM("acronym without a long form in the sources", false),
        ACRONYM_CONFLICT("acronym with conflicting long forms", false),
        ACRONYM_FIRST_USE_WITHOUT_LONG_FORM("chapter's first use of an acronym lacks its long form", false),
        WORDS_OFF_TARGET("section far from its word target", false);

        private final String label;
        private final boolean blocking;

        Kind(String label, boolean blocking) {
            this.label = label;
            this.blocking = blocking;
        }

        public String label() {
            return label;
        }

        public boolean isBlocking() {
            return blocking;
        }
    }

    /** One finding: its kind, the section (or "" for the book) and a short detail. */
    public static final class Finding {
        private final Kind kind;
        private final String sectionId;
        private final String detail;

        Finding(Kind kind, String sectionId, String detail) {
            this.kind = kind;
            this.sectionId = sectionId == null ? "" : sectionId;
            this.detail = detail == null ? "" : detail;
        }

        public Kind getKind() {
            return kind;
        }

        public String getSectionId() {
            return sectionId;
        }

        public String getDetail() {
            return detail;
        }

        @Override
        public String toString() {
            return kind.label() + (detail.isEmpty() ? "" : ": " + detail);
        }
    }

    private final List<Finding> findings;
    private final int sentences;
    private final int paragraphs;
    private final int wordsWritten;
    private final int wordsTarget;

    BookQualityReport(List<Finding> findings, int sentences, int paragraphs, int wordsWritten, int wordsTarget) {
        this.findings = Collections.unmodifiableList(new ArrayList<Finding>(findings));
        this.sentences = sentences;
        this.paragraphs = paragraphs;
        this.wordsWritten = wordsWritten;
        this.wordsTarget = wordsTarget;
    }

    public Status getStatus() {
        if (wordsWritten == 0) {
            return Status.NOT_STARTED; // a book without a written word is never complete
        }
        for (Finding finding : findings) {
            if (finding.getKind().isBlocking()) {
                return Status.INCOMPLETE_DRAFT;
            }
        }
        return Status.COMPLETE;
    }

    public boolean isComplete() {
        return getStatus() == Status.COMPLETE;
    }

    public List<Finding> getFindings() {
        return findings;
    }

    public List<Finding> findings(Kind kind) {
        List<Finding> out = new ArrayList<Finding>();
        for (Finding finding : findings) {
            if (finding.getKind() == kind) {
                out.add(finding);
            }
        }
        return out;
    }

    public int count(Kind kind) {
        return findings(kind).size();
    }

    /** The count per kind, in kind order, for kinds that occur. */
    public Map<Kind, Integer> counts() {
        Map<Kind, Integer> counts = new EnumMap<Kind, Integer>(Kind.class);
        for (Finding finding : findings) {
            Integer n = counts.get(finding.getKind());
            counts.put(finding.getKind(), n == null ? 1 : n + 1);
        }
        return counts;
    }

    public int getSentences() {
        return sentences;
    }

    public int getParagraphs() {
        return paragraphs;
    }

    public int getWordsWritten() {
        return wordsWritten;
    }

    public int getWordsTarget() {
        return wordsTarget;
    }

    /** Share of uncited sentences in percent (0 for a book without sentences). */
    public int uncitedSentencePercent() {
        return percent(count(Kind.UNCITED_SENTENCE), sentences);
    }

    /** Share of uncited paragraphs in percent (0 for a book without paragraphs). */
    public int uncitedParagraphPercent() {
        return percent(count(Kind.UNCITED_PARAGRAPH), paragraphs);
    }

    private static int percent(int part, int whole) {
        return whole == 0 ? 0 : (int) Math.round(100.0 * part / whole);
    }

    /**
     * One line per finding kind with its count, e.g. {@code "2 x open gap"}; what the {@code finish-document}
     * confirmation and the gate message name.
     */
    public List<String> warnings() {
        List<String> lines = new ArrayList<String>();
        for (Map.Entry<Kind, Integer> entry : counts().entrySet()) {
            lines.add(entry.getValue() + " x " + entry.getKey().label());
        }
        return lines;
    }

    /** A short summary: the status and, for a draft, the warnings. */
    public String summary() {
        StringBuilder sb = new StringBuilder("Book status: ").append(getStatus().label());
        List<String> warnings = warnings();
        if (!warnings.isEmpty()) {
            sb.append(" (");
            for (int i = 0; i < warnings.size(); i++) {
                sb.append(i == 0 ? "" : "; ").append(warnings.get(i));
            }
            sb.append(')');
        }
        return sb.toString();
    }

    /** The report as Markdown, for the Document tab and the final pin. */
    public String toMarkdown() {
        StringBuilder sb = new StringBuilder();
        sb.append("# Quality report\n\n");
        sb.append("- Status: ").append(getStatus().label()).append('\n');
        sb.append("- Words written: ").append(wordsWritten).append(" of ").append(wordsTarget).append(" planned\n");
        sb.append("- Uncited sentences: ").append(count(Kind.UNCITED_SENTENCE)).append(" of ").append(sentences)
                .append(" (").append(uncitedSentencePercent()).append(" %)\n");
        sb.append("- Uncited paragraphs: ").append(count(Kind.UNCITED_PARAGRAPH)).append(" of ").append(paragraphs)
                .append(" (").append(uncitedParagraphPercent()).append(" %)\n");
        for (Kind kind : Kind.values()) {
            List<Finding> list = findings(kind);
            if (list.isEmpty() || kind == Kind.UNCITED_SENTENCE || kind == Kind.UNCITED_PARAGRAPH) {
                continue;
            }
            sb.append("\n## ").append(capitalize(kind.label())).append(" (").append(list.size()).append(")\n\n");
            for (Finding finding : list) {
                sb.append("- ").append(finding.getDetail()).append('\n');
            }
        }
        return sb.toString();
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1);
    }
}
