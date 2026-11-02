package com.aresstack.askai.speech;

import com.aresstack.audio.dsp.AudioLevelMeter;

import java.io.File;

/**
 * Port for starting/stopping a microphone recording. The adapter owns all Java Sound / DSP wiring
 * (format negotiation, capture, WAV writing, level metering); the dictation use case only sees this
 * interface, which keeps the flow testable with a fake.
 */
public interface MicrophoneRecorder {

    /**
     * Begin capturing from {@code deviceName} (empty = system default) into a fresh temp file under
     * {@code workingDirectory}. Returns a live session handle; capture continues until {@link
     * Session#stop()} or {@link Session#discard()}.
     */
    Session start(String deviceName, File workingDirectory) throws Exception;

    /** A running recording: read the live level, then finalize (stop) or throw it away (discard). */
    interface Session {

        /** @return the live level meter, updated while recording (for the UI level bar). */
        AudioLevelMeter getMeter();

        /** @return a description of the negotiated capture device/format (for diagnostics). */
        String getDeviceName();

        /** Stop capturing and finalize the WAV file. */
        RawRecording stop() throws Exception;

        /** Stop capturing and delete the temp file without producing a recording. */
        void discard();
    }
}
