package com.aresstack.askai.audio;

import com.aresstack.audio.profile.AudioProcessingProfile;
import com.aresstack.audio.pipeline.AudioProcessingProfiles;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public final class FileAudioProfileRepositoryTest {

    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test(expected = IllegalArgumentException.class)
    public void builtInDefaultCannotBeOverwritten() throws Exception {
        FileAudioProfileRepository repository = new FileAudioProfileRepository(temporaryFolder.newFolder());

        repository.save(AudioProcessingProfiles.defaultSpeech());
    }

    @Test
    public void saveAsCreatesReloadableUserProfile() throws Exception {
        FileAudioProfileRepository repository = new FileAudioProfileRepository(temporaryFolder.newFolder());

        AudioProcessingProfile saved = repository.saveAs(AudioProcessingProfiles.defaultSpeech(), "Meeting room");
        AudioProcessingProfile loaded = repository.findById(saved.getId());

        assertFalse(loaded.isBuiltIn());
        assertEquals("Meeting room", loaded.getName());
        assertEquals(AudioProcessingProfiles.defaultSpeech().getBlocks().size(), loaded.getBlocks().size());
    }
}
