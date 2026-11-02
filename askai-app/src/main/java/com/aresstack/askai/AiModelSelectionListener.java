package com.aresstack.askai;

/**
 * Notified when the centrally-managed AI model selection changes, so infrastructure (e.g. the running
 * research sessions' descriptor refresh) can react WITHOUT the UI ever calling it directly. Fired by
 * {@link AskAiModel}: the chat-window main model and the AI-models reranker/embeddings selection are the two
 * write paths. Split by kind so only the affected descriptor is rewritten.
 */
public interface AiModelSelectionListener {

    /** The main (chat/generation) model changed — the shared model for all plugins. */
    void onMainModelChanged();

    /** The reranker and/or embeddings selection changed. */
    void onRerankerOrEmbeddingsChanged();
}
