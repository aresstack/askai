package com.aresstack.askai.research.runtime.team;

import com.aresstack.askai.agent.model.port.ChatRequest;
import com.aresstack.askai.agent.model.port.ModelFailure;
import com.aresstack.askai.agent.model.port.ModelMessage;
import com.aresstack.askai.agent.model.port.ModelPort;
import com.aresstack.askai.agent.model.port.ModelSlot;
import com.aresstack.askai.agent.model.port.OutputFormat;
import com.aresstack.askai.research.runtime.inference.BlockingModelCall;

import java.time.Duration;
import java.util.List;

/**
 * The productive {@link MainModelChat} over the host's model port (ADR 0001, 3.2). It replaces the former
 * hand-written {@code /api/chat} HTTP client: the runtime no longer knows Ollama, endpoints or model names, it
 * names a {@link ModelSlot} and the platform resolves it, schedules it against the model's capacity and keeps
 * the model warm.
 *
 * <p>The call shapes are exactly the former client's: the full message history, non-streaming from the
 * caller's view, the caller's temperature and output budget, no thinking setting (the model's default),
 * generic JSON or a JSON schema for {@link #completeJson}. Failures keep their honest typed status: a time-out
 * is {@code TIMEOUT}, an unusable answer (empty, reasoning only) is {@code INVALID_RESPONSE}, everything
 * else is {@code PROVIDER_FAILURE} with the port's named reason in the detail.</p>
 */
public final class PortMainModelChat implements MainModelChat {

    private final ModelPort port;
    private final ModelSlot slot;
    private final Duration timeout;
    private final BlockingModelCall calls;

    /**
     * @param port    the host's model port
     * @param slot    the calling role's slot
     * @param timeout the per-call time limit (the user's main-model timeout setting), or null for the
     *                platform default
     */
    public PortMainModelChat(ModelPort port, ModelSlot slot, Duration timeout) {
        if (port == null || slot == null) {
            throw new IllegalArgumentException("port and slot must not be null");
        }
        this.port = port;
        this.slot = slot;
        this.timeout = timeout;
        this.calls = new BlockingModelCall(port);
    }

    public ModelSlot getSlot() {
        return slot;
    }

    @Override
    public MainModelChatResult complete(List<ChatMessage> messages, double temperature, int maxOutputTokens) {
        return call(messages, temperature, maxOutputTokens, OutputFormat.text());
    }

    @Override
    public MainModelChatResult completeJson(List<ChatMessage> messages, double temperature,
                                            int maxOutputTokens, String schemaJson) {
        return call(messages, temperature, maxOutputTokens,
                schemaJson == null || schemaJson.trim().isEmpty()
                        ? OutputFormat.json() : OutputFormat.jsonSchema(schemaJson));
    }

    /** The slot name: plugins never see which model fills it. */
    @Override
    public String modelName() {
        return "slot " + slot.getName();
    }

    /** Aborts every call in flight (session close, pause, cancel); each returns an honest non-OK result. */
    @Override
    public void cancelInFlight() {
        calls.cancelAll();
    }

    private MainModelChatResult call(List<ChatMessage> messages, double temperature, int maxOutputTokens,
                                     OutputFormat format) {
        if (messages == null || messages.isEmpty()) {
            return MainModelChatResult.failure(MainModelChatResult.Status.INVALID_RESPONSE,
                    "no messages to send");
        }
        ChatRequest.Builder request = ChatRequest.builder(slot)
                .outputFormat(format)
                .temperature(temperature < 0 || Double.isNaN(temperature) ? null : temperature)
                .maxOutputTokens(Math.max(0, maxOutputTokens))
                .timeout(timeout);
        for (ChatMessage message : messages) {
            request.message(toModelMessage(message));
        }
        BlockingModelCall.Outcome outcome = calls.run(request.build());
        if (outcome.isOk()) {
            return MainModelChatResult.ok(outcome.result.getContent());
        }
        return MainModelChatResult.failure(statusOf(outcome.failure.getKind()),
                "main-model call failed: " + outcome.failure);
    }

    static ModelMessage toModelMessage(ChatMessage message) {
        switch (message.getRole()) {
            case SYSTEM:
                return ModelMessage.system(message.getContent());
            case ASSISTANT:
                return ModelMessage.assistant(message.getContent());
            default:
                return ModelMessage.user(message.getContent());
        }
    }

    static MainModelChatResult.Status statusOf(ModelFailure.Kind kind) {
        switch (kind) {
            case TIMEOUT:
                return MainModelChatResult.Status.TIMEOUT;
            case INVALID_RESPONSE:
                return MainModelChatResult.Status.INVALID_RESPONSE;
            default:
                return MainModelChatResult.Status.PROVIDER_FAILURE;
        }
    }
}
