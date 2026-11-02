package com.aresstack.askai.ui;

import com.aresstack.askai.audio.FileAudioProfileRepository;
import com.aresstack.askai.state.ApplicationStateService;
import com.aresstack.audio.pipeline.AudioProcessingProfiles;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The redesigned audio-processing page (wide pipeline on top, inspector card below) must build and render
 * headless without throwing, exercising the canvas + animated card + horizontal inspector together.
 */
public class AudioProcessingPanelRenderTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void buildsAndRendersHeadlessWithoutException() {
        AudioProcessingPanel panel = new AudioProcessingPanel(
                new FileAudioProfileRepository(folder.getRoot()));
        panel.setSize(1000, 460);
        panel.doLayout();

        BufferedImage image = new BufferedImage(1000, 460, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            panel.paint(graphics);
        } finally {
            graphics.dispose();
        }
    }

    @Test
    public void restoresTheRememberedProfileFromApplicationState() throws Exception {
        File stateFile = new File(folder.newFolder("state"), "application-state.json");
        ApplicationStateService state = new ApplicationStateService(stateFile);
        state.putAndSave("audio.selectedProfileId", AudioProcessingProfiles.CRYSTAL_VOICE_PROFILE_ID);

        AudioProcessingPanel panel = new AudioProcessingPanel(
                new FileAudioProfileRepository(folder.newFolder("p")), state);

        assertEquals(AudioProcessingProfiles.CRYSTAL_VOICE_PROFILE_ID, panel.selectedProfileId());
    }

    @Test
    public void unknownRememberedProfileFallsBackToABuiltInAndIsRewritten() throws Exception {
        File stateFile = new File(folder.newFolder("state"), "application-state.json");
        ApplicationStateService state = new ApplicationStateService(stateFile);
        state.putAndSave("audio.selectedProfileId", "no-such-profile");

        AudioProcessingPanel panel = new AudioProcessingPanel(
                new FileAudioProfileRepository(folder.newFolder("p")), state);

        // Falls back to an existing profile and persists that choice, so it never stays dangling.
        String fallback = panel.selectedProfileId();
        assertTrue("fallback must be a real built-in id", AudioProcessingProfiles.isBuiltInId(fallback));
        assertEquals(fallback, new ApplicationStateService(stateFile).get("audio.selectedProfileId", ""));
    }
}
