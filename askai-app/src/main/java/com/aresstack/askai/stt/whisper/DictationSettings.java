package com.aresstack.askai.stt.whisper;

/**
 * Which backend transcribes dictation, and (for Whisper) which model. Whisper is the default, like Piper for the
 * speech output: with no Whisper model installed yet, the Ollama audio model transcribes, so dictation keeps working.
 * Kept in its own small file ({@link DictationSettingsStore}) like the speech-output settings.
 */
public final class DictationSettings {

    public enum Engine {
        /** Local whisper.cpp on the CPU — the default. */
        WHISPER,
        /** An audio-capable model on the Ollama server (Settings > Speech > Dictation > Model). */
        OLLAMA
    }

    private final Engine engine;
    private final String whisperModelId;

    public DictationSettings(Engine engine, String whisperModelId) {
        this.engine = engine == null ? Engine.WHISPER : engine;
        this.whisperModelId = whisperModelId == null ? "" : whisperModelId.trim();
    }

    public static DictationSettings defaults() {
        return new DictationSettings(Engine.WHISPER, "");
    }

    public Engine getEngine() {
        return engine;
    }

    /** @return the chosen Whisper model id, or "" for automatic (the first installed curated model). */
    public String getWhisperModelId() {
        return whisperModelId;
    }

    public DictationSettings withEngine(Engine value) {
        return new DictationSettings(value, whisperModelId);
    }

    public DictationSettings withWhisperModelId(String value) {
        return new DictationSettings(engine, value);
    }

    public static Engine parseEngine(String value) {
        if (value == null) {
            return Engine.WHISPER;
        }
        try {
            return Engine.valueOf(value.trim());
        } catch (IllegalArgumentException invalid) {
            return Engine.WHISPER;
        }
    }
}
