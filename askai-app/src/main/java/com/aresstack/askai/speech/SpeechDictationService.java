package com.aresstack.askai.speech;

import com.aresstack.audio.application.NormalizationResult;
import com.aresstack.audio.application.RecordingQuality;
import com.aresstack.audio.application.RecordingQualityAnalyzer;
import com.aresstack.audio.dsp.AudioLevelMeter;

import java.io.File;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Swing-free coordinator for a single dictation: open microphone → record → stop → finalize →
 * normalize → check quality → verify audio model → transcribe → return text (or a structured failure).
 * Owns the {@link DictationState}, guarantees exactly one terminal callback per operation, keeps the
 * temporary recording after a failure so it can be retried or saved, and honours cancellation between
 * every step (not only during the HTTP call). All work runs on an injected {@link Executor} (a
 * synchronous one in tests), so the flow is deterministically testable.
 *
 * <p>Operations are tagged with a monotonic generation id: a late result from a cancelled or superseded
 * operation is dropped instead of being delivered. Retry/Save availability is exposed through
 * {@link #hasRetryableRecording()} / {@link #hasSavableRecording()} — the UI must use those rather than
 * inferring from a state/error flag, because a finalize failure or a cancel-while-recording leaves no
 * usable file.</p>
 */
public final class SpeechDictationService {

    private final Executor executor;
    private final MicrophoneRecorder recorder;
    private final RecordingNormalizer normalizer;
    private final AudioModelResolver modelResolver;
    private final SpeechTranscriber transcriber;
    private final RecordingQualityAnalyzer qualityAnalyzer;
    private final File workingDirectory;
    private final ServerProbe serverProbe; // optional, for the version in diagnostics
    private final DictationListener listener;

    private final AtomicLong tempCounter = new AtomicLong();

    // Guarded by this:
    private DictationState state = DictationState.IDLE;
    private MicrophoneRecorder.Session session;
    private RawRecording lastRaw;
    private File lastNormalized;
    private NormalizationResult lastNormalization;
    private String requestedModel = "";
    private String language = "";
    private String prompt = "";
    private long generation;
    private boolean terminalDelivered;
    private boolean cancelRequested;

    public SpeechDictationService(Executor executor, MicrophoneRecorder recorder,
                                  RecordingNormalizer normalizer, AudioModelResolver modelResolver,
                                  SpeechTranscriber transcriber, RecordingQualityAnalyzer qualityAnalyzer,
                                  File workingDirectory, ServerProbe serverProbe, DictationListener listener) {
        this.executor = executor;
        this.recorder = recorder;
        this.normalizer = normalizer;
        this.modelResolver = modelResolver;
        this.transcriber = transcriber;
        this.qualityAnalyzer = qualityAnalyzer == null ? RecordingQualityAnalyzer.withDefaults() : qualityAnalyzer;
        this.workingDirectory = workingDirectory;
        this.serverProbe = serverProbe;
        this.listener = listener;
    }

    public synchronized DictationState getState() {
        return state;
    }

    /** @return the live level meter of the active recording, or {@code null} when not recording. */
    public synchronized AudioLevelMeter getActiveMeter() {
        return session == null ? null : session.getMeter();
    }

    /** @return true when a normalized recording exists that a Retry can re-transcribe. */
    public synchronized boolean hasRetryableRecording() {
        return lastNormalized != null && lastNormalized.isFile();
    }

    /** @return true when a raw recording file exists that the user can Save. */
    public synchronized boolean hasSavableRecording() {
        return lastRaw != null && lastRaw.getFile() != null && lastRaw.getFile().isFile();
    }

    // ------------------------------------------------------------------ user actions

    /** Begin a recording from {@code deviceName} (empty = system default). */
    public void startRecording(final String deviceName) {
        final long op;
        synchronized (this) {
            if (!state.canStartRecording()) {
                return;
            }
            op = beginOperation();
            deleteTempsLocked(); // a fresh recording supersedes any kept one
            transition(op, DictationState.OPENING_MICROPHONE);
        }
        executor.execute(new Runnable() {
            public void run() {
                MicrophoneRecorder.Session opened;
                try {
                    opened = recorder.start(deviceName, workingDirectory);
                } catch (Exception ex) {
                    fail(op, DictationErrorKind.MICROPHONE_OPEN_FAILED, message(ex));
                    return;
                }
                boolean discard;
                synchronized (SpeechDictationService.this) {
                    discard = cancelRequested || op != generation;
                    if (!discard) {
                        session = opened;
                        transition(op, DictationState.RECORDING);
                    }
                }
                if (discard) {
                    opened.discard();
                    deliverCancelled(op);
                }
            }
        });
    }

    /** Stop the recording and run the full transcription flow for the given model/language/prompt. */
    public void stopAndTranscribe(String requestedModel, String language, String prompt) {
        final MicrophoneRecorder.Session active;
        final long op;
        synchronized (this) {
            if (state != DictationState.RECORDING || session == null) {
                return;
            }
            this.requestedModel = requestedModel == null ? "" : requestedModel;
            this.language = language == null ? "" : language;
            this.prompt = prompt == null ? "" : prompt;
            active = session;
            session = null;
            op = generation;
            transition(op, DictationState.FINALIZING_RECORDING);
        }
        executor.execute(new Runnable() {
            public void run() {
                RawRecording raw;
                try {
                    raw = active.stop();
                } catch (Exception ex) {
                    // The recorder cleans up its own temp on finalize failure; nothing usable remains.
                    fail(op, DictationErrorKind.FINALIZE_FAILED, message(ex));
                    return;
                }
                synchronized (SpeechDictationService.this) {
                    lastRaw = raw;
                }
                if (abortIfCancelled(op)) {
                    return;
                }
                if (!normalizeAndCheckQuality(op, raw)) {
                    return;
                }
                transcribeNormalized(op);
            }
        });
    }

    /** Re-run the transcription using the same (already recorded and normalized) audio. */
    public void retryTranscription() {
        final long op;
        synchronized (this) {
            if (lastNormalized == null || !lastNormalized.isFile()) {
                return;
            }
            op = beginOperation();
        }
        executor.execute(new Runnable() {
            public void run() {
                transcribeNormalized(op);
            }
        });
    }

    /** Cancel the current recording or transcription; a kept recording survives for retry. */
    public void cancel() {
        MicrophoneRecorder.Session toDiscard = null;
        final long op;
        synchronized (this) {
            cancelRequested = true;
            op = generation;
            if (state == DictationState.RECORDING && session != null) {
                toDiscard = session;
                session = null;
            }
        }
        if (toDiscard != null) {
            final MicrophoneRecorder.Session s = toDiscard;
            executor.execute(new Runnable() {
                public void run() {
                    s.discard();
                }
            });
            deliverCancelled(op);
        } else {
            // Transcription may be in flight: abort it. The worker's per-step cancel checks (and the
            // transcribe call surfacing CANCELLED) deliver the terminal CANCELLED.
            transcriber.cancel();
        }
    }

    /** Throw away any recording and reset to idle (Discard recording). */
    public void discard() {
        MicrophoneRecorder.Session toDiscard = null;
        synchronized (this) {
            cancelRequested = true;
            generation++; // supersede any in-flight worker
            terminalDelivered = true;
            if (session != null) {
                toDiscard = session;
                session = null;
            }
            deleteTempsLocked();
            state = DictationState.IDLE;
        }
        emitState(DictationState.IDLE);
        if (toDiscard != null) {
            final MicrophoneRecorder.Session s = toDiscard;
            executor.execute(new Runnable() {
                public void run() {
                    s.discard();
                }
            });
        }
    }

    /** @return the raw recording file for Save recording, or {@code null} when none exists. */
    public synchronized File savedRecordingSource() {
        return hasSavableRecording() ? lastRaw.getFile() : null;
    }

    // ------------------------------------------------------------------ flow steps

    private boolean normalizeAndCheckQuality(long op, RawRecording raw) {
        File target = new File(workingDirectory,
                "askai-speech-norm-" + System.nanoTime() + "-" + tempCounter.incrementAndGet() + ".wav");
        NormalizationResult norm;
        try {
            norm = normalizer.normalize(raw.getFile(), target);
        } catch (Exception ex) {
            fail(op, DictationErrorKind.NORMALIZE_FAILED, message(ex));
            return false;
        }
        synchronized (this) {
            lastNormalized = target;
            lastNormalization = norm;
        }
        if (abortIfCancelled(op)) {
            return false;
        }
        RecordingQuality quality = qualityAnalyzer.analyze(norm.getDurationMillis(), norm.getOverallRms(),
                norm.getPeak(), norm.getClippedSamples(), norm.getTotalSamples(), raw.getDroppedFrames());
        if (quality == RecordingQuality.TOO_SHORT) {
            fail(op, DictationErrorKind.QUALITY_TOO_SHORT, "Recording too short.");
            return false;
        }
        if (quality == RecordingQuality.NO_SIGNAL) {
            fail(op, DictationErrorKind.QUALITY_NO_SIGNAL, "No speech signal detected.");
            return false;
        }
        return true; // VALID / CLIPPED / DROPPED_FRAMES may proceed (clipping is a warning)
    }

    private void transcribeNormalized(long op) {
        transition(op, DictationState.VERIFYING_MODEL);
        AudioModelResolver.AudioModelResolution resolution = modelResolver.resolve(requestedModel);
        if (abortIfCancelled(op)) {
            return;
        }
        if (!resolution.isResolved()) {
            fail(op, mapResolution(resolution.getStatus()), "Audio model not confirmed: " + resolution.getStatus());
            return;
        }
        String model = resolution.getModelName();

        transition(op, DictationState.TRANSCRIBING);
        long startedAt = System.currentTimeMillis();
        String text;
        try {
            text = transcriber.transcribe(new SpeechTranscriber.TranscriptionInput(
                    lastNormalized, model, language, prompt));
        } catch (SpeechTranscriber.SpeechTranscriberException ex) {
            // A user cancel wins regardless of which transport error the disconnect surfaced
            // (the adapter may report FAILED/TIMEOUT/UNREACHABLE instead of CANCELLED after abort()).
            if (abortIfCancelled(op)) {
                return;
            }
            if (ex.getKind() == DictationErrorKind.CANCELLED) {
                deliverCancelled(op);
            } else {
                fail(op, ex.getKind(), message(ex));
            }
            return;
        }
        // A cancel that raced past the (already returned) HTTP call must still win: never insert text.
        if (abortIfCancelled(op)) {
            return;
        }
        if (text == null || text.trim().isEmpty()) {
            fail(op, DictationErrorKind.TRANSCRIPTION_EMPTY, "The model returned an empty transcription.");
            return;
        }
        long transcriptionMillis = System.currentTimeMillis() - startedAt;
        DictationDiagnostics diagnostics = buildDiagnostics(model, resolution.getCapabilityStatus(),
                transcriber.lastHttpStatus(), transcriptionMillis);
        deleteTemps();
        succeed(op, new DictationResult(text, model, diagnostics));
    }

    private DictationDiagnostics buildDiagnostics(String model, String capabilityStatus,
                                                  int httpStatus, long transcriptionMillis) {
        RawRecording raw;
        NormalizationResult norm;
        File normalized;
        synchronized (this) {
            raw = lastRaw;
            norm = lastNormalization;
            normalized = lastNormalized;
        }
        DictationDiagnostics.Builder builder = DictationDiagnostics.builder()
                .model(model)
                .capabilityStatus(capabilityStatus)
                .httpStatus(httpStatus)
                .transcriptionMillis(transcriptionMillis)
                .ollamaVersion(safeVersion());
        if (raw != null) {
            builder.device(raw.getDeviceName().isEmpty() ? "system default" : raw.getDeviceName())
                    .droppedFrames(raw.getDroppedFrames());
            if (raw.getCaptureFormat() != null) {
                builder.captureFormat(raw.getCaptureFormat().toString());
            }
        }
        if (norm != null) {
            builder.targetFormat(norm.getTargetFormat().toString())
                    .durationMillis(norm.getDurationMillis())
                    .rms(norm.getOverallRms())
                    .peak(norm.getPeak())
                    .clippedSamples(norm.getClippedSamples());
        }
        if (normalized != null && normalized.isFile()) {
            builder.wavBytes(normalized.length());
        }
        return builder.build();
    }

    private String safeVersion() {
        if (serverProbe == null) {
            return "";
        }
        try {
            return serverProbe.version();
        } catch (Exception ex) {
            return "";
        }
    }

    // ------------------------------------------------------------------ terminal transitions (generation-guarded)

    private synchronized long beginOperation() {
        generation++;
        terminalDelivered = false;
        cancelRequested = false;
        return generation;
    }

    /** @return true (and delivers CANCELLED) when a cancel was requested for the current operation. */
    private boolean abortIfCancelled(long op) {
        synchronized (this) {
            if (!cancelRequested || op != generation) {
                return op != generation; // a superseded op stops silently; the current one may proceed
            }
        }
        deliverCancelled(op);
        return true;
    }

    private void succeed(long op, DictationResult result) {
        synchronized (this) {
            if (op != generation || terminalDelivered) {
                return;
            }
            terminalDelivered = true;
            state = DictationState.TRANSCRIPTION_READY;
        }
        emitState(DictationState.TRANSCRIPTION_READY);
        listener.onResult(result);
    }

    private void fail(long op, DictationErrorKind kind, String detail) {
        synchronized (this) {
            if (op != generation || terminalDelivered) {
                return;
            }
            terminalDelivered = true;
            state = DictationState.FAILED;
            if (!kind.keepRecording()) {
                deleteTempsLocked();
            }
        }
        emitState(DictationState.FAILED);
        listener.onFailure(new DictationFailure(kind, detail));
    }

    private void deliverCancelled(long op) {
        synchronized (this) {
            if (op != generation || terminalDelivered) {
                return;
            }
            terminalDelivered = true;
            state = DictationState.CANCELLED;
        }
        emitState(DictationState.CANCELLED);
        listener.onFailure(new DictationFailure(DictationErrorKind.CANCELLED, "Cancelled."));
    }

    private void transition(long op, DictationState next) {
        synchronized (this) {
            if (op != generation || terminalDelivered) {
                return;
            }
            state = next;
        }
        emitState(next);
    }

    private void emitState(DictationState next) {
        listener.onState(next, null);
    }

    // ------------------------------------------------------------------ temp handling

    private void deleteTemps() {
        synchronized (this) {
            deleteTempsLocked();
        }
    }

    private void deleteTempsLocked() {
        if (lastRaw != null) {
            deleteQuietly(lastRaw.getFile());
            lastRaw = null;
        }
        if (lastNormalized != null) {
            deleteQuietly(lastNormalized);
            lastNormalized = null;
        }
        lastNormalization = null;
    }

    private static void deleteQuietly(File file) {
        if (file != null && file.isFile()) {
            file.delete();
        }
    }

    private static DictationErrorKind mapResolution(AudioModelResolver.AudioModelResolution.Status status) {
        switch (status) {
            case CAPABILITY_UNKNOWN:
                return DictationErrorKind.MODEL_CAPABILITY_UNKNOWN;
            case NOT_AUDIO_CAPABLE:
                return DictationErrorKind.MODEL_NOT_AUDIO;
            case NO_AUDIO_MODEL:
            default:
                return DictationErrorKind.NO_AUDIO_MODEL;
        }
    }

    private static String message(Throwable ex) {
        return ex.getMessage() == null ? ex.toString() : ex.getMessage();
    }
}
