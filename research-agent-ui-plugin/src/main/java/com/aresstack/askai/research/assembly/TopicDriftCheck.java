package com.aresstack.askai.research.assembly;

import com.aresstack.askai.research.runtime.writer.SentenceSimilarity;

import com.aresstack.askai.research.outline.Outline;
import com.aresstack.askai.research.outline.OutlineBrief;
import com.aresstack.askai.research.outline.OutlineChapter;
import com.aresstack.askai.research.outline.OutlineSection;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Topic drift of a written section (live run 3, fix 4): section 1.4 "Implementierung mit Kosten und Grenzen" spent two
 * sentences on radio alarm systems ("Funkalarmanlagen") because the corpus held an alarm vendor's blog. A drifting
 * section keeps returning to a subject that the book never set out to treat. The check is lexical and deterministic:
 * a term of at least {@link Criteria#getMinTermLength() min length} letters that occurs in at least
 * {@link Criteria#getMinSentences() min sentences} sentences of one section, and whose stem (first
 * {@link Criteria#getStemLength() stem length} letters) appears nowhere in the book's frame — the mission/title, the
 * glossary and every title, purpose and key point of the approved outline — is reported as a foreign subject. With
 * {@link Criteria#isNounsOnly() nouns only} (default, for German) a term counts only where it is written with a capital
 * letter inside a sentence, so verbs and adjectives ("ermöglichen", "umfassende") are no subjects. The finding only
 * warns: on live run 3 it names "Funkalarmanlagen" in 1.4 and also "Echtzeitüberwachung" in 1.2 and "Intelligenz"
 * and "Revolution" in 1.1, subjects the outline never mentions.
 * Embedding similarity to the section brief was measured on live run 3 with bge-m3 and does not separate the alarm
 * sentences from on-topic ones (0.43 / 0.53 against 0.42–0.82), so it never RAISES a finding. It only takes one back:
 * with the sentence-embedding slot, a lexical candidate whose every sentence reaches
 * {@link Criteria#getMinFrameSimilarity() min frame similarity} to a line of the book's frame (title, outline titles,
 * purposes, key points, glossary) is no foreign subject; the default lies above the alarm sentences (0.53).
 */
public final class TopicDriftCheck {

    /** Thresholds; immutable. */
    public static final class Criteria {
        /** Default: shorter words are too general to name a subject of their own. */
        public static final int DEFAULT_MIN_TERM_LENGTH = 10;
        /** Default: a subject the section returns to, not a passing mention. */
        public static final int DEFAULT_MIN_SENTENCES = 2;
        /** Default stem, the same as the claim check's. */
        public static final int DEFAULT_STEM_LENGTH = 5;

        private final int minTermLength;
        private final int minSentences;
        private final int stemLength;
        private final boolean nounsOnly;
        private final double minFrameSimilarity;

        public Criteria(int minTermLength, int minSentences, int stemLength, boolean nounsOnly) {
            this(minTermLength, minSentences, stemLength, nounsOnly, DEFAULT_MIN_FRAME_SIMILARITY);
        }

        public Criteria(int minTermLength, int minSentences, int stemLength, boolean nounsOnly,
                        double minFrameSimilarity) {
            if (minTermLength < 1 || minSentences < 1 || stemLength < 1) {
                throw new IllegalArgumentException("topic drift thresholds must be >= 1");
            }
            if (minFrameSimilarity < 0 || minFrameSimilarity > 1) {
                throw new IllegalArgumentException("frame similarity must be within 0..1");
            }
            this.minTermLength = minTermLength;
            this.minSentences = minSentences;
            this.stemLength = stemLength;
            this.nounsOnly = nounsOnly;
            this.minFrameSimilarity = minFrameSimilarity;
        }

        /** Default: German capitalises nouns, so capitalisation inside a sentence marks a subject. */
        public static final boolean DEFAULT_NOUNS_ONLY = true;
        /**
         * Default cosine (sentence-embedding slot) of every sentence of a candidate to the frame from which the
         * candidate is on topic after all; above the live-run-3 alarm sentences (0.43, 0.53). 0 = lexical only.
         */
        public static final double DEFAULT_MIN_FRAME_SIMILARITY = 0.70;


        public static Criteria defaults() {
            return new Criteria(DEFAULT_MIN_TERM_LENGTH, DEFAULT_MIN_SENTENCES, DEFAULT_STEM_LENGTH,
                    DEFAULT_NOUNS_ONLY);
        }

        public double getMinFrameSimilarity() {
            return minFrameSimilarity;
        }

        public boolean isNounsOnly() {
            return nounsOnly;
        }

        public int getMinTermLength() {
            return minTermLength;
        }

        public int getMinSentences() {
            return minSentences;
        }

        public int getStemLength() {
            return stemLength;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Criteria && ((Criteria) o).minTermLength == minTermLength
                    && ((Criteria) o).minSentences == minSentences && ((Criteria) o).stemLength == stemLength
                    && ((Criteria) o).nounsOnly == nounsOnly
                    && Double.compare(((Criteria) o).minFrameSimilarity, minFrameSimilarity) == 0;
        }

        @Override
        public int hashCode() {
            return ((minTermLength * 31 + minSentences) * 31 + stemLength) * 2 + (nounsOnly ? 1 : 0);
        }
    }

    private final Criteria criteria;
    private final Set<String> frame;
    private final List<String> frameLines = new ArrayList<String>();
    private final SentenceSimilarity similarity;

    /** @param title the book title (the mission); with the outline and the glossary it is the book's frame */
    public TopicDriftCheck(Criteria criteria, String title, Outline outline, List<String> glossary) {
        this(criteria, title, outline, glossary, SentenceSimilarity.none());
    }

    /** @param similarity the sentence-embedding slot; {@link SentenceSimilarity#none()} keeps the check lexical */
    public TopicDriftCheck(Criteria criteria, String title, Outline outline, List<String> glossary,
                           SentenceSimilarity similarity) {
        this.criteria = criteria == null ? Criteria.defaults() : criteria;
        this.similarity = similarity == null ? SentenceSimilarity.none() : similarity;
        StringBuilder text = new StringBuilder(title == null ? "" : title);
        if (outline != null) {
            for (OutlineChapter chapter : outline.getChapters()) {
                append(text, chapter.getTitle(), chapter.getBrief());
                for (OutlineSection section : chapter.getSections()) {
                    append(text, section.getTitle(), section.getBrief());
                }
            }
        }
        if (glossary != null) {
            for (String term : glossary) {
                text.append('\n').append(term == null ? "" : term);
            }
        }
        for (String line : text.toString().split("\n")) {
            if (!line.trim().isEmpty()) {
                frameLines.add(line.trim());
            }
        }
        Set<String> stems = new HashSet<String>();
        for (String word : words(text.toString())) {
            stems.add(stem(word));
        }
        this.frame = stems;
    }

    private static void append(StringBuilder text, String title, OutlineBrief brief) {
        text.append('\n').append(title == null ? "" : title);
        if (brief != null) {
            text.append('\n').append(brief.getPurpose() == null ? "" : brief.getPurpose());
            for (String point : brief.getKeyPoints()) {
                text.append('\n').append(point == null ? "" : point);
            }
        }
    }

    /**
     * The foreign subjects of one section, as written ("Funkalarmanlagen (2 sentences)"), in order of first use.
     *
     * @param sentences the section's sentences without markers
     */
    public List<String> foreignSubjects(List<String> sentences) {
        Map<String, String> firstForm = new LinkedHashMap<String, String>();
        Map<String, Integer> count = new LinkedHashMap<String, Integer>();
        Map<String, List<String>> where = new LinkedHashMap<String, List<String>>();
        for (String sentence : sentences) {
            Set<String> seen = new LinkedHashSet<String>();
            List<String> words = rawWords(sentence);
            for (int i = 0; i < words.size(); i++) {
                String word = words.get(i);
                String lower = word.toLowerCase(Locale.ROOT);
                if (lower.length() < criteria.minTermLength) {
                    continue;
                }
                if (criteria.nounsOnly && (i == 0 || !Character.isUpperCase(word.charAt(0)))) {
                    continue;
                }
                String stem = stem(lower);
                if (frame.contains(stem) || !seen.add(stem)) {
                    continue;
                }
                if (!firstForm.containsKey(stem)) {
                    firstForm.put(stem, word);
                }
                Integer n = count.get(stem);
                count.put(stem, n == null ? 1 : n + 1);
                where.computeIfAbsent(stem, k -> new ArrayList<String>()).add(sentence);
            }
        }
        List<String> out = new ArrayList<String>();
        for (Map.Entry<String, Integer> entry : count.entrySet()) {
            if (entry.getValue() >= criteria.minSentences && !onTopic(where.get(entry.getKey()))) {
                out.add(firstForm.get(entry.getKey()) + " (" + entry.getValue() + " sentences)");
            }
        }
        return out;
    }

    /** Every sentence of the candidate is close to a frame line (sentence-embedding slot); false without it. */
    private boolean onTopic(List<String> candidateSentences) {
        double bound = criteria.getMinFrameSimilarity();
        if (bound <= 0 || !similarity.isAvailable() || frameLines.isEmpty() || candidateSentences == null) {
            return false;
        }
        for (String sentence : candidateSentences) {
            if (similarity.best(sentence, frameLines) < bound) {
                return false;
            }
        }
        return true;
    }

    private String stem(String word) {
        return word.length() <= criteria.stemLength ? word : word.substring(0, criteria.stemLength);
    }

    private static List<String> words(String text) {
        List<String> out = new ArrayList<String>();
        for (String w : rawWords(text)) {
            out.add(w.toLowerCase(Locale.ROOT));
        }
        return out;
    }

    /** Letter runs of a text in their written form; hyphens and digits split. */
    private static List<String> rawWords(String text) {
        String n = Normalizer.normalize(text == null ? "" : text.replace("­", ""), Normalizer.Form.NFC);
        List<String> out = new ArrayList<String>();
        for (String w : n.split("[^\\p{L}]+")) {
            if (!w.isEmpty()) {
                out.add(w);
            }
        }
        return out;
    }
}
