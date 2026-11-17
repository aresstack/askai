package com.aresstack.askai.research.outline.synthesis;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phase 3: the book length and the word budgets of the outline. The deliverable names a length ("120 pages",
 * "20000-30000 words"); it is spread over the sections by their DISTINCT material (repeated material is no reason
 * for more words). Without a length, a section gets a base plus a share per distinct passage, at most the maximum.
 * The quality check uses the same bounds for the plausibility of the plan against the length. Immutable.
 */
public final class OutlineLengthSettings {

    /** Words per page until the writer calibrates it. */
    public static final int DEFAULT_WORDS_PER_PAGE = 300;
    /** A section shorter than this is no section of its own (also the base without a book length). */
    public static final int DEFAULT_MIN_SECTION_WORDS = 400;
    /** A section longer than this should be split (also the cap without a book length). */
    public static final int DEFAULT_MAX_SECTION_WORDS = 1100;
    /** Without a book length: words per distinct passage on top of the base. */
    public static final int DEFAULT_WORDS_PER_DISTINCT_PASSAGE = 100;
    /** Word targets are rounded to this step. */
    public static final int DEFAULT_WORD_STEP = 50;
    /**
     * The section limit of one chapter when code splits it by its material follows the book length: target words /
     * words per writer call / chapters, kept between this minimum and {@link #DEFAULT_MAX_SECTIONS_PER_CHAPTER}.
     * Live retest of #240: a fixed limit of 12 capped a book of four chapters at 48 sections.
     */
    public static final int DEFAULT_MIN_SECTIONS_PER_CHAPTER = 4;
    /**
     * The upper bound of the section limit (and the limit without a book length). Live run 5: one chapter got a
     * section per material key, 63 of the book's 81.
     */
    public static final int DEFAULT_MAX_SECTIONS_PER_CHAPTER = 24;
    /** Words one writer call aims for; the host passes the writer's "Words per call" setting (its default). */
    public static final int DEFAULT_WORDS_PER_CALL = 450;
    /**
     * A section with fewer distinct passages is folded into its neighbour. Live retest of #240: three sections of
     * chapter 4 had 3 to 4 passages.
     */
    public static final int DEFAULT_MIN_SECTION_PASSAGES = 4;
    /**
     * Two section titles whose content words overlap at least this share (of the shorter title) are near
     * duplicates, the share of the writer's repeat check (#221). Live retest of #240: two sections of one chapter were
     * both called "Kosten und Praxisbeispiele". 1 = only identical titles.
     */
    public static final double DEFAULT_MAX_TITLE_OVERLAP = 0.75;
    /**
     * How many times an even share of the sections or of the distinct material one chapter may hold before the plan
     * counts as unbalanced (a blocking finding). With n chapters the limit is factor / n; live run 5 had one chapter
     * with 63 of 81 sections in a book of seven chapters.
     */
    public static final double DEFAULT_CHAPTER_BALANCE_FACTOR = 2.5;

    private static final Pattern LENGTH = Pattern.compile(
            "(\\d+)\\s*(?:-\\s*(\\d+))?\\s*(pages?|words?|seiten|w(?:o|ö)rter)", Pattern.CASE_INSENSITIVE);

    private final int wordsPerPage;
    private final int minSectionWords;
    private final int maxSectionWords;
    private final int wordsPerDistinctPassage;
    private final int wordStep;
    private final int minSectionsPerChapter;
    private final int maxSectionsPerChapter;
    private final double chapterBalanceFactor;
    private final int minSectionPassages;
    private final double maxTitleOverlap;
    private final int wordsPerCall;

    public OutlineLengthSettings(int wordsPerPage, int minSectionWords, int maxSectionWords,
                                 int wordsPerDistinctPassage, int wordStep, int minSectionsPerChapter,
                                 int maxSectionsPerChapter, double chapterBalanceFactor, int minSectionPassages,
                                 double maxTitleOverlap, int wordsPerCall) {
        if (minSectionWords > maxSectionWords) {
            throw new IllegalArgumentException("the minimum section length exceeds the maximum");
        }
        this.wordsPerPage = Math.max(1, wordsPerPage);
        this.minSectionWords = Math.max(1, minSectionWords);
        this.maxSectionWords = Math.max(1, maxSectionWords);
        this.wordsPerDistinctPassage = Math.max(0, wordsPerDistinctPassage);
        this.wordStep = Math.max(1, wordStep);
        this.minSectionsPerChapter = Math.max(1, minSectionsPerChapter);
        this.maxSectionsPerChapter = Math.max(this.minSectionsPerChapter, maxSectionsPerChapter);
        this.minSectionPassages = Math.max(0, minSectionPassages);
        this.maxTitleOverlap = Math.min(1.0, Math.max(0.0, maxTitleOverlap));
        this.wordsPerCall = Math.max(1, wordsPerCall);
        this.chapterBalanceFactor = Math.max(1.0, chapterBalanceFactor);
    }

