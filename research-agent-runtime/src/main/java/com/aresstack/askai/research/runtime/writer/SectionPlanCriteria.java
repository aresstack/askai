package com.aresstack.askai.research.runtime.writer;

import java.util.Locale;

/**
 * How a section written in several key-point parts keeps its parts apart (writer A/B of 20 Nov 2026: every part
 * introduced the section anew and restated the facts of the parts before it; about a third of the words of a section
 * repeated an earlier sentence in other words, and the repeat check only takes out near-verbatim repeats).
 *
 * <ul>
 * <li>{@link Mode#OFF}: every part is written as before, like a section of its own.</li>
 * <li>{@link Mode#CONTINUITY}: every part call names its place in the section (the plan of all key points, which are
 * written, which are this part's, which follow) and carries the text of the parts before it, so it continues instead
 * of starting over.</li>
 * <li>{@link Mode#SEMANTIC}: as above, and the passages of the pack are assigned to the key points by sentence
 * embedding ({@link SectionPlanner}) instead of by shared word stems: each passage belongs to exactly one key point,
 * the prompt lists the passages under their key point, a key point without a passage is an open point found by code,
 * and the words of each key point follow the evidence it has ({@link #getWordsPerEvidenceWord()}).</li>
 * </ul>
 * Immutable; every bound is a setting.
 */
public final class SectionPlanCriteria {

    public enum Mode { OFF, CONTINUITY, SEMANTIC }

    /**
     * Default words a writer gets out of one word of evidence without repeating itself. Writer A/B of 20 Nov 2026
     * (gemma4:12b): the sections whose packs were used up wrote 0.51 and 0.57 words that repeat nothing per word of
     * their passages.
     */
    public static final double DEFAULT_WORDS_PER_EVIDENCE_WORD = 0.5;
    /**
     * Default least cosine between a key point and its best passage; below it the key point has no evidence. The
     * floor of the evidence pack's brief relevance for {@code bge-m3}.
     */
    public static final double DEFAULT_MIN_SIMILARITY = 0.45;
    /** Default characters of the section's earlier parts a part call carries (about 1,500 tokens). */
    public static final int DEFAULT_SO_FAR_CHARS = 6000;

    private final Mode mode;
    private final double wordsPerEvidenceWord;
    private final double minSimilarity;
    private final int soFarChars;

    public SectionPlanCriteria(Mode mode, double wordsPerEvidenceWord, double minSimilarity, int soFarChars) {
        if (wordsPerEvidenceWord < 0 || minSimilarity < 0 || minSimilarity > 1 || soFarChars < 0) {
            throw new IllegalArgumentException("words per evidence word >= 0, the similarity within 0..1 and the "
                    + "characters >= 0");
        }
        this.mode = mode == null ? Mode.OFF : mode;
        this.wordsPerEvidenceWord = wordsPerEvidenceWord;
        this.minSimilarity = minSimilarity;
        this.soFarChars = soFarChars;
    }

    public static SectionPlanCriteria off() {
        return new SectionPlanCriteria(Mode.OFF, 0, DEFAULT_MIN_SIMILARITY, DEFAULT_SO_FAR_CHARS);
    }

    public static SectionPlanCriteria of(Mode mode) {
        return new SectionPlanCriteria(mode, mode == Mode.SEMANTIC ? DEFAULT_WORDS_PER_EVIDENCE_WORD : 0,
                DEFAULT_MIN_SIMILARITY, DEFAULT_SO_FAR_CHARS);
    }

    /** The same criteria with another yield; 0 keeps the section's word target whatever its evidence. */
    public SectionPlanCriteria withWordsPerEvidenceWord(double value) {
        return new SectionPlanCriteria(mode, value, minSimilarity, soFarChars);
    }

    public Mode getMode() {
        return mode;
    }

    public boolean isOff() {
        return mode == Mode.OFF;
    }

    /** Words of text one word of evidence carries; caps the words of a key point and of the section (0 = no cap). */
    public double getWordsPerEvidenceWord() {
        return wordsPerEvidenceWord;
    }

    public double getMinSimilarity() {
        return minSimilarity;
    }

    public int getSoFarChars() {
        return soFarChars;
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "SectionPlanCriteria[%s, wordsPerEvidenceWord=%.2f, minSimilarity=%.2f, "
                + "soFarChars=%d]", mode, wordsPerEvidenceWord, minSimilarity, soFarChars);
    }
}
