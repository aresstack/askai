package com.aresstack.askai.client;

/**
 * Receives progress updates while an Ollama model is being pulled.
 */
public interface OllamaPullListener {

    void onProgress(OllamaPullProgress progress);
}
