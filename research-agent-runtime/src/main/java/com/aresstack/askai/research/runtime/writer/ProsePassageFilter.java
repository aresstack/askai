package com.aresstack.askai.research.runtime.writer;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tells prose passages from page boilerplate (live run 3, fix 2): navigation and footer text ("Vertriebsteam
 * kontaktieren Alle Produkte durchsuchen … Investor Relations …"), breadcrumbs and bare headings ("Analyse der
 * Implementierungskosten Anfängliche Investitionsanforderungen") are no evidence, because they carry no statement a
 * sentence could rest on. The same rule the Sources phase applies to a whole page (too little text to count) applies
 * here to one passage, with structural criteria only and no word lists:
 * <ol>
 * <li>at least {@link Criteria#getMinWords() min words} words;</li>
 * <li>at least {@link Criteria#getMinSentences() min sentences} finished sentences (a {@code .}, {@code !} or
 * {@code ?} after a letter, digit or closing bracket/quote, followed by white space or the end; the dot of
 * {@code dormakaba.com} does not count);</li>
 * <li>no run of more than {@link Criteria#getMaxUnpunctuatedRun() max run} words without any punctuation: menu
 * labels follow each other without commas or full stops, prose does not.</li>
 * </ol>
 * Stateless and deterministic; every bound is a setting.
 */
public final class ProsePassageFilter {

    /** A finished sentence: terminal punctuation after a word character or closer, then space or end. */
    private static final Pattern SENTENCE_END = Pattern.compile("[\\p{L}\\p{Nd}\\)\"“”»’'][.!?]+(?=\\s|$)");
    /** Any mark that interrupts a run of words in prose. */
    private static final Pattern PUNCTUATION = Pattern.compile("[.,;:!?()\\[\\]–—\"“”„«»]");

    /** The thresholds; immutable. */
    public static final class Criteria {
        /** Default minimum words: shorter passages are headings or labels (the live-run headings had 5 and 8). */
        public static final int DEFAULT_MIN_WORDS = 10;
        /** Default minimum finished sentences. */
        public static final int DEFAULT_MIN_SENTENCES = 1;
        /** Default longest punctuation-free run; the hexagon.com footer of live run 3 had more than 60 words. */
        public static final int DEFAULT_MAX_UNPUNCTUATED_RUN = 30;

        private final int minWords;
        private final int minSentences;
        private final int maxUnpunctuatedRun;

        public Criteria(int minWords, int minSentences, int maxUnpunctuatedRun) {
            if (minWords < 0 || minSentences < 0 || maxUnpunctuatedRun < 1) {
                throw new IllegalArgumentException("prose criteria must not be negative and the run must be >= 1");
            }
            this.minWords = minWords;
            this.minSentences = minSentences;
            this.maxUnpunctuatedRun = maxUnpunctuatedRun;
        }

        public static Criteria defaults() {
            return new Criteria(DEFAULT_MIN_WORDS, DEFAULT_MIN_SENTENCES, DEFAULT_MAX_UNPUNCTUATED_RUN);
        }

        public int getMinWords() {
            return minWords;
        }

        public int getMinSentences() {
            return minSentences;
        }

        public int getMaxUnpunctuatedRun() {
            return maxUnpunctuatedRun;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Criteria)) {
                return false;
            }
            Criteria c = (Criteria) o;
            return c.minWords == minWords && c.minSentences == minSentences
                    && c.maxUnpunctuatedRun == maxUnpunctuatedRun;
        }

        @Override
        public int hashCode() {
            return (minWords * 31 + minSentences) * 31 + maxUnpunctuatedRun;
        }

        @Override
        public String toString() {
            return "words>=" + minWords + ", sentences>=" + minSentences + ", run<=" + maxUnpunctuatedRun;
        }
    }

    private final Criteria criteria;

    public ProsePassageFilter(Criteria criteria) {
        this.criteria = criteria == null ? Criteria.defaults() : criteria;
    }

    public Criteria getCriteria() {
        return criteria;
    }

    /** Whether the passage is prose that can carry a statement. */
    public boolean isProse(String text) {
        return reason(text).isEmpty();
    }

    /** Why the passage is boilerplate ("5 words < 10", ...); empty when it is prose. */
    public String reason(String text) {
        String t = text == null ? "" : text.replace('­', ' ').trim();
        String[] words = t.isEmpty() ? new String[0] : t.split("\\s+");
        if (words.length < criteria.minWords) {
            return words.length + " words < " + criteria.minWords;
        }
        int sentences = 0;
        Matcher end = SENTENCE_END.matcher(t);
        while (end.find()) {
            sentences++;
        }
        if (sentences < criteria.minSentences) {
            return sentences + " finished sentences < " + criteria.minSentences;
        }
        int run = 0;
        int longest = 0;
        for (String word : words) {
            String bare = PUNCTUATION.matcher(word).replaceAll("");
            boolean punctuated = bare.length() != word.length();
            if (!bare.isEmpty()) {
                run++;
                longest = Math.max(longest, run);
            }
            if (punctuated) {
                run = 0;
            }
        }
        if (longest > criteria.maxUnpunctuatedRun) {
            return longest + " words without punctuation > " + criteria.maxUnpunctuatedRun;
        }
        return "";
    }
}
