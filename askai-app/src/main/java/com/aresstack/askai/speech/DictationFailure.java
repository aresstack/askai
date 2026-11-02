package com.aresstack.askai.speech;

/**
 * A structured dictation failure: the {@link DictationErrorKind}, a technical detail message (safe to
 * log), and whether a temporary recording remains available for Retry/Save. The Swing layer turns this
 * into a friendly message and the matching recovery actions.
 */
public final class DictationFailure {

    private final DictationErrorKind kind;
    private final String detail;

    public DictationFailure(DictationErrorKind kind, String detail) {
        this.kind = kind;
        this.detail = detail == null ? "" : detail;
    }

    public DictationErrorKind getKind() {
        return kind;
    }

    public String getDetail() {
        return detail;
    }

    /**
     * @return whether this kind of failure <em>intends</em> to keep the recording.
     * @deprecated the intent does not prove a file exists; the UI must ask
     *     {@link SpeechDictationService#hasRetryableRecording()} /
     *     {@link SpeechDictationService#hasSavableRecording()} to decide whether to offer Retry/Save.
     */
    @Deprecated
    public boolean keepsRecording() {
        return kind.keepRecording();
    }
}
