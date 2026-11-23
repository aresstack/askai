package com.aresstack.askai.research.benchmark.writer;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * One contestant of the writer A/B (plan section 5.4, PR 31): a Document model with the word target per writer
 * call and its thinking level. The three arms are fixed by the plan; nothing in the product reads this class.
 */
public final class WriterAbArm {

    public static final String GEMMA = "gemma4:e2b";
    public static final String LLAMA = "llama3.1:8b";
    public static final String GPT_OSS = "gpt-oss:20b";

    private final String model;
    private final int wordsPerCall;
    private final String thinking;

    public WriterAbArm(String model, int wordsPerCall, String thinking) {
        if (model == null || model.trim().isEmpty() || wordsPerCall < 0) {
            throw new IllegalArgumentException("a model and a word target of 0 (the setting) or more are required");
        }
        this.model = model.trim();
        this.wordsPerCall = wordsPerCall;
        this.thinking = thinking == null ? "" : thinking;
    }

    /**
     * gemma4:e2b, llama3.1:8b and gpt-oss:20b (thinking low), all with the app's words per call: one exported
     * chapter carries the packs of one word target, so arms with their own targets could not share it.
     */
    public static List<WriterAbArm> planArms() {
        return Collections.unmodifiableList(Arrays.asList(
                new WriterAbArm(GEMMA, 0, ""),
                new WriterAbArm(LLAMA, 0, ""),
                new WriterAbArm(GPT_OSS, 0, "low")));
    }

    /**
     * The arms of {@code -Daskai.benchmark.writerAb.arms}: comma-separated {@code model|words|thinking} entries
     * ({@code gemma4:12b|450|,gpt-oss:20b|450|low}); an empty word count keeps the app's setting (words per call,
     * 450 by default). {@code #} works like {@code |}, because a Windows batch file cannot pass {@code |} on
     * ({@code gpt-oss:20b##low}). Empty = the {@linkplain #planArms plan's arms}.
     */
    public static List<WriterAbArm> parse(String spec) {
        if (spec == null || spec.trim().isEmpty()) {
            return planArms();
        }
        List<WriterAbArm> arms = new java.util.ArrayList<WriterAbArm>();
        for (String entry : spec.split(",")) {
            if (entry.trim().isEmpty()) {
                continue;
            }
            String[] parts = entry.trim().split("[|#]", -1);
            arms.add(new WriterAbArm(parts[0], parts.length > 1 && !parts[1].trim().isEmpty()
                    ? Integer.parseInt(parts[1].trim()) : 0, parts.length > 2 ? parts[2].trim() : ""));
        }
        return Collections.unmodifiableList(arms);
    }

    public String getModel() {
        return model;
    }

    /** Words per call of this arm; 0 = the app's setting. */
    public int getWordsPerCall() {
        return wordsPerCall;
    }

    /** {@code ""} when the model gets no thinking field. */
    public String getThinking() {
        return thinking;
    }
}
