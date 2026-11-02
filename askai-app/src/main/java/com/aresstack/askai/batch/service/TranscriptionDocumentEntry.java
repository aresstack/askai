package com.aresstack.askai.batch.service;

/**
 * One transcription result to place into a batch Markdown document, identified by the stable pair
 * {@code modelId + profileId}. {@code profileName} is the visible heading text (may be renamed without
 * creating a new section, as long as the id is stable); {@code modelId} doubles as the visible model
 * heading (a model tag like {@code gemma4:e2b} is its own display name).
 */
public final class TranscriptionDocumentEntry {

    private final String modelId;
    private final String profileId;
    private final String profileName;
    private final String transcription;

    public TranscriptionDocumentEntry(String modelId, String profileId, String profileName,
                                      String transcription) {
        if (modelId == null || modelId.trim().isEmpty()) {
            throw new IllegalArgumentException("modelId must not be null or blank");
        }
        // The writer must never serialize a corrupt identity: null, blank, or the literal "null"
        // (all three have been observed in legacy documents written by older versions).
        if (!AudioProfileIdentityResolver.isValidId(profileId)) {
            throw new IllegalArgumentException(
                    "profileId must be a stable non-blank id, got: '" + profileId + "'");
        }
        this.modelId = modelId.trim();
        this.profileId = profileId.trim();
        this.profileName = profileName == null ? "" : profileName.trim();
        this.transcription = transcription == null ? "" : transcription;
    }

    public String getModelId() {
        return modelId;
    }

    public String getProfileId() {
        return profileId;
    }

    public String getProfileName() {
        return profileName;
    }

    public String getTranscription() {
        return transcription;
    }
}
