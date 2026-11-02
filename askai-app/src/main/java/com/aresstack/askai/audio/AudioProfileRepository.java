package com.aresstack.askai.audio;

import com.aresstack.audio.profile.AudioProcessingProfile;

import java.io.IOException;
import java.util.List;

/** Provide profile persistence without exposing file-system details to Swing or dictation code. */
public interface AudioProfileRepository {

    List<AudioProcessingProfile> findAll();

    AudioProcessingProfile findById(String profileId);

    AudioProcessingProfile saveAs(AudioProcessingProfile source, String newName) throws IOException;

    void save(AudioProcessingProfile profile) throws IOException;

    void delete(String profileId) throws IOException;
}
