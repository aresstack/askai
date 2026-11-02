package com.aresstack.askai.speech;

import java.io.File;

/**
 * Port that sends the canonical WAV to the transcription backend and returns the recognized text.
 * Blocking; {@link #cancel()} aborts an in-flight call from another thread. Backed by the existing
 * Ollama STT adapter; the interface keeps the use case testable and free of HTTP details.
 */
public interface SpeechTranscriber {

    /**
     * @return the recognized text (may be empty; the use case treats empty as a failure)
     * @throws SpeechTranscriberException with a structured {@link DictationErrorKind} on failure/cancel
     */
    String transcribe(TranscriptionInput input) throws SpeechTranscriberException;

    /** Abort the current {@link #transcribe} call (upload or response read) as soon as possible. */
    void cancel();

    /** @return the last HTTP status observed (for diagnostics), or 0 when not applicable. */
    int lastHttpStatus();

    /** Immutable transcription request. */
    final class TranscriptionInput {
        private final File audioFile;
        private final String model;
        private final String language;
        private final String prompt;

        public TranscriptionInput(File audioFile, String model, String language, String prompt) {
            this.audioFile = audioFile;
            this.model = model == null ? "" : model;
            this.language = language == null ? "" : language;
            this.prompt = prompt == null ? "" : prompt;
        }

        public File getAudioFile() { return audioFile; }
        public String getModel() { return model; }
        public String getLanguage() { return language; }
        public String getPrompt() { return prompt; }
    }

    /** Structured transcription failure carrying the mapped {@link DictationErrorKind}. */
    class SpeechTranscriberException extends Exception {
        private final DictationErrorKind kind;

        public SpeechTranscriberException(DictationErrorKind kind, String message) {
            super(message);
            this.kind = kind;
        }

        public DictationErrorKind getKind() {
            return kind;
        }
    }
}
