package com.aresstack.askai.stt.whisper;

import com.aresstack.askai.speech.AudioModelResolver;
import com.aresstack.askai.speech.SpeechTranscriber;

import java.util.function.Supplier;

/**
 * Routes dictation to Whisper or Ollama. While {@code whisperModel} yields a model (Whisper chosen and installed),
 * the model resolver answers {@code whisper:<id>} without asking Ollama, and the transcriber hands such models to
 * Whisper; otherwise everything goes to the Ollama adapters exactly as before.
 */
public final class DictationBackendRouting {

    private DictationBackendRouting() {
    }

    public static AudioModelResolver resolver(final AudioModelResolver ollama,
                                              final Supplier<WhisperModel> whisperModel) {
        return new AudioModelResolver() {
            public AudioModelResolution resolve(String requestedModel) {
                WhisperModel whisper = whisperModel.get();
                if (whisper != null) {
                    return new AudioModelResolution(AudioModelResolution.Status.RESOLVED,
                            WhisperSpeechTranscriber.MODEL_PREFIX + whisper.getId(), "whisper");
                }
                return ollama.resolve(requestedModel);
            }
        };
    }

    public static SpeechTranscriber transcriber(final SpeechTranscriber ollama, final SpeechTranscriber whisper) {
        return new SpeechTranscriber() {
            private volatile SpeechTranscriber current = ollama;

            public String transcribe(TranscriptionInput input) throws SpeechTranscriberException {
                current = WhisperSpeechTranscriber.isWhisperModel(input.getModel()) ? whisper : ollama;
                return current.transcribe(input);
            }

            public void cancel() {
                current.cancel();
            }

            public int lastHttpStatus() {
                return current.lastHttpStatus();
            }
        };
    }
}
