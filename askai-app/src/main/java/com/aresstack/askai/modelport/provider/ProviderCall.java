package com.aresstack.askai.modelport.provider;

import com.aresstack.askai.agent.model.port.ModelMessage;
import com.aresstack.askai.agent.model.port.OutputFormat;
import com.aresstack.askai.agent.model.port.ThinkingMode;
import com.aresstack.askai.agent.model.port.ToolDefinition;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One model turn as the port hands it to a provider: the model the slot resolved to, the caller's request and the
 * port's settings for it, already resolved. Provider-neutral; every provider maps it onto its own wire form.
 * Immutable.
 */
public final class ProviderCall {

    /**
     * Reasoning room a contract role gets on a model that cannot switch reasoning off (plan 4.1 rule 3;
     * provisional until the measurement PR): added to the answer budget when the caller asked for no reasoning.
     */
    public static final int LEVEL_ONLY_REASONING_TOKENS = 1000;

    private final String slot;
    private final String model;
    private final List<ModelMessage> messages;
    private final List<ToolDefinition> tools;
    private final OutputFormat outputFormat;
    private final ThinkingMode requestedThinking;
    private final ThinkingMode slotThinking;
    private final Double temperature;
    private final int answerTokens;
    private final int contextTokens;
    private final Boolean truncate;
    private final String keepAlive;
    private final AbortSignal abort;

    /**
     * @param slot              the calling slot's name (thinking and budget are resolved for it; logs)
     * @param model             the model the slot resolved to on this endpoint
     * @param requestedThinking the caller's thinking mode ({@link ThinkingMode#DEFAULT} = leave it to the slot)
     * @param slotThinking      the slot's thinking setting ({@link ThinkingMode#DEFAULT} = Auto)
     * @param answerTokens      the answer budget: the caller's, else the slot's; 0 = none
     * @param contextTokens     the context the call runs with (Ollama sends it as {@code num_ctx})
     * @param keepAlive         how long Ollama keeps the model loaded after the call; ignored by other providers
     */
    public ProviderCall(String slot, String model, List<ModelMessage> messages, List<ToolDefinition> tools,
                        OutputFormat outputFormat, ThinkingMode requestedThinking, ThinkingMode slotThinking,
                        Double temperature, int answerTokens, int contextTokens, Boolean truncate, String keepAlive,
                        AbortSignal abort) {
        this.slot = slot == null ? "" : slot;
        this.model = model == null ? "" : model;
        this.messages = Collections.unmodifiableList(new ArrayList<ModelMessage>(
                messages == null ? Collections.<ModelMessage>emptyList() : messages));
        this.tools = Collections.unmodifiableList(new ArrayList<ToolDefinition>(
                tools == null ? Collections.<ToolDefinition>emptyList() : tools));
        this.outputFormat = outputFormat == null ? OutputFormat.text() : outputFormat;
        this.requestedThinking = requestedThinking == null ? ThinkingMode.DEFAULT : requestedThinking;
        this.slotThinking = slotThinking == null ? ThinkingMode.DEFAULT : slotThinking;
        this.temperature = temperature;
        this.answerTokens = Math.max(0, answerTokens);
        this.contextTokens = Math.max(0, contextTokens);
        this.truncate = truncate;
        this.keepAlive = keepAlive;
        this.abort = abort == null ? AbortSignal.NEVER : abort;
    }

    public String getSlot() {
        return slot;
    }

    public String getModel() {
        return model;
    }

    public List<ModelMessage> getMessages() {
        return messages;
    }

    public List<ToolDefinition> getTools() {
        return tools;
    }

    public OutputFormat getOutputFormat() {
        return outputFormat;
    }

    /** The caller's thinking mode; {@link ThinkingMode#DEFAULT} = the slot decides. */
    public ThinkingMode getRequestedThinking() {
        return requestedThinking;
    }

    /** The caller's mode, else the slot's; {@link ThinkingMode#DEFAULT} here means Auto (resolved per model). */
    public ThinkingMode effectiveThinking() {
        return requestedThinking == ThinkingMode.DEFAULT ? slotThinking : requestedThinking;
    }

    public Double getTemperature() {
        return temperature;
    }

    /** The answer budget in tokens; 0 = none. */
    public int getAnswerTokens() {
        return answerTokens;
    }

    public int getContextTokens() {
        return contextTokens;
    }

    public Boolean getTruncate() {
        return truncate;
    }

    public String getKeepAlive() {
        return keepAlive;
    }

    public AbortSignal getAbort() {
        return abort;
    }
}
