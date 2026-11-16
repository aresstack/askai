package com.aresstack.askai.research.runtime.writer;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tells prose passages from page boilerplate (live run 3, fix 2): navigation and footer text ("Vertriebsteam
 * kontaktieren Alle Produkte durchsuchen … Investor Relations …"), breadcrumbs and bare headings ("Analyse der
 * Implementierungskosten Anfängliche Investitionsanforderungen") are no evidence, because they carry no statement a
 * sentence could rest on. The same rule the Sources phase applies to a whole page (too little text to count) applies
 * here to one passage, with structural criteria only and no word lists:
 * <ol>
 * <li>at least {@link Criteria#getMinWords() min words} words; a word holds at least one letter, so "©", "·",
 * "2026" and "12555" do not count (live run 4: "© 2026 Oxeltech GmbH · Mahlsdorfer Str. 10, 12555 Berlin" made
 * ten tokens with five words);</li>
 * <li>at least {@link Criteria#getMinSentences() min sentences} finished sentences (a {@code .}, {@code !} or
 * {@code ?} after a letter, digit or closing bracket/quote, followed by white space or the end; the dot of
 * {@code dormakaba.com} does not count);</li>
 * <li>no run of more than {@link Criteria#getMaxUnpunctuatedRun() max run} words without any punctuation: menu
 * labels follow each other without commas or full stops, prose does not.</li>
 * </ol>
 * One more rule needs the whole page ({@link #captions}): on a page that is almost no prose (a shop or listing
 * page), a short sentence standing alone between non-prose passages is a caption, slogan or teaser, not a statement.
 * Stateless and deterministic; every bound is a setting.
 */
public final class ProsePassageFilter {

    /** A finished sentence: terminal punctuation after a word character or closer, then space or end. */
    private static final Pattern SENTENCE_END = Pattern.compile("[\\p{L}\\p{Nd}\\)\"“”»’'][.!?]+(?=\\s|$)");
    /** Any mark that interrupts a run of words in prose. */
    private static final Pattern PUNCTUATION = Pattern.compile("[.,;:!?()\\[\\]–—\"“”„«»]");
    /** A token that is a word: it holds a letter. */
    private static final Pattern LETTER = Pattern.compile("\\p{L}");

    /** The thresholds; immutable. */
    public static final class Criteria {
        /** Default minimum words: shorter passages are headings or labels (the live-run headings had 5 and 8). */
        public static final int DEFAULT_MIN_WORDS = 10;
        /** Default minimum finished sentences. */
        public static final int DEFAULT_MIN_SENTENCES = 1;
        /** Default longest punctuation-free run; the hexagon.com footer of live run 3 had more than 60 words. */
        public static final int DEFAULT_MAX_UNPUNCTUATED_RUN = 30;
        /**
         * Default most words of a caption. Live run 4: the calendar slogans of a shop page had 10 to 19 words; the
         * shortest lone passage with content on a page that was almost no prose had 26 (bmas.de, "Das
         * Arbeitsschutzgesetz regelt …"). The default lies between.
         */
        public static final int DEFAULT_CAPTION_MAX_WORDS = 22;
        /**
         * Default share of prose passages below which a page is a listing. Live run 4: the shop page of
         * mediencenter.bgrci.de had 27 prose passages of 996 (0.027); the next pages with a short lone passage had
         * 0.1 and 0.11 (bgetem.de, pbvi.de). The default lies between.
         */
        public static final double DEFAULT_CAPTION_PAGE_PROSE_SHARE = 0.05;

        private final int minWords;
        private final int minSentences;
        private final int maxUnpunctuatedRun;
        private final int captionMaxWords;
        private final double captionPageProseShare;

        public Criteria(int minWords, int minSentences, int maxUnpunctuatedRun) {
            this(minWords, minSentences, maxUnpunctuatedRun, DEFAULT_CAPTION_MAX_WORDS,
                    DEFAULT_CAPTION_PAGE_PROSE_SHARE);
        }

        /**
         * @param captionMaxWords       most words of a lone passage that counts as a caption on a listing page
         * @param captionPageProseShare a page with a smaller share of prose passages is a listing (0 = never)
         */
        public Criteria(int minWords, int minSentences, int maxUnpunctuatedRun, int captionMaxWords,
                        double captionPageProseShare) {
            if (minWords < 0 || minSentences < 0 || maxUnpunctuatedRun < 1 || captionMaxWords < 0
                    || captionPageProseShare < 0 || captionPageProseShare > 1) {
                throw new IllegalArgumentException("prose criteria must not be negative, the run must be >= 1 and "
                        + "the page share must lie in 0..1");
            }
            this.minWords = minWords;
            this.minSentences = minSentences;
            this.maxUnpunctuatedRun = maxUnpunctuatedRun;
            this.captionMaxWords = captionMaxWords;
            this.captionPageProseShare = captionPageProseShare;
        }

        public static Criteria defaults() {
            return new Criteria(DEFAULT_MIN_WORDS, DEFAULT_MIN_SENTENCES, DEFAULT_MAX_UNPUNCTUATED_RUN,
                    DEFAULT_CAPTION_MAX_WORDS, DEFAULT_CAPTION_PAGE_PROSE_SHARE);
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

        public int getCaptionMaxWords() {
            return captionMaxWords;
        }

        public double getCaptionPageProseShare() {
            return captionPageProseShare;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Criteria)) {
                return false;
            }
            Criteria c = (Criteria) o;
            return c.minWords == minWords && c.minSentences == minSentences
                    && c.maxUnpunctuatedRun == maxUnpunctuatedRun && c.captionMaxWords == captionMaxWords
                    && Double.compare(c.captionPageProseShare, captionPageProseShare) == 0;
        }

        @Override
        public int hashCode() {
            return (((minWords * 31 + minSentences) * 31 + maxUnpunctuatedRun) * 31 + captionMaxWords) * 31
                    + Double.valueOf(captionPageProseShare).hashCode();
        }

        @Override
        public String toString() {
            return "words>=" + minWords + ", sentences>=" + minSentences + ", run<=" + maxUnpunctuatedRun
                    + ", caption<=" + captionMaxWords + " on pages with prose<" + captionPageProseShare;
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
        int count = words(t);
        if (count < criteria.minWords) {
            return count + " words < " + criteria.minWords;
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

    /** The words of a text: its white-space separated tokens that hold a letter. */
    static int words(String text) {
        String t = text == null ? "" : text.replace('­', ' ').trim();
        int count = 0;
        for (String token : t.isEmpty() ? new String[0] : t.split("\\s+")) {
            if (LETTER.matcher(token).find()) {
                count++;
            }
        }
        return count;
    }

    /**
     * The captions of one page (indices into {@code page}, its passages in page order): on a page where fewer than
     * {@link Criteria#getCaptionPageProseShare() the share} of the passages are prose, a prose passage of at most
     * {@link Criteria#getCaptionMaxWords() caption words} whose neighbours on the page are no prose. Live run 4: the
     * shop page of mediencenter.bgrci.de (996 passages, 27 of them prose) put calendar slogans between product lines
     * ("Refresh your first aid training regularly to be prepared for emergencies."); seven of them formed a topic
     * of their own and became a section of the outline.
     */
    public Set<Integer> captions(List<String> page) {
        if (page == null || page.isEmpty() || criteria.captionPageProseShare <= 0) {
            return Collections.emptySet();
        }
        boolean[] prose = new boolean[page.size()];
        int count = 0;
        for (int i = 0; i < prose.length; i++) {
            prose[i] = isProse(page.get(i));
            count += prose[i] ? 1 : 0;
        }
        if (count >= criteria.captionPageProseShare * page.size()) {
            return Collections.emptySet();
        }
        Set<Integer> captions = new TreeSet<Integer>();
        for (int i = 0; i < prose.length; i++) {
            if (prose[i] && (i == 0 || !prose[i - 1]) && (i == prose.length - 1 || !prose[i + 1])
                    && words(page.get(i)) <= criteria.captionMaxWords) {
                captions.add(i);
            }
        }
        return captions;
    }
}
