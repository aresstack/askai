package com.aresstack.askai.batch.ui;

import com.aresstack.askai.batch.service.BatchTranscriptionEvent;
import com.aresstack.askai.batch.service.BatchTranscriptionEventPublisher;
import com.aresstack.askai.batch.service.BatchTranscriptionRequest;
import com.aresstack.askai.batch.service.BatchTranscriptionService;

import java.util.function.Consumer;

/** UI-facing facade that hides concrete service orchestration from Swing. */
public final class BatchTranscriptionController {

    private final BatchTranscriptionService service;
    private final BatchTranscriptionEventPublisher events;
    private BatchTranscriptionService.BatchTask runningTask;

    public BatchTranscriptionController(BatchTranscriptionService service,
                                        BatchTranscriptionEventPublisher events) {
        this.service = service;
        this.events = events;
    }

    public BatchTranscriptionEventPublisher.Subscription observe(
            Consumer<BatchTranscriptionEvent> observer) {
        return events.subscribe(observer);
    }

    public synchronized void start(BatchTranscriptionRequest request) {
        if (runningTask != null) throw new IllegalStateException("A batch is already running.");
        runningTask = service.start(request);
    }

    public synchronized void cancel() {
        if (runningTask != null) runningTask.cancel();
    }

    public synchronized void markFinished() { runningTask = null; }
}
