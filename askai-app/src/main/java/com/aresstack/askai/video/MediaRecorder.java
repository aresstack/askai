package com.aresstack.askai.video;

/**
 * Strategy port for a video recording engine (ported from the WD4J/corenth {@code MediaRecorder}): the UI
 * only ever talks to this abstraction, never to JCodec/VLC/FFmpeg classes. Backends implement it; the
 * application controller drives it. No Swing here.
 */
public interface MediaRecorder {

    /** Start recording with the given profile. Throws (structured) if it cannot start. */
    void start(RecordingProfile profile) throws Exception;

    /** Stop the current recording and finalize the file. No-op if not recording. */
    void stop();

    /** @return whether a recording is currently in progress. */
    boolean isRecording();
}
