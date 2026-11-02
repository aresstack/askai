package com.aresstack.askai.speech;

import com.aresstack.audio.domain.PcmAudioFormat;

import java.io.File;

/** The raw recording a {@link MicrophoneRecorder} produced, before normalization. */
public final class RawRecording {

    private final File file;
    private final PcmAudioFormat captureFormat;
    private final long droppedFrames;
    private final String deviceName;

    public RawRecording(File file, PcmAudioFormat captureFormat, long droppedFrames, String deviceName) {
        this.file = file;
        this.captureFormat = captureFormat;
        this.droppedFrames = droppedFrames;
        this.deviceName = deviceName == null ? "" : deviceName;
    }

    public File getFile() {
        return file;
    }

    public PcmAudioFormat getCaptureFormat() {
        return captureFormat;
    }

    public long getDroppedFrames() {
        return droppedFrames;
    }

    /** @return the human-readable capture device, or "" for the system default. */
    public String getDeviceName() {
        return deviceName;
    }
}
