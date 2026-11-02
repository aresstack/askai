package com.aresstack.askai.speech;

/**
 * Result of the speech-to-text preflight: it separates "server offers the transcription endpoint" from
 * "a model can do audio", so a missing endpoint is not discovered only after a long recording.
 */
public enum ReadinessStatus {

    READY,
    SERVER_UNREACHABLE,
    SERVER_ENDPOINT_UNAVAILABLE,
    NO_AUDIO_MODEL,
    MODEL_CAPABILITY_UNKNOWN,
    MODEL_NOT_AUDIO_CAPABLE;

    public boolean isReady() {
        return this == READY;
    }
}
