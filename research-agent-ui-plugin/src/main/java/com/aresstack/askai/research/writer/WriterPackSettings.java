package com.aresstack.askai.research.writer;

import com.aresstack.askai.research.runtime.writer.ProsePassageFilter;

import java.util.Locale;

/**
 * The evidence pack size of the Document row's writer settings (plan D8, PR 27): how many passages one writer call
 * gets and how many tokens each may take; a section written in parts gets that many for each part. The host presets
 * them by model: 8 x 300 for {@code llama3.1:8b} and {@code gpt-oss:20b}, 6 x 250 for {@code gemma4:e2b} and every
 * other model. The {@linkplain ProsePassageFilter.Criteria prose criteria} keep navigation, footer and heading
 * passages out of the pack (live run 3: section 1.3 cited the hexagon.com footer for every sentence), and the
 * {@linkplain BriefRelevance brief relevance} keeps passages of another subject out (live run 4, book 6: Apple Watch
 * VO2max in 1.2, predictive maintenance in 4.1, BAuA "Flexible Arbeitszeiten" in 2.1).
 */
public final class WriterPackSettings {

    public static final WriterPackSettings LARGE = new WriterPackSettings(8, 300);
    public static final WriterPackSettings SMALL = new WriterPackSettings(6, 250);

    private final int passageCount;
    private final int passageTokens;
    private final ProsePassageFilter.Criteria prose;
    private final int passagesPerSource;
    private final BriefRelevance relevance;

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

    /** Without a brief relevance: every similarity is admitted. */
    public WriterPackSettings(int passageCount, int passageTokens, ProsePassageFilter.Criteria prose,
                              int passagesPerSource) {
        this(passageCount, passageTokens, prose, passagesPerSource, BriefRelevance.OFF);
    }

    public WriterPackSettings(int passageCount, int passageTokens, ProsePassageFilter.Criteria prose,
                              int passagesPerSource, BriefRelevance relevance) {
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
        this.relevance = relevance == null ? BriefRelevance.OFF : relevance;
    }

    /** How close a passage must be to the section's brief to enter the pack. */
    public BriefRelevance getRelevance() {
        return relevance;
    }

    /** How many passages one source may contribute. */
    public int getPassagesPerSource() {
        return passagesPerSource;
    }

    /**
     * The same pack with {@code count} passages: a section written in several key-point parts gets this pack's
     * passages for each part ({@code EvidenceAssignments}).
     */
    public WriterPackSettings withPassageCount(int count) {
        return new WriterPackSettings(count, passageTokens, prose, passagesPerSource, relevance);
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
                && ((WriterPackSettings) other).passagesPerSource == passagesPerSource
                && ((WriterPackSettings) other).relevance.equals(relevance);
    }

    @Override
    public int hashCode() {
        return (((passageCount * 31 + passageTokens) * 31 + prose.hashCode()) * 31 + passagesPerSource) * 31
                + relevance.hashCode();
    }

    @Override
    public String toString() {
        return passageCount + " x " + passageTokens;
    }

    /**
     * The least similarity (cosine of the passage vector and the section's brief query, the score of the semantic
     * search) a passage needs to enter the pack: at least {@link #getMinSimilarity() minSimilarity}, and at most
     * {@link #getMargin() margin} below the best admitted passage of the same section. The margin follows the
     * section: a narrow brief whose best passage scores 0.62 keeps everything from 0.47 up, a brief with passages
     * at 0.80 holds back what falls below 0.65. A value of 0 switches its bound off.
     *
     * <p><b>The values depend on the embedding model.</b> The defaults are calibrated for {@code bge-m3}: there,
     * passages of the same field that do not treat the asked subject score 0.36 to 0.62 against it (outline live
     * acceptance on #217: 24 construction passages against the uncovered requirement "Training apprentices", mean
     * 0.52), and sentences of another subject 0.43 and 0.53 against a section brief (live run 3, section 1.4);
     * the floor of 0.45 holds back what is clearly of another subject, the margin of 0.15 what lies in that band
     * while the section has clearly closer passages. {@code nomic-embed-text} compresses distances: its
     * similarities lie higher and closer together, so the floor filters almost nothing and the margin must be
     * smaller; recalibrate both after a change of the embedding model.</p>
     */
    public static final class BriefRelevance {

        /** Floor for {@code bge-m3}: below it a passage treats another subject. */
        public static final double DEFAULT_MIN_SIMILARITY = 0.45;
        /** Margin for {@code bge-m3}: a passage more than this below the section's best one is held back. */
        public static final double DEFAULT_MARGIN = 0.15;

        /** No bound: every similarity is admitted. */
        public static final BriefRelevance OFF = new BriefRelevance(0, 0);

        private final double minSimilarity;
        private final double margin;

        public BriefRelevance(double minSimilarity, double margin) {
            if (minSimilarity < 0 || minSimilarity > 1 || margin < 0 || margin > 1
                    || Double.isNaN(minSimilarity) || Double.isNaN(margin)) {
                throw new IllegalArgumentException("similarity floor and margin lie between 0 and 1");
            }
            this.minSimilarity = minSimilarity;
            this.margin = margin;
        }

        public static BriefRelevance defaults() {
            return new BriefRelevance(DEFAULT_MIN_SIMILARITY, DEFAULT_MARGIN);
        }

        public double getMinSimilarity() {
            return minSimilarity;
        }

        public double getMargin() {
            return margin;
        }

        /** Whether either bound holds anything back. */
        public boolean isActive() {
            return minSimilarity > 0 || margin > 0;
        }

        /**
         * The least similarity a passage of a section needs when its best admitted passage scores {@code best}:
         * the floor, raised to {@code best - margin} when that is higher.
         */
        public double floorFor(double best) {
            return margin > 0 ? Math.max(minSimilarity, best - margin) : minSimilarity;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof BriefRelevance
                    && Double.compare(((BriefRelevance) other).minSimilarity, minSimilarity) == 0
                    && Double.compare(((BriefRelevance) other).margin, margin) == 0;
        }

        @Override
        public int hashCode() {
            return Double.valueOf(minSimilarity).hashCode() * 31 + Double.valueOf(margin).hashCode();
        }

        @Override
        public String toString() {
            return "similarity >= " + minSimilarity + ", <= " + margin + " below the best";
        }
    }
}
