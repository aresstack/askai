package com.aresstack.askai.java8.ui.setup;

import java.util.function.Consumer;

/**
 * How Merlin says something: always as a speech bubble next to the wizard, additionally spoken when a voice is
 * selected and available. Without one it stays a bubble; the wizard never depends on speech output. EDT only.
 */
final class WizardNarrator {

    private final Consumer<String> bubble;
    private WizardVoice voice;

    /** @param bubble shows the sentence ({@code null} = clear the bubble) */
    WizardNarrator(Consumer<String> bubble) {
        if (bubble == null) {
            throw new IllegalArgumentException("bubble must not be null");
        }
        this.bubble = bubble;
    }

    /** The application's voice or {@code null} (then only bubbles). */
    void setVoice(WizardVoice voice) {
        this.voice = voice;
    }

    /** Shows the sentence and speaks it when possible; {@code null} or empty clears the bubble. */
    void say(String text) {
        if (text == null || text.trim().isEmpty()) {
            bubble.accept(null);
            return;
        }
        bubble.accept(text);
        if (canSpeak()) {
            voice.speak(text);
        }
    }

    /** Only shows, never speaks (an interim line like "One moment, let me think …"). */
    void show(String text) {
        bubble.accept(text);
    }

    boolean canSpeak() {
        return voice != null && voice.isAvailable();
    }

    /** Ends a running output (wizard closed); idempotent. */
    void stop() {
        if (voice != null) {
            voice.stop();
        }
    }
}
