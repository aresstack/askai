package com.aresstack.askai.audio.format;

import org.junit.Test;

import java.io.File;
import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SupportedAudioFormatsTest {

    @Test
    public void exposesTheSharedTranscriptionFormatsInStableOrder() {
        assertArrayEquals(new String[]{"wav", "mp3", "m4a", "ogg", "flac"},
                SupportedAudioFormats.extensionArray());
        assertTrue(SupportedAudioFormats.extensions().equals(
                Arrays.asList("wav", "mp3", "m4a", "ogg", "flac")));
    }

    @Test
    public void acceptsExtensionsWithoutDependingOnLetterCase() {
        assertTrue(SupportedAudioFormats.supports(new File("speech.MP3")));
        assertTrue(SupportedAudioFormats.supports(new File("speech.m4a")));
        assertTrue(SupportedAudioFormats.supports(new File("speech.ogg")));
        assertTrue(SupportedAudioFormats.supports(new File("speech.flac")));
        assertFalse(SupportedAudioFormats.supports(new File("speech.txt")));
    }
}
