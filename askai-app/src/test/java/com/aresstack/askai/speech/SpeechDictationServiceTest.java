package com.aresstack.askai.speech;

import com.aresstack.audio.application.NormalizationResult;
import com.aresstack.audio.dsp.AudioLevelMeter;
import com.aresstack.audio.domain.PcmAudioFormat;

import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The dictation flow, driven synchronously (inline executor) with fakes for every port: success,
 * failures at each stage, retry reusing the same recording, temp-file lifecycle and the
 * exactly-one-terminal-callback guarantee.
 */
public class SpeechDictationServiceTest {

    private static final Executor INLINE = new Executor() {
        public void execute(Runnable command) {
            command.run();
        }
    };

    private File workDir;
    private FakeRecorder recorder;
    private FakeNormalizer normalizer;
    private FakeResolver resolver;
    private FakeTranscriber transcriber;
    private RecordingListener listener;

    @Before
    public void setUp() throws IOException {
        workDir = File.createTempFile("askai-dictation-test", "");
        workDir.delete();
        workDir.mkdirs();
        recorder = new FakeRecorder();
        normalizer = new FakeNormalizer(goodMetrics());
        resolver = new FakeResolver(AudioModelResolver.AudioModelResolution.resolved("voxtral:latest"));
        transcriber = new FakeTranscriber();
        listener = new RecordingListener();
    }

    private SpeechDictationService service() {
        return new SpeechDictationService(INLINE, recorder, normalizer, resolver, transcriber,
                null, workDir, null, listener);
    }

    // ------------------------------------------------------------------ happy path

    @Test
    public void fullSuccessInsertsTextAndDeletesTemps() {
        transcriber.text = "hallo welt";
        SpeechDictationService service = service();
        service.startRecording("");
        assertEquals(DictationState.RECORDING, service.getState());
        service.stopAndTranscribe("Automatic", "de", "");

        assertEquals(1, listener.results.size());
        assertEquals("hallo welt", listener.results.get(0).getText());
        assertEquals(0, listener.failures.size());
        assertEquals(1, listener.terminalCount());
        assertEquals(DictationState.TRANSCRIPTION_READY, service.getState());
        // On success the temp files are gone.
        assertFalse(recorder.lastRawFile.isFile());
        assertFalse(normalizer.lastTarget.isFile());
    }

    // ------------------------------------------------------------------ stage failures

    @Test
    public void microphoneOpenFailure() {
        recorder.failOnStart = true;
        SpeechDictationService service = service();
        service.startRecording("");
        assertEquals(1, listener.failures.size());
        assertEquals(DictationErrorKind.MICROPHONE_OPEN_FAILED, listener.failures.get(0).getKind());
        assertEquals(1, listener.terminalCount());
    }

    @Test
    public void finalizeFailureKeepsNothingButReportsError() {
        recorder.failOnStop = true;
        SpeechDictationService service = service();
        service.startRecording("");
        service.stopAndTranscribe("Automatic", "", "");
        assertEquals(DictationErrorKind.FINALIZE_FAILED, listener.failures.get(0).getKind());
    }

    @Test
    public void noSignalBlocksUploadAndKeepsRecording() {
        normalizer.result = silentMetrics();
        SpeechDictationService service = service();
        service.startRecording("");
        service.stopAndTranscribe("Automatic", "", "");
        assertEquals(DictationErrorKind.QUALITY_NO_SIGNAL, listener.failures.get(0).getKind());
        assertTrue("raw kept for save", service.hasSavableRecording());
        assertTrue("raw kept for retry", recorder.lastRawFile.isFile());
        assertEquals(0, transcriber.calls);   // never uploaded
    }

    @Test
    public void modelNotAudioCapableFails() {
        resolver.resolution = new AudioModelResolver.AudioModelResolution(
                AudioModelResolver.AudioModelResolution.Status.NOT_AUDIO_CAPABLE, "llava", "completion, vision");
        SpeechDictationService service = service();
        service.startRecording("");
        service.stopAndTranscribe("llava", "", "");
        assertEquals(DictationErrorKind.MODEL_NOT_AUDIO, listener.failures.get(0).getKind());
        assertEquals(0, transcriber.calls);
    }

