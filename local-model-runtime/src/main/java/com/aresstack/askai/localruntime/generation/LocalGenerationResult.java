package com.aresstack.askai.localruntime.generation;

/**
 * The terminal result of a non-streaming generation. AskAI-owned. {@code doneReason} is a stable token
 * (e.g. {@code stop}, {@code length}, {@code cancel}); {@code backend} is the backend the runtime ACTUALLY
 * used (reported by the handle), never the requested mode — "" when unknown (e.g. a fake port).
 */
public record LocalGenerationResult(String text, int promptTokens, int generatedTokens, String doneReason,
                                    String backend) {

    public LocalGenerationResult {
        text = text == null ? "" : text;
        doneReason = doneReason == null || doneReason.isEmpty() ? "stop" : doneReason;
        backend = backend == null ? "" : backend;
    }

    /** Convenience for callers/tests that do not report an actual backend. */
    public LocalGenerationResult(String text, int promptTokens, int generatedTokens, String doneReason) {
        this(text, promptTokens, generatedTokens, doneReason, "");
    }
}
