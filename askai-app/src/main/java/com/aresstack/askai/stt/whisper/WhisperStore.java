package com.aresstack.askai.stt.whisper;

import com.aresstack.askai.settings.AskAiPaths;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * On-disk layout of the Whisper dictation engine and its models: {@code %APPDATA%\.askai\stt\whisper\}
 * (whisper-cli.exe + DLLs) and {@code %APPDATA%\.askai\stt\models\ggml-<id>.bin}. Downloads are staged and only
 * moved into place when complete, so an existing file always counts as installed.
 */
public final class WhisperStore {

    private final Path root;

    public WhisperStore() {
        this(AskAiPaths.appDirectory().resolve("stt"));
    }

    /** Visible for tests: an explicit root. */
    public WhisperStore(Path root) {
        this.root = root;
    }

    public Path root() {
        return root;
    }

    public Path engineDirectory() {
        return root.resolve("whisper");
    }

    public Path engineExecutable() {
        return engineDirectory().resolve("whisper-cli.exe");
    }

    public boolean isEngineInstalled() {
        return Files.isRegularFile(engineExecutable());
    }

    public Path modelFile(WhisperModel model) {
        return root.resolve("models").resolve(model.fileName());
    }

    public boolean isModelInstalled(WhisperModel model) {
        return model != null && Files.isRegularFile(modelFile(model));
    }

    /** @return the curated models that are installed, catalog (recommendation) order. */
    public List<WhisperModel> installedModels() {
        List<WhisperModel> installed = new ArrayList<WhisperModel>();
        for (WhisperModel model : WhisperModelCatalog.curated()) {
            if (isModelInstalled(model)) {
                installed.add(model);
            }
        }
        return installed;
    }

    /**
     * The Whisper model that transcribes for these settings, or {@code null} when Ollama transcribes: Ollama chosen,
     * no engine, or no model installed. An explicitly chosen installed model wins; otherwise the first installed one.
     */
    public WhisperModel modelFor(DictationSettings settings) {
        if (settings == null || settings.getEngine() != DictationSettings.Engine.WHISPER || !isEngineInstalled()) {
            return null;
        }
        WhisperModel chosen = WhisperModelCatalog.findById(settings.getWhisperModelId());
        if (isModelInstalled(chosen)) {
            return chosen;
        }
        List<WhisperModel> installed = installedModels();
        return installed.isEmpty() ? null : installed.get(0);
    }

    public void uninstallModel(WhisperModel model) throws IOException {
        Files.deleteIfExists(modelFile(model));
        if (Files.exists(modelFile(model))) {
            throw new IOException("could not remove " + modelFile(model) + " — is it transcribing right now?");
        }
    }

    static void deleteRecursively(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try {
            if (Files.isDirectory(path)) {
                try (DirectoryStream<Path> children = Files.newDirectoryStream(path)) {
                    for (Path child : children) {
                        deleteRecursively(child);
                    }
                }
            }
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // best-effort cleanup; a leftover .staging dir is invisible to the store
        }
    }
}
