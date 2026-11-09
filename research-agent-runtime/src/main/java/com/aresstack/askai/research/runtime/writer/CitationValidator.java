package com.aresstack.askai.research.runtime.writer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks the markers of a writer answer (plan D8, stage 1, PR 28). It strips markers that name no passage of the
 * pack and reports them, splits the text into paragraphs and sentences, keeps the cited numbers per sentence,
 * flags uncited sentences and paragraphs and turns {@code GAP:} lines into open points. Headings and list bullets,
 * which the writer must not produce, are reduced to plain text. Stateless and deterministic.
 */
public final class CitationValidator {

    /** {@code [3]}, {@code [1, 4]}, {@code [2; 5]}. */
    static final Pattern MARKER = Pattern.compile("\\[(\\d{1,3}(?:\\s*[,;]\\s*\\d{1,3})*)\\]");
    private static final Pattern GAP = Pattern.compile("^\\s*(?:[-*]\\s*)?GAP\\s*:\\s*(.*)$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern HEADING = Pattern.compile("^\\s*#{1,6}\\s+");
    private static final Pattern BULLET = Pattern.compile("^\\s*(?:[-*•]|\\d{1,2}[.)])\\s+");
    /** A sentence ends at . ! ? (optionally a closing quote or bracket) followed by whitespace. */
    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?][\"“”»’')]?)\\s+");
    private static final Pattern LEADING_MARKERS = Pattern.compile("^((?:\\s*" + MARKER.pattern() + ")+)\\s*");

    /** One sentence with the passage numbers it cites. */
    public static final class Sentence {
        private final String text;
        private final Set<Integer> cited;

        Sentence(String text, Set<Integer> cited) {
            this.text = text;
            this.cited = Collections.unmodifiableSet(new TreeSet<Integer>(cited));
        }

        public String getText() {
            return text;
        }

        public Set<Integer> getCited() {
            return cited;
        }

        /** The sentence without its markers, for the claim check. */
        public String plainText() {
            return MARKER.matcher(text).replaceAll("").replaceAll("\\s+([.,;:!?])", "$1").trim();
        }
    }

    /** One paragraph: its sentences and the union of their citations. */
    public static final class Paragraph {
        private final List<Sentence> sentences;
        private final Set<Integer> cited;

        Paragraph(List<Sentence> sentences) {
            this.sentences = Collections.unmodifiableList(new ArrayList<Sentence>(sentences));
            Set<Integer> all = new TreeSet<Integer>();
            for (Sentence s : sentences) {
                all.addAll(s.getCited());
            }
            this.cited = Collections.unmodifiableSet(all);
        }

        public List<Sentence> getSentences() {
            return sentences;
        }

        public Set<Integer> getCited() {
            return cited;
        }

