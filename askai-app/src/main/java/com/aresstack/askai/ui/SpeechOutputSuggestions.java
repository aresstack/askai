package com.aresstack.askai.ui;

import com.aresstack.askai.config.HuggingFaceSearchSuggestion;
import com.aresstack.askai.tts.PiperVoice;
import com.aresstack.askai.tts.PiperVoiceCatalog;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * DISCOVERY entries for the speech-output voices inside the HuggingFace suggestion dropdown —
 * where users already look for models. UI-injected, never persisted (they are navigation, not
 * user data, so they cannot be edited away or drift with the suggestion migrations). Selecting
 * one must NOT run the GGUF/Ollama import: {@link OllamaInstallPanel} intercepts it and opens
 * the dedicated Speech Output install flow for the recommended voice instead.
 */
final class SpeechOutputSuggestions {

    /** The speaker marker (🔊) that visually sets these apart from real search terms. */
    static final String MARKER = "🔊 ";

    private SpeechOutputSuggestions() {
    }

    /** The dropdown entries, one per recommended language (the curated "high" voices). */
    static List<HuggingFaceSearchSuggestion> entries() {
        List<HuggingFaceSearchSuggestion> entries = new ArrayList<HuggingFaceSearchSuggestion>();
        entries.add(entry("de_DE-thorsten-high"));
        entries.add(entry("en_US-lessac-high"));
        return entries;
    }

    /**
     * @return the recommended voice id when {@code term} is one of {@link #entries()}'s terms,
     *         or null for every real search term
     */
    static String voiceIdForTerm(String term) {
        String trimmed = term == null ? "" : term.trim();
        if (!trimmed.startsWith(MARKER)) {
            return null;
        }
        for (PiperVoice voice : PiperVoiceCatalog.curated()) {
            if (trimmed.equals(termFor(voice))) {
                return voice.getId();
            }
        }
        return null;
    }

    private static HuggingFaceSearchSuggestion entry(String voiceId) {
        PiperVoice voice = PiperVoiceCatalog.findById(voiceId);
        return new HuggingFaceSearchSuggestion(termFor(voice),
                EnumSet.of(HuggingFaceSearchSuggestion.Modality.AUDIO));
    }

    private static String termFor(PiperVoice voice) {
        return MARKER + "AI speech output — " + voice.getLanguage()
                + " (" + firstWord(voice.getDisplayName()) + " / Piper)";
    }

    private static String firstWord(String displayName) {
        int space = displayName.indexOf(' ');
        return space > 0 ? displayName.substring(0, space) : displayName;
    }
}
