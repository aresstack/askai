package com.aresstack.askai.audio.preview;

import com.aresstack.audio.domain.AudioBuffer;
import com.aresstack.audio.domain.PcmAudioFormat;

import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.BitstreamException;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.DecoderException;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.SampleBuffer;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Decode MP3 with the JLayer core decoder directly, instead of Java Sound's mp3 SPI conversion stream.
 *
 * <p><b>Why not {@code AudioSystem}?</b> Some MP3s — notably ffmpeg/Lavf-encoded Common Voice clips
 * (MPEG-1 Layer III, 32&nbsp;kHz mono) — decode to <b>zero PCM bytes</b> through the mp3spi
 * {@code DecodedMpegAudioInputStream} conversion, even though the JLayer core decodes every frame
 * correctly (its {@code FormatConversionProvider} registers the generic {@code MP3} encoding while the
 * reader reports {@code MPEG1L3}, and the async conversion then yields nothing). This decoder bypasses that
 * broken conversion layer and reads frames straight from JLayer.</p>
 *
 * <p>It preserves the source <b>sample rate</b> and <b>channel count</b>; output is interleaved signed
 * 16-bit PCM. A single corrupt frame is skipped rather than aborting the whole file.</p>
 */
public final class Mp3AudioFileDecoder implements AudioFileDecoder {

    public AudioBuffer decode(File file) throws IOException {
        if (file == null || !file.isFile()) {
            throw new IOException("MP3 file does not exist: " + file);
        }
        InputStream input = new BufferedInputStream(new FileInputStream(file));
        Bitstream bitstream = new Bitstream(input);
        Decoder decoder = new Decoder();
        List<short[]> chunks = new ArrayList<short[]>();
        int total = 0;
        int sampleRate = -1;
        int channels = -1;
        try {
            Header header;
            while ((header = readFrame(bitstream, file)) != null) {
                try {
                    SampleBuffer output = (SampleBuffer) decoder.decodeFrame(header, bitstream);
                    if (sampleRate < 0) {
                        sampleRate = decoder.getOutputFrequency();
                        channels = decoder.getOutputChannels();
                    }
                    int length = output.getBufferLength();
                    if (length > 0) {
                        short[] copy = new short[length];
                        System.arraycopy(output.getBuffer(), 0, copy, 0, length);
                        chunks.add(copy);
                        total += length;
                    }
                } catch (DecoderException ex) {
                    // Skip a single corrupt frame rather than dropping the whole recording.
                } finally {
                    bitstream.closeFrame();
                }
            }
        } finally {
            closeQuietly(bitstream);
        }
        if (total == 0 || sampleRate <= 0 || channels <= 0) {
            throw new IOException("No decodable MP3 audio frames in: " + file.getName());
        }
        return new AudioBuffer(flatten(chunks, total), new PcmAudioFormat(sampleRate, channels, 16));
    }

    private static short[] flatten(List<short[]> chunks, int total) {
        short[] samples = new short[total];
        int offset = 0;
        for (short[] chunk : chunks) {
            System.arraycopy(chunk, 0, samples, offset, chunk.length);
            offset += chunk.length;
        }
        return samples;
    }

    private static Header readFrame(Bitstream bitstream, File file) throws IOException {
        try {
            return bitstream.readFrame();
        } catch (BitstreamException ex) {
            throw new IOException("Failed to read MP3 frame from " + file.getName(), ex);
        }
    }

    private static void closeQuietly(Bitstream bitstream) {
        try {
            bitstream.close(); // also closes the underlying stream
        } catch (BitstreamException ignored) {
            // nothing more we can do while cleaning up
        }
    }
}
