package com.aresstack.askai.audio.preview;

import com.aresstack.audio.domain.AudioBuffer;
import com.aresstack.audio.infrastructure.WavFileReader;

import java.io.File;
import java.io.IOException;

/**
 * A WAV-backed {@link AudioTestSource}. Decodes via the existing {@link WavFileReader} — no second decoder
 * path — and keeps the file's real channel count (a stereo/multichannel recording stays as recorded; the
 * downmix happens only inside the pipeline).
 */
public final class WavAudioTestSource implements AudioTestSource {

    private final File file;
    private final boolean recording;

    public WavAudioTestSource(File file, boolean recording) {
        if (file == null) {
            throw new IllegalArgumentException("File must not be null.");
        }
        this.file = file;
        this.recording = recording;
    }

    public String getId() {
        return file.getAbsolutePath() + "@" + file.length() + "@" + file.lastModified();
    }

    public String getDisplayName() {
        return file.getName();
    }

    public File getFile() {
        return file;
    }

    public boolean isRecording() {
        return recording;
    }

    public AudioBuffer readBuffer() throws IOException {
        WavFileReader.WavData data = WavFileReader.read(file);
        return new AudioBuffer(data.getSamples(), data.getFormat());
    }
}
