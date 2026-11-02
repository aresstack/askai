package com.aresstack.askai.audio.preview;

import com.aresstack.audio.domain.PcmAudioFormat;

/**
 * Play raw or processed preview audio away from the Swing event-dispatch thread.
 */
public interface AudioPreviewPlaybackService {

    /**
     * Start the supplied 16-bit PCM samples and stop any previous playback first.
     * Run {@code onFinished} only after normal completion.
     */
    void play(short[] samples, PcmAudioFormat format, Runnable onFinished);

    /** Select the exact backend-neutral output device; use the system default when {@code device} is null. */
    void setOutputDevice(AudioOutputDevice device);

    void stop();

    boolean isPlaying();
}
