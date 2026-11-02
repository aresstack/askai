package com.aresstack.askai.stt;

import com.aresstack.askai.speech.DictationErrorKind;
import com.aresstack.askai.speech.SpeechTranscriber;

import java.util.function.Supplier;

/**
 * {@link SpeechTranscriber} port backed by the hardened {@link OllamaSpeechToTextClient}. Lives in the
 * {@code stt} package so it can use the package-private client and map its {@link TranscriptionErrorKind}
 * onto the dictation-level {@link DictationErrorKind}. Blocking; {@link #cancel()} aborts the in-flight
 * HTTP call from another thread.
 */
public final class OllamaSpeechTranscriber implements SpeechTranscriber {

    private final Supplier<String> baseUrlSupplier;
    private final Supplier<Integer> timeoutSecondsSupplier;
    private volatile OllamaSpeechToTextClient active;
    private volatile int lastStatus;

    public OllamaSpeechTranscriber(Supplier<String> baseUrlSupplier, Supplier<Integer> timeoutSecondsSupplier) {
        this.baseUrlSupplier = baseUrlSupplier;
        this.timeoutSecondsSupplier = timeoutSecondsSupplier;
    }

    public String transcribe(TranscriptionInput input) throws SpeechTranscriberException {
        OllamaSpeechToTextClient client = new OllamaSpeechToTextClient(
                baseUrlSupplier.get(), timeoutSecondsSupplier.get());
        active = client;
        try {
            String text = client.transcribe(new SpeechToTextService.TranscriptionRequest(
                    input.getAudioFile(), input.getModel(), input.getLanguage(), input.getPrompt()));
            lastStatus = client.lastHttpStatus();
            return text;
        } catch (SpeechToTextException ex) {
            lastStatus = ex.getHttpStatus() != 0 ? ex.getHttpStatus() : client.lastHttpStatus();
            throw new SpeechTranscriberException(map(ex.getKind()), ex.getMessage());
        } finally {
            active = null;
        }
    }

    public void cancel() {
        OllamaSpeechToTextClient client = active;
        if (client != null) {
            client.abort();
        }
    }

    public int lastHttpStatus() {
        return lastStatus;
    }

    private static DictationErrorKind map(TranscriptionErrorKind kind) {
        switch (kind) {
            case ENDPOINT_NOT_FOUND:
                return DictationErrorKind.SERVER_ENDPOINT_UNAVAILABLE;
            case MODEL_NOT_AUDIO:
                return DictationErrorKind.MODEL_NOT_AUDIO;
            case UNREACHABLE:
                return DictationErrorKind.SERVER_UNREACHABLE;
            case CANCELLED:
                return DictationErrorKind.CANCELLED;
            case EMPTY_RESULT:
                return DictationErrorKind.TRANSCRIPTION_EMPTY;
            case TIMEOUT:
            case BAD_REQUEST:
            case SERVER_ERROR:
            case BAD_JSON:
            case FAILED:
            default:
                return DictationErrorKind.TRANSCRIPTION_FAILED;
        }
    }
}
