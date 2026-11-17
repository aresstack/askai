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
     * Sections code makes at most for one chapter when it splits the chapter by its material (no valid section plan,
     * or material placed by code); more material is packed into that many sections. Live run 5: one chapter got a
     * section per material key, 63 of the book's 81.
     */
    public static final int DEFAULT_MAX_SECTIONS_PER_CHAPTER = 12;
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
    private final int maxSectionsPerChapter;
    private final double chapterBalanceFactor;

    public OutlineLengthSettings(int wordsPerPage, int minSectionWords, int maxSectionWords,
                                 int wordsPerDistinctPassage, int wordStep, int maxSectionsPerChapter,
                                 double chapterBalanceFactor) {
        if (minSectionWords > maxSectionWords) {
            throw new IllegalArgumentException("the minimum section length exceeds the maximum");
        }
        this.wordsPerPage = Math.max(1, wordsPerPage);
        this.minSectionWords = Math.max(1, minSectionWords);
        this.maxSectionWords = Math.max(1, maxSectionWords);
        this.wordsPerDistinctPassage = Math.max(0, wordsPerDistinctPassage);
        this.wordStep = Math.max(1, wordStep);
        this.maxSectionsPerChapter = Math.max(1, maxSectionsPerChapter);
        this.chapterBalanceFactor = Math.max(1.0, chapterBalanceFactor);
    }

    public static OutlineLengthSettings defaults() {
        return new OutlineLengthSettings(DEFAULT_WORDS_PER_PAGE, DEFAULT_MIN_SECTION_WORDS, DEFAULT_MAX_SECTION_WORDS,
                DEFAULT_WORDS_PER_DISTINCT_PASSAGE, DEFAULT_WORD_STEP, DEFAULT_MAX_SECTIONS_PER_CHAPTER,
                DEFAULT_CHAPTER_BALANCE_FACTOR);
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

    public int getMaxSectionsPerChapter() {
        return maxSectionsPerChapter;
    }

    public double getChapterBalanceFactor() {
        return chapterBalanceFactor;
    }

    /** The largest share of sections or distinct material one of {@code chapters} chapters may hold. */
    public double maxChapterShare(int chapters) {
        return chapters <= 0 ? 1.0 : Math.min(1.0, chapterBalanceFactor / chapters);
    }
}
