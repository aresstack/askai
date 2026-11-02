package io.github.ollama4j.models;

import java.util.Collections;
import java.util.List;

public final class ChatCompletion {

    private final String thinking;
    private final String content;
    private final List<ToolCall> toolCalls;
    private final long evalCount;
    private final long evalDurationNanos;

    public ChatCompletion(String content, long evalCount, long evalDurationNanos) {
        this("", content, Collections.<ToolCall>emptyList(), evalCount, evalDurationNanos);
    }

    public ChatCompletion(String thinking, String content, List<ToolCall> toolCalls,
                          long evalCount, long evalDurationNanos) {
        this.thinking = thinking == null ? "" : thinking;
        this.content = content == null ? "" : content;
        this.toolCalls = toolCalls == null
                ? Collections.<ToolCall>emptyList()
                : Collections.unmodifiableList(new java.util.ArrayList<ToolCall>(toolCalls));
        this.evalCount = evalCount;
        this.evalDurationNanos = evalDurationNanos;
    }

    /** @return the full reasoning streamed during this turn (empty when the model did not think). */
    public String getThinking() {
        return thinking;
    }

    public String getContent() {
        return content;
    }

    /** @return the tool calls the assistant requested this turn (empty when none). */
    public List<ToolCall> getToolCalls() {
        return toolCalls;
    }

    public long getEvalCount() {
        return evalCount;
    }

    public long getEvalDurationNanos() {
        return evalDurationNanos;
    }

    public boolean hasMetrics() {
        return evalCount > 0L && evalDurationNanos > 0L;
    }

    public double tokensPerSecond() {
        if (!hasMetrics()) {
            return 0.0d;
        }
        return evalCount / (evalDurationNanos / 1000000000.0d);
    }
}
