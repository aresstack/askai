package com.aresstack.askai.speech;

/**
 * Callbacks from {@link SpeechDictationService}. State transitions arrive via {@link #onState}; each
 * dictation ends with exactly one terminal call — {@link #onResult} or {@link #onFailure}. All calls
 * come from the service's worker thread; a Swing listener marshals them to the EDT.
 */
public interface DictationListener {

    /** A new state was entered; {@code message} overrides the state's default text when non-null. */
    void onState(DictationState state, String message);

    /** Terminal success: the transcript is ready to be inserted (never auto-sent). */
    void onResult(DictationResult result);

    /** Terminal failure with a structured reason and whether the recording was kept for retry. */
    void onFailure(DictationFailure failure);
}
