package io.github.ollama4j.http;

public interface OllamaLineListener {
    void onLine(String line) throws Exception;
}
