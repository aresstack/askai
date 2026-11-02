package com.aresstack.askai.client;

/**
 * Signals an unsuccessful Ollama HTTP request.
 */
public final class OllamaRequestException extends Exception {

    public OllamaRequestException(String message) {
        super(message);
    }

    public OllamaRequestException(String message, Throwable cause) {
        super(message, cause);
    }
}
