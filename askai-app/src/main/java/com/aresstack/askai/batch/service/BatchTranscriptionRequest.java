package com.aresstack.askai.batch.service;

import com.aresstack.audio.profile.AudioProcessingProfile;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Describe one deterministic model -> file -> profile batch run. */
public final class BatchTranscriptionRequest {

    private final List<File> audioFiles;
    private final List<String> modelNames;
    private final List<AudioProcessingProfile> profiles;
    private final String language;
    private final String prompt;

    public BatchTranscriptionRequest(List<File> audioFiles, List<String> modelNames,
                                     List<AudioProcessingProfile> profiles,
                                     String language, String prompt) {
        this.audioFiles = immutableCopy(audioFiles, "Audio files");
        this.modelNames = immutableCopy(modelNames, "Model names");
        this.profiles = immutableCopy(profiles, "Profiles");
        this.language = language == null ? "" : language.trim();
        this.prompt = prompt == null ? "" : prompt.trim();
        validate();
    }

    private void validate() {
        if (audioFiles.isEmpty()) throw new IllegalArgumentException("Select at least one audio file.");
        if (modelNames.isEmpty()) throw new IllegalArgumentException("Select at least one audio model.");
        if (profiles.isEmpty()) throw new IllegalArgumentException("Select at least one audio profile.");
        for (File file : audioFiles) {
            if (file == null || !file.isFile()) {
                throw new IllegalArgumentException("Every selected audio file must exist.");
            }
        }
    }

    private static <T> List<T> immutableCopy(List<T> source, String name) {
        if (source == null) throw new IllegalArgumentException(name + " must not be null.");
        return Collections.unmodifiableList(new ArrayList<T>(source));
    }

    public List<File> getAudioFiles() { return audioFiles; }
    public List<String> getModelNames() { return modelNames; }
    public List<AudioProcessingProfile> getProfiles() { return profiles; }
    public String getLanguage() { return language; }
    public String getPrompt() { return prompt; }
    public int getTotalItems() { return audioFiles.size() * modelNames.size() * profiles.size(); }
}
