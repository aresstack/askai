package com.aresstack.askai.stt;

/**
 * Structured classification of a transcription failure, produced by {@link OllamaSpeechToTextClient}.
 * The adapter stays free of UI wording and install instructions; the application layer maps each kind
 * to a user hint (e.g. "Install audio model", "Retry").
 */
public enum TranscriptionErrorKind {

    /** {@code /v1/audio/transcriptions} is not registered on this server (HTTP 404). */
    ENDPOINT_NOT_FOUND,
    /** The server reported the model cannot accept audio (no mmproj / not multimodal). */
    MODEL_NOT_AUDIO,
    /** The server rejected the request (HTTP 400/422). */
    BAD_REQUEST,
    /** The server failed while transcribing (HTTP 5xx). */
    SERVER_ERROR,
    /** The request timed out. */
    TIMEOUT,
    /** The caller aborted the request. */
    CANCELLED,
    /** The server was not reachable. */
    UNREACHABLE,
    /** The response was well-formed but carried no usable transcription. */
    EMPTY_RESULT,
    /** The response body was not the expected JSON. */
    BAD_JSON,
    /** Any other failure. */
    FAILED
}
