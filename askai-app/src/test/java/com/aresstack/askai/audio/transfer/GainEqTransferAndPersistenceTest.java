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

/** The Slice-4A blocks persist and survive JSON export/import with their parameters intact. */
public class GainEqTransferAndPersistenceTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void newBlocksSurviveSaveAndReload() throws Exception {
        File dir = folder.newFolder("profiles");
        FileAudioProfileRepository repo = new FileAudioProfileRepository(dir);
        repo.save(eqProfile("persist", "EQ persist"));

        AudioProcessingProfile reloaded = new FileAudioProfileRepository(dir).findById("persist");
        assertEqChainPreserved(reloaded);
    }

    @Test
    public void newBlocksSurviveJsonExportAndImport() throws Exception {
        File dir = folder.newFolder("profiles");
        FileAudioProfileRepository repo = new FileAudioProfileRepository(dir);
        repo.save(eqProfile("orig", "EQ transfer"));

        File json = folder.newFile("eq.json");
        new AudioProfileExportService().export(repo.findAll(), json);

        File targetDir = folder.newFolder("target");
        FileAudioProfileRepository targetRepo = new FileAudioProfileRepository(targetDir);
        AudioProfileImportService importService = new AudioProfileImportService(targetRepo);
        AudioProfileImportPreview preview = importService.preview(json);
        AudioProfileImportResult result = importService.commit(preview);
        assertEquals(1, result.getImportedCount());

        AudioProcessingProfile imported = null;
        for (AudioProcessingProfile profile : targetRepo.findAll()) {
            if ("EQ transfer".equals(profile.getName())) {
                imported = profile;
            }
        }
        assertNotNull("imported EQ profile", imported);
        assertEqChainPreserved(imported);
    }

    private static void assertEqChainPreserved(AudioProcessingProfile profile) {
        List<AudioBlockDefinition> blocks = profile.getBlocks();
        assertEquals(4, blocks.size());
        assertEquals(AudioBlockType.GAIN, blocks.get(0).getType());
        assertEquals("3.0", blocks.get(0).getParameter("gainDb", ""));
        assertEquals(AudioBlockType.PARAMETRIC_EQ, blocks.get(1).getType());
        assertEquals("1500", blocks.get(1).getParameter("centerHz", ""));
        assertEquals("6", blocks.get(1).getParameter("gainDb", ""));
        assertEquals("1.2", blocks.get(1).getParameter("q", ""));
        assertEquals(AudioBlockType.LOW_SHELF, blocks.get(2).getType());
        assertEquals("180", blocks.get(2).getParameter("cutoffHz", ""));
        assertEquals(AudioBlockType.HIGH_SHELF, blocks.get(3).getType());
        assertEquals("6000", blocks.get(3).getParameter("cutoffHz", ""));
    }

    private static AudioProcessingProfile eqProfile(String id, String name) {
        AudioBlockRegistry registry = AudioBlockRegistry.getInstance();
        List<AudioBlockDefinition> blocks = new ArrayList<AudioBlockDefinition>();
        blocks.add(registry.defaultDefinition(AudioBlockType.GAIN, "g").withParameter("gainDb", "3.0"));
        blocks.add(registry.defaultDefinition(AudioBlockType.PARAMETRIC_EQ, "p")
                .withParameter("centerHz", "1500").withParameter("gainDb", "6").withParameter("q", "1.2"));
        blocks.add(registry.defaultDefinition(AudioBlockType.LOW_SHELF, "l").withParameter("cutoffHz", "180"));
        blocks.add(registry.defaultDefinition(AudioBlockType.HIGH_SHELF, "h").withParameter("cutoffHz", "6000"));
        return new AudioProcessingProfile(id, name, false, blocks);
    }
}
