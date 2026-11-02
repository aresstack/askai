package com.aresstack.askai.stt;

/**
 * A speech-to-text failure. Carries a structured {@link TranscriptionErrorKind} and (when relevant)
 * the HTTP status, so the application layer can pick the right user hint and recovery action instead
 * of parsing a message string. The message stays factual (it may include the server's own error text)
 * and free of hardcoded install instructions.
 */
public class SpeechToTextException extends Exception {

    private final TranscriptionErrorKind kind;
    private final int httpStatus;

    public SpeechToTextException(String message) {
        this(TranscriptionErrorKind.FAILED, 0, message, null);
    }

    public SpeechToTextException(String message, Throwable cause) {
        this(TranscriptionErrorKind.FAILED, 0, message, cause);
    }

    public SpeechToTextException(TranscriptionErrorKind kind, int httpStatus, String message) {
        this(kind, httpStatus, message, null);
    }

    public SpeechToTextException(TranscriptionErrorKind kind, int httpStatus, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind == null ? TranscriptionErrorKind.FAILED : kind;
        this.httpStatus = httpStatus;
    }

    public TranscriptionErrorKind getKind() {
        return kind;
    }

    /** @return the HTTP status associated with the failure, or 0 when not applicable. */
    public int getHttpStatus() {
        return httpStatus;
    }
}
