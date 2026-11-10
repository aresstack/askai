package com.aresstack.askai.research.writer;

import com.aresstack.askai.research.runtime.writer.ProsePassageFilter;

import java.util.Locale;

/**
 * The evidence pack size of the Document row's writer settings (plan D8, PR 27): how many passages a section gets
 * and how many tokens each may take. The host presets them by model: 8 x 300 for {@code llama3.1:8b} and
 * {@code gpt-oss:20b}, 6 x 250 for {@code gemma4:e2b} and every other model. The {@linkplain
 * ProsePassageFilter.Criteria prose criteria} keep navigation, footer and heading passages out of the pack (live
 * run 3: section 1.3 cited the hexagon.com footer for every sentence).
 */
public final class WriterPackSettings {

    public static final WriterPackSettings LARGE = new WriterPackSettings(8, 300);
    public static final WriterPackSettings SMALL = new WriterPackSettings(6, 250);

    private final int passageCount;
    private final int passageTokens;
    private final ProsePassageFilter.Criteria prose;
    private final int passagesPerSource;

    /** The smallest passage a pack accepts. */
    public static final int MIN_PASSAGE_TOKENS = 20;

    /** Default: at most two passages of one source, so one page cannot fill the pack. */
    public static final int DEFAULT_PASSAGES_PER_SOURCE = 2;

    /** With the default prose criteria. */
    public WriterPackSettings(int passageCount, int passageTokens) {
        this(passageCount, passageTokens, ProsePassageFilter.Criteria.defaults());
    }

    public WriterPackSettings(int passageCount, int passageTokens, ProsePassageFilter.Criteria prose) {
        this(passageCount, passageTokens, prose, DEFAULT_PASSAGES_PER_SOURCE);
    }

    public WriterPackSettings(int passageCount, int passageTokens, ProsePassageFilter.Criteria prose,
                              int passagesPerSource) {
        if (passagesPerSource < 1) {
            throw new IllegalArgumentException("a source must be able to contribute at least one passage");
        }
        if (passageCount < 1 || passageTokens < MIN_PASSAGE_TOKENS) {
            throw new IllegalArgumentException("an evidence pack needs at least one passage of 20 tokens");
        }
        this.passageCount = passageCount;
        this.passageTokens = passageTokens;
        this.prose = prose == null ? ProsePassageFilter.Criteria.defaults() : prose;
        this.passagesPerSource = passagesPerSource;
    }

    /** How many passages one source may contribute. */
    public int getPassagesPerSource() {
        return passagesPerSource;
    }

    /** The host preset for a writer model id. */
    public static WriterPackSettings presetFor(String modelId) {
        String m = modelId == null ? "" : modelId.trim().toLowerCase(Locale.ROOT);
        return m.startsWith("llama3.1:8b") || m.startsWith("gpt-oss:20b") ? LARGE : SMALL;
    }

    public int getPassageCount() {
        return passageCount;
    }

    public int getPassageTokens() {
        return passageTokens;
    }

    /** What a passage needs to count as prose and enter the pack. */
    public ProsePassageFilter.Criteria getProse() {
        return prose;
    }

    /** The token budget of the evidence block alone. */
    public int evidenceTokenBudget() {
        return passageCount * passageTokens;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof WriterPackSettings && ((WriterPackSettings) other).passageCount == passageCount
                && ((WriterPackSettings) other).passageTokens == passageTokens
                && ((WriterPackSettings) other).prose.equals(prose)
                && ((WriterPackSettings) other).passagesPerSource == passagesPerSource;
    }

    @Override
    public int hashCode() {
        return ((passageCount * 31 + passageTokens) * 31 + prose.hashCode()) * 31 + passagesPerSource;
    }

    @Override
    public String toString() {
        return passageCount + " x " + passageTokens;
    }
}
