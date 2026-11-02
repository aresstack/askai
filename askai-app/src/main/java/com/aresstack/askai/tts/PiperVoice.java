package com.aresstack.askai.tts;

/**
 * One curated Piper read-aloud voice from the HuggingFace {@code rhasspy/piper-voices} repository.
 * The voice is exactly two files under {@code hfPath}: {@code <id>.onnx} (the model) and
 * {@code <id>.onnx.json} (its config, carries the sample rate). Pure value object.
 */
public final class PiperVoice {

    private final String id;
    private final String displayName;
    private final String language;
    private final String languageCode;
    private final String hfPath;
    private final long approximateSizeMb;

    public PiperVoice(String id, String displayName, String language, String languageCode,
                      String hfPath, long approximateSizeMb) {
        this.id = id;
        this.displayName = displayName;
        this.language = language;
        this.languageCode = languageCode;
        this.hfPath = hfPath;
        this.approximateSizeMb = approximateSizeMb;
    }

    /** The voice id, e.g. {@code de_DE-thorsten-high}; also the install directory name. */
    public String getId() {
        return id;
    }

    /** Human label, e.g. {@code Thorsten (high quality)}. */
    public String getDisplayName() {
        return displayName;
    }

    /** Human language label, e.g. {@code German}. */
    public String getLanguage() {
        return language;
    }

    /** ISO-639-1 language code, e.g. {@code de} — the per-language selection key. */
    public String getLanguageCode() {
        return languageCode;
    }

    /** Repo-relative directory in {@code rhasspy/piper-voices}, e.g. {@code de/de_DE/thorsten/high}. */
    public String getHfPath() {
        return hfPath;
    }

    public long getApproximateSizeMb() {
        return approximateSizeMb;
    }

    public String onnxFileName() {
        return id + ".onnx";
    }

    public String configFileName() {
        return id + ".onnx.json";
    }

    @Override
    public String toString() {
        return language + " — " + displayName;
    }
}
