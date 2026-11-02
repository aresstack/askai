package com.aresstack.askai.audio.preview;

/** Own one opened Java Sound line until playback completes or is stopped. */
interface JavaSoundPlaybackSession extends AutoCloseable {

    PlaybackMetrics play(PlaybackCancellation cancellation) throws Exception;

    void stop();

    void close();
}