    @Test
    public void unknownCapabilityFails() {
        resolver.resolution = new AudioModelResolver.AudioModelResolution(
                AudioModelResolver.AudioModelResolution.Status.CAPABILITY_UNKNOWN, "old", "");
        SpeechDictationService service = service();
        service.startRecording("");
        service.stopAndTranscribe("old", "", "");
        assertEquals(DictationErrorKind.MODEL_CAPABILITY_UNKNOWN, listener.failures.get(0).getKind());
    }

    @Test
    public void noAudioModelFails() {
        resolver.resolution = new AudioModelResolver.AudioModelResolution(
                AudioModelResolver.AudioModelResolution.Status.NO_AUDIO_MODEL, "", "");
        SpeechDictationService service = service();
        service.startRecording("");
        service.stopAndTranscribe("Automatic", "", "");
        assertEquals(DictationErrorKind.NO_AUDIO_MODEL, listener.failures.get(0).getKind());
    }

    @Test
    public void emptyTranscriptionFailsAndKeepsRecording() {
        transcriber.text = "   ";
        SpeechDictationService service = service();
        service.startRecording("");
        service.stopAndTranscribe("Automatic", "", "");
        assertEquals(DictationErrorKind.TRANSCRIPTION_EMPTY, listener.failures.get(0).getKind());
        assertTrue(normalizer.lastTarget.isFile());   // kept for retry
    }

    @Test
    public void transcriptionFailureIsReportedStructured() {
        transcriber.failWith = DictationErrorKind.SERVER_ENDPOINT_UNAVAILABLE;
        SpeechDictationService service = service();
        service.startRecording("");
        service.stopAndTranscribe("Automatic", "", "");
        assertEquals(DictationErrorKind.SERVER_ENDPOINT_UNAVAILABLE, listener.failures.get(0).getKind());
    }

    @Test
    public void cancelledTranscriptionIsNotATechnicalError() {
        transcriber.failWith = DictationErrorKind.CANCELLED;
        SpeechDictationService service = service();
        service.startRecording("");
        service.stopAndTranscribe("Automatic", "", "");
        assertEquals(DictationErrorKind.CANCELLED, listener.failures.get(0).getKind());
        assertEquals(DictationState.CANCELLED, service.getState());
    }

    // ------------------------------------------------------------------ retry & discard

    @Test
    public void retryReusesTheSameRecording() {
        transcriber.failWith = DictationErrorKind.TRANSCRIPTION_FAILED;
        SpeechDictationService service = service();
        service.startRecording("");
        service.stopAndTranscribe("Automatic", "", "");
        assertEquals(1, listener.failures.size());
        File firstUpload = transcriber.lastFile;

        transcriber.failWith = null;
        transcriber.text = "second try";
        service.retryTranscription();

        assertEquals(1, listener.results.size());
        assertEquals("second try", listener.results.get(0).getText());
        assertEquals("retry uploads the same normalized file", firstUpload, transcriber.lastFile);
        assertEquals(2, transcriber.calls);
    }

    @Test
    public void discardDuringRecordingReturnsToIdleWithoutTerminal() {
        SpeechDictationService service = service();
        service.startRecording("");
        service.discard();
        assertEquals(DictationState.IDLE, service.getState());
        assertEquals(0, listener.terminalCount());
        assertTrue(recorder.discarded);
    }

    // ------------------------------------------------------------------ cancel races

    @Test
    public void cancelRacingWithReturnedResultDoesNotInsert() {
        // The transcription "returns" successfully, but a cancel arrives at the same instant: the guard
        // after transcribe must win so the (already fetched) text is never inserted.
        final SpeechDictationService[] holder = new SpeechDictationService[1];
        SpeechTranscriber racing = new SpeechTranscriber() {
            public String transcribe(TranscriptionInput input) {
                holder[0].cancel();     // cancel races in just as the result is ready
                return "late text";
            }

            public void cancel() {
            }

            public int lastHttpStatus() {
                return 200;
            }
        };
        SpeechDictationService service = new SpeechDictationService(INLINE, recorder, normalizer, resolver,
                racing, null, workDir, null, listener);
        holder[0] = service;
        service.startRecording("");
        service.stopAndTranscribe("Automatic", "", "");

        assertEquals("late text must not be inserted", 0, listener.results.size());
        assertEquals(1, listener.failures.size());
        assertEquals(DictationErrorKind.CANCELLED, listener.failures.get(0).getKind());
        assertEquals(1, listener.terminalCount());
    }

