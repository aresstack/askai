package com.aresstack.askai.modelport.provider;

import com.aresstack.askai.client.OllamaResidentModel;

import java.util.List;

/**
 * The wire of one KIND of model endpoint behind the model port (ADR 0001, 3.2): how a chat turn, a load, an unload
 * and the model lists reach the endpoint. The port above it stays provider-neutral: it resolves slots to models,
 * admits calls through its scheduler, enforces time limits and cancels. A provider only speaks its endpoint's
 * protocol and says what kind of resource the endpoint is.
 *
 * <p>Two implementations:</p>
 * <ul>
 *   <li>{@link OllamaModelProvider}: Ollama and AskAI's local runtime (which speaks Ollama's API). One machine,
 *       one GPU: models compete for memory, so the port groups work by model, warms the active model and may
 *       unload the one a switch left behind.</li>
 *   <li>{@link OpenAiCompatibleModelProvider}: {@code POST /chat/completions} of any OpenAI-compatible service. The
 *       service serves many models side by side: every model runs at the same time as the others, several calls
 *       per model, and AskAI never loads or unloads anything.</li>
 * </ul>
 *
 * <p>Threading: every method may block; the port calls them on its own worker threads, never on a caller's.
 * No method writes a credential into a message, an exception or a log line.</p>
 */
public interface ModelProvider {

    /**
     * True when the endpoint's models share one machine's memory (a local GPU): the scheduler then lets two
     * models hold places at the same time only when the memory planning says they fit, a change of model is a
     * deliberate step, and the port may unload the model a switch left behind. False for a service that serves
     * many models side by side: models never wait for each other and nothing is loaded or unloaded.
     */
    boolean sharesMemory();

    /**
     * Concurrent calls per model on this provider when the user set no value for the model; {@code 0} = the
     * port's own setting ({@code capacity.default} of the model-port settings, Ollama's default).
     */
    int defaultCapacity();

    /**
     * The context the port plans a call of {@code model} with (the minimum a request may ask for); {@code 0} = the
     * port's per-model setting (Ollama's {@code num_ctx}, which every call sends).
     */
    int contextTokens(String model);

    /**
     * One model turn: sends the call, streams thinking and content through {@code stream}, returns the completed
     * turn. Blocks the calling (port) thread until the turn ended, failed or was aborted through the call's
     * {@link ProviderCall#getAbort() abort signal}.
     *
     * @throws ProviderException when the endpoint is unreachable, answered with an error or the stream broke off
     */
    ProviderCompletion chat(String baseUrl, ProviderCall call, ProviderStream stream) throws ProviderException;

    /** Loads {@code model} so its first call does not pay the load; a provider without residency does nothing. */
    void load(String baseUrl, String model, int contextTokens, String keepAlive) throws ProviderException;

    /** Frees {@code model}'s memory now (Ollama: {@code keep_alive 0}); a provider without residency does nothing. */
    void unload(String baseUrl, String model) throws ProviderException;

    /** The models loaded now, or {@code null} when the provider has no residency to report (a remote API). */
    List<OllamaResidentModel> residentModels(String baseUrl) throws ProviderException;

    /** The models the endpoint offers (installed or listed), or {@code null} when it cannot tell. */
    List<String> modelNames(String baseUrl) throws ProviderException;

    /** Whether {@code names} (from {@link #modelNames}) contain {@code model} in this provider's naming. */
    boolean offers(List<String> names, String model);

    /** The user-facing message for a slot whose model the endpoint does not offer. */
    String missingModelMessage(String model, String slot);

    /**
     * True when {@code model}, in this provider's naming, accepts only thinking levels and can never turn thinking
     * off (plan 4.1, rule 3), so it cannot serve a helper slot. The rule is the model-port settings' own; a provider
     * whose names carry more than the model (a vendor prefix) says how to read them.
     */
    default boolean isLevelOnlyModel(String model) {
        return com.aresstack.askai.modelport.ModelPortSettings.isLevelOnlyModel(model, null);
    }

    /** The user-facing message for a helper slot whose model cannot turn thinking off: where to pick another. */
    default String cannotTurnThinkingOffMessage(String model, String slot) {
        return com.aresstack.askai.modelport.ModelPortSettings.thinkingOffRequiredMessage(slot, model);
    }
}
