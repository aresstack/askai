package com.aresstack.askai.audio.preview;

import com.aresstack.audio.domain.PcmAudioFormat;
import com.aresstack.audio.openal.OpenAlAudioBackend;
import com.aresstack.audio.openal.OpenAlCancellation;
import com.aresstack.audio.openal.OpenAlException;
import com.aresstack.audio.openal.OpenAlPlayback;
import com.aresstack.audio.openal.OpenAlPlaybackResult;

import java.util.function.Consumer;

/**
 * Asynchronous playback through OpenAL Soft (WASAPI on Windows) on exactly the selected endpoint. There is
 * no fallback to any other physical device: if the chosen device cannot be opened, playback fails and the
 * failure — including the failing phase and the raw {@code alcGetError}/{@code alGetError} codes — is
 * reported. Success reports the backend, opened specifier, sample format and played duration.
 */
public final class OpenAlAudioPreviewPlaybackService implements AudioPreviewPlaybackService {

    private final OpenAlAudioBackend openAl;

    private Thread thread;
    private long generation;
    private volatile AudioOutputDevice device;
    private volatile Consumer<String> errorHandler;
    private volatile Consumer<String> infoHandler;

    public OpenAlAudioPreviewPlaybackService() {
        this(new OpenAlPlayback());
    }

    OpenAlAudioPreviewPlaybackService(OpenAlAudioBackend openAl) {
        this.openAl = openAl;
    }

    public void setErrorHandler(Consumer<String> handler) {
        this.errorHandler = handler;
    }

    public void setInfoHandler(Consumer<String> handler) {
        this.infoHandler = handler;
    }

    public void setOutputDevice(AudioOutputDevice device) {
        this.device = device;
    }

    public synchronized void play(short[] samples, PcmAudioFormat format, final Runnable onFinished) {
        if (samples == null || format == null) {
            throw new IllegalArgumentException("Samples and format must not be null.");
        }
        stop();
        final long playbackGeneration = ++generation;
        final AudioOutputDevice target = device;
        final short[] pcm = samples;
        final int channels = format.getChannels();
        final int sampleRate = format.getSampleRateHz();
        Thread playbackThread = new Thread(new Runnable() {
            public void run() {
                playInternal(playbackGeneration, target, pcm, channels, sampleRate, onFinished);
            }
        }, "askai-openal-playback");
        playbackThread.setDaemon(true);
        thread = playbackThread;
        playbackThread.start();
    }

    private void playInternal(final long playbackGeneration, AudioOutputDevice target, short[] pcm,
                              int channels, int sampleRate, Runnable onFinished) {
        try {
            if (target == null || !target.isOpenAl()) {
                reportError("No OpenAL output device selected. No other device was used.");
                return;
            }
            OpenAlNativeSupport.configureLibraryPath();
            OpenAlCancellation cancellation = new OpenAlCancellation() {
                public boolean isCancelled() {
                    return playbackGeneration != generation || Thread.currentThread().isInterrupted();
                }
            };
            OpenAlPlaybackResult result = openAl.play(pcm, channels, sampleRate,
                    target.getOpenAlSpecifier(), cancellation);
            if (playbackGeneration != generation) {
                return;
            }
            reportInfo(result); // report the opened device/format before firing completion
            if (!result.isCancelled() && onFinished != null) {
                onFinished.run();
            }
        } catch (OpenAlException ex) {
            if (playbackGeneration == generation) {
                reportError(ex.getMessage());
            }
        } catch (Exception ex) {
            if (playbackGeneration == generation) {
                reportError(ex.getMessage() == null ? ex.toString() : ex.getMessage());
            }
        } finally {
            clearThread(playbackGeneration);
        }
    }

    public synchronized void stop() {
        ++generation;
        Thread current = thread;
        thread = null;
        if (current != null && current != Thread.currentThread()) {
            current.interrupt();
        }
    }

    public synchronized boolean isPlaying() {
        return thread != null && thread.isAlive();
    }

    private synchronized void clearThread(long playbackGeneration) {
        if (playbackGeneration == generation && Thread.currentThread() == thread) {
            thread = null;
        }
    }

    private void reportInfo(OpenAlPlaybackResult result) {
        Consumer<String> handler = infoHandler;
        if (handler != null) {
            handler.accept("Played via " + result.describe());
        }
    }

    private void reportError(String message) {
        Consumer<String> handler = errorHandler;
        if (handler != null) {
            handler.accept(message);
        }
    }
}
