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

/** The FFT block variants persist and survive JSON export/import with their parameters intact. */
public class FftBlocksTransferTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void survivesSaveAndReload() throws Exception {
        File dir = folder.newFolder("profiles");
        new FileAudioProfileRepository(dir).save(profile("persist", "FFT persist"));
        assertPreserved(new FileAudioProfileRepository(dir).findById("persist"));
    }

    @Test
    public void survivesJsonExportAndImport() throws Exception {
        File dir = folder.newFolder("profiles");
        FileAudioProfileRepository repo = new FileAudioProfileRepository(dir);
        repo.save(profile("orig", "FFT transfer"));

        File json = folder.newFile("fft.json");
        new AudioProfileExportService().export(repo.findAll(), json);

        File targetDir = folder.newFolder("target");
        FileAudioProfileRepository target = new FileAudioProfileRepository(targetDir);
        AudioProfileImportService importService = new AudioProfileImportService(target);
        AudioProfileImportResult result = importService.commit(importService.preview(json));
        assertEquals(1, result.getImportedCount());

        AudioProcessingProfile imported = null;
        for (AudioProcessingProfile p : target.findAll()) {
            if ("FFT transfer".equals(p.getName())) {
                imported = p;
            }
        }
        assertNotNull(imported);
        assertPreserved(imported);
    }

    private static void assertPreserved(AudioProcessingProfile profile) {
        List<AudioBlockDefinition> b = profile.getBlocks();
        assertEquals(4, b.size());
        assertEquals(AudioBlockType.DE_ESSER_FFT, b.get(0).getType());
        assertEquals("7000", b.get(0).getParameter("targetFrequencyHz", ""));
        assertEquals(AudioBlockType.ADAPTIVE_HUM_REMOVAL_FFT, b.get(1).getType());
        assertEquals("60", b.get(1).getParameter("baseFrequencyHz", ""));
        assertEquals(AudioBlockType.PLOSIVE_REDUCTION_FFT, b.get(2).getType());
        assertEquals("0.8", b.get(2).getParameter("strength", ""));
        assertEquals(AudioBlockType.BREATH_REDUCTION_FFT, b.get(3).getType());
        assertEquals("18", b.get(3).getParameter("maxAttenuationDb", ""));
    }

    private static AudioProcessingProfile profile(String id, String name) {
        AudioBlockRegistry registry = AudioBlockRegistry.getInstance();
        List<AudioBlockDefinition> blocks = new ArrayList<AudioBlockDefinition>();
        blocks.add(registry.defaultDefinition(AudioBlockType.DE_ESSER_FFT, "d")
                .withParameter("targetFrequencyHz", "7000"));
        blocks.add(registry.defaultDefinition(AudioBlockType.ADAPTIVE_HUM_REMOVAL_FFT, "h")
                .withParameter("baseFrequencyHz", "60"));
        blocks.add(registry.defaultDefinition(AudioBlockType.PLOSIVE_REDUCTION_FFT, "p")
                .withParameter("strength", "0.8"));
        blocks.add(registry.defaultDefinition(AudioBlockType.BREATH_REDUCTION_FFT, "b")
                .withParameter("maxAttenuationDb", "18"));
        return new AudioProcessingProfile(id, name, false, blocks);
    }
}
