package io.github.ollama4j.models;

import java.util.List;

/**
 * Structured streaming callback for {@code /api/chat}. Keeps the three distinct kinds of output that
 * Ollama streams strictly separate: reasoning ({@code message.thinking}), the answer
 * ({@code message.content}) and tool calls ({@code message.tool_calls}). Callers never parse JSON or
 * text markers to tell them apart.
 */
public interface ChatStreamListener {

    /** A non-empty delta of {@code message.thinking} (reasoning), streamed live. */
    void onThinkingDelta(String delta);

    /** A non-empty delta of {@code message.content} (the final answer), streamed live. */
    void onContentDelta(String delta);

    /** Tool calls emitted in a chunk; may be called more than once as they arrive. */
    void onToolCalls(List<ToolCall> toolCalls);

    /** The stream finished: carries the aggregated thinking/content/tool-calls and the final metrics. */
    void onComplete(ChatCompletion completion);
}
