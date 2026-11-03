package com.aresstack.askai.localruntime.generation;

import java.util.Locale;

/**
 * The typed failure vocabulary of AskAI's local generation port. These are AskAI-owned and never leak a
 * class from the (future) win-directml generation library; the HTTP layer maps them to a stable error
 * {@code code} so the host routes on the code, never on a message.
 */
public enum LocalGenerationErrorCode {

    MODEL_NOT_FOUND,
    MODEL_NOT_LOADABLE,
    MODEL_CAPABILITY_MISMATCH,
    UNSUPPORTED_BACKEND,
    INVALID_REQUEST,
    PACKAGE_MISSING,
    PACKAGE_NOT_LOADABLE,
    GENERATION_FAILED,
    /** No productive generation runtime is linked into this build yet (development branch). */
    RUNTIME_NOT_LINKED;

    public String token() {
        return name();
    }

    public String lower() {
        return name().toLowerCase(Locale.ROOT);
    }
}
