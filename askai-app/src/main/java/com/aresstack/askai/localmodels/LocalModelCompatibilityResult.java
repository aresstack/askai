package com.aresstack.askai.localmodels;

/**
 * Outcome of the local-runtime compatibility analysis for one Hugging Face repository. A model is
 * installable only with {@link Status#SUPPORTED}; every other status carries the CONCRETE reason
 * the UI shows next to the disabled install control.
 */
public final class LocalModelCompatibilityResult {

    public enum Status {
        SUPPORTED,
        UNSUPPORTED_ARCHITECTURE,
        UNSUPPORTED_TOKENIZER,
        MISSING_REQUIRED_FILES,
        UNKNOWN_CONFIGURATION
    }

    private final Status status;
    private final LocalRuntimeCapability capability;
    /** The win-directml-java runtime id (e.g. MS_MARCO_MINILM_L6); empty unless SUPPORTED. */
    private final String runtimeModelId;
    /** The runtime's canonical model directory name; empty unless SUPPORTED. */
    private final String runtimeDirectoryName;
    private final String reason;

    public LocalModelCompatibilityResult(Status status, LocalRuntimeCapability capability,
                                         String runtimeModelId, String runtimeDirectoryName,
                                         String reason) {
        this.status = status;
        this.capability = capability;
        this.runtimeModelId = runtimeModelId == null ? "" : runtimeModelId;
        this.runtimeDirectoryName = runtimeDirectoryName == null ? "" : runtimeDirectoryName;
        this.reason = reason == null ? "" : reason;
    }

    public boolean isSupported() {
        return status == Status.SUPPORTED;
    }

    public Status getStatus() {
        return status;
    }

    public LocalRuntimeCapability getCapability() {
        return capability;
    }

    public String getRuntimeModelId() {
        return runtimeModelId;
    }

    public String getRuntimeDirectoryName() {
        return runtimeDirectoryName;
    }

    public String getReason() {
        return reason;
    }

    @Override
    public String toString() {
        return status + (reason.isEmpty() ? "" : ": " + reason);
    }
}
