package com.aresstack.askai.stt;

import com.aresstack.audio.pipeline.AudioProcessingProfiles;

/**
 * Persisted speech-to-text settings. The backend is an extension point: only
 * {@link Backend#OLLAMA} exists today (the OpenAI-compatible {@code /v1/audio/transcriptions}
 * endpoint, reusing the configured Ollama base URL), but the enum keeps the door open for
 * whisper.cpp / faster-whisper / other OpenAI-compatible servers.
 */
public final class SpeechToTextConfiguration {

    public enum Backend {
        OLLAMA
    }

    public static final int DEFAULT_MAX_FILE_SIZE_MB = 200;
    public static final int DEFAULT_TIMEOUT_SECONDS = 600;
    public static final String DEFAULT_AUDIO_PROCESSING_PROFILE_ID = AudioProcessingProfiles.DEFAULT_PROFILE_ID;
    /** Silence length that auto-stops a recording (when enabled) — Gemini-style hands-free feel. */
    public static final int DEFAULT_AUTO_STOP_SILENCE_SECONDS = 2;
    /**
     * Level (percent on the LOGARITHMIC dB scale, see {@link DecibelLevelScale}) above which the
     * recording counts as SPEECH, below as noise. 63 is the dB equivalent of the old linear 8 —
     * the same physical level as before, expressed on the new scale.
     */
    public static final int DEFAULT_SIGNAL_THRESHOLD_PERCENT = 63;

    private final boolean enabled;
    private final Backend backend;
    private final String modelName;
    private final String language;
    private final String prompt;
    private final int maxFileSizeMb;
    private final int timeoutSeconds;
    // Dictation additions:
    private final String microphoneDeviceId;   // "" = system default
    private final boolean audioModelAutomatic;  // pick a verified audio model automatically
    private final String lastAudioModel;        // last model that transcribed successfully (preferred)
    private final String audioProcessingProfileId;
    // Hands-free additions (both ON = the Gemini feel):
    private final boolean autoSendTranscription;   // send right after transcription (no review stop)
    private final boolean autoStopOnSilence;       // stop the recording after a long-enough pause
    private final int autoStopSilenceSeconds;      // how long that pause must be
    private final int signalThresholdPercent;      // the Skype-style noise gate (draggable line)

    public SpeechToTextConfiguration(boolean enabled, Backend backend, String modelName, String language,
                                     String prompt, int maxFileSizeMb, int timeoutSeconds) {
        this(enabled, backend, modelName, language, prompt, maxFileSizeMb, timeoutSeconds, "", true, "",
                DEFAULT_AUDIO_PROCESSING_PROFILE_ID);
    }

    public SpeechToTextConfiguration(boolean enabled, Backend backend, String modelName, String language,
                                     String prompt, int maxFileSizeMb, int timeoutSeconds,
                                     String microphoneDeviceId, boolean audioModelAutomatic, String lastAudioModel) {
        this(enabled, backend, modelName, language, prompt, maxFileSizeMb, timeoutSeconds,
                microphoneDeviceId, audioModelAutomatic, lastAudioModel,
                DEFAULT_AUDIO_PROCESSING_PROFILE_ID);
    }

    public SpeechToTextConfiguration(boolean enabled, Backend backend, String modelName, String language,
                                     String prompt, int maxFileSizeMb, int timeoutSeconds,
                                     String microphoneDeviceId, boolean audioModelAutomatic, String lastAudioModel,
                                     String audioProcessingProfileId) {
        this(enabled, backend, modelName, language, prompt, maxFileSizeMb, timeoutSeconds,
                microphoneDeviceId, audioModelAutomatic, lastAudioModel, audioProcessingProfileId,
                false, false, DEFAULT_AUTO_STOP_SILENCE_SECONDS, DEFAULT_SIGNAL_THRESHOLD_PERCENT);
    }

    public SpeechToTextConfiguration(boolean enabled, Backend backend, String modelName, String language,
                                     String prompt, int maxFileSizeMb, int timeoutSeconds,
                                     String microphoneDeviceId, boolean audioModelAutomatic, String lastAudioModel,
                                     String audioProcessingProfileId, boolean autoSendTranscription,
                                     boolean autoStopOnSilence, int autoStopSilenceSeconds) {
        this(enabled, backend, modelName, language, prompt, maxFileSizeMb, timeoutSeconds,
                microphoneDeviceId, audioModelAutomatic, lastAudioModel, audioProcessingProfileId,
                autoSendTranscription, autoStopOnSilence, autoStopSilenceSeconds,
                DEFAULT_SIGNAL_THRESHOLD_PERCENT);
    }

