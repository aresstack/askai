package com.aresstack.askai.audio.preview;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Mixer;

/** Play a complete in-memory preview through a Java Sound Clip. */
final class ClipPlaybackStrategy implements JavaSoundPlaybackStrategy {

    public String getName() {
        return "Clip";
    }

    public JavaSoundPlaybackSession open(AudioOutputDevice device, PreparedAudio audio) throws Exception {
        AudioFormat format = audio.getFormat();
        DataLine.Info lineInfo = new DataLine.Info(Clip.class, format);
        Mixer mixer = device.getMixer();
        Clip clip = (Clip) (mixer == null ? AudioSystem.getLine(lineInfo) : mixer.getLine(lineInfo));
        try {
            clip.open(format, audio.getBytes(), 0, audio.getBytes().length);
            return new ClipSession(clip, audio);
        } catch (Exception ex) {
            closeQuietly(clip);
            throw ex;
        }
    }

    private static final class ClipSession implements JavaSoundPlaybackSession {
        private final Clip clip;
        private final PreparedAudio audio;

        private ClipSession(Clip clip, PreparedAudio audio) {
            this.clip = clip;
            this.audio = audio;
        }

        public PlaybackMetrics play(PlaybackCancellation cancellation) throws Exception {
            clip.setFramePosition(0);
            clip.start();
            long audioMillis = durationMillis(audio);
            long deadline = System.currentTimeMillis() + audioMillis + 3000L;
            while (!cancellation.isCancelled() && clip.getLongFramePosition() < clip.getFrameLength()) {
                if (System.currentTimeMillis() > deadline) {
                    throw new IllegalStateException("Clip playback did not reach the end within "
                            + (audioMillis + 3000L) + " ms.");
                }
                Thread.sleep(10L);
            }
            return new PlaybackMetrics(clip.getLongFramePosition(), audio.getBytes().length);
        }

        public void stop() {
            closeQuietly(clip);
        }

        public void close() {
            closeQuietly(clip);
        }
    }

    private static long durationMillis(PreparedAudio audio) {
        AudioFormat format = audio.getFormat();
        return Math.max(1L, Math.round(audio.getBytes().length * 1000.0
                / (format.getFrameSize() * format.getFrameRate())));
    }

    private static void closeQuietly(Clip clip) {
        if (clip == null) {
            return;
        }
        try {
            clip.stop();
        } catch (Exception ignored) {
            // Ignore cleanup failures.
        }
        try {
            clip.close();
        } catch (Exception ignored) {
            // Ignore cleanup failures.
        }
    }
}
