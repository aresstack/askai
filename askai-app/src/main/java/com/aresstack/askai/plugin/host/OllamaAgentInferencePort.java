package com.aresstack.askai.plugin.host;

import com.aresstack.askai.agent.model.inference.AgentInferencePort;
import com.aresstack.askai.client.OllamaChatTurn;
import com.aresstack.askai.service.OllamaService;

import java.util.ArrayList;
import java.util.List;

/**
 * Host implementation of the neutral {@link AgentInferencePort} on the EXISTING chat stack: the centrally
 * selected main model, {@link OllamaService#streamChat} (daemon pool, streaming, interrupting cancel).
 * Plugins get one prompt-in/stream-out seam and never see Ollama types or model selection.
 */
public final class OllamaAgentInferencePort implements AgentInferencePort {

    /** The centrally selected main chat model ("" when none) — read per call, so changes apply live. */
    public interface ModelSource {
        String mainModelName();
    }

    private static final Cancellable NONE = new Cancellable() {
        public void cancel() {
        }
    };

    private final OllamaService ollama;
    private final ModelSource models;

    public OllamaAgentInferencePort(OllamaService ollama, ModelSource models) {
        this.ollama = ollama;
        this.models = models;
    }

    @Override
    public Cancellable generate(InferenceRequest request, final Listener listener) {
        String model = models.mainModelName();
        if (model == null || model.trim().isEmpty()) {
            listener.onFailed("no main model selected");
            return NONE;
        }
        List<OllamaChatTurn> turns = new ArrayList<OllamaChatTurn>();
        if (!request.getSystemPrompt().isEmpty()) {
            turns.add(new OllamaChatTurn(OllamaChatTurn.ROLE_SYSTEM, request.getSystemPrompt()));
        }
        turns.add(new OllamaChatTurn(OllamaChatTurn.ROLE_USER, request.getUserPrompt()));
        final StringBuilder content = new StringBuilder();
        final OllamaService.Task task = ollama.streamChat(
                new OllamaService.ChatRequest(model.trim(), "5m", turns),
                new OllamaService.ChatListener() {
                    public void onThinkingDelta(String delta) {
                        listener.onThinkingDelta(delta);
                    }

                    public void onContent(String delta) {
                        content.append(delta);
                        listener.onContentDelta(delta);
                    }

                    public void onStatus(String status) {
                    }

                    public void onComplete(OllamaService.ChatResult result) {
                        String text = content.length() > 0 ? content.toString()
                                : result == null ? "" : result.getFallbackText();
                        listener.onCompleted(text == null ? "" : text);
                    }

                    public void onError(Exception ex) {
                        listener.onFailed(ex == null || ex.getMessage() == null
                                ? "inference failed" : ex.getMessage());
                    }
                });
        return new Cancellable() {
            public void cancel() {
                task.cancel(); // Future.cancel(true) → the streaming HTTP call aborts, the model is freed
            }
        };
    }
}
