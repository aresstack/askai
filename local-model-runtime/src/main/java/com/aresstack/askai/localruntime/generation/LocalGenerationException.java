package com.aresstack.askai.localruntime.generation;

/**
 * A typed generation failure carrying a {@link LocalGenerationErrorCode}. AskAI-owned; the HTTP layer maps
 * {@link #code()} to the response error code.
 */
public final class LocalGenerationException extends Exception {

    private final LocalGenerationErrorCode code;

    public LocalGenerationException(LocalGenerationErrorCode code, String message) {
        super(message);
        this.code = code == null ? LocalGenerationErrorCode.GENERATION_FAILED : code;
    }

    public LocalGenerationException(LocalGenerationErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = code == null ? LocalGenerationErrorCode.GENERATION_FAILED : code;
    }

    public LocalGenerationErrorCode code() {
        return code;
    }
}
