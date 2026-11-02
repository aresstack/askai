package io.github.ollama4j.http;

public final class OllamaHttpResponse {

    private final int statusCode;
    private final String body;

    public OllamaHttpResponse(int statusCode, String body) {
        this.statusCode = statusCode;
        this.body = body == null ? "" : body;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public String getBody() {
        return body;
    }

    public boolean isSuccessful() {
        return statusCode >= 200 && statusCode < 300;
    }
}
