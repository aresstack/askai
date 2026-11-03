package com.aresstack.askai.ui;

import com.aresstack.askai.stt.whisper.WhisperModel;
import com.aresstack.askai.stt.whisper.WhisperModelCatalog;
import com.aresstack.askai.stt.whisper.WhisperStore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class WhisperModelsPanelTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void installAndUninstallToggleTheRowAndNotifyTheSelectors() throws Exception {
        final WhisperStore store = new WhisperStore(temp.getRoot().toPath());
        final int[] changes = {0};
        WhisperModelsPanel panel = new WhisperModelsPanel(store, (model, progress) -> {
            Files.createDirectories(store.engineDirectory());
            Files.write(store.engineExecutable(), new byte[]{1});
            Files.createDirectories(store.modelFile(model).getParent());
            Files.write(store.modelFile(model), new byte[]{1});
            progress.onProgress("Downloading model", 1, 1);
        }, Runnable::run, Runnable::run);
        panel.addModelsChangedListener(() -> changes[0]++);
        WhisperModel small = WhisperModelCatalog.recommended();
        assertEquals("Not installed", panel.statusText(small.getId()));

        panel.installButton(small.getId()).doClick();
        assertEquals("Installed", panel.statusText(small.getId()));
        assertEquals("Uninstall", panel.installButton(small.getId()).getText());
        assertTrue(store.isModelInstalled(small));

        panel.installButton(small.getId()).doClick();
        assertFalse(store.isModelInstalled(small));
        assertEquals("Install", panel.installButton(small.getId()).getText());
        assertEquals(2, changes[0]);
    }

    @Test
    public void aFailedInstallIsShown() {
        WhisperStore store = new WhisperStore(temp.getRoot().toPath());
        WhisperModelsPanel panel = new WhisperModelsPanel(store, (model, progress) -> {
            throw new java.io.IOException("proxy said no");
        }, Runnable::run, Runnable::run);
        panel.installButton("base").doClick();
        assertTrue(panel.errorText().contains("proxy said no"));
        assertEquals("Not installed", panel.statusText("base"));
    }
}
