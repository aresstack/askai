package com.aresstack.askai.audio.preview;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.SourceDataLine;

/** Stream a complete preview through a SourceDataLine with one explicit buffer policy. */
final class SourceDataLinePlaybackStrategy implements JavaSoundPlaybackStrategy {

    private final Integer bufferMillis;

    private SourceDataLinePlaybackStrategy(Integer bufferMillis) {
        this.bufferMillis = bufferMillis;
    }

    static SourceDataLinePlaybackStrategy withBufferMillis(int bufferMillis) {
        if (bufferMillis <= 0) {
            throw new IllegalArgumentException("Buffer duration must be positive.");
        }
        return new SourceDataLinePlaybackStrategy(bufferMillis);
    }

    static SourceDataLinePlaybackStrategy withDefaultBuffer() {
        return new SourceDataLinePlaybackStrategy(null);
    }

    public String getName() {
        return bufferMillis == null ? "SourceDataLine (default buffer)"
                : "SourceDataLine (" + bufferMillis + " ms buffer)";
    }

    public JavaSoundPlaybackSession open(AudioOutputDevice device, PreparedAudio audio) throws Exception {
        AudioFormat format = audio.getFormat();
        DataLine.Info lineInfo = new DataLine.Info(SourceDataLine.class, format);
        Mixer mixer = device.getMixer();
        SourceDataLine line = (SourceDataLine) (mixer == null
                ? AudioSystem.getLine(lineInfo) : mixer.getLine(lineInfo));
        try {
            if (bufferMillis == null) {
                line.open(format);
            } else {
                line.open(format, alignedBufferSize(format, bufferMillis.intValue()));
            }
            return new SourceDataLineSession(line, audio);
        } catch (Exception ex) {
            closeQuietly(line);
            throw ex;
        }
    }

    private static int alignedBufferSize(AudioFormat format, int millis) {
        int frameSize = Math.max(1, format.getFrameSize());
        long frameCount = Math.max(1L, Math.round(format.getFrameRate() * millis / 1000.0));
        long byteCount = frameCount * frameSize;
        if (byteCount > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE - (Integer.MAX_VALUE % frameSize);
        }
        return (int) byteCount;
    }

    private static final class SourceDataLineSession implements JavaSoundPlaybackSession {
        private final SourceDataLine line;
        private final PreparedAudio audio;

        private SourceDataLineSession(SourceDataLine line, PreparedAudio audio) {
            this.line = line;
            this.audio = audio;
        }

        public PlaybackMetrics play(PlaybackCancellation cancellation) {
            line.start();
            int written = writeAll(cancellation, line, audio.getBytes(), audio.getFormat().getFrameSize());
            if (!cancellation.isCancelled()) {
                line.drain();
            }
            if (!cancellation.isCancelled() && written != audio.getBytes().length) {
                throw new IllegalStateException("The output line accepted " + written + " of "
                        + audio.getBytes().length + " bytes.");
            }
            return new PlaybackMetrics(line.getLongFramePosition(), written);
        }

        public void stop() {
            closeQuietly(line);
        }

        public void close() {
            closeQuietly(line);
        }
    }

    private static int writeAll(PlaybackCancellation cancellation, SourceDataLine line,
                                byte[] bytes, int frameSize) {
        int offset = 0;
        int alignedFrameSize = Math.max(1, frameSize);
        int chunkSize = alignDown(8192, alignedFrameSize);
        while (offset < bytes.length && !cancellation.isCancelled()) {
            int remaining = bytes.length - offset;
            int requested = alignDown(Math.min(chunkSize, remaining), alignedFrameSize);
            if (requested == 0) {
                requested = remaining;
            }
            int written = line.write(bytes, offset, requested);
            if (written <= 0) {
                throw new IllegalStateException("The output line stopped accepting audio data.");
            }
            offset += written;
        }
        return offset;
    }

    private static int alignDown(int value, int frameSize) {
        return value - (value % frameSize);
    }

    private static void closeQuietly(SourceDataLine line) {
        if (line == null) {
            return;
        }
        try {
            line.stop();
            line.flush();
        } catch (Exception ignored) {
            // Ignore cleanup failures.
        }
        try {
            line.close();
        } catch (Exception ignored) {
            // Ignore cleanup failures.
        }
    }
}
