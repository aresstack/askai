package com.aresstack.askai.ui;

import com.aresstack.askai.config.HuggingFaceSearchSuggestion;
import com.aresstack.askai.tts.PiperVoiceCatalog;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The 🔊 discovery entries: clearly marked, audio-tagged, resolvable to a recommended curated
 * voice — and NEVER matching a real search term, so ordinary suggestions keep searching.
 */
public class SpeechOutputSuggestionsTest {

    @Test
    public void discoveryEntriesAreMarkedAudioTaggedAndResolvable() {
        List<HuggingFaceSearchSuggestion> entries = SpeechOutputSuggestions.entries();
        assertEquals("one per recommended language", 2, entries.size());
        for (HuggingFaceSearchSuggestion entry : entries) {
            assertTrue("marked: " + entry.getTerm(),
                    entry.getTerm().startsWith(SpeechOutputSuggestions.MARKER));
            assertTrue("audio-tagged for the dropdown icon",
                    entry.getModalities().contains(HuggingFaceSearchSuggestion.Modality.AUDIO));
            String voiceId = SpeechOutputSuggestions.voiceIdForTerm(entry.getTerm());
            assertTrue("resolves to a curated voice",
                    PiperVoiceCatalog.findById(voiceId) != null);
            assertTrue("the recommendation is the high-quality tier", voiceId.endsWith("-high"));
        }
        assertEquals("de_DE-thorsten-high",
                SpeechOutputSuggestions.voiceIdForTerm(entries.get(0).getTerm()));
        assertEquals("en_US-lessac-high",
                SpeechOutputSuggestions.voiceIdForTerm(entries.get(1).getTerm()));
    }

    @Test
    public void realSearchTermsNeverResolve() {
        assertNull(SpeechOutputSuggestions.voiceIdForTerm("qwen2.5-coder-14b"));
        assertNull(SpeechOutputSuggestions.voiceIdForTerm("voxtral-mini-3b"));
        assertNull(SpeechOutputSuggestions.voiceIdForTerm(""));
        assertNull(SpeechOutputSuggestions.voiceIdForTerm(null));
        assertNull("the marker alone is not an entry",
                SpeechOutputSuggestions.voiceIdForTerm(SpeechOutputSuggestions.MARKER + "whatever"));
    }
}
