package com.aresstack.askai.tts;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Persisted speech-output (read-aloud) settings, PER LANGUAGE — like the per-language NLP models:
 * German and English each get their own engine/voice choice, so an English answer is never read
 * with a German voice. The per-language default is {@link Engine#PIPER} with no explicit voice: the first
 * installed Piper voice of that language speaks, and until one is installed the Windows OS voice reads
 * aloud (it needs no download). Switching a language to {@link Engine#WINDOWS} is an explicit choice
 * (Settings → Speech → Speech output). Kept OUT of {@code AppConfiguration} on purpose: these
 * settings live in their own small properties file (see {@link TtsSettingsStore}).
 */
public final class TextToSpeechSettings {

    public enum Engine {
        /** The Windows OS voice (System.Speech / SAPI) — the zero-setup default per language. */
        WINDOWS,
        /** A locally installed Piper model voice — runs entirely on the CPU. */
        PIPER
    }

    /** One language's choice: the engine, and (for PIPER) the selected voice id. */
    public static final class Selection {
        public static final Selection WINDOWS_DEFAULT = new Selection(Engine.WINDOWS, "");
        /** The default: Piper, the first installed voice of the language (Windows until one exists). */
        public static final Selection PIPER_DEFAULT = new Selection(Engine.PIPER, "");

        private final Engine engine;
        private final String voiceId;

        public Selection(Engine engine, String voiceId) {
            this.engine = engine == null ? Engine.PIPER : engine;
            this.voiceId = voiceId == null ? "" : voiceId.trim();
        }

        public Engine getEngine() {
            return engine;
        }

        public String getVoiceId() {
            return voiceId;
        }
    }

    /** The languages a voice can be selected for (ISO-639-1), in display order. */
    public static final String[] LANGUAGE_CODES = {"de", "en"};

    /** Upper bound for waiting on the FIRST audio byte (engine start + model load), not playback. */
    public static final int DEFAULT_STARTUP_TIMEOUT_SECONDS = 60;
    /** Connect/read timeout for voice/engine downloads (per network operation, not per file). */
    public static final int DEFAULT_NETWORK_TIMEOUT_SECONDS = 60;

    private final Map<String, Selection> byLanguage;
    private final int startupTimeoutSeconds;
    private final int networkTimeoutSeconds;
    /** Read-aloud starts ACTIVE in research chats: new answers are spoken without pressing Play. */
    private final boolean readAloudAutoStart;
    /** NLP-detected German/English passages each go to their OWN voice (fallback: single voice). */
    private final boolean mixedLanguageSplit;
    /** The user's chunk granularity: whole answer, per paragraph, or per sentence. */
    private final Chunking chunking;
    /** Parallel synthesis workers for the batch pipeline (playback stays strictly in order). */
    private final int synthesisWorkers;

    /** The spoken chunk granularity — the user's own choice in the settings. */
    public enum Chunking {
        /** One piece per same-language segment (the whole answer when monolingual). */
        ANSWER,
        /** One piece per paragraph. */
        PARAGRAPHS,
        /** One piece per sentence. */
        SENTENCES
    }

    /** Default parallel synthesis workers (CPU-bound piper processes; playback stays ordered). */
    public static final int DEFAULT_SYNTHESIS_WORKERS = 2;

    public TextToSpeechSettings(Map<String, Selection> byLanguage, int startupTimeoutSeconds,
                                int networkTimeoutSeconds) {
        this(byLanguage, startupTimeoutSeconds, networkTimeoutSeconds, false, true,
                Chunking.PARAGRAPHS, DEFAULT_SYNTHESIS_WORKERS);
    }

    public TextToSpeechSettings(Map<String, Selection> byLanguage, int startupTimeoutSeconds,
                                int networkTimeoutSeconds, boolean readAloudAutoStart) {
        this(byLanguage, startupTimeoutSeconds, networkTimeoutSeconds, readAloudAutoStart,
                true, Chunking.PARAGRAPHS, DEFAULT_SYNTHESIS_WORKERS);
    }

    public TextToSpeechSettings(Map<String, Selection> byLanguage, int startupTimeoutSeconds,
                                int networkTimeoutSeconds, boolean readAloudAutoStart,
                                boolean mixedLanguageSplit, Chunking chunking,
                                int synthesisWorkers) {
        Map<String, Selection> selections = new LinkedHashMap<String, Selection>();
        if (byLanguage != null) {
            selections.putAll(byLanguage);
        }
        this.byLanguage = Collections.unmodifiableMap(selections);
        this.startupTimeoutSeconds = startupTimeoutSeconds > 0
                ? startupTimeoutSeconds : DEFAULT_STARTUP_TIMEOUT_SECONDS;
        this.networkTimeoutSeconds = networkTimeoutSeconds > 0
                ? networkTimeoutSeconds : DEFAULT_NETWORK_TIMEOUT_SECONDS;
        this.readAloudAutoStart = readAloudAutoStart;
        this.mixedLanguageSplit = mixedLanguageSplit;
        this.chunking = chunking == null ? Chunking.PARAGRAPHS : chunking;
        this.synthesisWorkers = synthesisWorkers > 0
                ? synthesisWorkers : DEFAULT_SYNTHESIS_WORKERS;
    }

    public static TextToSpeechSettings defaults() {
        return new TextToSpeechSettings(null, DEFAULT_STARTUP_TIMEOUT_SECONDS,
                DEFAULT_NETWORK_TIMEOUT_SECONDS);
    }

    /** @return this language's choice; unknown/unset languages are the Piper default. */
    public Selection selectionFor(String languageCode) {
        Selection selection = languageCode == null ? null : byLanguage.get(languageCode.trim());
        return selection == null ? Selection.PIPER_DEFAULT : selection;
    }

    public TextToSpeechSettings withSelection(String languageCode, Engine engine, String voiceId) {
        Map<String, Selection> selections = new LinkedHashMap<String, Selection>(byLanguage);
        selections.put(languageCode, new Selection(engine, voiceId));
        return new TextToSpeechSettings(selections, startupTimeoutSeconds, networkTimeoutSeconds,
                readAloudAutoStart, mixedLanguageSplit, chunking, synthesisWorkers);
    }

    /** @return whether read-aloud starts ACTIVE (auto-reading new answers) in research chats. */
    public boolean isReadAloudAutoStart() {
        return readAloudAutoStart;
    }

    public TextToSpeechSettings withReadAloudAutoStart(boolean value) {
        return new TextToSpeechSettings(byLanguage, startupTimeoutSeconds, networkTimeoutSeconds,
                value, mixedLanguageSplit, chunking, synthesisWorkers);
    }

    /** @return whether NLP-detected German/English passages are routed to their own voices. */
    public boolean isMixedLanguageSplit() {
        return mixedLanguageSplit;
    }

    public TextToSpeechSettings withMixedLanguageSplit(boolean value) {
        return new TextToSpeechSettings(byLanguage, startupTimeoutSeconds, networkTimeoutSeconds,
                readAloudAutoStart, value, chunking, synthesisWorkers);
    }

    /** @return the user-chosen chunk granularity (answer / paragraphs / sentences). */
    public Chunking getChunking() {
        return chunking;
    }

    public TextToSpeechSettings withChunking(Chunking value) {
        return new TextToSpeechSettings(byLanguage, startupTimeoutSeconds, networkTimeoutSeconds,
                readAloudAutoStart, mixedLanguageSplit, value, synthesisWorkers);
    }

    /** @return the parallel synthesis worker count of the batch pipeline. */
    public int getSynthesisWorkers() {
        return synthesisWorkers;
    }

    public TextToSpeechSettings withSynthesisWorkers(int value) {
        return new TextToSpeechSettings(byLanguage, startupTimeoutSeconds, networkTimeoutSeconds,
                readAloudAutoStart, mixedLanguageSplit, chunking, value);
    }

    public static Chunking parseChunking(String value, Chunking fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Chunking.valueOf(value.trim());
        } catch (IllegalArgumentException invalid) {
            return fallback;
        }
    }

    /** Visible for the store: every explicitly configured language. */
    Map<String, Selection> selections() {
        return byLanguage;
    }

    public int getStartupTimeoutSeconds() {
        return startupTimeoutSeconds;
    }

    public int getNetworkTimeoutSeconds() {
        return networkTimeoutSeconds;
    }

    public static Engine parseEngine(String value) {
        if (value == null) {
            return Engine.PIPER;
        }
        try {
            return Engine.valueOf(value.trim());
        } catch (IllegalArgumentException invalid) {
            return Engine.PIPER;
        }
    }
}
