package com.aresstack.askai.ui;

import com.aresstack.askai.tts.PiperInstaller;
import com.aresstack.askai.tts.PiperTtsStore;
import com.aresstack.askai.tts.PiperVoice;
import com.aresstack.askai.tts.PiperVoiceCatalog;
import com.aresstack.askai.tts.TextToSpeechSettings;
import com.aresstack.askai.tts.TtsSettingsStore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.util.concurrent.Executor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The Speech Output tab's contract: install → "use it now?" → yes selects the voice centrally;
 * no keeps the Windows default; a failure surfaces and the row stays installable.
 */
public class SpeechOutputModelsPanelTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private final Executor direct = new Executor() {
        public void execute(Runnable command) {
            command.run();
        }
    };

    private PiperTtsStore store;
    private TtsSettingsStore settings;

    private SpeechOutputModelsPanel buildPanel(SpeechOutputModelsPanel.InstallAction installer,
                                               final boolean adopt) {
        store = new PiperTtsStore(temp.getRoot().toPath().resolve("tts"));
        settings = new TtsSettingsStore(temp.getRoot().toPath().resolve("tts.properties"));
        return new SpeechOutputModelsPanel(store, settings, installer,
                new SpeechOutputModelsPanel.AdoptPrompt() {
                    public boolean confirmUseNow(PiperVoice voice) {
                        return adopt;
                    }
                }, direct, direct);
    }

    /** A fake install that actually materializes the voice files (so the store sees it). */
    private SpeechOutputModelsPanel.InstallAction fakeInstall() {
        return new SpeechOutputModelsPanel.InstallAction() {
            public void install(PiperVoice voice, PiperInstaller.Progress progress) throws Exception {
                Files.createDirectories(store.voiceDirectory(voice));
                Files.write(store.voiceModelFile(voice), new byte[]{1});
                Files.write(store.voiceConfigFile(voice), new byte[]{1});
                progress.onProgress("Downloading voice", 1, 1);
            }
        };
    }

    @Test
    public void installAndAdoptSelectsTheVoiceForItsOwnLanguageOnly() throws Exception {
        SpeechOutputModelsPanel panel = buildPanel(fakeInstall(), true);
        PiperVoice voice = PiperVoiceCatalog.curated().get(0); // German
        assertEquals("Not installed", panel.statusText(voice.getId()));
        panel.installButton(voice.getId()).doClick();
        assertEquals("Installed · in use", panel.statusText(voice.getId()));
        assertEquals("the action toggles once installed", "Uninstall",
                panel.installButton(voice.getId()).getText());
        TextToSpeechSettings selected = settings.load();
        assertEquals(TextToSpeechSettings.Engine.PIPER,
                selected.selectionFor(voice.getLanguageCode()).getEngine());
        assertEquals(voice.getId(), selected.selectionFor(voice.getLanguageCode()).getVoiceId());
        assertEquals("adopting a German voice never touches English",
                TextToSpeechSettings.Engine.WINDOWS, selected.selectionFor("en").getEngine());
    }

    @Test
    public void installWithoutAdoptionKeepsTheWindowsDefault() throws Exception {
        SpeechOutputModelsPanel panel = buildPanel(fakeInstall(), false);
        PiperVoice voice = PiperVoiceCatalog.curated().get(1);
        panel.installButton(voice.getId()).doClick();
        assertEquals("Installed", panel.statusText(voice.getId()));
        assertEquals(TextToSpeechSettings.Engine.WINDOWS,
                settings.load().selectionFor(voice.getLanguageCode()).getEngine());
    }

    @Test
    public void uninstallRemovesTheFilesResetsTheSelectionAndAllowsAFreshInstall() throws Exception {
        SpeechOutputModelsPanel panel = buildPanel(fakeInstall(), true);
        PiperVoice voice = PiperVoiceCatalog.curated().get(0);
        panel.installButton(voice.getId()).doClick(); // install + adopt
        assertEquals("Uninstall", panel.installButton(voice.getId()).getText());

        panel.installButton(voice.getId()).doClick(); // now: uninstall
        assertEquals("Not installed", panel.statusText(voice.getId()));
        assertEquals("Install", panel.installButton(voice.getId()).getText());
        assertFalse("files are gone — a reinstall starts clean",
                store.isVoiceInstalled(voice));
        assertFalse("no dangling directory",
                java.nio.file.Files.exists(store.voiceDirectory(voice)));
        assertEquals("the selection returns to the Windows default",
                TextToSpeechSettings.Engine.WINDOWS,
                settings.load().selectionFor(voice.getLanguageCode()).getEngine());

        panel.installButton(voice.getId()).doClick(); // fresh install works again
        assertTrue(store.isVoiceInstalled(voice));
        assertEquals("Uninstall", panel.installButton(voice.getId()).getText());
    }

    @Test
    public void uninstallLeavesAForeignSelectionAlone() throws Exception {
        SpeechOutputModelsPanel panel = buildPanel(fakeInstall(), false);
        PiperVoice high = PiperVoiceCatalog.curated().get(0);   // de high
        PiperVoice medium = PiperVoiceCatalog.curated().get(1); // de medium
        panel.installButton(high.getId()).doClick();
        panel.installButton(medium.getId()).doClick();
        settings.save(settings.load().withSelection("de",
                TextToSpeechSettings.Engine.PIPER, medium.getId()));

        panel.installButton(high.getId()).doClick(); // uninstall the NOT-selected voice
        assertEquals("the selected medium voice keeps its selection", medium.getId(),
                settings.load().selectionFor("de").getVoiceId());
        assertTrue(store.isVoiceInstalled(medium));
    }

    @Test
    public void highlightVoiceTintsTheTargetRow() throws Exception {
        SpeechOutputModelsPanel panel = buildPanel(fakeInstall(), false);
        PiperVoice voice = PiperVoiceCatalog.curated().get(0);
        java.awt.Container row = panel.installButton(voice.getId()).getParent();
        java.awt.Color before = row.getBackground();
        panel.highlightVoice(voice.getId());
        assertTrue("the discovery hand-over visibly marks the recommended voice",
                !row.getBackground().equals(before));
        panel.highlightVoice("no-such-voice"); // unknown ids are a quiet no-op
    }

    @Test
    public void aFailedInstallSurfacesAndStaysInstallable() throws Exception {
        SpeechOutputModelsPanel panel = buildPanel(new SpeechOutputModelsPanel.InstallAction() {
            public void install(PiperVoice voice, PiperInstaller.Progress progress) throws Exception {
                throw new java.io.IOException("network unplugged");
            }
        }, true);
        PiperVoice voice = PiperVoiceCatalog.curated().get(0);
        panel.installButton(voice.getId()).doClick();
        assertTrue(panel.errorText().contains("network unplugged"));
        assertEquals("Not installed", panel.statusText(voice.getId()));
        assertTrue(panel.installButton(voice.getId()).isEnabled());
        assertEquals(TextToSpeechSettings.Engine.WINDOWS,
                settings.load().selectionFor(voice.getLanguageCode()).getEngine());
    }
}
