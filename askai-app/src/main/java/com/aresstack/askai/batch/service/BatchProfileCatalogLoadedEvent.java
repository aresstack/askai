package com.aresstack.askai.batch.service;

import com.aresstack.audio.profile.AudioProcessingProfile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable event describing the asynchronously reloaded audio-processing profiles for the batch view. */
public final class BatchProfileCatalogLoadedEvent {

    private final List<AudioProcessingProfile> profiles;
    private final boolean successful;
    private final String message;

    private BatchProfileCatalogLoadedEvent(List<AudioProcessingProfile> profiles, boolean successful,
                                           String message) {
        this.profiles = profiles == null
                ? Collections.<AudioProcessingProfile>emptyList()
                : Collections.unmodifiableList(new ArrayList<AudioProcessingProfile>(profiles));
        this.successful = successful;
        this.message = message == null ? "" : message;
    }

    public static BatchProfileCatalogLoadedEvent loaded(List<AudioProcessingProfile> profiles) {
        return new BatchProfileCatalogLoadedEvent(profiles, true, "");
    }

    public static BatchProfileCatalogLoadedEvent failed(String message) {
        return new BatchProfileCatalogLoadedEvent(Collections.<AudioProcessingProfile>emptyList(), false, message);
    }

    public List<AudioProcessingProfile> getProfiles() { return profiles; }

    public boolean isSuccessful() { return successful; }

    public String getMessage() { return message; }
}
