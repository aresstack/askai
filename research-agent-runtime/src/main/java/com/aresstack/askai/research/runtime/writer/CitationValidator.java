package com.aresstack.askai.research.runtime.writer;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks the markers of a writer answer (plan D8, stage 1, PR 28). It strips markers that name no passage of the
 * pack and reports them, splits the text into paragraphs and sentences, keeps the cited numbers per sentence,
 * flags uncited sentences and paragraphs and turns {@code GAP:} lines into open points. Headings and list bullets,
 * which the writer must not produce, are reduced to plain text.
 *
 * <p>What is no prose leaves the text (live run 4, books 2 and 3): a heading or list line without a sentence end and
 * without a marker, and a paragraph made only of such words, is a label the writer echoed (its key points, run
 * together into one line); a sentence the answer, or an earlier part of the same section, already contains is a
 * repeat; a paragraph of uncited questions holds the writer's open questions, which become open points like
 * {@code GAP:} lines. A {@code GAP:} line that repeats a sentence of the text (or of an earlier part) or another
 * {@code GAP:} line is no open point (live run 4, book 5: 12 of 23 gap lines were sentences of the section's own
 * text, and each became a search query); it is dropped and reported. A finding about a claim is still only a flag:
 * no claim is removed. Stateless and deterministic.</p>
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
    /** A part ending in a short abbreviation ("z.", "B.", "Dr.", "ca.", "vgl.") continues in the next part. */
    private static final Pattern ABBREVIATION = Pattern.compile(
            "(?:^|\\s)(?:\\p{L}|ca|dr|nr|vgl|bzw|usw|etc|prof|abb|kap|mio|mrd|jh|evtl|ggf|inkl|tab|e\\.g|i\\.e|vs)\\.$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern LEADING_MARKERS = Pattern.compile("^((?:\\s*" + MARKER.pattern() + ")+)\\s*");
    /** A sentence end anywhere: prose has one, a label (a heading, a key point, a list item) has none. */
    private static final Pattern ANY_SENTENCE_END = Pattern.compile("[.!?]");
    /** Closing quotes and brackets after a question mark: {@code "Wer zahlt?“}. */
    private static final Pattern TRAILING_CLOSERS = Pattern.compile("[\"'“”»«’)]+$");
    /** The closing punctuation of a sentence, with its quotes and brackets: no part of what it says. */
    private static final Pattern SENTENCE_CLOSE = Pattern.compile("[\\s.!?…\"'“”»«’)]+$");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

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
        private final List<String> labelLines;
        private final List<String> repeatedSentences;
        private final List<String> questions;
        private final List<String> droppedGaps;

        Result(List<Paragraph> paragraphs, List<String> strippedMarkers, List<String> gaps, List<ClaimFlag> flags) {
            this(paragraphs, strippedMarkers, gaps, flags, Collections.<String>emptyList(),
                    Collections.<String>emptyList(), Collections.<String>emptyList());
        }

        Result(List<Paragraph> paragraphs, List<String> strippedMarkers, List<String> gaps, List<ClaimFlag> flags,
               List<String> labelLines, List<String> repeatedSentences, List<String> questions) {
            this(paragraphs, strippedMarkers, gaps, flags, labelLines, repeatedSentences, questions,
                    Collections.<String>emptyList());
        }

        Result(List<Paragraph> paragraphs, List<String> strippedMarkers, List<String> gaps, List<ClaimFlag> flags,
               List<String> labelLines, List<String> repeatedSentences, List<String> questions,
               List<String> droppedGaps) {
            this.paragraphs = Collections.unmodifiableList(paragraphs);
            this.strippedMarkers = Collections.unmodifiableList(strippedMarkers);
            this.gaps = Collections.unmodifiableList(gaps);
            this.flags = Collections.unmodifiableList(flags);
            this.labelLines = Collections.unmodifiableList(new ArrayList<String>(labelLines));
            this.repeatedSentences = Collections.unmodifiableList(new ArrayList<String>(repeatedSentences));
            this.questions = Collections.unmodifiableList(new ArrayList<String>(questions));
            this.droppedGaps = Collections.unmodifiableList(new ArrayList<String>(droppedGaps));
        }

        /**
         * {@code GAP:} lines that are no open point: a sentence of the text, another {@code GAP:} line, or what
         * {@link #withoutGaps} took out (the writer's word target, a sentence the repair asked to leave out).
         */
        public List<String> getDroppedGaps() {
            return droppedGaps;
        }

        /** A copy without the open points that {@code drop} accepts; they join {@link #getDroppedGaps()}. */
        Result withoutGaps(java.util.function.Predicate<String> drop) {
            List<String> kept = new ArrayList<String>();
            List<String> dropped = new ArrayList<String>(droppedGaps);
            for (String point : gaps) {
                if (drop.test(point)) {
                    dropped.add(point);
                } else {
                    kept.add(point);
                }
            }
            if (dropped.size() == droppedGaps.size()) {
                return this;
            }
            return new Result(paragraphs, strippedMarkers, kept, flags, labelLines, repeatedSentences, questions,
                    dropped);
        }

        /** Headings, key points and list items without a sentence end or a marker; removed from the text. */
        public List<String> getLabelLines() {
            return labelLines;
        }

        /** Sentences the answer or an earlier part of the section already contained; removed from the text. */
        public List<String> getRepeatedSentences() {
            return repeatedSentences;
        }

        /** The uncited questions of question-only paragraphs; removed from the text, kept as open points. */
        public List<String> getQuestions() {
            return questions;
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
        return validate(answer, passageCount, Collections.<String>emptySet());
    }

    /**
     * @param answer       the writer's plain-text answer
     * @param passageCount k, the number of passages in the pack: valid markers are {@code [1]..[k]}
     * @param written      the {@linkplain #sentenceKeys(String) sentence keys} of the section's earlier parts: a
     *                     sentence of this answer that repeats one of them is removed like a repeat within it
     */
    public Result validate(String answer, int passageCount, Set<String> written) {
        List<String> stripped = new ArrayList<String>();
        List<String> gapLines = new ArrayList<String>();
        List<String> questionPoints = new ArrayList<String>();
        List<String> labelLines = new ArrayList<String>();
        List<String> repeated = new ArrayList<String>();
        List<String> questions = new ArrayList<String>();
        List<Paragraph> paragraphs = new ArrayList<Paragraph>();
        List<StringBuilder> blocks = new ArrayList<StringBuilder>();
        StringBuilder current = null;
        for (String raw : (answer == null ? "" : answer).replace("\r\n", "\n").split("\n", -1)) {
            Matcher gap = GAP.matcher(raw);
            if (gap.matches()) {
                String point = gap.group(1).trim();
                if (!point.isEmpty()) {
                    gapLines.add(point);
                }
                current = null;
                continue;
            }
            boolean structured = HEADING.matcher(raw).find() || BULLET.matcher(raw).find();
            String line = BULLET.matcher(HEADING.matcher(raw).replaceFirst("")).replaceFirst("").trim();
            if (line.isEmpty()) {
                current = null;
                continue;
            }
            if (structured && isLabel(line)) {
                labelLines.add(line); // a heading or list item ends the paragraph it would have been glued to
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
        Set<String> seen = new HashSet<String>(written == null ? Collections.<String>emptySet() : written);
        Set<String> prose = new HashSet<String>(seen); // what the text says: no open point
        Set<String> openPoints = new HashSet<String>();
        for (String point : gapLines) {
            openPoints.add(pointKey(point));
        }
        for (StringBuilder block : blocks) {
            String text = stripUnknown(block.toString(), passageCount, stripped);
            if (text.isEmpty()) {
                continue;
            }
            if (isLabel(text)) {
                labelLines.add(text); // key points written as plain lines: one line of words without a sentence
                continue;
            }
            List<Sentence> kept = new ArrayList<Sentence>();
            for (Sentence sentence : sentences(text)) {
                String key = key(sentence.plainText());
                if (!key.isEmpty() && !seen.add(key)) {
                    repeated.add(sentence.getText());
                    continue;
                }
                kept.add(sentence);
            }
            if (kept.isEmpty()) {
                continue;
            }
            if (onlyUncitedQuestions(kept)) {
                for (Sentence question : kept) {
                    String point = question.plainText();
                    questions.add(point);
                    if (openPoints.add(key(point))) {
                        questionPoints.add(point);
                    }
                }
                continue;
            }
            for (Sentence sentence : kept) {
                prose.add(key(sentence.plainText()));
            }
            paragraphs.add(new Paragraph(kept));
        }
        List<String> gaps = new ArrayList<String>();
        List<String> droppedGaps = new ArrayList<String>();
        Set<String> gapKeys = new HashSet<String>();
        for (String point : gapLines) {
            String key = pointKey(point);
            if (prose.contains(key) || !gapKeys.add(key)) {
                droppedGaps.add(point); // a sentence of the text, or the same gap again
            } else {
                gaps.add(point);
            }
        }
        gaps.addAll(questionPoints);
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
        return new Result(paragraphs, stripped, gaps, flags, labelLines, repeated, questions, droppedGaps);
    }

    /** The {@linkplain #key(String) key} of an open point or a listed sentence, its markers left out. */
    static String pointKey(String text) {
        return key(new Sentence(text == null ? "" : text, Collections.<Integer>emptySet()).plainText());
    }

    /**
     * The keys of a written text's sentences (case, spacing, closing punctuation and markers ignored), so a later
     * part of the same section can leave out what this text already says.
     */
    public static Set<String> sentenceKeys(String text) {
        Set<String> keys = new HashSet<String>();
        for (String paragraph : (text == null ? "" : text).split("\\n\\s*\\n")) {
            for (Sentence sentence : sentences(paragraph.replace('\n', ' ').trim())) {
                String key = key(sentence.plainText());
                if (!key.isEmpty()) {
                    keys.add(key);
                }
            }
        }
        return keys;
    }

    /** A line or paragraph without any sentence end and without a marker: a label, not prose. */
    static boolean isLabel(String text) {
        return !ANY_SENTENCE_END.matcher(text).find() && !MARKER.matcher(text).find();
    }

    private static boolean onlyUncitedQuestions(List<Sentence> sentences) {
        for (Sentence sentence : sentences) {
            if (!sentence.getCited().isEmpty()
                    || !TRAILING_CLOSERS.matcher(sentence.plainText()).replaceAll("").endsWith("?")) {
                return false;
            }
        }
        return true;
    }

    /**
     * What a repeat is found by: case, spacing and the closing punctuation are ignored, every other character counts,
     * so "-20 °C" and "20 °C", or "5 %" and "5 ‰", stay two claims.
     */
    private static String key(String text) {
        String n = Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        return SENTENCE_CLOSE.matcher(WHITESPACE.matcher(n).replaceAll(" ").trim()).replaceAll("");
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
            if (!parts.isEmpty() && (startsLowercase(part) || ABBREVIATION.matcher(parts.get(parts.size() - 1)).find())) {
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
