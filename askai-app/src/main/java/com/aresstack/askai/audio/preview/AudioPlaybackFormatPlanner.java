package com.aresstack.askai.audio.preview;

import com.aresstack.audio.domain.PcmAudioFormat;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/** Prepare the source PCM plus common stereo conversions for Java Sound playback. */
final class AudioPlaybackFormatPlanner {

    AudioPlaybackPlan createPlan(short[] samples, PcmAudioFormat pcmFormat) {
        AudioFormat sourceFormat = new AudioFormat(
                pcmFormat.getSampleRateHz(), 16, pcmFormat.getChannels(), true, false);
        byte[] sourceBytes = toLittleEndianBytes(samples);
        List<PreparedAudio> candidates = new ArrayList<PreparedAudio>();
        List<String> failures = new ArrayList<String>();
        for (AudioFormat target : candidateFormats(sourceFormat)) {
            try {
                candidates.add(new PreparedAudio(target, convert(sourceFormat, sourceBytes, target),
                        !sameFormat(sourceFormat, target)));
            } catch (Exception ex) {
                failures.add("conversion to " + AudioPlaybackMessages.describe(target) + ": "
                        + AudioPlaybackMessages.compact(ex));
            }
        }
        return new AudioPlaybackPlan(candidates, failures);
    }

    private static List<AudioFormat> candidateFormats(AudioFormat source) {
        List<AudioFormat> formats = new ArrayList<AudioFormat>();
        addDistinct(formats, source);
        addDistinct(formats, new AudioFormat(source.getSampleRate(), 16, 2, true, false));
        addDistinct(formats, new AudioFormat(48000f, 16, 2, true, false));
        addDistinct(formats, new AudioFormat(44100f, 16, 2, true, false));
        return formats;
    }

    private static void addDistinct(List<AudioFormat> formats, AudioFormat candidate) {
        for (AudioFormat existing : formats) {
            if (sameFormat(existing, candidate)) {
                return;
            }
        }
        formats.add(candidate);
    }

    private static byte[] convert(AudioFormat sourceFormat, byte[] sourceBytes, AudioFormat targetFormat)
            throws Exception {
        if (sameFormat(sourceFormat, targetFormat)) {
            return sourceBytes;
        }
        if (!AudioSystem.isConversionSupported(targetFormat, sourceFormat)) {
            throw new IllegalArgumentException("Java Sound does not support this conversion.");
        }
        AudioInputStream sourceStream = new AudioInputStream(new ByteArrayInputStream(sourceBytes),
                sourceFormat, sourceBytes.length / sourceFormat.getFrameSize());
        AudioInputStream convertedStream = null;
        try {
            convertedStream = AudioSystem.getAudioInputStream(targetFormat, sourceStream);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = convertedStream.read(buffer)) != -1) {
                if (read > 0) {
                    output.write(buffer, 0, read);
                }
            }
            return output.toByteArray();
        } finally {
            closeQuietly(convertedStream);
            closeQuietly(sourceStream);
        }
    }

    private static boolean sameFormat(AudioFormat left, AudioFormat right) {
        return left.getEncoding().equals(right.getEncoding())
                && Float.compare(left.getSampleRate(), right.getSampleRate()) == 0
                && left.getSampleSizeInBits() == right.getSampleSizeInBits()
                && left.getChannels() == right.getChannels()
                && left.getFrameSize() == right.getFrameSize()
                && Float.compare(left.getFrameRate(), right.getFrameRate()) == 0
                && left.isBigEndian() == right.isBigEndian();
    }

    private static byte[] toLittleEndianBytes(short[] samples) {
        byte[] bytes = new byte[samples.length * 2];
        for (int i = 0; i < samples.length; i++) {
            bytes[i * 2] = (byte) (samples[i] & 0xFF);
            bytes[i * 2 + 1] = (byte) ((samples[i] >> 8) & 0xFF);
        }
        return bytes;
    }

    private static void closeQuietly(AudioInputStream stream) {
        if (stream == null) {
            return;
        }
        try {
            stream.close();
        } catch (Exception ignored) {
            // Ignore cleanup failures.
        }
    }
}
