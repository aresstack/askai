package com.aresstack.askai.audio.preview;

import javax.sound.sampled.AudioFormat;

/** Hold one fully converted playback candidate. */
final class PreparedAudio {

    private final AudioFormat format;
    private final byte[] bytes;
    private final boolean converted;

    PreparedAudio(AudioFormat format, byte[] bytes, boolean converted) {
        this.format = format;
        this.bytes = bytes;
        this.converted = converted;
    }

    AudioFormat getFormat() {
        return format;
    }

    byte[] getBytes() {
        return bytes;
    }

    boolean isConverted() {
        return converted;
    }
}
