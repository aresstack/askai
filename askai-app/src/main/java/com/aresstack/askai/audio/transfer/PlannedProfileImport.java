package com.aresstack.askai.audio.transfer;

import com.aresstack.audio.profile.AudioProcessingProfile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A profile that will be imported, with the resolved (final) identity and any changes applied during
 * collision handling. The {@link #getResolvedProfile()} already has {@code builtIn=false} and its final id
 * and name, so committing it is a plain save.
 */
public final class PlannedProfileImport {

    private final String originalId;
    private final String originalName;
    private final AudioProcessingProfile resolvedProfile;
    private final boolean idReassigned;
    private final boolean nameReassigned;
    private final List<String> warnings;

    public PlannedProfileImport(String originalId, String originalName,
                                AudioProcessingProfile resolvedProfile, boolean idReassigned,
                                boolean nameReassigned, List<String> warnings) {
        this.originalId = originalId;
        this.originalName = originalName;
        this.resolvedProfile = resolvedProfile;
        this.idReassigned = idReassigned;
        this.nameReassigned = nameReassigned;
        this.warnings = Collections.unmodifiableList(
                new ArrayList<String>(warnings == null ? new ArrayList<String>() : warnings));
    }

    public String getOriginalId() {
        return originalId;
    }

    public String getOriginalName() {
        return originalName;
    }

    public AudioProcessingProfile getResolvedProfile() {
        return resolvedProfile;
    }

    public String getFinalId() {
        return resolvedProfile.getId();
    }

    public String getFinalName() {
        return resolvedProfile.getName();
    }

    public boolean isIdReassigned() {
        return idReassigned;
    }

    public boolean isNameReassigned() {
        return nameReassigned;
    }

    public List<String> getWarnings() {
        return warnings;
    }
}
