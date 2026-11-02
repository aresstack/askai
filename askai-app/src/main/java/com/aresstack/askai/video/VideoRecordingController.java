package com.aresstack.askai.video;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * The application controller between the Swing dialog and the {@link MediaRecorder} backends. It owns the
 * IDLE/RECORDING state and the backend SELECTION, drives start/stop off the EDT on a daemon executor, and
 * reports state + structured errors back through a {@link Listener} (the UI marshals to the EDT). It knows
 * nothing about Swing, JCodec, VLC or FFmpeg — only the neutral ports. There is NO silent fallback: a
 * backend is used exactly as selected, or the operation fails.
 */
public final class VideoRecordingController {

    public enum State { IDLE, RECORDING }

    /** UI callback surface; invoked on the controller's worker thread (the UI re-posts to the EDT). */
    public interface Listener {
        void onStateChanged(State state);

        void onRecordingStarted(RecordingProfile profile);

        void onRecordingStopped(Path outputFile);

        void onError(String message);
    }

    private final List<MediaRecorderProvider> providers;
    private final ExecutorService executor;

    private volatile State state = State.IDLE;
    private MediaRecorderProvider selected;
    private MediaRecorder activeRecorder;
    private RecordingProfile activeProfile;
    private Listener listener;

    public VideoRecordingController(List<MediaRecorderProvider> providers) {
        if (providers == null || providers.isEmpty()) {
            throw new IllegalArgumentException("at least one recorder provider is required");
        }
        this.providers = Collections.unmodifiableList(new ArrayList<MediaRecorderProvider>(providers));
        this.executor = Executors.newSingleThreadExecutor(new ThreadFactory() {
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "video-recording-controller");
                t.setDaemon(true);
                return t;
            }
        });
        this.selected = firstAvailableOrFirst();
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /** All backends, in registration order (JCodec first). */
    public List<MediaRecorderProvider> providers() {
        return providers;
    }

    /** Only the backends whose runtime is present right now — the UI offers exactly these. */
    public List<MediaRecorderProvider> availableProviders() {
        List<MediaRecorderProvider> available = new ArrayList<MediaRecorderProvider>();
        for (MediaRecorderProvider provider : providers) {
            if (provider.isAvailable()) {
                available.add(provider);
            }
        }
        return available;
    }

    public MediaRecorderProvider getSelectedProvider() {
        return selected;
    }

    public State getState() {
        return state;
    }

    public boolean isRecording() {
        return state == State.RECORDING;
    }

    /** Select a backend by id — rejected while recording (no backend switch mid-recording). */
    public void selectProvider(String providerId) {
        if (state == State.RECORDING) {
            error("The backend cannot be changed while recording.");
            return;
        }
        for (MediaRecorderProvider provider : providers) {
            if (provider.getId().equals(providerId)) {
                if (!provider.isAvailable()) {
                    error("The '" + provider.getDisplayName() + "' backend is not available.");
                    return;
                }
                selected = provider;
                return;
            }
        }
        error("Unknown recording backend: " + providerId);
    }

    /** Start recording with the selected backend. Rejected if already recording. */
    public void start(final RecordingProfile profile) {
        if (state == State.RECORDING) {
            error("A recording is already in progress.");
            return;
        }
        if (profile == null) {
            error("No recording profile.");
            return;
        }
        final MediaRecorderProvider provider = selected;
        if (provider == null || !provider.isAvailable()) {
            error("The selected recording backend is not available.");
            return;
        }
        executor.execute(new Runnable() {
            public void run() {
                MediaRecorder recorder = provider.createRecorder();
                try {
                    recorder.start(profile);
                } catch (Exception ex) {
                    // A failed start leaves the controller cleanly IDLE — never a half-recording state.
                    activeRecorder = null;
                    activeProfile = null;
                    setState(State.IDLE);
                    error("Recording could not be started: " + messageOf(ex));
                    return;
                }
                activeRecorder = recorder;
                activeProfile = profile;
                setState(State.RECORDING);
                if (listener != null) {
                    listener.onRecordingStarted(profile);
                }
            }
        });
    }

    /** Stop the current recording and finalize the file; returns the controller to IDLE. */
    public void stop() {
        if (state != State.RECORDING) {
            return;
        }
        executor.execute(new Runnable() {
            public void run() {
                MediaRecorder recorder = activeRecorder;
                RecordingProfile profile = activeProfile;
                activeRecorder = null;
                activeProfile = null;
                Exception failure = null;
                if (recorder != null) {
                    try {
                        recorder.stop();
                    } catch (Exception ex) {
                        failure = ex;
                    }
                }
                setState(State.IDLE);
                if (failure != null) {
                    error("Recording stopped with an error: " + messageOf(failure));
                } else if (listener != null && profile != null) {
                    listener.onRecordingStopped(profile.getOutputFile());
                }
            }
        });
    }

    /** Best-effort synchronous stop for application shutdown — a recording must not corrupt the file. */
    public void stopIfRecordingForShutdown() {
        MediaRecorder recorder = activeRecorder;
        if (recorder != null && recorder.isRecording()) {
            try {
                recorder.stop();
            } catch (Exception ignored) {
                // best effort on shutdown
            }
        }
        activeRecorder = null;
        activeProfile = null;
        state = State.IDLE;
        executor.shutdownNow();
    }

    // ------------------------------------------------------------------ internals

    private MediaRecorderProvider firstAvailableOrFirst() {
        for (MediaRecorderProvider provider : providers) {
            if (provider.isAvailable()) {
                return provider;
            }
        }
        return providers.get(0);
    }

    private void setState(State next) {
        this.state = next;
        if (listener != null) {
            listener.onStateChanged(next);
        }
    }

    private void error(String message) {
        if (listener != null) {
            listener.onError(message);
        }
    }

    private static String messageOf(Exception ex) {
        return ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
    }
}
