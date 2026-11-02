package com.aresstack.askai.tts;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;

import static org.junit.Assert.assertTrue;

/** The settings "Test" button: a failure always comes back as a concrete reason, never silence. */
public class SpeechOutputTesterTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private SpeechOutputTester tester;
    private TtsSettingsStore settings;
    private PiperTtsStore store;

    private void build() {
        settings = new TtsSettingsStore(temp.getRoot().toPath().resolve("tts.properties"));
        store = new PiperTtsStore(temp.getRoot().toPath().resolve("tts"));
        tester = new SpeechOutputTester(settings, store);
    }

    @Test
    public void anUnknownVoiceIdNamesItself() throws Exception {
        build();
        settings.save(TextToSpeechSettings.defaults()
                .withSelection("de", TextToSpeechSettings.Engine.PIPER, "no-such-voice"));
        assertTrue(tester.speakSample("de").contains("no-such-voice"));
    }

    @Test
    public void aMissingEngineNamesTheExpectedPath() throws Exception {
        build();
        PiperVoice voice = PiperVoiceCatalog.findById("de_DE-thorsten-high");
        Files.createDirectories(store.voiceDirectory(voice));
        Files.write(store.voiceModelFile(voice), new byte[]{1});
        Files.write(store.voiceConfigFile(voice), new byte[]{1});
        settings.save(TextToSpeechSettings.defaults()
                .withSelection("de", TextToSpeechSettings.Engine.PIPER, voice.getId()));
        assertTrue(tester.speakSample("de").contains("piper.exe"));
    }

    @Test
    public void missingVoiceFilesNameTheVoiceDirectory() throws Exception {
        build();
        Files.createDirectories(store.engineDirectory());
        Files.write(store.engineExecutable(), new byte[]{1});
        settings.save(TextToSpeechSettings.defaults()
                .withSelection("de", TextToSpeechSettings.Engine.PIPER, "de_DE-thorsten-high"));
        assertTrue(tester.speakSample("de").contains("de_DE-thorsten-high"));
    }

    @Test
    public void aCrossLanguageSelectionIsRefusedWithAReason() throws Exception {
        build();
        settings.save(TextToSpeechSettings.defaults()
                .withSelection("en", TextToSpeechSettings.Engine.PIPER, "de_DE-thorsten-high"));
        assertTrue(tester.speakSample("en").contains("does not match"));
    }
}
