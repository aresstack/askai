package com.aresstack.askai.modelport.provider;

import com.aresstack.askai.agent.model.port.ChatResult;
import com.aresstack.askai.agent.model.port.TokenUsage;
import com.aresstack.askai.agent.model.port.ToolCall;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A completed model turn as a provider read it; the port adds the endpoint, the model label and the wall time and
 * decides whether it is a usable result (an empty answer without tool calls never is). Immutable.
 */
public final class ProviderCompletion {

    private final String content;
    private final String thinking;
    private final List<ToolCall> toolCalls;
    private final ChatResult.FinishReason finishReason;
    private final TokenUsage usage;
    private final Duration loadDuration;
    private final Duration promptDuration;

    /**
     * @param finishReason   why the model stopped, already mapped (tool calls win over the provider's word)
     * @param usage          token accounting; null = not reported
     * @param loadDuration   time the provider spent loading the model; null = not reported
     * @param promptDuration time the provider spent evaluating the prompt; null = not reported
     */
    public ProviderCompletion(String content, String thinking, List<ToolCall> toolCalls,
                              ChatResult.FinishReason finishReason, TokenUsage usage, Duration loadDuration,
                              Duration promptDuration) {
        this.content = content == null ? "" : content;
        this.thinking = thinking == null ? "" : thinking;
        this.toolCalls = toolCalls == null || toolCalls.isEmpty() ? Collections.<ToolCall>emptyList()
                : Collections.unmodifiableList(new ArrayList<ToolCall>(toolCalls));
        this.finishReason = finishReason == null ? ChatResult.FinishReason.OTHER : finishReason;
        this.usage = usage == null ? TokenUsage.unknown() : usage;
        this.loadDuration = loadDuration == null ? Duration.ZERO : loadDuration;
        this.promptDuration = promptDuration == null ? Duration.ZERO : promptDuration;
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

    public ChatResult.FinishReason getFinishReason() {
        return finishReason;
    }

    public TokenUsage getUsage() {
        return usage;
    }

    public Duration getLoadDuration() {
        return loadDuration;
    }

    public Duration getPromptDuration() {
        return promptDuration;
    }
}
