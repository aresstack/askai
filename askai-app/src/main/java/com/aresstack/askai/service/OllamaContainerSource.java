package com.aresstack.askai.service;

import com.aresstack.askai.client.AskAiOllamaClient;

/**
 * One Ollama-compatible model container (the remote Ollama server or AskAI's local runtime). The
 * {@link VirtualOllamaContainerService} aggregates ALL sources into the one model list the UI
 * shows and routes every operation to the container that owns the model — a local model is never
 * accidentally sent to the remote server and vice versa.
 */
public interface OllamaContainerSource {

    String getContainerId();

    String getDisplayName();

    /** The current base URL, or empty when the container is not reachable/started. */
    String getBaseUrl();

    boolean isLocal();

    /** @throws Exception when the container cannot be made reachable (started/connected). */
    AskAiOllamaClient createClient() throws Exception;

    boolean ownsModel(String virtualModelName);
}
