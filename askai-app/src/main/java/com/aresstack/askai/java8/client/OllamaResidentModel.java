package com.aresstack.askai.java8.client;

/**
 * One model Ollama reports as loaded ({@code GET /api/ps}), in AskAI domain terms: name, loaded size, the part
 * in GPU memory, the planned unload time and the loaded context length ({@code -1}/"" when not reported).
 * Neither the KV-cache share nor the number of parallel slots is reported by Ollama.
 */
public final class OllamaResidentModel {

    private final String name;
    private final long sizeBytes;
    private final long vramBytes;
    private final String expiresAt;
    private final int contextLength;

    public OllamaResidentModel(String name, long sizeBytes, long vramBytes, String expiresAt, int contextLength) {
        this.name = name == null ? "" : name;
        this.sizeBytes = sizeBytes;
        this.vramBytes = vramBytes;
        this.expiresAt = expiresAt == null ? "" : expiresAt;
        this.contextLength = contextLength;
    }

    public String getName() {
        return name;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public long getVramBytes() {
        return vramBytes;
    }

    /** ISO-8601 planned unload time, or "" when not reported. */
    public String getExpiresAt() {
        return expiresAt;
    }

    public int getContextLength() {
        return contextLength;
    }
}
