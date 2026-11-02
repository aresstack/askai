package com.aresstack.askai.audio.preview;

import com.aresstack.audio.domain.PcmAudioFormat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * Coordinate asynchronous Java Sound playback on exactly one explicitly selected output target.
 */
public final class JavaSoundAudioPreviewPlaybackService implements AudioPreviewPlaybackService {

    private final AudioPlaybackFormatPlanner formatPlanner;
    private final List<JavaSoundPlaybackStrategy> strategies;

    private Thread thread;
    private JavaSoundPlaybackSession activeSession;
    private long generation;
    private volatile AudioOutputDevice outputDevice = AudioOutputDevice.systemDefault();
    private volatile String unavailableOutputDeviceName;
    private volatile Consumer<String> errorHandler;
    private volatile Consumer<String> infoHandler;

    public JavaSoundAudioPreviewPlaybackService() {
        this(new AudioPlaybackFormatPlanner(), Arrays.<JavaSoundPlaybackStrategy>asList(
                new ClipPlaybackStrategy(),
                SourceDataLinePlaybackStrategy.withBufferMillis(100),
                SourceDataLinePlaybackStrategy.withBufferMillis(250),
                SourceDataLinePlaybackStrategy.withBufferMillis(500),
                SourceDataLinePlaybackStrategy.withDefaultBuffer()));
    }

    JavaSoundAudioPreviewPlaybackService(AudioPlaybackFormatPlanner formatPlanner,
                                         List<JavaSoundPlaybackStrategy> strategies) {
        if (formatPlanner == null) {
            throw new IllegalArgumentException("Format planner must not be null.");
        }
        if (strategies == null || strategies.isEmpty()) {
            throw new IllegalArgumentException("At least one playback strategy is required.");
        }
        this.formatPlanner = formatPlanner;
        this.strategies = Collections.unmodifiableList(
                new ArrayList<JavaSoundPlaybackStrategy>(strategies));
    }

    public void setOutputDevice(AudioOutputDevice device) {
        this.outputDevice = device == null ? AudioOutputDevice.systemDefault() : device;
        this.unavailableOutputDeviceName = null;
    }

    /** Keep the existing settings-panel contract while resolving one exact mixer without fallback. */
    public void setOutputDeviceName(String deviceName) {
        String requestedName = deviceName == null ? "" : deviceName.trim();
        if (requestedName.length() == 0) {
            setOutputDevice(AudioOutputDevice.systemDefault());
            return;
        }
        for (AudioOutputDevice device : new AudioOutputDeviceCatalog().findAll()) {
            if (!device.isSystemDefault()
                    && (requestedName.equals(device.getDisplayName())
                    || requestedName.equals(device.getMixerInfo().getName()))) {
                setOutputDevice(device);
                return;
            }
        }
        this.unavailableOutputDeviceName = requestedName;
    }

    /** Report playback errors to the UI; accept null to disable reporting. */
    public void setErrorHandler(Consumer<String> handler) {
        this.errorHandler = handler;
    }

    /** Report the exact device, backend, and format that completed playback. */
    public void setInfoHandler(Consumer<String> handler) {
        this.infoHandler = handler;
    }

    public synchronized void play(short[] samples, PcmAudioFormat format, final Runnable onFinished) {
        validate(samples, format);
        stopCurrentPlayback();
        final long playbackGeneration = ++generation;
        final AudioOutputDevice selectedDevice = outputDevice;
        final String unavailableDevice = unavailableOutputDeviceName;
        final short[] selectedSamples = samples;
        final PcmAudioFormat selectedFormat = format;
        Thread playbackThread = new Thread(new Runnable() {
            public void run() {
                if (unavailableDevice != null) {
                    reportError("Selected output \"" + unavailableDevice
                            + "\" is no longer available. No other device was used.");
                    clearThread(playbackGeneration);
                    return;
                }
                playPreparedAudio(playbackGeneration, selectedDevice, selectedSamples,
                        selectedFormat, onFinished);
            }
        }, "askai-audio-preview-playback");
        playbackThread.setDaemon(true);
        thread = playbackThread;
        playbackThread.start();
    }

    private void playPreparedAudio(final long playbackGeneration, AudioOutputDevice selectedDevice,
                                   short[] samples, PcmAudioFormat format, Runnable onFinished) {
        List<String> failures = new ArrayList<String>();
        PlaybackSuccess success = null;
        try {
            AudioPlaybackPlan plan = formatPlanner.createPlan(samples, format);
            failures.addAll(plan.getFailures());
            PlaybackCancellation cancellation = new PlaybackCancellation() {
                public boolean isCancelled() {
                    return !isCurrent(playbackGeneration);
                }
            };
            success = tryStrategies(playbackGeneration, selectedDevice, plan, cancellation, failures);
            if (!isCurrent(playbackGeneration)) {
                return;
            }
            if (success == null) {
                reportError(AudioPlaybackMessages.buildFailure(selectedDevice, format, failures));
                return;
            }
            if (onFinished != null) {
                onFinished.run();
            }
            reportInfo(success);
        } catch (Exception ex) {
            if (isCurrent(playbackGeneration)) {
                reportError(AudioPlaybackMessages.compact(ex));
            }
        } finally {
            clearThread(playbackGeneration);
        }
    }

