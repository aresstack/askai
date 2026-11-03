package com.aresstack.askai.localruntime.generation;

import java.nio.file.Path;

/**
 * The request to load a generation model into the port. AskAI-owned; carries only identifiers and a
 * directory — never a class from the underlying library. {@link #loadPolicy()} is always
 * {@link LocalGenerationLoadPolicy#PACKAGE_ONLY}: the runtime must load from the compiled package.
 */
public final class LocalGenerationLoadRequest {

    private final String virtualName;
    private final Path modelDirectory;
    private final String runtimeModelId;
    private final String runtimeFamily;
    private final String chatTemplate;
    private final LocalGenerationBackend backend;
    private final LocalGenerationLoadPolicy loadPolicy;

    private LocalGenerationLoadRequest(Builder b) {
        this.virtualName = b.virtualName;
        this.modelDirectory = b.modelDirectory;
        this.runtimeModelId = b.runtimeModelId;
        this.runtimeFamily = b.runtimeFamily;
        this.chatTemplate = b.chatTemplate == null ? "" : b.chatTemplate;
        this.backend = b.backend;
        this.loadPolicy = LocalGenerationLoadPolicy.PACKAGE_ONLY;
    }

    public String virtualName() {
        return virtualName;
    }

    public Path modelDirectory() {
        return modelDirectory;
    }

    public String runtimeModelId() {
        return runtimeModelId;
    }

    public String runtimeFamily() {
        return runtimeFamily;
    }

    public String chatTemplate() {
        return chatTemplate;
    }

    public LocalGenerationBackend backend() {
        return backend;
    }

    public LocalGenerationLoadPolicy loadPolicy() {
        return loadPolicy;
    }

    public static Builder builder(String virtualName, Path modelDirectory) {
        return new Builder(virtualName, modelDirectory);
    }

    public static final class Builder {
        private final String virtualName;
        private final Path modelDirectory;
        private String runtimeModelId;
        private String runtimeFamily;
        private String chatTemplate;
        private LocalGenerationBackend backend;

        private Builder(String virtualName, Path modelDirectory) {
            this.virtualName = virtualName;
            this.modelDirectory = modelDirectory;
        }

        public Builder runtimeModelId(String value) {
            this.runtimeModelId = value;
            return this;
        }

        public Builder runtimeFamily(String value) {
            this.runtimeFamily = value;
            return this;
        }

        public Builder chatTemplate(String value) {
            this.chatTemplate = value;
            return this;
        }

        public Builder backend(LocalGenerationBackend value) {
            this.backend = value;
            return this;
        }

        public LocalGenerationLoadRequest build() {
            return new LocalGenerationLoadRequest(this);
        }
    }
}
