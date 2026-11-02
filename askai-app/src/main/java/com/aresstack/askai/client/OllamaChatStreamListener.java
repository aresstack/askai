package com.aresstack.askai.client;

import java.util.List;

/**
 * Receives incremental output from an Ollama chat stream, keeping the three kinds strictly separate:
 * reasoning ({@code onThinkingDelta}), the answer ({@code onContent}) and tool calls
 * ({@code onToolCalls}). The thinking and tool-call callbacks default to no-ops so existing content-only
 * listeners stay source-compatible.
 */
public interface OllamaChatStreamListener {

    /** A non-empty reasoning delta ({@code message.thinking}). */
    default void onThinkingDelta(String delta) {
    }

    /** A non-empty answer delta ({@code message.content}). */
    void onContent(String content);

    /** Tool calls emitted in a chunk ({@code message.tool_calls}); may be called more than once. */
    default void onToolCalls(List<OllamaToolCall> toolCalls) {
    }

    void onStatus(String status);

    void onComplete(OllamaChatCompletion completion);
}
