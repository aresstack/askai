package com.aresstack.askai.audio.preview;

import com.aresstack.askai.audio.format.SupportedAudioFormats;
import com.aresstack.audio.domain.AudioBuffer;
import com.aresstack.audio.domain.PcmAudioFormat;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;

/**
 * Decode WAV, M4A/AAC, OGG and FLAC through the installed Java Sound providers, and MP3 through the JLayer
 * core (see {@link Mp3AudioFileDecoder} for why the Java Sound mp3 conversion is bypassed).
 *
 * <p>This separates the <b>container format</b> (the file: wav/mp3/m4a/ogg/flac) from the <b>internal
 * sample format</b> the DSP pipeline works on (signed 16-bit little-endian PCM). Only the sample encoding
 * is normalized: the original <b>sample rate</b> and <b>channel count</b> of the source are preserved as
 * they are. The decoder performs no downmix to mono and no resampling — any such change is an explicit DSP
 * block or a final transport step, never a side effect of decoding.</p>
 */
public final class JavaSoundAudioFileDecoder implements AudioFileDecoder {

    private static final int BUFFER_SIZE = 16384;

    private final Mp3AudioFileDecoder mp3Decoder = new Mp3AudioFileDecoder();

    public AudioBuffer decode(File file) throws IOException {
        requireSupportedFile(file);
        if ("mp3".equals(SupportedAudioFormats.extensionOf(file.getName()))) {
            // The mp3spi conversion yields 0 bytes for some ffmpeg/Lavf MP3s; decode via JLayer directly.
            return mp3Decoder.decode(file);
        }
        AudioInputStream source = null;
        AudioInputStream pcm = null;
        try {
            source = AudioSystem.getAudioInputStream(file);
            AudioFormat targetFormat = targetFormat(source.getFormat());
            pcm = AudioSystem.getAudioInputStream(targetFormat, source);
            return new AudioBuffer(toSamples(readAllBytes(pcm)), new PcmAudioFormat(
                    Math.round(targetFormat.getSampleRate()),
                    targetFormat.getChannels(),
                    targetFormat.getSampleSizeInBits()));
        } catch (UnsupportedAudioFileException ex) {
            throw new IOException("Unsupported or unreadable audio file: " + file.getName(), ex);
        } catch (IllegalArgumentException ex) {
            throw new IOException("No decoder is available for audio file: " + file.getName(), ex);
        } finally {
            closeQuietly(pcm);
            if (pcm != source) {
                closeQuietly(source);
            }
        }
    }

    /**
     * Build the decode target: 16-bit signed little-endian PCM at the source's own sample rate and channel
     * count. Only the sample encoding is fixed; rate and channels are carried over from the source.
     */
    private static AudioFormat targetFormat(AudioFormat sourceFormat) {
        float sampleRate = sourceFormat.getSampleRate();
        if (sampleRate <= 0.0f) {
            sampleRate = sourceFormat.getFrameRate();
        }
        int channels = sourceFormat.getChannels();
        if (sampleRate <= 0.0f || channels <= 0) {
            throw new IllegalArgumentException("Audio stream does not expose a usable PCM format.");
        }
        return new AudioFormat(AudioFormat.Encoding.PCM_SIGNED,
                sampleRate,
                16,
                channels,
                channels * 2,
                sampleRate,
                false);
    }

    private static byte[] readAllBytes(AudioInputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[BUFFER_SIZE];
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (read > 0) {
                output.write(buffer, 0, read);
            }
        }
        return output.toByteArray();
    }

    private static short[] toSamples(byte[] bytes) throws IOException {
        if ((bytes.length & 1) != 0) {
            throw new IOException("Decoded PCM data has an incomplete 16-bit sample.");
        }
        short[] samples = new short[bytes.length / 2];
        for (int index = 0; index < samples.length; index++) {
            int offset = index * 2;
            samples[index] = (short) ((bytes[offset] & 0xff) | (bytes[offset + 1] << 8));
        }
        return samples;
    }

    private static void requireSupportedFile(File file) {
        if (file == null) {
            throw new IllegalArgumentException("Audio file must not be null.");
        }
        if (!file.isFile()) {
            throw new IllegalArgumentException("Audio file does not exist: " + file);
        }
        if (!SupportedAudioFormats.supports(file)) {
            throw new IllegalArgumentException("Unsupported audio format: " + file.getName());
        }
    }

    private static void closeQuietly(AudioInputStream stream) {
        if (stream == null) {
            return;
        }
        try {
            stream.close();
        } catch (IOException ignored) {
            // Preserve the primary decoding result or failure.
        }
    }
}
