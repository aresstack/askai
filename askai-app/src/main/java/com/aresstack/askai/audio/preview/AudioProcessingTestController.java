package com.aresstack.askai.audio.preview;

import com.aresstack.audio.application.AudioProcessingPreviewService;
import com.aresstack.audio.application.ProcessedAudioPreview;
import com.aresstack.audio.application.ProcessedWaveExportService;
import com.aresstack.audio.domain.AudioBuffer;
import com.aresstack.audio.pipeline.AudioProfileValidationResult;
import com.aresstack.audio.pipeline.AudioProfileValidator;
import com.aresstack.audio.profile.AudioProcessingProfile;

import java.io.File;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * Coordinates the DSP test/preview workflow, free of Swing: pick a source, process the current (possibly
 * unsaved) pipeline snapshot through the productive preview service off the calling thread, play original
 * or processed audio, mark results outdated when the pipeline changes, and export the last good result.
 *
 * <p>A monotonically increasing generation id makes a new action supersede older ones: a completed run or
 * a pending playback callback whose generation is stale is ignored, so cancelling or starting a new action
 * can never apply an out-of-date result. Listener callbacks fire on the background/calling thread; the UI
 * listener marshals them onto the EDT.</p>
 */
public final class AudioProcessingTestController {

    public enum State {
        NO_SOURCE, READY, RECORDING, PROCESSING, PROCESSED,
        PLAYING_ORIGINAL, PLAYING_PROCESSED, RESULT_OUTDATED, CANCELLED, FAILED
    }

    public interface Listener {
        void stateChanged(State state);

        void previewUpdated(ProcessedAudioPreview preview, boolean outdated);

        void failed(String message);
    }

    private final AudioProcessingPreviewService previewService;
    private final AudioPreviewPlaybackService playback;
    private final ProcessedWaveExportService exportService;
    private final Supplier<AudioProcessingProfile> snapshotSupplier;
    private final Executor backgroundExecutor;
    private final Listener listener;
    private final AudioProfileValidator validator = new AudioProfileValidator();

    private AudioTestSource source;
    private ProcessedAudioPreview lastPreview;
    private boolean outdated;
    private State state = State.NO_SOURCE;
    private int generation;

    public AudioProcessingTestController(AudioProcessingPreviewService previewService,
                                        AudioPreviewPlaybackService playback,
                                        ProcessedWaveExportService exportService,
                                        Supplier<AudioProcessingProfile> snapshotSupplier,
                                        Executor backgroundExecutor, Listener listener) {
        this.previewService = previewService;
        this.playback = playback;
        this.exportService = exportService;
        this.snapshotSupplier = snapshotSupplier;
        this.backgroundExecutor = backgroundExecutor;
        this.listener = listener;
    }

    public State getState() {
        return state;
    }

    public AudioTestSource getSource() {
        return source;
    }

    public ProcessedAudioPreview getPreview() {
        return lastPreview;
    }

    public boolean isOutdated() {
        return outdated;
    }

    public void setSource(AudioTestSource newSource) {
        this.source = newSource;
        this.lastPreview = null;
        this.outdated = false;
        this.generation++; // drop any in-flight action tied to the previous source
        setState(newSource == null ? State.NO_SOURCE : State.READY);
    }

    /** Report a recording in progress so actions stay mutually exclusive and the status shows RECORDING. */
    public void noteRecording(boolean recording) {
        this.generation++;
        setState(recording ? State.RECORDING : idleState());
    }

    public void processAndPlay() {
        run(true);
    }

    public void process() {
        run(false);
    }

