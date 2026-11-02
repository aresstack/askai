package com.aresstack.askai.java8.ui.setup;

/**
 * The voices of the hand-over at the end of the first setup: Merlin's and the chat assistant's (with the
 * assistant's display name). A voice may be missing ({@code null} or not available): then the line is only a
 * speech bubble. {@link #close()} releases both once the hand-over is over.
 */
public final class SetupVoices {

    /** The chat assistant's name in the hand-over when the host names none. */
    public static final String DEFAULT_ASSISTANT_NAME = "AskAI";

    private final WizardVoice merlin;
    private final WizardVoice assistant;
    private final String assistantName;
    private final Runnable release;

    /**
     * @param assistantName display name of the chat assistant; empty = {@link #DEFAULT_ASSISTANT_NAME}
     * @param release       releases the voices or {@code null}
     */
    public SetupVoices(WizardVoice merlin, WizardVoice assistant, String assistantName, Runnable release) {
        this.merlin = merlin;
        this.assistant = assistant;
        this.assistantName = assistantName == null || assistantName.trim().isEmpty() ? DEFAULT_ASSISTANT_NAME
                : assistantName.trim();
        this.release = release;
    }

    /** Without voices: the hand-over runs in speech bubbles only. */
    public static SetupVoices silent(String assistantName) {
        return new SetupVoices(null, null, assistantName, null);
    }

    WizardVoice merlin() {
        return merlin;
    }

    WizardVoice assistant() {
        return assistant;
    }

    String assistantName() {
        return assistantName;
    }

    /** Stops and releases both voices; safe to call twice. */
    void close() {
        if (merlin != null) {
            merlin.stop();
        }
        if (assistant != null) {
            assistant.stop();
        }
        if (release != null) {
            release.run();
        }
    }
}
