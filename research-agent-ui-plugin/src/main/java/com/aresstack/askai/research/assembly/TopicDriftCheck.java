package com.aresstack.askai.research.assembly;

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
 * sentences from on-topic ones (0.43 / 0.53 against 0.42–0.82), so it is not used.
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

        public Criteria(int minTermLength, int minSentences, int stemLength, boolean nounsOnly) {
            if (minTermLength < 1 || minSentences < 1 || stemLength < 1) {
                throw new IllegalArgumentException("topic drift thresholds must be >= 1");
            }
            this.minTermLength = minTermLength;
            this.minSentences = minSentences;
            this.stemLength = stemLength;
            this.nounsOnly = nounsOnly;
        }

        /** Default: German capitalises nouns, so capitalisation inside a sentence marks a subject. */
        public static final boolean DEFAULT_NOUNS_ONLY = true;

        public static Criteria defaults() {
            return new Criteria(DEFAULT_MIN_TERM_LENGTH, DEFAULT_MIN_SENTENCES, DEFAULT_STEM_LENGTH,
                    DEFAULT_NOUNS_ONLY);
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
                    && ((Criteria) o).nounsOnly == nounsOnly;
        }

        @Override
        public int hashCode() {
            return ((minTermLength * 31 + minSentences) * 31 + stemLength) * 2 + (nounsOnly ? 1 : 0);
        }
    }

    private final Criteria criteria;
    private final Set<String> frame;

    /** @param title the book title (the mission); with the outline and the glossary it is the book's frame */
    public TopicDriftCheck(Criteria criteria, String title, Outline outline, List<String> glossary) {
        this.criteria = criteria == null ? Criteria.defaults() : criteria;
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
            }
        }
        List<String> out = new ArrayList<String>();
        for (Map.Entry<String, Integer> entry : count.entrySet()) {
            if (entry.getValue() >= criteria.minSentences) {
                out.add(firstForm.get(entry.getKey()) + " (" + entry.getValue() + " sentences)");
            }
        }
        return out;
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
