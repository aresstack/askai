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
        if (model == null || model.trim().isEmpty() || wordsPerCall <= 0) {
            throw new IllegalArgumentException("model and a positive word target are required");
        }
        this.model = model.trim();
        this.wordsPerCall = wordsPerCall;
        this.thinking = thinking == null ? "" : thinking;
    }

    /** gemma4:e2b with 450-word parts, llama3.1:8b and gpt-oss:20b (thinking low) with 1,100 words. */
    public static List<WriterAbArm> planArms() {
        return Collections.unmodifiableList(Arrays.asList(
                new WriterAbArm(GEMMA, 450, ""),
                new WriterAbArm(LLAMA, 1100, ""),
                new WriterAbArm(GPT_OSS, 1100, "low")));
    }

    public String getModel() {
        return model;
    }

    public int getWordsPerCall() {
        return wordsPerCall;
    }

    /** {@code ""} when the model gets no thinking field. */
    public String getThinking() {
        return thinking;
    }
}
