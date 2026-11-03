package com.aresstack.askai.stt;

import com.aresstack.audio.pipeline.AudioProcessingProfiles;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class SpeechToTextConfigurationProfileTest {

    @Test
    public void defaultsSelectBuiltInAudioProfile() {
        assertEquals(AudioProcessingProfiles.DEFAULT_PROFILE_ID,
                SpeechToTextConfiguration.defaults().getAudioProcessingProfileId());
    }

    @Test
    public void unrelatedChangesPreserveSelectedAudioProfile() {
        SpeechToTextConfiguration configuration = SpeechToTextConfiguration.defaults()
                .withAudioProcessingProfileId("meeting-room")
                .withModelName("audio-model")
                .withMicrophoneDeviceId("microphone-1");

        assertEquals("meeting-room", configuration.getAudioProcessingProfileId());
    }

    @Test
    public void changingTheProfileKeepsTheNoiseGate() {
        SpeechToTextConfiguration configuration = SpeechToTextConfiguration.defaults()
                .withSignalThresholdPercent(12)
                .withAudioProcessingProfileId("meeting-room");

        assertEquals(12, configuration.getSignalThresholdPercent());
    }

    @Test
    public void dictationSettingsChangeOneValueAndKeepTheRest() {
        SpeechToTextConfiguration configuration = SpeechToTextConfiguration.defaults()
                .withMicrophoneDeviceId("microphone-1")
                .withAutoSendTranscription(true)
                .withEnabled(false)
                .withLanguage("de")
                .withPrompt(" AskAI, Serp4J ")
                .withMaxFileSizeMb(50)
                .withTimeoutSeconds(90);

        assertEquals(false, configuration.isEnabled());
        assertEquals("de", configuration.getLanguage());
        assertEquals("AskAI, Serp4J", configuration.getPrompt());
        assertEquals(50, configuration.getMaxFileSizeMb());
        assertEquals(90, configuration.getTimeoutSeconds());
        assertEquals("microphone-1", configuration.getMicrophoneDeviceId());
        assertEquals(true, configuration.isAutoSendTranscription());
    }
}
