package com.aresstack.askai.tts;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The curated read-aloud voices offered in Models → Setup → Speech Output. Hand-picked from
 * HuggingFace {@code rhasspy/piper-voices} for quality per megabyte; sizes verified against the HF
 * tree API. "High" is the natural-sounding recommendation; "medium" is the smaller/faster option.
 * All of them run entirely on the CPU — the GPU stays free for the chat models.
 */
public final class PiperVoiceCatalog {

    /** The HuggingFace repository every curated voice lives in. */
    public static final String VOICES_REPOSITORY = "rhasspy/piper-voices";

    private static final List<PiperVoice> CURATED = Collections.unmodifiableList(Arrays.asList(
            new PiperVoice("de_DE-thorsten-high", "Thorsten (high quality)", "German", "de",
                    "de/de_DE/thorsten/high", 109),
            new PiperVoice("de_DE-thorsten-medium", "Thorsten (smaller, faster)", "German", "de",
                    "de/de_DE/thorsten/medium", 61),
            new PiperVoice("en_US-lessac-high", "Lessac (high quality)", "English", "en",
                    "en/en_US/lessac/high", 109),
            new PiperVoice("en_US-lessac-medium", "Lessac (smaller, faster)", "English", "en",
                    "en/en_US/lessac/medium", 61)));

    private PiperVoiceCatalog() {
    }

    public static List<PiperVoice> curated() {
        return CURATED;
    }

    /** @return the curated voice with this id, or null (an unknown persisted id is not an error). */
    public static PiperVoice findById(String id) {
        if (id == null) {
            return null;
        }
        for (PiperVoice voice : CURATED) {
            if (voice.getId().equals(id.trim())) {
                return voice;
            }
        }
        return null;
    }
}
