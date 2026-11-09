package com.aresstack.askai.research.writer;

import java.util.Locale;

/**
 * The evidence pack size of the Document row's writer settings (plan D8, PR 27): how many passages a section gets
 * and how many tokens each may take. The host presets them by model: 8 x 300 for {@code llama3.1:8b} and
 * {@code gpt-oss:20b}, 6 x 250 for {@code gemma4:e2b} and every other model.
 */
public final class WriterPackSettings {

    public static final WriterPackSettings LARGE = new WriterPackSettings(8, 300);
    public static final WriterPackSettings SMALL = new WriterPackSettings(6, 250);

    private final int passageCount;
    private final int passageTokens;

    public WriterPackSettings(int passageCount, int passageTokens) {
        if (passageCount < 1 || passageTokens < 20) {
            throw new IllegalArgumentException("an evidence pack needs at least one passage of 20 tokens");
        }
        this.passageCount = passageCount;
        this.passageTokens = passageTokens;
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

    /** The token budget of the evidence block alone. */
    public int evidenceTokenBudget() {
        return passageCount * passageTokens;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof WriterPackSettings && ((WriterPackSettings) other).passageCount == passageCount
                && ((WriterPackSettings) other).passageTokens == passageTokens;
    }

    @Override
    public int hashCode() {
        return passageCount * 31 + passageTokens;
    }

    @Override
    public String toString() {
        return passageCount + " x " + passageTokens;
    }
}
