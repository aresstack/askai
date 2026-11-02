package com.aresstack.askai.service;

import com.aresstack.askai.client.OllamaChatTurn;
import com.aresstack.askai.client.OllamaModelInfoView;
import com.aresstack.askai.client.OllamaModelInfo;
import com.aresstack.askai.client.OllamaPullProgress;
import com.aresstack.askai.client.OllamaRunningModelInfo;

import java.util.List;

/**
 * UI-facing Ollama service boundary. Swing panels depend on this interface,
 * not on HTTP clients, JSON, or a concrete Ollama library. It exposes the full
 * set of operations AskAI wires from {@code ollama4j}.
 */
public interface OllamaService {

    Task listModelNames(ModelNamesListener listener);

    /**
     * The model names offered for CHAT selection: like {@link #listModelNames} but local models
     * that cannot chat (e.g. rerank-only) are filtered out by their /api/show capabilities. A
     * locally installed CHAT-capable model stays selectable; remote models are never filtered.
     */
    Task listChatModelNames(ModelNamesListener listener);

    Task listInstalledModels(InstalledModelsListener listener);

    Task listRunningModels(RunningModelsListener listener);

    Task getServerVersion(ServerVersionListener listener);

    Task ping(ActionListener listener);

    Task getModelInfo(String modelName, ModelInfoListener listener);

    Task deleteModel(String modelName, ActionListener listener);

    Task unloadModel(String modelName, ActionListener listener);

    Task pullModel(String modelName, PullListener listener);

    Task generate(String modelName, String prompt, ActionListener listener);

    Task embed(String modelName, String input, EmbedListener listener);

    Task streamChat(ChatRequest request, ChatListener listener);

    interface Task {
        void cancel();
    }

    interface ModelNamesListener extends FailureListener {
        void onModelNames(List<String> names);
    }

    interface InstalledModelsListener extends FailureListener {
        void onInstalledModels(List<OllamaModelInfo> models);
    }

    interface RunningModelsListener extends FailureListener {
        void onRunningModels(List<OllamaRunningModelInfo> models);
    }

    interface ServerVersionListener extends FailureListener {
        void onServerVersion(String version);
    }

    interface ModelInfoListener extends FailureListener {
        void onModelInfo(OllamaModelInfoView info);
    }

    interface ActionListener extends FailureListener {
        void onComplete(String message);
    }

    interface PullListener extends FailureListener {
        void onProgress(OllamaPullProgress progress);

        void onComplete(String message);
    }

    interface EmbedListener extends FailureListener {
        void onEmbedding(int vectorCount, int dimensions);
    }

    interface ChatListener extends FailureListener {
        /** A reasoning delta ({@code message.thinking}); default no-op for content-only listeners. */
        default void onThinkingDelta(String delta) {
        }

        /** An answer delta ({@code message.content}). */
        void onContent(String content);

        /** Tool calls emitted in a chunk; default no-op for listeners that ignore tools. */
        default void onToolCalls(java.util.List<com.aresstack.askai.client.OllamaToolCall> toolCalls) {
        }

        void onStatus(String status);

        void onComplete(ChatResult result);
    }

    interface FailureListener {
        void onError(Exception ex);
    }

    final class ChatRequest {
        private final String modelName;
        private final String keepAlive;
        private final List<OllamaChatTurn> messages;
        private final ThinkingOption thinking;

        public ChatRequest(String modelName, String keepAlive, List<OllamaChatTurn> messages) {
            this(modelName, keepAlive, messages, ThinkingOption.defaultOption());
        }

        public ChatRequest(String modelName, String keepAlive, List<OllamaChatTurn> messages,
                           ThinkingOption thinking) {
            this.modelName = modelName;
            this.keepAlive = keepAlive;
            this.messages = messages;
            this.thinking = thinking == null ? ThinkingOption.defaultOption() : thinking;
        }

        public String getModelName() {
            return modelName;
        }

        public String getKeepAlive() {
            return keepAlive;
        }

        public List<OllamaChatTurn> getMessages() {
            return messages;
        }

        /** @return the typed thinking configuration (never null). */
        public ThinkingOption getThinking() {
            return thinking;
        }
    }

    final class ChatResult {
        private final String thinking;
        private final String fallbackText;
        private final java.util.List<com.aresstack.askai.client.OllamaToolCall> toolCalls;
        private final long evalCount;
        private final long evalDurationNanos;

        public ChatResult(String fallbackText, long evalCount, long evalDurationNanos) {
            this("", fallbackText, java.util.Collections.<com.aresstack.askai.client.OllamaToolCall>emptyList(),
                    evalCount, evalDurationNanos);
        }

        public ChatResult(String thinking, String fallbackText,
                          java.util.List<com.aresstack.askai.client.OllamaToolCall> toolCalls,
                          long evalCount, long evalDurationNanos) {
            this.thinking = thinking == null ? "" : thinking;
            this.fallbackText = fallbackText == null ? "" : fallbackText;
            this.toolCalls = toolCalls == null
                    ? java.util.Collections.<com.aresstack.askai.client.OllamaToolCall>emptyList()
                    : java.util.Collections.unmodifiableList(
                            new java.util.ArrayList<com.aresstack.askai.client.OllamaToolCall>(toolCalls));
            this.evalCount = evalCount;
            this.evalDurationNanos = evalDurationNanos;
        }

        /** @return the full reasoning of this turn (empty when the model did not think). */
        public String getThinking() {
            return thinking;
        }

        /** @return the tool calls the assistant requested this turn (empty when none). */
        public java.util.List<com.aresstack.askai.client.OllamaToolCall> getToolCalls() {
            return toolCalls;
        }

        public String getFallbackText() {
            return fallbackText;
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
            return evalCount / (evalDurationNanos / 1_000_000_000.0d);
        }
    }
}
