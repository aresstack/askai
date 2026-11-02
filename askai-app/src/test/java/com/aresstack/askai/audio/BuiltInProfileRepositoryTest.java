package com.aresstack.askai.audio;

import com.aresstack.audio.pipeline.AudioProcessingProfiles;
import com.aresstack.audio.profile.AudioProcessingProfile;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** The repository serves all three built-in profiles and refuses to overwrite or delete them. */
public class BuiltInProfileRepositoryTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void findAllIncludesTheThreeBuiltIns() throws Exception {
        FileAudioProfileRepository repo = new FileAudioProfileRepository(folder.newFolder("p"));
        Set<String> ids = new HashSet<String>();
        for (AudioProcessingProfile profile : repo.findAll()) {
            if (profile.isBuiltIn()) {
                ids.add(profile.getId());
            }
        }
        assertTrue(ids.contains(AudioProcessingProfiles.OFF_PROFILE_ID));
        assertTrue(ids.contains(AudioProcessingProfiles.DEFAULT_PROFILE_ID));
        assertTrue(ids.contains(AudioProcessingProfiles.CRYSTAL_VOICE_PROFILE_ID));
    }

    @Test
    public void findByIdReturnsTheBuiltIns() throws Exception {
        FileAudioProfileRepository repo = new FileAudioProfileRepository(folder.newFolder("p"));
        assertEquals("Crystal voice", repo.findById(AudioProcessingProfiles.CRYSTAL_VOICE_PROFILE_ID).getName());
        assertTrue(repo.findById(AudioProcessingProfiles.OFF_PROFILE_ID).getBlocks().isEmpty());
    }

    @Test
    public void builtInsCannotBeOverwrittenOrDeleted() throws Exception {
        FileAudioProfileRepository repo = new FileAudioProfileRepository(folder.newFolder("p"));
        try {
            repo.save(AudioProcessingProfiles.crystalVoice());
            fail("saving a built-in must throw");
        } catch (IllegalArgumentException expected) {
            // expected
        }
        try {
            repo.delete(AudioProcessingProfiles.CRYSTAL_VOICE_PROFILE_ID);
            fail("deleting a built-in must throw");
        } catch (IllegalArgumentException | java.io.IOException expected) {
            // expected
        }
    }
}
