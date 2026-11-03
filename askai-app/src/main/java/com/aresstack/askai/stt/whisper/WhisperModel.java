package com.aresstack.askai.stt.whisper;

/**
 * One curated Whisper dictation model from the HuggingFace {@code ggerganov/whisper.cpp} repository: a single
 * ggml file ({@code ggml-<id>.bin}). Multilingual models only, so German and English dictation both work.
 * Pure value object.
 */
public final class WhisperModel {

    private final String id;
    private final String displayName;
    private final long approximateSizeMb;

    public WhisperModel(String id, String displayName, long approximateSizeMb) {
        this.id = id;
        this.displayName = displayName;
        this.approximateSizeMb = approximateSizeMb;
    }

    /** The model id, e.g. {@code small}; also part of the file name. */
    public String getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public long getApproximateSizeMb() {
        return approximateSizeMb;
    }

    /** The file in the HuggingFace repository and on disk, e.g. {@code ggml-small.bin}. */
    public String fileName() {
        return "ggml-" + id + ".bin";
    }

    @Override
    public String toString() {
        return displayName;
    }
}
