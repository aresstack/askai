package com.aresstack.askai.hf;

public final class HuggingFaceFile {

    private final String modelId;
    private final String path;
    private final long size;
    private final String sha256;

    public HuggingFaceFile(String modelId, String path, long size) {
        this(modelId, path, size, "");
    }

    public HuggingFaceFile(String modelId, String path, long size, String sha256) {
        this.modelId = modelId == null ? "" : modelId;
        this.path = path == null ? "" : path;
        this.size = size;
        this.sha256 = sha256 == null ? "" : sha256.trim().toLowerCase();
    }

    public String getModelId() {
        return modelId;
    }

    public String getPath() {
        return path;
    }

    public long getSize() {
        return size;
    }

    /** @return the expected SHA-256 (git-LFS oid) as lowercase hex, or "" when unknown. */
    public String getSha256() {
        return sha256;
    }

    public String getFileName() {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    public boolean isGguf() {
        return path.toLowerCase().endsWith(".gguf");
    }

    public String toString() {
        return path;
    }
}
