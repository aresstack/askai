package com.aresstack.askai.batch.service;

import java.io.File;

/** Immutable application event emitted by the batch transcription service. */
public final class BatchTranscriptionEvent {

    public enum Type {
        BATCH_STARTED,
        MODEL_STARTED,
        ITEM_STARTED,
        ITEM_COMPLETED,
        ITEM_FAILED,
        BATCH_CANCELLED,
        BATCH_COMPLETED
    }

    private final Type type;
    private final int completedItems;
    private final int totalItems;
    private final String modelName;
    private final String profileName;
    private final File audioFile;
    private final File markdownFile;
    private final String message;

    private BatchTranscriptionEvent(Type type, int completedItems, int totalItems,
                                    String modelName, String profileName, File audioFile,
                                    File markdownFile, String message) {
        this.type = type;
        this.completedItems = completedItems;
        this.totalItems = totalItems;
        this.modelName = modelName == null ? "" : modelName;
        this.profileName = profileName == null ? "" : profileName;
        this.audioFile = audioFile;
        this.markdownFile = markdownFile;
        this.message = message == null ? "" : message;
    }

    public static BatchTranscriptionEvent of(Type type, int completedItems, int totalItems,
                                             String modelName, String profileName, File audioFile,
                                             File markdownFile, String message) {
        return new BatchTranscriptionEvent(type, completedItems, totalItems, modelName,
                profileName, audioFile, markdownFile, message);
    }

    public Type getType() { return type; }
    public int getCompletedItems() { return completedItems; }
    public int getTotalItems() { return totalItems; }
    public String getModelName() { return modelName; }
    public String getProfileName() { return profileName; }
    public File getAudioFile() { return audioFile; }
    public File getMarkdownFile() { return markdownFile; }
    public String getMessage() { return message; }
}
