package com.aresstack.askai.stt;

import java.io.File;

/**
 * UI-facing speech-to-text boundary. Panels depend on this interface, never on HTTP details,
 * multipart requests, or a concrete backend. The domain here is deliberately backend-neutral
 * ("speech to text", not "Ollama transcribes audio") so other backends (whisper.cpp server,
 * faster-whisper, any OpenAI-compatible STT server, a local sidecar) can be added later.
 */
public interface SpeechToTextService {

    /**
     * Transcribes the given audio file asynchronously. The listener is called exactly once with
     * either the transcription or an error. Cancelling the returned task aborts the request; a
     * cancelled task reports through {@link TranscriptionListener#onError(Exception)}.
     */
    Task transcribe(TranscriptionRequest request, TranscriptionListener listener);

    interface Task {
        void cancel();
    }

    interface TranscriptionListener {
        void onTranscription(String text);

        void onError(Exception ex);
    }

    /** What to transcribe and how; carries no backend specifics. */
    final class TranscriptionRequest {

        private final File audioFile;
        private final String modelName;
        private final String language;
        private final String prompt;

        /**
         * @param audioFile the audio file to transcribe (must exist)
         * @param modelName the STT model to use; empty means the backend/service decides
         * @param language  ISO language hint ("de", "en", ...); empty or "auto" lets the model detect
         * @param prompt    optional context prompt guiding the transcription; empty for none
         */
        public TranscriptionRequest(File audioFile, String modelName, String language, String prompt) {
            this.audioFile = audioFile;
            this.modelName = modelName == null ? "" : modelName.trim();
            this.language = language == null ? "" : language.trim();
            this.prompt = prompt == null ? "" : prompt.trim();
        }

        public File getAudioFile() {
            return audioFile;
        }

        public String getModelName() {
            return modelName;
        }

        public String getLanguage() {
            return language;
        }

        public String getPrompt() {
            return prompt;
        }
    }
}
