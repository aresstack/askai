package com.aresstack.askai.batch.service;

import com.aresstack.askai.batch.service.BatchAudioPreparationService.PreparedBatchAudio;
import com.aresstack.audio.application.DefaultAudioProcessingPreviewService;
import com.aresstack.audio.domain.PcmAudioFormat;
import com.aresstack.audio.infrastructure.WavFileAudioSink;
import com.aresstack.audio.infrastructure.WavFileReader;
import com.aresstack.audio.pipeline.AudioBlockRegistry;
import com.aresstack.audio.pipeline.AudioProcessingProfiles;
import com.aresstack.audio.profile.AudioBlockDefinition;
import com.aresstack.audio.profile.AudioBlockType;
import com.aresstack.audio.profile.AudioProcessingProfile;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * Batch preparation writes the STT transport WAV preserving the source format: "Off" and a format-neutral
 * DSP profile keep 48 kHz stereo as-is, and only an explicit resampler/channel block converts. Temp files
 * are cleaned up.
 */
public class BatchAudioPreparationServiceTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private final BatchAudioPreparationService service =
            new BatchAudioPreparationService(new DefaultAudioProcessingPreviewService());

    @Test
    public void offProfilePreservesTheSourceFormat() throws Exception {
        File source = writeWav("source48stereo.wav", new PcmAudioFormat(48000, 2, 16), stereoTone(48000));

        PreparedBatchAudio prepared = service.prepare(source, AudioProcessingProfiles.off());
        File temp = prepared.getFile();

        assertNotEquals("Off must not return the original file", source, temp);
        WavFileReader.WavData out = WavFileReader.read(temp);
        assertEquals("Off keeps the source rate — no forced 16 kHz", 48000, out.getFormat().getSampleRateHz());
        assertEquals("Off keeps the source channels — no forced mono", 2, out.getFormat().getChannels());

        prepared.close();
        assertFalse("temp STT file deleted on close", temp.exists());
        assertTrue("the user's original file is never touched", source.isFile());
    }

    @Test
    public void formatNeutralDspKeepsTheSourceFormat() throws Exception {
        File source = writeWav("stereo48.wav", new PcmAudioFormat(48000, 2, 16), stereoTone(48000));

        PreparedBatchAudio prepared = service.prepare(source, profile("gain", block(AudioBlockType.GAIN, "g")));
        WavFileReader.WavData out = WavFileReader.read(prepared.getFile());

        assertEquals("gain does not change the rate", 48000, out.getFormat().getSampleRateHz());
        assertEquals("gain does not change the channels", 2, out.getFormat().getChannels());
        prepared.close();
    }

    @Test
    public void anExplicitResamplerAndMixerProfileStillConverts() throws Exception {
        File source = writeWav("stereo44.wav", new PcmAudioFormat(44100, 2, 16), stereoTone(44100));
        AudioBlockDefinition mixer = block(AudioBlockType.CHANNEL_MIXER, "m");
        AudioBlockDefinition resampler = block(AudioBlockType.RESAMPLER, "r").withParameter("targetRateHz", "16000");

        PreparedBatchAudio prepared = service.prepare(source, profile("speech", mixer, resampler));
        WavFileReader.WavData out = WavFileReader.read(prepared.getFile());

        assertEquals("explicit resampler still converts", 16000, out.getFormat().getSampleRateHz());
        assertEquals("explicit mixer still down-mixes", 1, out.getFormat().getChannels());
        prepared.close();
    }

    @Test
    public void alreadySixteenKMonoSourcePassesThroughTheFormat() throws Exception {
        File source = writeWav("mono16.wav", new PcmAudioFormat(16000, 1, 16), monoTone(16000));

        PreparedBatchAudio prepared = service.prepare(source, AudioProcessingProfiles.off());
        WavFileReader.WavData out = WavFileReader.read(prepared.getFile());

        assertEquals(16000, out.getFormat().getSampleRateHz());
        assertEquals(1, out.getFormat().getChannels());
        prepared.close();
    }

    private File writeWav(String name, PcmAudioFormat format, short[] samples) throws Exception {
        File file = folder.newFile(name);
        WavFileAudioSink sink = new WavFileAudioSink(file);
        sink.open(format);
        sink.write(samples, samples.length);
        sink.close();
        return file;
    }

    private static AudioBlockDefinition block(AudioBlockType type, String id) {
        return AudioBlockRegistry.getInstance().defaultDefinition(type, id);
    }

    private static AudioProcessingProfile profile(String id, AudioBlockDefinition... blocks) {
        List<AudioBlockDefinition> list = new ArrayList<AudioBlockDefinition>();
        for (AudioBlockDefinition block : blocks) {
            list.add(block);
        }
        return new AudioProcessingProfile(id, id, false, list);
    }

    private static short[] monoTone(int frames) {
        short[] samples = new short[frames];
        for (int i = 0; i < frames; i++) {
            samples[i] = (short) Math.round(6000.0d * Math.sin(2.0d * Math.PI * 200.0d * i / 16000.0d));
        }
        return samples;
    }

    private static short[] stereoTone(int frames) {
        short[] samples = new short[frames * 2];
        for (int frame = 0; frame < frames; frame++) {
            short value = (short) Math.round(6000.0d * Math.sin(2.0d * Math.PI * 200.0d * frame / 16000.0d));
            samples[frame * 2] = value;
            samples[frame * 2 + 1] = (short) (value / 2);
        }
        return samples;
    }
}
