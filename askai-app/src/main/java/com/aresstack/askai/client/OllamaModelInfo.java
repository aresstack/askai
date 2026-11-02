package com.aresstack.askai.client;

/**
 * Installed Ollama model metadata.
 */
public final class OllamaModelInfo {

    private final String name;
    private final String model;
    private final String modifiedAt;
    private final long size;
    private final String digest;
    private final OllamaModelDetails details;
    // Virtual-container origin (R0); the plain constructor keeps the remote defaults so every
    // existing caller stays source-compatible.
    private final String containerId;
    private final String containerDisplayName;
    private final boolean local;

    public OllamaModelInfo(String name, String model, String modifiedAt, long size, String digest,
                           OllamaModelDetails details) {
        this(name, model, modifiedAt, size, digest, details, "", "", false);
    }

    public OllamaModelInfo(String name, String model, String modifiedAt, long size, String digest,
                           OllamaModelDetails details, String containerId,
                           String containerDisplayName, boolean local) {
        this.name = safe(name);
        this.model = safe(model);
        this.modifiedAt = safe(modifiedAt);
        this.size = size;
        this.digest = safe(digest);
        this.details = details == null ? OllamaModelDetails.empty() : details;
        this.containerId = safe(containerId);
        this.containerDisplayName = safe(containerDisplayName);
        this.local = local;
    }

    /** A copy tagged with its virtual-container origin. */
    public OllamaModelInfo withContainer(String containerId, String containerDisplayName,
                                         boolean local) {
        return new OllamaModelInfo(name, model, modifiedAt, size, digest, details, containerId,
                containerDisplayName, local);
    }

    public String getContainerId() {
        return containerId;
    }

    public String getContainerDisplayName() {
        return containerDisplayName;
    }

    public boolean isLocal() {
        return local;
    }

    public String getName() {
        return name;
    }

    public String getModel() {
        return model;
    }

    public String getDisplayName() {
        return !name.isEmpty() ? name : model;
    }

    public String getModifiedAt() {
        return modifiedAt;
    }

    public long getSize() {
        return size;
    }

    public String getDigest() {
        return digest;
    }

    public OllamaModelDetails getDetails() {
        return details;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
