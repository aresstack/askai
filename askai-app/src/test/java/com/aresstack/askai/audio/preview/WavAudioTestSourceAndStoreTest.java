package com.aresstack.askai.audio.preview;

import com.aresstack.audio.domain.AudioBuffer;
import com.aresstack.audio.domain.PcmAudioFormat;
import com.aresstack.audio.infrastructure.WavFileAudioSink;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** A WAV test source keeps the file's real channel count; the store persists confirmed recordings. */
public class WavAudioTestSourceAndStoreTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void stereoWavIsReadAsStereo() throws IOException {
        File wav = folder.newFile("stereo.wav");
        writeWav(wav, new short[]{1, -1, 2, -2, 3, -3, 4, -4}, new PcmAudioFormat(44100, 2, 16));

        AudioBuffer buffer = new WavAudioTestSource(wav, false).readBuffer();
        assertEquals(2, buffer.getFormat().getChannels());
        assertEquals(44100, buffer.getFormat().getSampleRateHz());
        assertEquals(8, buffer.getSamples().length);
    }

    @Test
    public void confirmedRecordingIsMovedIntoTheStoreCollisionFree() throws IOException {
        File dir = folder.newFolder("audio-tests");
        AudioTestRecordingStore store = new AudioTestRecordingStore(dir);

        File rawA = folder.newFile("raw-a.wav");
        writeWav(rawA, new short[]{5, 6, 7, 8}, new PcmAudioFormat(16000, 1, 16));
        File savedA = store.saveConfirmed(rawA, "office test");
        assertTrue(savedA.isFile());
        assertFalse("raw temp is consumed by the move", rawA.exists());
        assertEquals(dir, savedA.getParentFile());
        assertTrue(savedA.getName().endsWith(".wav"));

        File rawB = folder.newFile("raw-b.wav");
        writeWav(rawB, new short[]{9, 10}, new PcmAudioFormat(16000, 1, 16));
        File savedB = store.saveConfirmed(rawB, "office test");
        assertFalse("second save must not overwrite the first", savedA.getName().equals(savedB.getName()));
    }

    private static void writeWav(File file, short[] samples, PcmAudioFormat format) throws IOException {
        WavFileAudioSink sink = new WavFileAudioSink(file);
        sink.open(format);
        try {
            sink.write(samples, samples.length);
        } finally {
            sink.close();
        }
    }
}
