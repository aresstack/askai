package com.aresstack.askai.tts;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/** Piper is the default engine: which voice speaks for a language's selection. */
public class PiperTtsStoreVoiceForTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private PiperTtsStore store;

    private void install(PiperVoice voice) throws Exception {
        Files.createDirectories(store.voiceDirectory(voice));
        Files.write(store.voiceModelFile(voice), new byte[]{1});
        Files.write(store.voiceConfigFile(voice), new byte[]{1});
    }

    private void installEngine() throws Exception {
        Files.createDirectories(store.engineExecutable().getParent());
        Files.write(store.engineExecutable(), new byte[]{1});
    }

    @Test
    public void theDefaultSpeaksWithTheFirstInstalledVoiceOfTheLanguage() throws Exception {
        store = new PiperTtsStore(temp.getRoot().toPath());
        PiperVoice german = PiperVoiceCatalog.curated().get(0);
        TextToSpeechSettings.Selection selection = TextToSpeechSettings.defaults().selectionFor("de");
        assertEquals(TextToSpeechSettings.Engine.PIPER, selection.getEngine());
        assertNull("nothing installed yet: the Windows voice speaks", store.voiceFor(selection, "de"));

        installEngine();
        install(german);
        assertEquals(german.getId(), store.voiceFor(selection, "de").getId());
        assertNull("a German voice never reads English", store.voiceFor(selection, "en"));
    }

    @Test
    public void anExplicitWindowsChoiceNeverUsesPiper() throws Exception {
        store = new PiperTtsStore(temp.getRoot().toPath());
        installEngine();
        install(PiperVoiceCatalog.curated().get(0));
        assertNull(store.voiceFor(TextToSpeechSettings.Selection.WINDOWS_DEFAULT, "de"));
    }

    @Test
    public void anExplicitVoiceWinsAndARemovedOneFallsBackToAnInstalledVoice() throws Exception {
        store = new PiperTtsStore(temp.getRoot().toPath());
        PiperVoice high = PiperVoiceCatalog.curated().get(0);
        PiperVoice medium = PiperVoiceCatalog.curated().get(1);
        installEngine();
        install(high);
        install(medium);
        assertEquals(medium.getId(), store.voiceFor(new TextToSpeechSettings.Selection(
                TextToSpeechSettings.Engine.PIPER, medium.getId()), "de").getId());
        store.uninstallVoice(medium);
        assertEquals(high.getId(), store.voiceFor(new TextToSpeechSettings.Selection(
                TextToSpeechSettings.Engine.PIPER, medium.getId()), "de").getId());
    }
}