    @Test
    public void cancelWinsEvenWhenTransportReportsAnotherError() {
        // After abort() the adapter may raise e.g. TRANSCRIPTION_FAILED instead of CANCELLED; the
        // user's cancel must still win and never surface as a technical error.
        final SpeechDictationService[] holder = new SpeechDictationService[1];
        SpeechTranscriber failingAfterCancel = new SpeechTranscriber() {
            public String transcribe(TranscriptionInput input) throws SpeechTranscriberException {
                holder[0].cancel();
                throw new SpeechTranscriberException(DictationErrorKind.TRANSCRIPTION_FAILED, "connection reset");
            }

            public void cancel() {
            }

            public int lastHttpStatus() {
                return 0;
            }
        };
        SpeechDictationService service = new SpeechDictationService(INLINE, recorder, normalizer, resolver,
                failingAfterCancel, null, workDir, null, listener);
        holder[0] = service;
        service.startRecording("");
        service.stopAndTranscribe("Automatic", "", "");

        assertEquals(1, listener.failures.size());
        assertEquals(DictationErrorKind.CANCELLED, listener.failures.get(0).getKind());
        assertEquals(0, listener.results.size());
        assertEquals(1, listener.terminalCount());
    }

    @Test
    public void cancelDuringNormalizationStopsBeforeTranscription() {
        final SpeechDictationService[] holder = new SpeechDictationService[1];
        final NormalizationResult good = goodMetrics();
        RecordingNormalizer cancelling = new RecordingNormalizer() {
            public NormalizationResult normalize(File rawWav, File targetWav) throws Exception {
                writeStub(targetWav);
                holder[0].cancel();     // cancel arrives while normalizing
                return new NormalizationResult(targetWav, good.getSourceFormat(), good.getTargetFormat(),
                        good.getDurationMillis(), good.getOverallRms(), good.getPeak(),
                        good.getClippedSamples(), good.getTotalSamples());
            }
        };
        SpeechDictationService service = new SpeechDictationService(INLINE, recorder, cancelling, resolver,
                transcriber, null, workDir, null, listener);
        holder[0] = service;
        service.startRecording("");
        service.stopAndTranscribe("Automatic", "", "");

        assertEquals(DictationErrorKind.CANCELLED, listener.failures.get(0).getKind());
        assertEquals("must not reach transcription", 0, transcriber.calls);
    }

    @Test
    public void asyncCancelDuringTranscriptionIsCancelled() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            final CountDownLatch entered = new CountDownLatch(1);
            final CountDownLatch proceed = new CountDownLatch(1);
            final boolean[] cancelled = {false};
            SpeechTranscriber blocking = new SpeechTranscriber() {
                public String transcribe(TranscriptionInput input) throws SpeechTranscriberException {
                    entered.countDown();
                    try {
                        proceed.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException ignored) {
                    }
                    if (cancelled[0]) {
                        throw new SpeechTranscriberException(DictationErrorKind.CANCELLED, "aborted");
                    }
                    return "text";
                }

                public void cancel() {
                    cancelled[0] = true;
                    proceed.countDown();
                }

                public int lastHttpStatus() {
                    return 200;
                }
            };
            SpeechDictationService service = new SpeechDictationService(pool, recorder, normalizer, resolver,
                    blocking, null, workDir, null, listener);
            service.startRecording("");
            waitForState(service, DictationState.RECORDING);
            service.stopAndTranscribe("Automatic", "", "");
            assertTrue("transcription started", entered.await(5, TimeUnit.SECONDS));

