package com.aresstack.askai.batch.service;

import com.aresstack.askai.batch.service.BatchAudioPreparationService.PreparedBatchAudio;
import com.aresstack.askai.stt.SpeechToTextService;
import com.aresstack.audio.profile.AudioProcessingProfile;

import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Execute model -> file -> profile sequentially and publish progress as application events. */
public final class BatchTranscriptionService {

    public interface BatchTask { void cancel(); }

    /**
     * Hard wall-clock cap per model/file/profile combination. A stuck combination (a model looping at
     * 100% GPU that never returns) is aborted after this many seconds and reported as ITEM_FAILED so the
     * batch continues. This is intentionally shorter than the STT read/idle timeout, which only fires
     * after that many seconds without any response byte.
     */
    public static final long DEFAULT_ITEM_TIMEOUT_SECONDS = 120L;

    private final SpeechToTextService speechToTextService;
    private final BatchAudioPreparationService audioPreparationService;
    private final BatchMarkdownResultWriter resultWriter;
    private final BatchTranscriptionEventPublisher eventPublisher;
    private final long itemTimeoutSeconds;
    private final ExecutorService executor;

    public BatchTranscriptionService(SpeechToTextService speechToTextService,
                                     BatchAudioPreparationService audioPreparationService,
                                     BatchMarkdownResultWriter resultWriter,
                                     BatchTranscriptionEventPublisher eventPublisher) {
        this(speechToTextService, audioPreparationService, resultWriter, eventPublisher,
                DEFAULT_ITEM_TIMEOUT_SECONDS);
    }

    public BatchTranscriptionService(SpeechToTextService speechToTextService,
                                     BatchAudioPreparationService audioPreparationService,
                                     BatchMarkdownResultWriter resultWriter,
                                     BatchTranscriptionEventPublisher eventPublisher,
                                     long itemTimeoutSeconds) {
        this.speechToTextService = speechToTextService;
        this.audioPreparationService = audioPreparationService;
        this.resultWriter = resultWriter;
        this.eventPublisher = eventPublisher;
        this.itemTimeoutSeconds = itemTimeoutSeconds > 0 ? itemTimeoutSeconds : DEFAULT_ITEM_TIMEOUT_SECONDS;
        this.executor = Executors.newSingleThreadExecutor(new BatchThreadFactory());
    }

    public BatchTask start(final BatchTranscriptionRequest request) {
        final Cancellation cancellation = new Cancellation();
        executor.execute(new Runnable() {
            public void run() { execute(request, cancellation); }
        });
        return cancellation;
    }

    private void execute(BatchTranscriptionRequest request, Cancellation cancellation) {
        int completed = 0;
        publish(BatchTranscriptionEvent.Type.BATCH_STARTED, completed, request, "", "", null, null,
                "Batch started.");
        try {
            for (String modelName : request.getModelNames()) {
                ensureActive(cancellation);
                publish(BatchTranscriptionEvent.Type.MODEL_STARTED, completed, request, modelName,
                        "", null, null, "Processing model " + modelName + ".");
                for (File audioFile : request.getAudioFiles()) {
                    for (AudioProcessingProfile profile : request.getProfiles()) {
                        ensureActive(cancellation);
                        String profileName = profile.getName();
                        publish(BatchTranscriptionEvent.Type.ITEM_STARTED, completed, request, modelName,
                                profileName, audioFile, null, "Processing " + audioFile.getName() + ".");
                        try {
                            String transcription = transcribe(audioFile, modelName, profile, request, cancellation);
                            File markdown = resultWriter.append(audioFile, modelName, profile.getId(),
                                    profileName, transcription);
                            completed++;
                            publish(BatchTranscriptionEvent.Type.ITEM_COMPLETED, completed, request, modelName,
                                    profileName, audioFile, markdown, "Transcription appended.");
                        } catch (CancelledException ex) {
                            throw ex;
                        } catch (Exception ex) {
                            completed++;
                            publish(BatchTranscriptionEvent.Type.ITEM_FAILED, completed, request, modelName,
                                    profileName, audioFile, null, describe(ex));
                        }
                    }
                }
            }
            publish(BatchTranscriptionEvent.Type.BATCH_COMPLETED, completed, request, "", "", null,
                    null, "Batch completed.");
        } catch (CancelledException ex) {
            publish(BatchTranscriptionEvent.Type.BATCH_CANCELLED, completed, request, "", "", null,
                    null, "Batch cancelled.");
        }
    }

    private String transcribe(File audioFile, String modelName, AudioProcessingProfile profile,
                              BatchTranscriptionRequest request, Cancellation cancellation) throws Exception {
        PreparedBatchAudio prepared = audioPreparationService.prepare(audioFile, profile);
        try {
            final CountDownLatch latch = new CountDownLatch(1);
            final AtomicReference<String> result = new AtomicReference<String>();
            final AtomicReference<Exception> failure = new AtomicReference<Exception>();
            SpeechToTextService.TranscriptionRequest sttRequest =
                    new SpeechToTextService.TranscriptionRequest(prepared.getFile(), modelName,
                            request.getLanguage(), request.getPrompt());
            SpeechToTextService.Task task = speechToTextService.transcribe(sttRequest,
                    new SpeechToTextService.TranscriptionListener() {
                        public void onTranscription(String text) { result.set(text); latch.countDown(); }
                        public void onError(Exception ex) { failure.set(ex); latch.countDown(); }
                    });
            cancellation.setCurrentTask(task);
            boolean answered;
            try {
                answered = latch.await(itemTimeoutSeconds, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new CancelledException();
            } finally {
                cancellation.clearCurrentTask(task);
            }
            if (!answered) {
                // Stuck combination: abort the in-flight STT call so Ollama can stop and unload, then fail
                // this item and let the batch continue with the next combination.
                task.cancel();
                throw new TimeoutException(
                        "The transcription exceeded the batch item limit of " + itemTimeoutSeconds
                                + " seconds and was skipped.");
            }
            ensureActive(cancellation);
            if (failure.get() != null) throw failure.get();
            return result.get() == null ? "" : result.get();
        } finally {
            prepared.close();
        }
    }

    private void ensureActive(Cancellation cancellation) {
        if (cancellation.cancelled.get() || Thread.currentThread().isInterrupted()) {
            throw new CancelledException();
        }
    }

    private void publish(BatchTranscriptionEvent.Type type, int completed,
                         BatchTranscriptionRequest request, String model, String profile,
                         File audio, File markdown, String message) {
        eventPublisher.publish(BatchTranscriptionEvent.of(type, completed, request.getTotalItems(),
                model, profile, audio, markdown, message));
    }

    private static String describe(Exception ex) {
        return ex.getMessage() == null ? ex.toString() : ex.getMessage();
    }

    private static final class CancelledException extends RuntimeException { }

    private static final class Cancellation implements BatchTask {
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private final AtomicReference<SpeechToTextService.Task> currentTask =
                new AtomicReference<SpeechToTextService.Task>();
        public void cancel() {
            cancelled.set(true);
            SpeechToTextService.Task task = currentTask.get();
            if (task != null) task.cancel();
        }
        private void setCurrentTask(SpeechToTextService.Task task) {
            currentTask.set(task);
            if (cancelled.get()) task.cancel();
        }
        private void clearCurrentTask(SpeechToTextService.Task task) { currentTask.compareAndSet(task, null); }
    }

    private static final class BatchThreadFactory implements ThreadFactory {
        private final AtomicInteger counter = new AtomicInteger();
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "askai-batch-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
