package com.aresstack.askai.audio.transfer;

/**
 * A controlled, user-presentable transfer failure (e.g. wrong format, unsupported newer schema version,
 * malformed JSON, or nothing to export). Distinct from {@link java.io.IOException} so the UI can show a
 * clear message without leaking stack traces.
 */
public final class AudioProfileTransferException extends Exception {

    public AudioProfileTransferException(String message) {
        super(message);
    }

    public AudioProfileTransferException(String message, Throwable cause) {
        super(message, cause);
    }
}
