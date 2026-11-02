package com.aresstack.askai.java8.tts;

import com.aresstack.askai.java8.settings.AskAiPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * On-disk layout of the Piper speech-output engine and its installed voices:
 * {@code %APPDATA%\.askai\tts\piper\} (engine: piper.exe + espeak data + dlls) and
 * {@code %APPDATA%\.askai\tts\voices\<voiceId>\} (one directory per voice: .onnx + .onnx.json).
 * "Installed" is fail-closed: a voice counts only when BOTH files exist — a half-written directory
 * (crashed download) is invisible and simply reinstalled.
 */
public final class PiperTtsStore {

    private final Path root;

    public PiperTtsStore() {
        this(AskAiPaths.appDirectory().resolve("tts"));
    }

    /** Visible for tests: an explicit root. */
    public PiperTtsStore(Path root) {
        this.root = root;
    }

    public Path engineDirectory() {
        return root.resolve("piper");
    }

    public Path engineExecutable() {
        return engineDirectory().resolve("piper.exe");
    }

    public boolean isEngineInstalled() {
        return Files.isRegularFile(engineExecutable());
    }

    public Path voiceDirectory(PiperVoice voice) {
        return root.resolve("voices").resolve(voice.getId());
    }

    public Path voiceModelFile(PiperVoice voice) {
        return voiceDirectory(voice).resolve(voice.onnxFileName());
    }

    public Path voiceConfigFile(PiperVoice voice) {
        return voiceDirectory(voice).resolve(voice.configFileName());
    }

    public boolean isVoiceInstalled(PiperVoice voice) {
        return voice != null
                && Files.isRegularFile(voiceModelFile(voice))
                && Files.isRegularFile(voiceConfigFile(voice));
    }

    /** @return the curated voices that are fully installed (engine not required), catalog order. */
    public List<PiperVoice> installedVoices() {
        List<PiperVoice> installed = new ArrayList<PiperVoice>();
        for (PiperVoice voice : PiperVoiceCatalog.curated()) {
            if (isVoiceInstalled(voice)) {
                installed.add(voice);
            }
        }
        return installed;
    }

    /** @return the installed voices of ONE language (the per-language selector's choices). */
    public List<PiperVoice> installedVoices(String languageCode) {
        List<PiperVoice> installed = new ArrayList<PiperVoice>();
        for (PiperVoice voice : installedVoices()) {
            if (voice.getLanguageCode().equals(languageCode)) {
                installed.add(voice);
            }
        }
        return installed;
    }

    /** @return whether this voice is fully usable: engine + both voice files present. */
    public boolean isReadyToSpeak(PiperVoice voice) {
        return isEngineInstalled() && isVoiceInstalled(voice);
    }

    /**
     * Remove the voice's directory completely, so a later install starts CLEAN (the installer
     * stages + moves, so there is no partial state to inherit). The shared engine stays — other
     * voices use it. Fails loudly when files resist deletion (e.g. the voice is speaking right
     * now and Windows holds the model file open).
     */
    public void uninstallVoice(PiperVoice voice) throws IOException {
        Path directory = voiceDirectory(voice);
        deleteRecursively(directory);
        if (Files.exists(directory)) {
            throw new IOException("could not remove " + directory
                    + " — is the voice speaking right now?");
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        if (Files.isDirectory(path)) {
            try (java.nio.file.DirectoryStream<Path> children
                         = Files.newDirectoryStream(path)) {
                for (Path child : children) {
                    deleteRecursively(child);
                }
            }
        }
        Files.deleteIfExists(path);
    }
}
