package com.aresstack.askai.audio.preview;

import com.aresstack.audio.domain.PcmAudioFormat;

import javax.sound.sampled.AudioFormat;
import java.util.List;

/** Format concise playback diagnostics for the status line and tooltip. */
final class AudioPlaybackMessages {

    private AudioPlaybackMessages() {
    }

    static String buildFailure(AudioOutputDevice device, PcmAudioFormat source, List<String> failures) {
        StringBuilder message = new StringBuilder();
        message.append("Selected output \"").append(device.getDisplayName())
                .append("\" could not play ").append(source.getSampleRateHz()).append(" Hz, ")
                .append(source.getChannels()).append(" ch, ").append(source.getBitsPerSample())
                .append(" bit. No other device was used.");
        int start = Math.max(0, failures.size() - 6);
        for (int i = start; i < failures.size(); i++) {
            message.append(" ").append(i - start + 1).append(") ").append(failures.get(i));
        }
        return message.toString();
    }

    static String describeAttempt(JavaSoundPlaybackStrategy strategy, AudioFormat format, Exception ex) {
        return strategy.getName() + " " + describe(format) + ": " + compact(ex);
    }

    static String compact(Throwable throwable) {
        String message = throwable.getMessage();
        return throwable.getClass().getSimpleName()
                + (message == null || message.trim().length() == 0 ? "" : " — " + message.trim());
    }

    static String describe(AudioFormat format) {
        return (int) format.getSampleRate() + " Hz, " + format.getChannels() + " ch, "
                + format.getSampleSizeInBits() + " bit";
    }
}