    public SpeechToTextConfiguration(boolean enabled, Backend backend, String modelName, String language,
                                     String prompt, int maxFileSizeMb, int timeoutSeconds,
                                     String microphoneDeviceId, boolean audioModelAutomatic, String lastAudioModel,
                                     String audioProcessingProfileId, boolean autoSendTranscription,
                                     boolean autoStopOnSilence, int autoStopSilenceSeconds,
                                     int signalThresholdPercent) {
        this.enabled = enabled;
        this.backend = backend == null ? Backend.OLLAMA : backend;
        this.modelName = modelName == null ? "" : modelName.trim();
        this.language = language == null ? "" : language.trim();
        this.prompt = prompt == null ? "" : prompt.trim();
        this.maxFileSizeMb = maxFileSizeMb > 0 ? maxFileSizeMb : DEFAULT_MAX_FILE_SIZE_MB;
        this.timeoutSeconds = timeoutSeconds > 0 ? timeoutSeconds : DEFAULT_TIMEOUT_SECONDS;
        this.microphoneDeviceId = microphoneDeviceId == null ? "" : microphoneDeviceId;
        this.audioModelAutomatic = audioModelAutomatic;
        this.lastAudioModel = lastAudioModel == null ? "" : lastAudioModel.trim();
        this.audioProcessingProfileId = audioProcessingProfileId == null
                || audioProcessingProfileId.trim().isEmpty()
                ? DEFAULT_AUDIO_PROCESSING_PROFILE_ID : audioProcessingProfileId.trim();
        this.autoSendTranscription = autoSendTranscription;
        this.autoStopOnSilence = autoStopOnSilence;
        this.autoStopSilenceSeconds = autoStopSilenceSeconds > 0
                ? autoStopSilenceSeconds : DEFAULT_AUTO_STOP_SILENCE_SECONDS;
        // 0 is a VALID gate position: the line at the very bottom means "accept everything,
        // even the faintest noise" — only out-of-range values fall back to the default.
        this.signalThresholdPercent = signalThresholdPercent >= 0 && signalThresholdPercent <= 95
                ? signalThresholdPercent : DEFAULT_SIGNAL_THRESHOLD_PERCENT;
    }