            service.cancel();            // abort the in-flight transcription
            waitForTerminal();
            assertEquals(1, listener.failures.size());
            assertEquals(DictationErrorKind.CANCELLED, listener.failures.get(0).getKind());
            assertEquals(0, listener.results.size());
            assertTrue("recording kept for retry", service.hasRetryableRecording());
        } finally {
            pool.shutdownNow();
        }
    }

    private void waitForState(SpeechDictationService service, DictationState target) throws InterruptedException {
        for (int i = 0; i < 500 && service.getState() != target; i++) {
            Thread.sleep(5);
        }
    }

    private void waitForTerminal() throws InterruptedException {
        for (int i = 0; i < 500 && listener.terminalCount() == 0; i++) {
            Thread.sleep(5);
        }
    }

    @Test
    public void exactlyOneTerminalCallbackOnSuccess() {
        transcriber.text = "once";
        SpeechDictationService service = service();
        service.startRecording("");
        service.stopAndTranscribe("Automatic", "", "");
        // Extra user actions after completion must not produce more terminal callbacks.
        service.stopAndTranscribe("Automatic", "", "");
        assertEquals(1, listener.terminalCount());
    }

    // ------------------------------------------------------------------ fakes

    private static NormalizationResult goodMetrics() {
        return metrics(2000L, 3000.0d, 20000, 0L, 32000L);
    }

    private static NormalizationResult silentMetrics() {
        return metrics(2000L, 5.0d, 40, 0L, 32000L);
    }

    private static NormalizationResult metrics(long duration, double rms, int peak, long clipped, long total) {
        return new NormalizationResult(null, new PcmAudioFormat(48000, 2, 16),
                new PcmAudioFormat(16000, 1, 16), duration, rms, peak, clipped, total);
    }

    private static final class RecordingListener implements DictationListener {
        final List<DictationResult> results =
                java.util.Collections.synchronizedList(new ArrayList<DictationResult>());
        final List<DictationFailure> failures =
                java.util.Collections.synchronizedList(new ArrayList<DictationFailure>());

        public void onState(DictationState state, String message) {
        }

        public void onResult(DictationResult result) {
            results.add(result);
        }

        public void onFailure(DictationFailure failure) {
            failures.add(failure);
        }

        int terminalCount() {
            return results.size() + failures.size();
        }
    }

    private final class FakeRecorder implements MicrophoneRecorder {
        boolean failOnStart;
        boolean failOnStop;
        boolean discarded;
        File lastRawFile;

        public Session start(String deviceName, File workingDirectory) throws Exception {
            if (failOnStart) {
                throw new IOException("microphone busy");
            }
            return new Session() {
                public AudioLevelMeter getMeter() {
                    return new AudioLevelMeter();
                }

                public String getDeviceName() {
                    return deviceName == null ? "" : deviceName;
                }

                public RawRecording stop() throws Exception {
                    if (failOnStop) {
                        throw new IOException("could not finalize WAV");
                    }
                    lastRawFile = new File(workingDirectory, "raw-" + System.nanoTime() + ".wav");
                    writeStub(lastRawFile);
                    return new RawRecording(lastRawFile, new PcmAudioFormat(48000, 2, 16), 0L, getDeviceName());
                }

                public void discard() {
                    discarded = true;
                }
            };
        }
    }

    private final class FakeNormalizer implements RecordingNormalizer {
        NormalizationResult result;
        File lastTarget;

        FakeNormalizer(NormalizationResult result) {
            this.result = result;
        }

        public NormalizationResult normalize(File rawWav, File targetWav) throws Exception {
            lastTarget = targetWav;
            writeStub(targetWav); // so isFile()/length() work like a real normalized WAV
            NormalizationResult r = result;
            return new NormalizationResult(targetWav, r.getSourceFormat(), r.getTargetFormat(),
                    r.getDurationMillis(), r.getOverallRms(), r.getPeak(), r.getClippedSamples(),
                    r.getTotalSamples());
        }
    }

    private static final class FakeResolver implements AudioModelResolver {
        AudioModelResolution resolution;

        FakeResolver(AudioModelResolution resolution) {
            this.resolution = resolution;
        }

        public AudioModelResolution resolve(String requestedModel) {
            return resolution;
        }
    }

    private static final class FakeTranscriber implements SpeechTranscriber {
        String text = "text";
        DictationErrorKind failWith;
        int calls;
        File lastFile;

        public String transcribe(TranscriptionInput input) throws SpeechTranscriberException {
            calls++;
            lastFile = input.getAudioFile();
            if (failWith != null) {
                throw new SpeechTranscriberException(failWith, "transcription problem");
            }
            return text;
        }

        public void cancel() {
        }

        public int lastHttpStatus() {
            return 200;
        }
    }

    private static void writeStub(File file) {
        try {
            java.io.FileOutputStream out = new java.io.FileOutputStream(file);
            out.write(new byte[]{0, 0, 0, 0});
            out.close();
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
    }
}
