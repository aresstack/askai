package com.aresstack.askai.ui.setup;

/**
 * A voice Merlin may speak with (the wizard's replacement for the Enterprise AI Client's {@code ReadAloudControl}).
 * The wizard never depends on it: without a voice, or when it is not available, every sentence stays a speech
 * bubble. Implementations must not block the EDT.
 */
public interface WizardVoice {

    /** {@code true} when a sentence spoken now would actually be heard. */
    boolean isAvailable();

    /** Speaks {@code text}, replacing whatever this voice is saying. */
    void speak(String text);

    /** Stops the current output; idempotent. */
    void stop();
}
