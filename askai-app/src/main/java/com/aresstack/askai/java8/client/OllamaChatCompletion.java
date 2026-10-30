package com.aresstack.askai.java8.client;

/**
 * Final result of an Ollama chat call, mapped from ollama4j metadata.
 *
 * <p>Carries the assembled assistant content plus the token metrics the UI needs,
 * so the UI no longer parses raw chat JSON for {@code eval_count}/{@code eval_duration}.</p>
 */
public final class OllamaChatCompletion {

    private final String thinking;
    private final String content;
    private final java.util.List<OllamaToolCall> toolCalls;
    private final long evalCount;
    private final long evalDurationNanos;
    private final long promptEvalCount;
    private final String doneReason;

    public OllamaChatCompletion(String content, long evalCount, long evalDurationNanos) {
        this("", content, java.util.Collections.<OllamaToolCall>emptyList(), evalCount, evalDurationNanos);
    }

    public OllamaChatCompletion(String thinking, String content, java.util.List<OllamaToolCall> toolCalls,
                                long evalCount, long evalDurationNanos) {
        this(thinking, content, toolCalls, evalCount, evalDurationNanos, -1L, "");
    }

    /**
     * @param promptEvalCount evaluated prompt tokens, or -1 when not reported
     * @param doneReason      Ollama's {@code done_reason} ({@code stop}, {@code length}, ...), or ""
     */
    public OllamaChatCompletion(String thinking, String content, java.util.List<OllamaToolCall> toolCalls,
                                long evalCount, long evalDurationNanos, long promptEvalCount,
                                String doneReason) {
        this.promptEvalCount = promptEvalCount;
        this.doneReason = doneReason == null ? "" : doneReason;
        this.thinking = thinking == null ? "" : thinking;
        this.content = content == null ? "" : content;
        this.toolCalls = toolCalls == null
                ? java.util.Collections.<OllamaToolCall>emptyList()
                : java.util.Collections.unmodifiableList(new java.util.ArrayList<OllamaToolCall>(toolCalls));
        this.evalCount = evalCount;
        this.evalDurationNanos = evalDurationNanos;
    }

    public static OllamaChatCompletion empty() {
        return new OllamaChatCompletion("", 0L, 0L);
    }

    /** @return the full reasoning streamed this turn (empty when the model did not think). */
    public String getThinking() {
        return thinking;
    }

    /** @return the tool calls the assistant requested this turn (empty when none). */
    public java.util.List<OllamaToolCall> getToolCalls() {
        return toolCalls;
    }

    public String getContent() {
        return content;
    }

    public long getEvalCount() {
        return evalCount;
    }

    public long getEvalDurationNanos() {
        return evalDurationNanos;
    }

    /** Evaluated prompt tokens, or -1 when not reported. */
    public long getPromptEvalCount() {
        return promptEvalCount;
    }

    /** Ollama's {@code done_reason}, or "" when not reported. */
    public String getDoneReason() {
        return doneReason;
    }

    public boolean hasMetrics() {
        return evalCount > 0L && evalDurationNanos > 0L;
    }

    /**
     * Output tokens per second, or {@code 0} when metrics are unavailable.
     */
    public double tokensPerSecond() {
        if (!hasMetrics()) {
            return 0.0d;
        }
        return evalCount / (evalDurationNanos / 1_000_000_000.0d);
    }
}
