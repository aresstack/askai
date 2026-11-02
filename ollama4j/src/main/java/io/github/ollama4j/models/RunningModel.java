package io.github.ollama4j.models;

public final class RunningModel {

    private final String name;
    private final long size;
    private final long sizeVram;
    private final String expiresAt;

    public RunningModel(String name, long size, long sizeVram, String expiresAt) {
        this.name = name == null ? "" : name;
        this.size = size;
        this.sizeVram = sizeVram;
        this.expiresAt = expiresAt == null ? "" : expiresAt;
    }

    public String getName() {
        return name;
    }

    public long getSize() {
        return size;
    }

    public long getSizeVram() {
        return sizeVram;
    }

    public String getExpiresAt() {
        return expiresAt;
    }
}
