package com.aresstack.askai.audio.transfer;

import com.aresstack.askai.audio.AudioProfileRepository;
import com.aresstack.audio.application.AudioProfileSignature;
import com.aresstack.audio.pipeline.AudioProcessingProfiles;
import com.aresstack.audio.profile.AudioProcessingProfile;

import java.io.IOException;

/**
 * Persists validated, imported user profiles on top of the existing {@link AudioProfileRepository}. Each
 * profile is saved atomically (the underlying repository writes one file via a temp-then-move) and then
 * read back to verify it landed intact — no silent partial success. The built-in default profile can never
 * be written through here, enforced independently of the UI.
 */
public final class AudioProfileTransferRepository {

    private final AudioProfileRepository repository;

    public AudioProfileTransferRepository(AudioProfileRepository repository) {
        if (repository == null) {
            throw new IllegalArgumentException("Repository must not be null.");
        }
        this.repository = repository;
    }

    /** Atomically persist one validated user profile and confirm it reads back with the same content. */
    public void commit(AudioProcessingProfile profile) throws IOException {
        if (profile == null) {
            throw new IllegalArgumentException("Profile must not be null.");
        }
        if (profile.isBuiltIn()
                || AudioProcessingProfiles.DEFAULT_PROFILE_ID.equals(profile.getId())) {
            throw new IllegalStateException("The built-in default profile can never be imported or overwritten.");
        }
        repository.save(profile);
        AudioProcessingProfile readBack = repository.findById(profile.getId());
        if (readBack == null
                || !profile.getName().equals(readBack.getName())
                || !AudioProfileSignature.of(profile).equals(AudioProfileSignature.of(readBack))) {
            throw new IOException("Read-back verification failed for imported profile \""
                    + profile.getName() + "\".");
        }
    }
}
