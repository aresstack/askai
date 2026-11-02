package com.aresstack.askai.audio.preview;

import com.aresstack.audio.domain.PcmAudioFormat;

/** The shared audible probes for output-device tests (settings row + DSP test panel). */
public final class TestTones {

    /** The beep's PCM format: 44.1 kHz mono 16-bit. */
    public static final PcmAudioFormat BEEP_FORMAT = new PcmAudioFormat(44100, 1, 16);

    private TestTones() {
    }

    /** A ~350 ms 880 Hz sine "bing" with a short fade-in and exponential decay. */
    public static short[] beep() {
        int rate = 44100;
        int length = rate * 350 / 1000;
        double frequency = 880.0;
        short[] samples = new short[length];
        for (int i = 0; i < length; i++) {
            double t = (double) i / rate;
            double envelope = Math.min(1.0, i / (rate * 0.01)) * Math.exp(-3.5 * t);
            double value = Math.sin(2.0 * Math.PI * frequency * t) * envelope * 0.6;
            samples[i] = (short) Math.max(Short.MIN_VALUE,
                    Math.min(Short.MAX_VALUE, value * Short.MAX_VALUE));
        }
        return samples;
    }
}
