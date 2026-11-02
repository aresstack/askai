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
}