    private void run(final boolean playAfter) {
        if (source == null || state == State.RECORDING) {
            return;
        }
        final int gen = ++generation;
        final AudioProcessingProfile snapshot = snapshotSupplier.get(); // immutable snapshot captured now
        // Runtime guard: never process an invalid pipeline, even for non-Swing callers. Errors block; the
        // per-processor defensive bypass remains only as a last resort, not as a substitute for this check.
        AudioProfileValidationResult validation = validator.validateResult(snapshot);
        if (validation.hasErrors()) {
            setState(State.FAILED);
            listener.failed("Cannot process: fix " + validation.errorCount()
                    + " validation error(s) in the pipeline first.");
            return;
        }
        final AudioTestSource current = source;
        setState(State.PROCESSING);
        backgroundExecutor.execute(new Runnable() {
            public void run() {
                try {
                    AudioBuffer buffer = current.readBuffer();
                    ProcessedAudioPreview preview = previewService.process(buffer, snapshot, current.getId());
                    if (gen != generation) {
                        return; // superseded or cancelled
                    }
                    lastPreview = preview;
                    outdated = false;
                    listener.previewUpdated(preview, false);
                    setState(State.PROCESSED);
                    if (playAfter) {
                        startPlayback(gen, preview.getSamples(), preview.getFormat(), State.PLAYING_PROCESSED);
                    }
                } catch (Exception ex) {
                    if (gen != generation) {
                        return;
                    }
                    // Keep the last good preview on failure.
                    setState(State.FAILED);
                    listener.failed(describe(ex));
                }
            }
        });
    }

    public void playOriginal() {
        if (source == null || state == State.RECORDING) {
            return;
        }
        final int gen = ++generation;
        final AudioTestSource current = source;
        setState(State.PLAYING_ORIGINAL);
        backgroundExecutor.execute(new Runnable() {
            public void run() {
                try {
                    AudioBuffer buffer = current.readBuffer();
                    if (gen != generation) {
                        return;
                    }
                    startPlayback(gen, buffer.getSamples(), buffer.getFormat(), State.PLAYING_ORIGINAL);
                } catch (Exception ex) {
                    if (gen != generation) {
                        return;
                    }
                    setState(State.FAILED);
                    listener.failed(describe(ex));
                }
            }
        });
    }

    public void playProcessed() {
        if (lastPreview == null) {
            return;
        }
        int gen = ++generation;
        startPlayback(gen, lastPreview.getSamples(), lastPreview.getFormat(), State.PLAYING_PROCESSED);
    }

    private void startPlayback(final int gen, short[] samples, com.aresstack.audio.domain.PcmAudioFormat format,
                              State playingState) {
        setState(playingState);
        playback.play(samples, format, new Runnable() {
            public void run() {
                if (gen != generation) {
                    return;
                }
                setState(idleState());
            }
        });
    }

    /** Stop playback; while processing this also cancels the run (its result is dropped). */
    public void stop() {
        boolean wasProcessing = state == State.PROCESSING;
        generation++;
        playback.stop();
        setState(wasProcessing ? State.CANCELLED : idleState());
    }

    /** Mark the current preview as belonging to an older pipeline; it stays playable. */
    public void pipelineChanged() {
        if (lastPreview == null) {
            return;
        }
        outdated = true;
        listener.previewUpdated(lastPreview, true);
        if (state == State.PROCESSED) {
            setState(State.RESULT_OUTDATED);
        }
    }

    /** Export the last successful preview to {@code target} (overwrite confirmation is the UI's concern). */
    public void export(final File target) {
        final ProcessedAudioPreview preview = lastPreview;
        if (preview == null) {
            return;
        }
        backgroundExecutor.execute(new Runnable() {
            public void run() {
                try {
                    exportService.export(preview, target);
                } catch (Exception ex) {
                    listener.failed(describe(ex));
                }
            }
        });
    }

    private State idleState() {
        if (source == null) {
            return State.NO_SOURCE;
        }
        if (lastPreview != null) {
            return outdated ? State.RESULT_OUTDATED : State.PROCESSED;
        }
        return State.READY;
    }

    private void setState(State newState) {
        this.state = newState;
        listener.stateChanged(newState);
    }

    private static String describe(Exception ex) {
        return ex.getMessage() == null ? ex.toString() : ex.getMessage();
    }
}
