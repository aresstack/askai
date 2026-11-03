package com.aresstack.askai.stt.whisper;

import com.aresstack.askai.settings.AskAiPaths;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * The dictation backend file: {@code %APPDATA%\.askai\askai-stt.properties} ({@code stt.engine},
 * {@code stt.whisperModel}). A separate file, so the old {@code stt.backend=OLLAMA} that {@code askai.properties}
 * always carried never overrides the new Whisper default. Reads are cheap and always fresh.
 */
public final class DictationSettingsStore {

    private static final String KEY_ENGINE = "stt.engine";
    private static final String KEY_WHISPER_MODEL = "stt.whisperModel";

    private final Path file;

    public DictationSettingsStore() {
        this(AskAiPaths.appDirectory().resolve("askai-stt.properties"));
    }

    /** Visible for tests: an explicit file location. */
    public DictationSettingsStore(Path file) {
        this.file = file;
    }

    public DictationSettings load() {
        if (!Files.isRegularFile(file)) {
            return DictationSettings.defaults();
        }
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            properties.load(in);
        } catch (IOException unreadable) {
            return DictationSettings.defaults(); // a broken file must never break dictation
        }
        return new DictationSettings(DictationSettings.parseEngine(properties.getProperty(KEY_ENGINE)),
                properties.getProperty(KEY_WHISPER_MODEL, ""));
    }

    public void save(DictationSettings settings) throws IOException {
        DictationSettings value = settings == null ? DictationSettings.defaults() : settings;
        Properties properties = new Properties();
        properties.setProperty(KEY_ENGINE, value.getEngine().name());
        properties.setProperty(KEY_WHISPER_MODEL, value.getWhisperModelId());
        Files.createDirectories(file.getParent());
        try (OutputStream out = Files.newOutputStream(file)) {
            properties.store(out, "AskAI dictation backend (WHISPER = local whisper.cpp, OLLAMA = Ollama audio model)");
        }
    }
}
