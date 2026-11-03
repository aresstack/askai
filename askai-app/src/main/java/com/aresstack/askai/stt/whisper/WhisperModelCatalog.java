package com.aresstack.askai.stt.whisper;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The curated Whisper dictation models, recommended first. "Automatic" dictation uses the first INSTALLED model
 * in this order, so the recommended one wins when several are installed.
 */
public final class WhisperModelCatalog {

    /** The HuggingFace repository every curated model lives in. */
    public static final String REPOSITORY = "ggerganov/whisper.cpp";

    private static final List<WhisperModel> CURATED = Collections.unmodifiableList(Arrays.asList(
            new WhisperModel("base", "Base (recommended)", 142),
            new WhisperModel("small", "Small (more accurate)", 466),
            new WhisperModel("large-v3-turbo-q5_0", "Large v3 Turbo (most accurate)", 547)));

    private WhisperModelCatalog() {
    }

    public static List<WhisperModel> curated() {
        return CURATED;
    }

    /** @return the curated model with this id, or null (an unknown persisted id is not an error). */
    public static WhisperModel findById(String id) {
        if (id == null) {
            return null;
        }
        for (WhisperModel model : CURATED) {
            if (model.getId().equals(id.trim())) {
                return model;
            }
        }
        return null;
    }

    /** The model a fresh install offers first (the wizard's and the settings' one-click download). */
    public static WhisperModel recommended() {
        return CURATED.get(0);
    }
}