    private PlaybackSuccess tryStrategies(long playbackGeneration, AudioOutputDevice selectedDevice,
                                          AudioPlaybackPlan plan, PlaybackCancellation cancellation,
                                          List<String> failures) {
        for (PreparedAudio candidate : plan.getCandidates()) {
            for (JavaSoundPlaybackStrategy strategy : strategies) {
                if (!isCurrent(playbackGeneration)) {
                    return null;
                }
                JavaSoundPlaybackSession session = null;
                try {
                    session = strategy.open(selectedDevice, candidate);
                    if (!activateSession(playbackGeneration, session)) {
                        return null;
                    }
                    PlaybackMetrics metrics = session.play(cancellation);
                    if (!isCurrent(playbackGeneration)) {
                        return null;
                    }
                    return new PlaybackSuccess(selectedDevice, strategy.getName(), candidate, metrics);
                } catch (Exception ex) {
                    failures.add(AudioPlaybackMessages.describeAttempt(
                            strategy, candidate.getFormat(), ex));
                } finally {
                    deactivateAndClose(playbackGeneration, session);
                }
            }
        }
        return null;
    }

    private synchronized boolean activateSession(long playbackGeneration,
                                                 JavaSoundPlaybackSession session) {
        if (playbackGeneration != generation || Thread.currentThread().isInterrupted()) {
            closeQuietly(session);
            return false;
        }
        activeSession = session;
        return true;
    }

    private synchronized void deactivateAndClose(long playbackGeneration,
                                                  JavaSoundPlaybackSession session) {
        if (playbackGeneration == generation && activeSession == session) {
            activeSession = null;
        }
        closeQuietly(session);
    }

    private synchronized boolean isCurrent(long playbackGeneration) {
        return playbackGeneration == generation && !Thread.currentThread().isInterrupted();
    }

    private synchronized void clearThread(long playbackGeneration) {
        if (playbackGeneration == generation && Thread.currentThread() == thread) {
            thread = null;
            activeSession = null;
        }
    }

    public synchronized void stop() {
        ++generation;
        stopCurrentPlayback();
    }

    private void stopCurrentPlayback() {
        JavaSoundPlaybackSession session = activeSession;
        activeSession = null;
        if (session != null) {
            stopQuietly(session);
        }
        Thread current = thread;
        thread = null;
        if (current != null && current != Thread.currentThread()) {
            current.interrupt();
        }
    }

    public synchronized boolean isPlaying() {
        return thread != null && thread.isAlive();
    }

    private void reportInfo(PlaybackSuccess success) {
        Consumer<String> handler = infoHandler;
        if (handler == null) {
            return;
        }
        handler.accept("Played on " + success.device.getDisplayName() + " via " + success.backend
                + " @ " + AudioPlaybackMessages.describe(success.audio.getFormat())
                + (success.audio.isConverted() ? " (converted)" : "")
                + "; accepted " + success.metrics.getByteCount() + " bytes, position "
                + success.metrics.getFramePosition() + " frames.");
    }

    private void reportError(String message) {
        Consumer<String> handler = errorHandler;
        if (handler != null) {
            handler.accept(message);
        }
    }

    private static void validate(short[] samples, PcmAudioFormat format) {
        if (samples == null) {
            throw new IllegalArgumentException("Samples must not be null.");
        }
        if (format == null) {
            throw new IllegalArgumentException("Format must not be null.");
        }
    }

    private static void stopQuietly(JavaSoundPlaybackSession session) {
        try {
            session.stop();
        } catch (Exception ignored) {
            // Ignore cleanup failures.
        }
    }

    private static void closeQuietly(JavaSoundPlaybackSession session) {
        if (session == null) {
            return;
        }
        try {
            session.close();
        } catch (Exception ignored) {
            // Ignore cleanup failures.
        }
    }

    private static final class PlaybackSuccess {
        private final AudioOutputDevice device;
        private final String backend;
        private final PreparedAudio audio;
        private final PlaybackMetrics metrics;

        private PlaybackSuccess(AudioOutputDevice device, String backend,
                                PreparedAudio audio, PlaybackMetrics metrics) {
            this.device = device;
            this.backend = backend;
            this.audio = audio;
            this.metrics = metrics;
        }
    }
}