        public String text() {
            StringBuilder sb = new StringBuilder();
            for (Sentence s : sentences) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(s.getText());
            }
            return sb.toString();
        }
    }

    /** The checked answer. */
    public static final class Result {
        private final List<Paragraph> paragraphs;
        private final List<String> strippedMarkers;
        private final List<String> gaps;
        private final List<ClaimFlag> flags;

        Result(List<Paragraph> paragraphs, List<String> strippedMarkers, List<String> gaps, List<ClaimFlag> flags) {
            this.paragraphs = Collections.unmodifiableList(paragraphs);
            this.strippedMarkers = Collections.unmodifiableList(strippedMarkers);
            this.gaps = Collections.unmodifiableList(gaps);
            this.flags = Collections.unmodifiableList(flags);
        }

        public List<Paragraph> getParagraphs() {
            return paragraphs;
        }

        /** Markers that named no passage of the pack, as written (e.g. {@code [9]}); removed from the text. */
        public List<String> getStrippedMarkers() {
            return strippedMarkers;
        }

        /** The text of the {@code GAP:} lines: the section's open points. */
        public List<String> getGaps() {
            return gaps;
        }

        /** Uncited sentences and paragraphs. */
        public List<ClaimFlag> getFlags() {
            return flags;
        }

        /** Every passage number the text cites, ascending. */
        public Set<Integer> citedNumbers() {
            Set<Integer> all = new TreeSet<Integer>();
            for (Paragraph p : paragraphs) {
                all.addAll(p.getCited());
            }
            return all;
        }

        /** The section text: paragraphs separated by a blank line, markers kept, gap lines removed. */
        public String text() {
            StringBuilder sb = new StringBuilder();
            for (Paragraph p : paragraphs) {
                if (sb.length() > 0) {
                    sb.append("\n\n");
                }
                sb.append(p.text());
            }
            return sb.toString();
        }

        public int wordCount() {
            String plain = MARKER.matcher(text()).replaceAll(" ").trim();
            return plain.isEmpty() ? 0 : plain.split("\\s+").length;
        }
    }

    /**
     * @param answer      the writer's plain-text answer
     * @param passageCount k, the number of passages in the pack: valid markers are {@code [1]..[k]}
     */
    public Result validate(String answer, int passageCount) {
        List<String> stripped = new ArrayList<String>();
        List<String> gaps = new ArrayList<String>();
        List<Paragraph> paragraphs = new ArrayList<Paragraph>();
        List<StringBuilder> blocks = new ArrayList<StringBuilder>();
        StringBuilder current = null;
        for (String raw : (answer == null ? "" : answer).replace("\r\n", "\n").split("\n", -1)) {
            Matcher gap = GAP.matcher(raw);
            if (gap.matches()) {
                String point = gap.group(1).trim();
                if (!point.isEmpty()) {
                    gaps.add(point);
                }
                current = null;
                continue;
            }
            String line = BULLET.matcher(HEADING.matcher(raw).replaceFirst("")).replaceFirst("").trim();
            if (line.isEmpty()) {
                current = null;
                continue;
            }
            if (current == null) {
                current = new StringBuilder();
                blocks.add(current);
            } else {
                current.append(' ');
            }
            current.append(line);
        }
        for (StringBuilder block : blocks) {
            String text = stripUnknown(block.toString(), passageCount, stripped);
            List<Sentence> sentences = sentences(text);
            if (!sentences.isEmpty()) {
                paragraphs.add(new Paragraph(sentences));
            }
        }
        List<ClaimFlag> flags = new ArrayList<ClaimFlag>();
        for (int p = 0; p < paragraphs.size(); p++) {
            Paragraph paragraph = paragraphs.get(p);
            if (paragraph.getCited().isEmpty()) {
                flags.add(new ClaimFlag(ClaimFlag.Kind.UNCITED_PARAGRAPH, p, paragraph.text(), ""));
            }
            for (Sentence s : paragraph.getSentences()) {
                if (s.getCited().isEmpty()) {
                    flags.add(new ClaimFlag(ClaimFlag.Kind.UNCITED_SENTENCE, p, s.getText(), ""));
                }
            }
        }
        return new Result(paragraphs, stripped, gaps, flags);
    }

    /** Removes the numbers of a marker outside 1..k; a marker left empty disappears with its leading space. */
    static String stripUnknown(String text, int k, List<String> stripped) {
        Matcher m = MARKER.matcher(text);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            List<String> kept = new ArrayList<String>();
            for (String n : m.group(1).split("[,;]")) {
                int number = Integer.parseInt(n.trim());
                if (number >= 1 && number <= k) {
                    kept.add(String.valueOf(number));
                } else {
                    stripped.add("[" + number + "]");
                }
            }
            String replacement = kept.isEmpty() ? "" : "[" + String.join(", ", kept) + "]";
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        return out.toString().replaceAll("\\s+([.,;:!?])", "$1").replaceAll("\\s{2,}", " ").trim();
    }

    /** Splits a paragraph into sentences; markers written after the full stop belong to the sentence before. */
    static List<Sentence> sentences(String paragraph) {
        List<String> parts = new ArrayList<String>();
        for (String piece : SENTENCE_END.split(paragraph)) {
            String part = piece.trim();
            if (part.isEmpty()) {
                continue;
            }
            Matcher lead = LEADING_MARKERS.matcher(part);
            if (!parts.isEmpty() && lead.find()) {
                int last = parts.size() - 1;
                parts.set(last, parts.get(last) + " " + lead.group(1).trim());
                part = part.substring(lead.end()).trim();
                if (part.isEmpty()) {
                    continue;
                }
            }
            if (!parts.isEmpty() && startsLowercase(part)) {
                int last = parts.size() - 1; // "z. B. die" – an abbreviation, not a sentence end
                parts.set(last, parts.get(last) + " " + part);
                continue;
            }
            parts.add(part);
        }
        List<Sentence> sentences = new ArrayList<Sentence>();
        for (String part : parts) {
            Set<Integer> cited = new LinkedHashSet<Integer>();
            Matcher m = MARKER.matcher(part);
            while (m.find()) {
                for (String n : m.group(1).split("[,;]")) {
                    cited.add(Integer.parseInt(n.trim()));
                }
            }
            sentences.add(new Sentence(part, cited));
        }
        return sentences;
    }

    private static boolean startsLowercase(String part) {
        int cp = part.codePointAt(0);
        return Character.isLowerCase(cp);
    }
}
