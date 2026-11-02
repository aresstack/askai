package io.github.ollama4j.models;

public interface ChatTokenListener {
    void onToken(String token);
}
