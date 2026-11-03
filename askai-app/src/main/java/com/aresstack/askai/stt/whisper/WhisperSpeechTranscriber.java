package com.aresstack.askai.stt.whisper;

import com.aresstack.askai.speech.DictationErrorKind;
import com.aresstack.askai.speech.SpeechTranscriber;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * {@link SpeechTranscriber} backed by the local whisper.cpp CLI: runs {@code whisper-cli.exe} on the normalized
 * 16 kHz mono WAV and reads the plain-text result it writes next to the audio (UTF-8, so umlauts survive the
 * Windows console). The model comes from {@link TranscriptionInput#getModel()} as {@code @whisper:<id>}.
 * {@link #cancel()} kills the running process.
 */
public final class WhisperSpeechTranscriber implements SpeechTranscriber {

    /**
     * Prefix that marks a Whisper model in the dictation pipeline's model name. Starts with '@', which Ollama model
     * names cannot contain, so an Ollama model called e.g. {@code whisper:latest} never routes to the local engine.
     */
    public static final String MODEL_PREFIX = "@whisper:";

    private final WhisperStore store;
    private final Supplier<Integer> timeoutSecondsSupplier;
    private volatile Process active;
    private volatile boolean cancelled;

    public WhisperSpeechTranscriber(WhisperStore store, Supplier<Integer> timeoutSecondsSupplier) {
        this.store = store;
        this.timeoutSecondsSupplier = timeoutSecondsSupplier;
    }

    public static boolean isWhisperModel(String model) {
        return model != null && model.startsWith(MODEL_PREFIX);
    }

    public String transcribe(TranscriptionInput input) throws SpeechTranscriberException {
        cancelled = false;
        WhisperModel model = WhisperModelCatalog.findById(input.getModel().substring(
                isWhisperModel(input.getModel()) ? MODEL_PREFIX.length() : 0));
        if (model == null || !store.isModelInstalled(model) || !store.isEngineInstalled()) {
            throw new SpeechTranscriberException(DictationErrorKind.NO_AUDIO_MODEL,
                    "Whisper model not installed: " + input.getModel());
        }
        File audio = input.getAudioFile();
        Path outputBase = audio.toPath().resolveSibling(stripExtension(audio.getName()) + "-whisper");
        Path outputText = outputBase.resolveSibling(outputBase.getFileName() + ".txt");
        List<String> command = command(store, model, audio, outputBase, input.getLanguage(), input.getPrompt());
        Process process;
        try {
            process = new ProcessBuilder(command)
                    .directory(store.engineDirectory().toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(outputBase.resolveSibling(outputBase.getFileName() + ".log").toFile())
                    .start();
        } catch (IOException ex) {
            throw new SpeechTranscriberException(DictationErrorKind.TRANSCRIPTION_FAILED,
                    "Whisper could not start: " + ex.getMessage());
        }
        active = process;
        try {
            int timeout = Math.max(1, timeoutSecondsSupplier.get());
            if (!process.waitFor(timeout, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new SpeechTranscriberException(DictationErrorKind.TRANSCRIPTION_FAILED,
                        "Whisper did not finish within " + timeout + " s.");
            }
            if (cancelled) {
                throw new SpeechTranscriberException(DictationErrorKind.CANCELLED, "Cancelled.");
            }
            if (process.exitValue() != 0 || !Files.isRegularFile(outputText)) {
                throw new SpeechTranscriberException(DictationErrorKind.TRANSCRIPTION_FAILED,
                        "Whisper failed (exit " + process.exitValue() + ")" + logTail(outputBase));
            }
            return new String(Files.readAllBytes(outputText), StandardCharsets.UTF_8).trim();
        } catch (InterruptedException ex) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new SpeechTranscriberException(DictationErrorKind.CANCELLED, "Interrupted.");
        } catch (IOException ex) {
            throw new SpeechTranscriberException(DictationErrorKind.TRANSCRIPTION_FAILED,
                    "Whisper result unreadable: " + ex.getMessage());
        } finally {
            active = null;
            deleteQuietly(outputText);
            deleteQuietly(outputBase.resolveSibling(outputBase.getFileName() + ".log"));
        }
    }

    /** Visible for tests: the whisper-cli invocation. */
    static List<String> command(WhisperStore store, WhisperModel model, File audio, Path outputBase,
                                String language, String prompt) {
        List<String> command = new ArrayList<String>();
        command.add(store.engineExecutable().toString());
        command.add("-m");
        command.add(store.modelFile(model).toString());
        command.add("-f");
        command.add(audio.getAbsolutePath());
        command.add("-l");
        command.add(language == null || language.trim().isEmpty() ? "auto" : language.trim());
        command.add("-t");
        command.add(String.valueOf(Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors() - 1))));
        command.add("-nt"); // plain text, no timestamps
        command.add("-np"); // no progress prints
        command.add("-otxt");
        command.add("-of");
        command.add(outputBase.toString());
        if (prompt != null && !prompt.trim().isEmpty()) {
            command.add("--prompt");
            command.add(prompt.trim());
        }
        return command;
    }

    public void cancel() {
        cancelled = true;
        Process process = active;
        if (process != null) {
            process.destroyForcibly();
        }
    }

    public int lastHttpStatus() {
        return 0; // no HTTP involved
    }

    private static String logTail(Path outputBase) {
        try {
            Path log = outputBase.resolveSibling(outputBase.getFileName() + ".log");
            String text = new String(Files.readAllBytes(log), StandardCharsets.UTF_8).trim();
            return text.isEmpty() ? "" : ": " + (text.length() > 300 ? text.substring(text.length() - 300) : text);
        } catch (IOException ex) {
            return "";
        }
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // temp output; the dictation work dir is cleaned anyway
        }
    }
}
