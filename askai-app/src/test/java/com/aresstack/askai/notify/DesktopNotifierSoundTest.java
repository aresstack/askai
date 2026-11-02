package com.aresstack.askai.notify;

import org.junit.Test;

import javax.sound.sampled.AudioFormat;

import static org.junit.Assert.assertTrue;

/** The generated notification tones produce non-empty PCM and scale with the volume. */
public class DesktopNotifierSoundTest {

    private static final AudioFormat FORMAT = new AudioFormat(44100f, 16, 1, true, false);

    @Test
    public void everySoundTypeProducesAudio() {
        for (String type : new String[] {
                DesktopNotifier.SOUND_CLICK, DesktopNotifier.SOUND_POP,
                DesktopNotifier.SOUND_BEEP, DesktopNotifier.SOUND_CHIME}) {
            byte[] pcm = DesktopNotifier.buildSound(FORMAT, type, 0.7);
            assertTrue(type + " produces samples", pcm.length > 0);
        }
    }

    @Test
    public void zeroVolumeIsSilent() {
        byte[] pcm = DesktopNotifier.buildSound(FORMAT, DesktopNotifier.SOUND_CLICK, 0.0);
        for (byte b : pcm) {
            if (b != 0) {
                throw new AssertionError("zero volume must be silent");
            }
        }
    }

    @Test
    public void louderVolumeHasBiggerPeak() {
        int quiet = peak(DesktopNotifier.buildSound(FORMAT, DesktopNotifier.SOUND_BEEP, 0.2));
        int loud = peak(DesktopNotifier.buildSound(FORMAT, DesktopNotifier.SOUND_BEEP, 1.0));
        assertTrue("louder volume raises the peak amplitude", loud > quiet);
    }

    private static int peak(byte[] pcm) {
        int max = 0;
        for (int i = 0; i + 1 < pcm.length; i += 2) {
            int sample = (short) ((pcm[i] & 0xFF) | (pcm[i + 1] << 8));
            max = Math.max(max, Math.abs(sample));
        }
        return max;
    }
}
