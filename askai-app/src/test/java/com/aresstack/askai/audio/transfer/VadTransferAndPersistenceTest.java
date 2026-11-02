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

/** The VAD block persists and survives JSON export/import with its parameters intact. */
public class VadTransferAndPersistenceTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void vadSurvivesSaveAndReload() throws Exception {
        File dir = folder.newFolder("profiles");
        new FileAudioProfileRepository(dir).save(vadProfile("persist", "VAD persist"));
        AudioProcessingProfile reloaded = new FileAudioProfileRepository(dir).findById("persist");
        assertVadPreserved(reloaded);
    }

    @Test
    public void vadSurvivesJsonExportAndImport() throws Exception {
        File dir = folder.newFolder("profiles");
        FileAudioProfileRepository repo = new FileAudioProfileRepository(dir);
        repo.save(vadProfile("orig", "VAD transfer"));

        File json = folder.newFile("vad.json");
        new AudioProfileExportService().export(repo.findAll(), json);

        File targetDir = folder.newFolder("target");
        FileAudioProfileRepository targetRepo = new FileAudioProfileRepository(targetDir);
        AudioProfileImportService importService = new AudioProfileImportService(targetRepo);
        AudioProfileImportResult result = importService.commit(importService.preview(json));
        assertEquals(1, result.getImportedCount());

        AudioProcessingProfile imported = null;
        for (AudioProcessingProfile profile : targetRepo.findAll()) {
            if ("VAD transfer".equals(profile.getName())) {
                imported = profile;
            }
        }
        assertNotNull(imported);
        assertVadPreserved(imported);
    }

    private static void assertVadPreserved(AudioProcessingProfile profile) {
        List<AudioBlockDefinition> blocks = profile.getBlocks();
        assertEquals(1, blocks.size());
        assertEquals(AudioBlockType.VOICE_ACTIVITY_DETECTION, blocks.get(0).getType());
        assertEquals("0.7", blocks.get(0).getParameter("sensitivity", ""));
        assertEquals("0.6", blocks.get(0).getParameter("minSpeechProbability", ""));
        assertEquals("25", blocks.get(0).getParameter("frameDurationMs", ""));
    }

    private static AudioProcessingProfile vadProfile(String id, String name) {
        AudioBlockRegistry registry = AudioBlockRegistry.getInstance();
        AudioBlockDefinition vad = registry.defaultDefinition(AudioBlockType.VOICE_ACTIVITY_DETECTION, "v")
                .withParameter("sensitivity", "0.7")
                .withParameter("minSpeechProbability", "0.6")
                .withParameter("frameDurationMs", "25");
        List<AudioBlockDefinition> blocks = new ArrayList<AudioBlockDefinition>();
        blocks.add(vad);
        return new AudioProcessingProfile(id, name, false, blocks);
    }
}
