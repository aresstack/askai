package com.aresstack.askai.catalog;

import com.aresstack.audio.profile.AudioProcessingProfile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * An immutable result of one global catalog refresh: the installed chat models, the audio-capable models
 * and the audio-processing profiles. Each part carries a "loaded" flag so a partial failure (e.g. profiles
 * loaded but the model query failed) can be applied without clearing a still-valid list. Distributed to all
 * subscribers, each of which keeps its own selection by stable id.
 */
public final class GlobalCatalogSnapshot {

    private final boolean modelsLoaded;
    private final List<String> chatModels;
    private final boolean audioModelsLoaded;
    private final List<String> audioModels;
    private final boolean profilesLoaded;
    private final List<AudioProcessingProfile> audioProfiles;
    private final List<String> failures;

    public GlobalCatalogSnapshot(boolean modelsLoaded, List<String> chatModels,
                                 boolean audioModelsLoaded, List<String> audioModels,
                                 boolean profilesLoaded, List<AudioProcessingProfile> audioProfiles,
                                 List<String> failures) {
        this.modelsLoaded = modelsLoaded;
        this.chatModels = copy(chatModels);
        this.audioModelsLoaded = audioModelsLoaded;
        this.audioModels = copy(audioModels);
        this.profilesLoaded = profilesLoaded;
        this.audioProfiles = copy(audioProfiles);
        this.failures = copy(failures);
    }

    public boolean isModelsLoaded() {
        return modelsLoaded;
    }

    public List<String> getChatModels() {
        return chatModels;
    }

    public boolean isAudioModelsLoaded() {
        return audioModelsLoaded;
    }

    public List<String> getAudioModels() {
        return audioModels;
    }

    public boolean isProfilesLoaded() {
        return profilesLoaded;
    }

    public List<AudioProcessingProfile> getAudioProfiles() {
        return audioProfiles;
    }

    /** @return human-readable messages for the catalogs that failed to load (empty when all succeeded). */
    public List<String> getFailures() {
        return failures;
    }

    public boolean hasFailures() {
        return !failures.isEmpty();
    }

    private static <T> List<T> copy(List<T> value) {
        return value == null
                ? Collections.<T>emptyList()
                : Collections.unmodifiableList(new ArrayList<T>(value));
    }
}
