package com.aresstack.askai.tts;

import com.aresstack.askai.settings.AskAiPaths;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * The speech-output settings file: {@code %APPDATA%\.askai\askai-tts.properties}, one
 * engine/voice pair PER LANGUAGE ({@code tts.<lang>.engine} / {@code tts.<lang>.voice}). A separate
 * file (not {@code askai.properties}) so the central repository's whole-file rewrites can
 * never clobber these keys and vice versa. Reads are cheap and always fresh — callers load per use
 * instead of caching, so a change from the settings dialog is picked up by the very next speak.
 * The short-lived single-selection keys ({@code tts.engine}/{@code tts.voice}) migrate silently
 * into the voice's own language.
 */
public final class TtsSettingsStore {

    private static final String KEY_STARTUP_TIMEOUT_SECONDS = "tts.startupTimeoutSeconds";
    private static final String KEY_NETWORK_TIMEOUT_SECONDS = "tts.networkTimeoutSeconds";
    private static final String KEY_READ_ALOUD_AUTO_START = "tts.readAloudAutoStart";
    private static final String KEY_MIXED_LANGUAGE_SPLIT = "tts.mixedLanguageSplit";
    private static final String KEY_CHUNKING = "tts.chunking";
    private static final String KEY_SYNTHESIS_WORKERS = "tts.synthesisWorkers";
    /** The short-lived paragraph boolean this enum replaced — read once for migration. */
    private static final String LEGACY_KEY_PARAGRAPH_WISE = "tts.paragraphWiseSynthesis";
    private static final String LEGACY_KEY_ENGINE = "tts.engine";
    private static final String LEGACY_KEY_VOICE = "tts.voice";

    private final Path file;

    public TtsSettingsStore() {
        this(AskAiPaths.appDirectory().resolve("askai-tts.properties"));
    }

    /** Visible for tests: an explicit file location. */
    public TtsSettingsStore(Path file) {
        this.file = file;
    }

    public TextToSpeechSettings load() {
        Properties properties = new Properties();
        if (Files.isRegularFile(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                properties.load(in);
            } catch (IOException unreadable) {
                return TextToSpeechSettings.defaults(); // a broken file must never break speaking
            }
        }
        Map<String, TextToSpeechSettings.Selection> selections =
                new LinkedHashMap<String, TextToSpeechSettings.Selection>();
        for (String language : TextToSpeechSettings.LANGUAGE_CODES) {
            String engine = properties.getProperty("tts." + language + ".engine");
            if (engine != null) {
                selections.put(language, new TextToSpeechSettings.Selection(
                        TextToSpeechSettings.parseEngine(engine),
                        properties.getProperty("tts." + language + ".voice", "")));
            }
        }
        migrateLegacySingleSelection(properties, selections);
        return new TextToSpeechSettings(selections,
                parseInt(properties.getProperty(KEY_STARTUP_TIMEOUT_SECONDS),
                        TextToSpeechSettings.DEFAULT_STARTUP_TIMEOUT_SECONDS),
                parseInt(properties.getProperty(KEY_NETWORK_TIMEOUT_SECONDS),
                        TextToSpeechSettings.DEFAULT_NETWORK_TIMEOUT_SECONDS),
                Boolean.parseBoolean(properties.getProperty(KEY_READ_ALOUD_AUTO_START, "false")),
                Boolean.parseBoolean(properties.getProperty(KEY_MIXED_LANGUAGE_SPLIT, "true")),
                chunking(properties),
                parseInt(properties.getProperty(KEY_SYNTHESIS_WORKERS),
                        TextToSpeechSettings.DEFAULT_SYNTHESIS_WORKERS));
    }

    /** The chunk granularity; the legacy paragraph boolean migrates silently. */
    private static TextToSpeechSettings.Chunking chunking(Properties properties) {
        String raw = properties.getProperty(KEY_CHUNKING);
        if (raw != null) {
            return TextToSpeechSettings.parseChunking(raw, TextToSpeechSettings.Chunking.PARAGRAPHS);
        }
        String legacy = properties.getProperty(LEGACY_KEY_PARAGRAPH_WISE);
        if ("false".equalsIgnoreCase(legacy)) {
            return TextToSpeechSettings.Chunking.ANSWER;
        }
        return TextToSpeechSettings.Chunking.PARAGRAPHS;
    }

    /** The pre-per-language format chose ONE voice; it lands in that voice's own language. */
    private static void migrateLegacySingleSelection(
            Properties properties, Map<String, TextToSpeechSettings.Selection> selections) {
        if (TextToSpeechSettings.parseEngine(properties.getProperty(LEGACY_KEY_ENGINE))
                != TextToSpeechSettings.Engine.PIPER) {
            return;
        }
        PiperVoice voice = PiperVoiceCatalog.findById(properties.getProperty(LEGACY_KEY_VOICE));
        if (voice != null && !selections.containsKey(voice.getLanguageCode())) {
            selections.put(voice.getLanguageCode(), new TextToSpeechSettings.Selection(
                    TextToSpeechSettings.Engine.PIPER, voice.getId()));
        }
    }

    public void save(TextToSpeechSettings settings) throws IOException {
        TextToSpeechSettings value = settings == null ? TextToSpeechSettings.defaults() : settings;
        Properties properties = new Properties();
        for (Map.Entry<String, TextToSpeechSettings.Selection> entry
                : value.selections().entrySet()) {
            properties.setProperty("tts." + entry.getKey() + ".engine",
                    entry.getValue().getEngine().name());
            properties.setProperty("tts." + entry.getKey() + ".voice",
                    entry.getValue().getVoiceId());
        }
        properties.setProperty(KEY_STARTUP_TIMEOUT_SECONDS,
                String.valueOf(value.getStartupTimeoutSeconds()));
        properties.setProperty(KEY_NETWORK_TIMEOUT_SECONDS,
                String.valueOf(value.getNetworkTimeoutSeconds()));
        properties.setProperty(KEY_READ_ALOUD_AUTO_START,
                String.valueOf(value.isReadAloudAutoStart()));
        properties.setProperty(KEY_MIXED_LANGUAGE_SPLIT,
                String.valueOf(value.isMixedLanguageSplit()));
        properties.setProperty(KEY_CHUNKING, value.getChunking().name());
        properties.setProperty(KEY_SYNTHESIS_WORKERS,
                String.valueOf(value.getSynthesisWorkers()));
        Files.createDirectories(file.getParent());
        try (OutputStream out = Files.newOutputStream(file)) {
            properties.store(out, "AskAI speech output (read-aloud) settings, one voice per language");
        }
    }

    private static int parseInt(String raw, int fallback) {
        if (raw == null || raw.trim().isEmpty()) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException invalid) {
            return fallback;
        }
    }
}
