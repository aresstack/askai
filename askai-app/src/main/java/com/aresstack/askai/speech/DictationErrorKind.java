package com.aresstack.askai.speech;

/**
 * Structured, UI-independent classification of why a dictation could not complete. The application
 * layer maps each kind to a user-facing hint (including whether to offer "Install audio model" or
 * "Retry"); the ports/adapters never build user sentences themselves.
 */
public enum DictationErrorKind {

    MICROPHONE_OPEN_FAILED(false),
    RECORDING_FAILED(false),
    // A finalize failure leaves no reliably usable file (the header was never patched), so nothing
    // is kept; the recorder cleans up its own temp on stop() failure.
    FINALIZE_FAILED(false),
    NORMALIZE_FAILED(true),
    QUALITY_TOO_SHORT(true),
    QUALITY_NO_SIGNAL(true),
    NO_AUDIO_MODEL(true),
    MODEL_CAPABILITY_UNKNOWN(true),
    MODEL_NOT_AUDIO(true),
    SERVER_ENDPOINT_UNAVAILABLE(true),
    SERVER_UNREACHABLE(true),
    TRANSCRIPTION_FAILED(true),
    TRANSCRIPTION_EMPTY(true),
    CANCELLED(true);

    private final boolean keepRecording;

    DictationErrorKind(boolean keepRecording) {
        this.keepRecording = keepRecording;
    }

    /** @return whether the temporary recording should be kept (for Retry/Save) after this error. */
    public boolean keepRecording() {
        return keepRecording;
    }
}
