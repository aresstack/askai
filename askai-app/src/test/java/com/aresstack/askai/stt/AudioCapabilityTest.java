package com.aresstack.askai.stt;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** STT gating: only the exact "audio" capability qualifies a model for speech-to-text. */
public class AudioCapabilityTest {

    @Test
    public void devstralIsNotAudioCapable() {
        // devstral-small-2:24b reports completion, vision, tools — no audio.
        assertFalse(AudioCapability.isAudioCapable(Arrays.asList("completion", "vision", "tools")));
    }

    @Test
    public void exactAudioCapabilityQualifies() {
        assertTrue(AudioCapability.isAudioCapable(Arrays.asList("completion", "audio")));
        assertTrue("case-insensitive", AudioCapability.isAudioCapable(Arrays.asList("Audio")));
        assertTrue("trimmed", AudioCapability.isAudioCapable(Arrays.asList("  audio  ")));
    }

    @Test
    public void visionMmprojMultimodalAreNeverAudio() {
        assertFalse(AudioCapability.isAudioCapable(Arrays.asList("vision")));
        assertFalse(AudioCapability.isAudioCapable(Arrays.asList("completion", "vision")));
        assertFalse(AudioCapability.isAudioCapable(Arrays.asList("mmproj")));
        assertFalse(AudioCapability.isAudioCapable(Arrays.asList("multimodal")));
        // A near-miss substring must not match.
        assertFalse(AudioCapability.isAudioCapable(Arrays.asList("audio-text-to-text")));
    }

    @Test
    public void emptyOrNullIsNotAudio() {
        assertFalse(AudioCapability.isAudioCapable(Collections.<String>emptyList()));
        assertFalse(AudioCapability.isAudioCapable(null));
    }
}
