package io.github.ollama4j.models;

public final class Model {
    private final String name;
    private final String modifiedAt;
    private final long size;
    private final String digest;
    private final ModelDetails details;

    public Model(String name, String modifiedAt, long size, String digest, ModelDetails details) {
        this.name = name == null ? "" : name;
        this.modifiedAt = modifiedAt == null ? "" : modifiedAt;
        this.size = size;
        this.digest = digest == null ? "" : digest;
        this.details = details == null ? ModelDetails.empty() : details;
    }

    public String getName() { return name; }
    public String getModifiedAt() { return modifiedAt; }
    public long getSize() { return size; }
    public String getDigest() { return digest; }
    public ModelDetails getDetails() { return details; }
}
