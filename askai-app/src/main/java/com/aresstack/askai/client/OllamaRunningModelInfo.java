package com.aresstack.askai.client;

/**
 * Active Ollama model metadata.
 */
public final class OllamaRunningModelInfo {

    private final String name;
    private final String model;
    private final String expiresAt;
    private final long size;
    private final long sizeVram;
    private final OllamaModelDetails details;
    // Virtual-container origin (R0); the plain constructor keeps the remote defaults.
    private final String containerId;
    private final String containerDisplayName;
    private final boolean local;

    public OllamaRunningModelInfo(String name, String model, String expiresAt, long size, long sizeVram,
                                  OllamaModelDetails details) {
        this(name, model, expiresAt, size, sizeVram, details, "", "", false);
    }

    public OllamaRunningModelInfo(String name, String model, String expiresAt, long size, long sizeVram,
                                  OllamaModelDetails details, String containerId,
                                  String containerDisplayName, boolean local) {
        this.name = safe(name);
        this.model = safe(model);
        this.expiresAt = safe(expiresAt);
        this.size = size;
        this.sizeVram = sizeVram;
        this.details = details == null ? OllamaModelDetails.empty() : details;
        this.containerId = safe(containerId);
        this.containerDisplayName = safe(containerDisplayName);
        this.local = local;
    }

    /** A copy tagged with its virtual-container origin. */
    public OllamaRunningModelInfo withContainer(String containerId, String containerDisplayName,
                                                boolean local) {
        return new OllamaRunningModelInfo(name, model, expiresAt, size, sizeVram, details,
                containerId, containerDisplayName, local);
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

    public String getDisplayName() {
        return !name.isEmpty() ? name : model;
    }

    public String getExpiresAt() {
        return expiresAt;
    }

    public long getSize() {
        return size;
    }

    public long getSizeVram() {
        return sizeVram;
    }

    public OllamaModelDetails getDetails() {
        return details;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
