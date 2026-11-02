package com.aresstack.askai.audio.transfer;

import com.aresstack.askai.audio.FileAudioProfileRepository;
import com.aresstack.audio.pipeline.AudioBlockRegistry;
import com.aresstack.audio.profile.AudioBlockDefinition;
import com.aresstack.audio.profile.AudioBlockType;
import com.aresstack.audio.profile.AudioProcessingProfile;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/** Expander and Silence Trimmer persist and survive JSON export/import with their parameters intact. */
public class ExpanderSilenceTrimmerTransferTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void bothBlocksSurviveSaveAndReload() throws Exception {
        File dir = folder.newFolder("profiles");
        new FileAudioProfileRepository(dir).save(profile("persist", "Cleanup persist"));
        assertPreserved(new FileAudioProfileRepository(dir).findById("persist"));
    }

    @Test
    public void bothBlocksSurviveJsonExportAndImport() throws Exception {
        File dir = folder.newFolder("profiles");
        FileAudioProfileRepository repo = new FileAudioProfileRepository(dir);
        repo.save(profile("orig", "Cleanup transfer"));

        File json = folder.newFile("cleanup.json");
        new AudioProfileExportService().export(repo.findAll(), json);

        File targetDir = folder.newFolder("target");
        FileAudioProfileRepository target = new FileAudioProfileRepository(targetDir);
        AudioProfileImportService importService = new AudioProfileImportService(target);
        AudioProfileImportResult result = importService.commit(importService.preview(json));
        assertEquals(1, result.getImportedCount());

        AudioProcessingProfile imported = null;
        for (AudioProcessingProfile profile : target.findAll()) {
            if ("Cleanup transfer".equals(profile.getName())) {
                imported = profile;
            }
        }
        assertNotNull(imported);
        assertPreserved(imported);
    }

    private static void assertPreserved(AudioProcessingProfile profile) {
        List<AudioBlockDefinition> blocks = profile.getBlocks();
        assertEquals(3, blocks.size());
        assertEquals(AudioBlockType.VOICE_ACTIVITY_DETECTION, blocks.get(0).getType());
        assertEquals(AudioBlockType.EXPANDER, blocks.get(1).getType());
        assertEquals("2.5", blocks.get(1).getParameter("ratio", ""));
        assertEquals("true", blocks.get(1).getParameter("speechProtection", ""));
        assertEquals(AudioBlockType.SILENCE_TRIMMER, blocks.get(2).getType());
        assertEquals("FAIL", blocks.get(2).getParameter("noSpeechBehavior", ""));
        assertEquals("250", blocks.get(2).getParameter("preRollMs", ""));
    }

    private static AudioProcessingProfile profile(String id, String name) {
        AudioBlockRegistry registry = AudioBlockRegistry.getInstance();
        List<AudioBlockDefinition> blocks = new ArrayList<AudioBlockDefinition>();
        blocks.add(registry.defaultDefinition(AudioBlockType.VOICE_ACTIVITY_DETECTION, "vad"));
        blocks.add(registry.defaultDefinition(AudioBlockType.EXPANDER, "exp")
                .withParameter("ratio", "2.5").withParameter("speechProtection", "true"));
        blocks.add(registry.defaultDefinition(AudioBlockType.SILENCE_TRIMMER, "trim")
                .withParameter("noSpeechBehavior", "FAIL").withParameter("preRollMs", "250"));
        return new AudioProcessingProfile(id, name, false, blocks);
    }
}
