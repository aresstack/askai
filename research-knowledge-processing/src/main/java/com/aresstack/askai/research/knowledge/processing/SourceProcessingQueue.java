package com.aresstack.askai.research.knowledge.processing;

import java.util.List;

/**
 * The project-scoped, serial, persistent FIFO of capture-processing jobs (§4, §23). Not RAM-only: a crash
 * between acceptance and processing must not leave a capture permanently unprocessed. Idempotent enqueue
 * (§4.3): re-delivering the same {@link SourceProcessingRequest#idempotencyKey()} does not create a second
 * fachliche job. Java-8 compatible; a single {@code SourceProcessingWorker} drains it (§5, §23).
 */
public interface SourceProcessingQueue {

    /**
     * Enqueue a request (idempotent per idempotency key); returns the resulting QUEUED (or existing) job. A
     * previously {@link SourceProcessingJob.State#SUPERSEDED} job of the same key is RE-ACTIVATED to QUEUED at
     * the tail — so a return to an earlier embedding world re-derives that capture rather than staying retired.
     */
    SourceProcessingJob enqueue(SourceProcessingRequest request);

    /** The next QUEUED job marked PROCESSING, or {@code null} when the queue is empty. */
    SourceProcessingJob takeNext();

    void markCompleted(SourceProcessingJob job);

    void markFailed(SourceProcessingJob job, SourceProcessingFailure failure);

    /**
     * Retire a job UNPROCESSED because its embedding-world fingerprint no longer matches the active session's
     * world (§4.3). Unlike {@link #markCompleted} this makes no claim that the work was done — a SUPERSEDED key
     * is never treated as already-completed, so the capture is honestly re-derivable under the active world.
     */
    void markSuperseded(SourceProcessingJob job);

    /**
     * Put a job back to QUEUED at the TAIL for another attempt (a retryable failure). Tail order is deliberate:
     * a retry must NOT preempt jobs that were enqueued after it, so a failing job never blocks the FIFO.
     */
    void requeue(SourceProcessingJob job);

    /**
     * Crash recovery (§4.2, §25): every job stranded in PROCESSING is returned to QUEUED so the worker picks
     * it up again. Returns the recovered jobs (possibly empty).
     */
    List<SourceProcessingJob> recoverStrandedJobs();

    /** True when this exact processing (idempotency key) already completed — used to short-circuit (§4.3). */
    boolean isAlreadyCompleted(String idempotencyKey);

    /**
     * READ-ONLY observation for reconciliation (#39): the source ids owning a job the
     * reconciliation must respect — QUEUED, PROCESSING, FAILED and COMPLETED all count as
     * "processing is known for this source" (a FAILED job is a deliberate outcome a repair
     * must not sneak past). Only a retired SUPERSEDED job does NOT count: it names a
     * no-longer-active world and must never masquerade as current-world truth. Never
     * mutates the queue.
     */
    java.util.Set<String> sourceIdsWithKnownProcessing();

    /**
     * D6: the sources whose processing is still pending (queued, running or due for a retry): their passages are not
     * in the knowledge store yet, so the evidence analysis must not report their material as missing.
     */
    java.util.Set<String> pendingSourceIds();
}
