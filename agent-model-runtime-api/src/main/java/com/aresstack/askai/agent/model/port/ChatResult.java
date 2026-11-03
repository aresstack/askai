package com.aresstack.askai.agent.model.port;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The successful outcome of one chat call (ADR 0001, 3.2). Failures never arrive as a result; they arrive
 * as a {@link ModelFailure}.
 *
 * <p>An answer can consist of text, tool calls, or both. A thinking model's reasoning is reported
 * separately in {@link #getThinking()} and never mixed into {@link #getContent()}. An empty content WITH
 * tool calls is a regular result ({@link FinishReason#TOOL_CALLS}); an empty content WITHOUT tool calls is
 * reported by the implementation as a {@link ModelFailure.Kind#INVALID_RESPONSE} failure, so that "the model
 * spent everything on reasoning" never looks like a success.</p>
 */
public final class ChatResult {

    /** Why the model stopped. */
    public enum FinishReason {
        /** Natural end of the answer. */
        STOP,
        /** The output token limit was reached; the content may be cut. */
        LENGTH,
        /** The model stopped to have tools executed. */
        TOOL_CALLS,
        /** Any other provider reason. */
        OTHER
    }

    private final String content;
    private final String thinking;
    private final List<ToolCall> toolCalls;
    private final FinishReason finishReason;
    private final TokenUsage usage;
    private final String endpointId;
    private final Duration duration;

    /**
     * @param content      the answer text (for JSON output formats: the raw JSON text); null becomes ""
     * @param thinking     the reasoning text; null becomes ""
     * @param toolCalls    the requested tool calls; null becomes empty
     * @param finishReason why the model stopped; null becomes {@link FinishReason#OTHER}
     * @param usage        token accounting; null becomes {@link TokenUsage#unknown()}
     * @param endpointId   the endpoint that served the call (receipts, task model); null becomes ""
     * @param duration     wall time of the call, queueing in the scheduler excluded; null becomes zero
     */
    public ChatResult(String content, String thinking, List<ToolCall> toolCalls, FinishReason finishReason,
                      TokenUsage usage, String endpointId, Duration duration) {
        this.content = content == null ? "" : content;
        this.thinking = thinking == null ? "" : thinking;
        this.toolCalls = toolCalls == null || toolCalls.isEmpty()
                ? Collections.<ToolCall>emptyList()
                : Collections.unmodifiableList(new ArrayList<ToolCall>(toolCalls));
        this.finishReason = finishReason == null ? FinishReason.OTHER : finishReason;
        this.usage = usage == null ? TokenUsage.unknown() : usage;
        this.endpointId = endpointId == null ? "" : endpointId;
        this.duration = duration == null ? Duration.ZERO : duration;
    }

    public String getContent() {
        return content;
    }

    public String getThinking() {
        return thinking;
    }

    public List<ToolCall> getToolCalls() {
        return toolCalls;
    }

    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }

    public FinishReason getFinishReason() {
        return finishReason;
    }

    public TokenUsage getUsage() {
        return usage;
    }

    public String getEndpointId() {
        return endpointId;
    }

    public Duration getDuration() {
        return duration;
    }

    /**
     * This answer as an assistant message for the next turn of the same exchange (content, reasoning and
     * tool calls), so a tool loop can append it to its history unchanged.
     */
    public ModelMessage toAssistantMessage() {
        return ModelMessage.assistant(content, thinking, toolCalls);
    }
}