    public static OutlineLengthSettings defaults() {
        return new OutlineLengthSettings(DEFAULT_WORDS_PER_PAGE, DEFAULT_MIN_SECTION_WORDS, DEFAULT_MAX_SECTION_WORDS,
                DEFAULT_WORDS_PER_DISTINCT_PASSAGE, DEFAULT_WORD_STEP, DEFAULT_MIN_SECTIONS_PER_CHAPTER,
                DEFAULT_MAX_SECTIONS_PER_CHAPTER, DEFAULT_CHAPTER_BALANCE_FACTOR, DEFAULT_MIN_SECTION_PASSAGES,
                DEFAULT_MAX_TITLE_OVERLAP, DEFAULT_WORDS_PER_CALL);
    }

    /**
     * The target length in words of a deliverable description, the middle of a range; {@code 0} when it names no
     * length.
     */
    public int targetWords(String deliverable) {
        if (deliverable == null) {
            return 0;
        }
        Matcher matcher = LENGTH.matcher(deliverable);
        if (!matcher.find()) {
            return 0;
        }
        long min = Long.parseLong(matcher.group(1));
        long max = matcher.group(2) == null ? min : Long.parseLong(matcher.group(2));
        long middle = (min + max) / 2;
        String unit = matcher.group(3).toLowerCase(Locale.ROOT);
        long words = unit.startsWith("page") || unit.startsWith("seite") ? middle * wordsPerPage : middle;
        return (int) Math.min(Integer.MAX_VALUE, words);
    }

    /** The word target of a section with {@code distinct} distinct passages when the book names no length. */
    public int unboundedTarget(int distinct) {
        long words = (long) minSectionWords + (long) wordsPerDistinctPassage * Math.max(0, distinct);
        return round(Math.min(maxSectionWords, words));
    }

    /** {@code words} rounded to the step, never below one step. */
    public int round(long words) {
        return (int) Math.max(wordStep, Math.round(words / (double) wordStep) * wordStep);
    }

    public int getWordsPerPage() {
        return wordsPerPage;
    }

    public int getMinSectionWords() {
        return minSectionWords;
    }

    public int getMaxSectionWords() {
        return maxSectionWords;
    }

    public int getWordsPerDistinctPassage() {
        return wordsPerDistinctPassage;
    }

    public int getWordStep() {
        return wordStep;
    }

    public int getMinSectionsPerChapter() {
        return minSectionsPerChapter;
    }

    public int getMaxSectionsPerChapter() {
        return maxSectionsPerChapter;
    }

    public int getMinSectionPassages() {
        return minSectionPassages;
    }

    public double getMaxTitleOverlap() {
        return maxTitleOverlap;
    }

    public int getWordsPerCall() {
        return wordsPerCall;
    }

    /**
     * The most sections code makes for one of {@code chapters} chapters: the book's target words / words per call /
     * chapters, between the minimum and the maximum; the maximum without a book length.
     */
    public int sectionLimit(String deliverable, int chapters) {
        int target = targetWords(deliverable);
        if (target <= 0 || chapters <= 0) {
            return maxSectionsPerChapter;
        }
        long limit = (long) Math.ceil((double) target / wordsPerCall / chapters);
        return (int) Math.max(minSectionsPerChapter, Math.min(maxSectionsPerChapter, limit));
    }

    public double getChapterBalanceFactor() {
        return chapterBalanceFactor;
    }

    /** The largest share of sections or distinct material one of {@code chapters} chapters may hold. */
    public double maxChapterShare(int chapters) {
        return chapters <= 0 ? 1.0 : Math.min(1.0, chapterBalanceFactor / chapters);
    }
}
