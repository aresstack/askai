package com.aresstack.askai.speech;

/**
 * The explicit, Swing-independent state of a single dictation (record → transcribe → insert). Each
 * state fixes which user actions are allowed, how the microphone button reads, whether Stop/Discard
 * apply, the visible status message and whether a temporary recording exists. Because there is one
 * current state, mutually exclusive situations (recording <em>and</em> transcribing at once) cannot
 * occur.
 */
public enum DictationState {

    IDLE("Record", "Select a model and start chatting.", false, false, false, false),
    OPENING_MICROPHONE("…", "Opening microphone …", false, false, false, false),
    RECORDING("Stop", "Recording", true, true, true, false),
    FINALIZING_RECORDING("…", "Processing recording …", false, false, true, false),
    VERIFYING_MODEL("…", "Verifying audio model …", false, true, true, false),
    UPLOADING_AUDIO("Cancel", "Uploading audio …", false, true, true, false),
    TRANSCRIBING("Cancel", "Transcribing …", false, true, true, false),
    TRANSCRIPTION_READY("Record", "Transcription ready. Review the text and press Send.", false, false, false, true),
    CANCELLED("Record", "Cancelled.", false, false, true, true),
    FAILED("Record", "Dictation failed.", false, false, true, true);

    private final String micButtonLabel;
    private final String defaultStatusMessage;
    private final boolean canStop;
    private final boolean canDiscard;
    private final boolean hasTempRecording;
    private final boolean terminal;

    DictationState(String micButtonLabel, String defaultStatusMessage, boolean canStop,
                   boolean canDiscard, boolean hasTempRecording, boolean terminal) {
        this.micButtonLabel = micButtonLabel;
        this.defaultStatusMessage = defaultStatusMessage;
        this.canStop = canStop;
        this.canDiscard = canDiscard;
        this.hasTempRecording = hasTempRecording;
        this.terminal = terminal;
    }

    public String getMicButtonLabel() {
        return micButtonLabel;
    }

    public String getDefaultStatusMessage() {
        return defaultStatusMessage;
    }

    /** @return true while a recording is running and can be stopped for transcription. */
    public boolean canStop() {
        return canStop;
    }

    /** @return true while the current work (recording or transcription) can be discarded/cancelled. */
    public boolean canDiscard() {
        return canDiscard;
    }

    /**
     * @return a coarse hint whether this state <em>usually</em> has a temp recording.
     * @deprecated a state alone does not prove a file exists (a finalize failure or a cancel while
     *     recording leaves none). The UI must ask {@link SpeechDictationService#hasRetryableRecording()}
     *     / {@link SpeechDictationService#hasSavableRecording()} instead.
     */
    @Deprecated
    public boolean hasTempRecording() {
        return hasTempRecording;
    }

    /** @return true when the dictation has finished (ready, cancelled or failed). */
    public boolean isTerminal() {
        return terminal;
    }

    /** @return true when a new recording may be started from this state. */
    public boolean canStartRecording() {
        return this == IDLE || this == TRANSCRIPTION_READY || this == CANCELLED || this == FAILED;
    }
}
