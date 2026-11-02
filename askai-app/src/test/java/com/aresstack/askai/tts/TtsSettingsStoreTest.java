package com.aresstack.askai.tts;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;

/** The per-language speech-output settings: round trip, Windows default, legacy migration. */
public class TtsSettingsStoreTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void missingFileYieldsTheWindowsDefaultForEveryLanguage() {
        TtsSettingsStore store = new TtsSettingsStore(
                temp.getRoot().toPath().resolve("nope.properties"));
        TextToSpeechSettings settings = store.load();
        for (String language : TextToSpeechSettings.LANGUAGE_CODES) {
            assertEquals(TextToSpeechSettings.Engine.WINDOWS,
                    settings.selectionFor(language).getEngine());
            assertEquals("", settings.selectionFor(language).getVoiceId());
        }
        assertEquals(TextToSpeechSettings.DEFAULT_STARTUP_TIMEOUT_SECONDS,
                settings.getStartupTimeoutSeconds());
        assertEquals(TextToSpeechSettings.DEFAULT_NETWORK_TIMEOUT_SECONDS,
                settings.getNetworkTimeoutSeconds());
    }

    @Test
    public void perLanguageSelectionsRoundTripIndependently() throws Exception {
        TtsSettingsStore store = new TtsSettingsStore(
                temp.getRoot().toPath().resolve("tts.properties"));
        store.save(TextToSpeechSettings.defaults()
                .withSelection("de", TextToSpeechSettings.Engine.PIPER, "de_DE-thorsten-high"));
        TextToSpeechSettings loaded = store.load();
        assertEquals(TextToSpeechSettings.Engine.PIPER, loaded.selectionFor("de").getEngine());
        assertEquals("de_DE-thorsten-high", loaded.selectionFor("de").getVoiceId());
        assertEquals("English stays on its own Windows default",
                TextToSpeechSettings.Engine.WINDOWS, loaded.selectionFor("en").getEngine());

        store.save(loaded.withSelection("en", TextToSpeechSettings.Engine.PIPER,
                "en_US-lessac-high"));
        loaded = store.load();
        assertEquals("de_DE-thorsten-high", loaded.selectionFor("de").getVoiceId());
        assertEquals("en_US-lessac-high", loaded.selectionFor("en").getVoiceId());
    }

    @Test
    public void theLegacySingleSelectionMigratesIntoItsOwnLanguage() throws Exception {
        Path file = temp.getRoot().toPath().resolve("legacy.properties");
        Files.write(file, ("tts.engine=PIPER\ntts.voice=de_DE-thorsten-medium\n")
                .getBytes(StandardCharsets.UTF_8));
        TextToSpeechSettings loaded = new TtsSettingsStore(file).load();
        assertEquals(TextToSpeechSettings.Engine.PIPER, loaded.selectionFor("de").getEngine());
        assertEquals("de_DE-thorsten-medium", loaded.selectionFor("de").getVoiceId());
        assertEquals("the German legacy choice never leaks into English",
                TextToSpeechSettings.Engine.WINDOWS, loaded.selectionFor("en").getEngine());
    }

    @Test
    public void readAloudAutoStartRoundTripsAndDefaultsOff() throws Exception {
        TtsSettingsStore store = new TtsSettingsStore(
                temp.getRoot().toPath().resolve("tts.properties"));
        org.junit.Assert.assertFalse("off by default — auto-reading is an opt-in",
                store.load().isReadAloudAutoStart());
        store.save(store.load().withReadAloudAutoStart(true));
        org.junit.Assert.assertTrue(store.load().isReadAloudAutoStart());
        store.save(store.load().withSelection("de", TextToSpeechSettings.Engine.PIPER,
                "de_DE-thorsten-high"));
        org.junit.Assert.assertTrue("selection changes keep the auto-start flag",
                store.load().isReadAloudAutoStart());
    }

    @Test
    public void theNlpReadAloudRefinementsDefaultOnAndRoundTrip() throws Exception {
        TtsSettingsStore store = new TtsSettingsStore(
                temp.getRoot().toPath().resolve("tts.properties"));
        org.junit.Assert.assertTrue("mixed-language split defaults ON",
                store.load().isMixedLanguageSplit());
        org.junit.Assert.assertEquals("paragraph chunks are the default",
                TextToSpeechSettings.Chunking.PARAGRAPHS, store.load().getChunking());
        org.junit.Assert.assertEquals(TextToSpeechSettings.DEFAULT_SYNTHESIS_WORKERS,
                store.load().getSynthesisWorkers());
        store.save(store.load().withMixedLanguageSplit(false)
                .withChunking(TextToSpeechSettings.Chunking.SENTENCES)
                .withSynthesisWorkers(4));
        org.junit.Assert.assertFalse(store.load().isMixedLanguageSplit());
        org.junit.Assert.assertEquals(TextToSpeechSettings.Chunking.SENTENCES,
                store.load().getChunking());
        org.junit.Assert.assertEquals(4, store.load().getSynthesisWorkers());
    }

    @Test
    public void theLegacyParagraphBooleanMigratesIntoTheChunkingEnum() throws Exception {
        Path file = temp.getRoot().toPath().resolve("legacy-chunking.properties");
        Files.write(file, "tts.paragraphWiseSynthesis=false\n".getBytes(StandardCharsets.UTF_8));
        org.junit.Assert.assertEquals("false meant one joined chunk per answer",
                TextToSpeechSettings.Chunking.ANSWER, new TtsSettingsStore(file).load().getChunking());
        Files.write(file, "tts.paragraphWiseSynthesis=true\n".getBytes(StandardCharsets.UTF_8));
        org.junit.Assert.assertEquals(TextToSpeechSettings.Chunking.PARAGRAPHS,
                new TtsSettingsStore(file).load().getChunking());
    }

    @Test
    public void anUnknownEngineFallsBackToWindows() {
        assertEquals(TextToSpeechSettings.Engine.WINDOWS,
                TextToSpeechSettings.parseEngine("KOKORO"));
        assertEquals(TextToSpeechSettings.Engine.PIPER,
                TextToSpeechSettings.parseEngine(" PIPER "));
    }
}