    /** Enabled by default with no dedicated model: the chat panel then falls back to the chat model. */
    public static SpeechToTextConfiguration defaults() {
        return new SpeechToTextConfiguration(true, Backend.OLLAMA, "", "auto", "",
                DEFAULT_MAX_FILE_SIZE_MB, DEFAULT_TIMEOUT_SECONDS, "", true, "",
                DEFAULT_AUDIO_PROCESSING_PROFILE_ID);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Backend getBackend() {
        return backend;
    }

    /** @return the dedicated STT model, or "" when the current chat model should be used. */
    public String getModelName() {
        return modelName;
    }

    /** @return the default language hint; "auto" or "" means let the model detect. */
    public String getLanguage() {
        return language;
    }

    public String getPrompt() {
        return prompt;
    }

    public int getMaxFileSizeMb() {
        return maxFileSizeMb;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    /** @return the selected capture device id, or "" for the system default microphone. */
    public String getMicrophoneDeviceId() {
        return microphoneDeviceId;
    }

    /** @return whether an audio model should be picked automatically (verified via /api/show). */
    public boolean isAudioModelAutomatic() {
        return audioModelAutomatic;
    }

    /** @return the last model that transcribed successfully (preferred by automatic selection). */
    public String getLastAudioModel() {
        return lastAudioModel;
    }

    /** @return the reusable audio-processing profile selected for transcription input. */
    public String getAudioProcessingProfileId() {
        return audioProcessingProfileId;
    }

    /** @return whether the transcribed text is sent immediately (no review stop). */
    public boolean isAutoSendTranscription() {
        return autoSendTranscription;
    }

    /** @return whether a long-enough silence auto-stops the recording. */
    public boolean isAutoStopOnSilence() {
        return autoStopOnSilence;
    }

    /** @return the silence length (seconds) that triggers the auto-stop. */
    public int getAutoStopSilenceSeconds() {
        return autoStopSilenceSeconds;
    }

    public SpeechToTextConfiguration withModelName(String value) {
        return new SpeechToTextConfiguration(enabled, backend, value, language, prompt, maxFileSizeMb,
                timeoutSeconds, microphoneDeviceId, audioModelAutomatic, lastAudioModel,
                audioProcessingProfileId, autoSendTranscription, autoStopOnSilence,
                autoStopSilenceSeconds, signalThresholdPercent);
    }

    public SpeechToTextConfiguration withMicrophoneDeviceId(String value) {
        return new SpeechToTextConfiguration(enabled, backend, modelName, language, prompt, maxFileSizeMb,
                timeoutSeconds, value, audioModelAutomatic, lastAudioModel,
                audioProcessingProfileId, autoSendTranscription, autoStopOnSilence,
                autoStopSilenceSeconds, signalThresholdPercent);
    }

    public SpeechToTextConfiguration withAudioModelAutomatic(boolean value) {
        return new SpeechToTextConfiguration(enabled, backend, modelName, language, prompt, maxFileSizeMb,
                timeoutSeconds, microphoneDeviceId, value, lastAudioModel,
                audioProcessingProfileId, autoSendTranscription, autoStopOnSilence,
                autoStopSilenceSeconds, signalThresholdPercent);
    }

    public SpeechToTextConfiguration withLastAudioModel(String value) {
        return new SpeechToTextConfiguration(enabled, backend, modelName, language, prompt, maxFileSizeMb,
                timeoutSeconds, microphoneDeviceId, audioModelAutomatic, value,
                audioProcessingProfileId, autoSendTranscription, autoStopOnSilence,
                autoStopSilenceSeconds, signalThresholdPercent);
    }


    public SpeechToTextConfiguration withAudioProcessingProfileId(String value) {
        return new SpeechToTextConfiguration(enabled, backend, modelName, language, prompt, maxFileSizeMb,
                timeoutSeconds, microphoneDeviceId, audioModelAutomatic, lastAudioModel, value,
                autoSendTranscription, autoStopOnSilence, autoStopSilenceSeconds);
    }

    public SpeechToTextConfiguration withAutoSendTranscription(boolean value) {
        return new SpeechToTextConfiguration(enabled, backend, modelName, language, prompt, maxFileSizeMb,
                timeoutSeconds, microphoneDeviceId, audioModelAutomatic, lastAudioModel,
                audioProcessingProfileId, value, autoStopOnSilence, autoStopSilenceSeconds,
                signalThresholdPercent);
    }

    public SpeechToTextConfiguration withAutoStopOnSilence(boolean value) {
        return new SpeechToTextConfiguration(enabled, backend, modelName, language, prompt, maxFileSizeMb,
                timeoutSeconds, microphoneDeviceId, audioModelAutomatic, lastAudioModel,
                audioProcessingProfileId, autoSendTranscription, value, autoStopSilenceSeconds,
                signalThresholdPercent);
    }

    public SpeechToTextConfiguration withAutoStopSilenceSeconds(int value) {
        return new SpeechToTextConfiguration(enabled, backend, modelName, language, prompt, maxFileSizeMb,
                timeoutSeconds, microphoneDeviceId, audioModelAutomatic, lastAudioModel,
                audioProcessingProfileId, autoSendTranscription, autoStopOnSilence, value,
                signalThresholdPercent);
    }

    /** @return the level (percent) above which the recording counts as speech (noise gate). */
    public int getSignalThresholdPercent() {
        return signalThresholdPercent;
    }

    public SpeechToTextConfiguration withSignalThresholdPercent(int value) {
        return new SpeechToTextConfiguration(enabled, backend, modelName, language, prompt, maxFileSizeMb,
                timeoutSeconds, microphoneDeviceId, audioModelAutomatic, lastAudioModel,
                audioProcessingProfileId, autoSendTranscription, autoStopOnSilence, autoStopSilenceSeconds,
                value);
    }

    public static Backend parseBackend(String value) {
        if (value == null) {
            return Backend.OLLAMA;
        }
        try {
            return Backend.valueOf(value.trim());
        } catch (IllegalArgumentException ex) {
            return Backend.OLLAMA;
        }
    }
}
