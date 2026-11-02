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

/** The Speech Gate block persists and survives JSON export/import with its parameters intact. */
public class SpeechGateTransferTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void survivesSaveAndReload() throws Exception {
        File dir = folder.newFolder("profiles");
        new FileAudioProfileRepository(dir).save(profile("persist", "Speech Gate persist"));
        assertPreserved(new FileAudioProfileRepository(dir).findById("persist"));
    }

    @Test
    public void survivesJsonExportAndImport() throws Exception {
        File dir = folder.newFolder("profiles");
        FileAudioProfileRepository repo = new FileAudioProfileRepository(dir);
        repo.save(profile("orig", "Speech Gate transfer"));

        File json = folder.newFile("speech-gate.json");
        new AudioProfileExportService().export(repo.findAll(), json);

        File targetDir = folder.newFolder("target");
        FileAudioProfileRepository target = new FileAudioProfileRepository(targetDir);
        AudioProfileImportService importService = new AudioProfileImportService(target);
        AudioProfileImportResult result = importService.commit(importService.preview(json));
        assertEquals(1, result.getImportedCount());

        AudioProcessingProfile imported = null;
        for (AudioProcessingProfile p : target.findAll()) {
            if ("Speech Gate transfer".equals(p.getName())) {
                imported = p;
            }
        }
        assertNotNull(imported);
        assertPreserved(imported);
    }

    private static void assertPreserved(AudioProcessingProfile profile) {
        List<AudioBlockDefinition> blocks = profile.getBlocks();
        assertEquals(2, blocks.size());
        assertEquals(AudioBlockType.VOICE_ACTIVITY_DETECTION, blocks.get(0).getType());
        AudioBlockDefinition gate = blocks.get(1);
        assertEquals(AudioBlockType.SPEECH_GATE, gate.getType());
        assertEquals("0.6", gate.getParameter("minSpeechProbability", ""));
        assertEquals("150", gate.getParameter("preRollMs", ""));
        assertEquals("400", gate.getParameter("postRollMs", ""));
    }

    private static AudioProcessingProfile profile(String id, String name) {
        AudioBlockRegistry registry = AudioBlockRegistry.getInstance();
        List<AudioBlockDefinition> blocks = new ArrayList<AudioBlockDefinition>();
        blocks.add(registry.defaultDefinition(AudioBlockType.VOICE_ACTIVITY_DETECTION, "vad"));
        blocks.add(registry.defaultDefinition(AudioBlockType.SPEECH_GATE, "gate")
                .withParameter("minSpeechProbability", "0.6")
                .withParameter("preRollMs", "150")
                .withParameter("postRollMs", "400"));
        return new AudioProcessingProfile(id, name, false, blocks);
    }
}
