package com.aresstack.askai.research.knowledge.processing;

import java.util.Set;

/**
 * #39 — the processing-reconciliation seam: a source accepted while NO embedding world was
 * configured never received a processing job (the acceptance scheduler was {@code NONE}, and
 * {@code ALREADY_ACCEPTED} returns before scheduling on a re-import). Once a session starts
 * WITH a valid embedding world, this closes exactly that gap:
 *
 * <pre>
 *   accepted, corpus-eligible sources with segmentable text
 *   − sources the knowledge project already knows (any processed capture)
 *   − sources the queue already has a job for (QUEUED/PROCESSING/FAILED/COMPLETED)
 *   → enqueue the missing derivation
 * </pre>
 *
 * <p>The second subtraction closes the crash window between acceptance and processing: a
 * normal acceptance job persisted as QUEUED but not yet worked leaves NO capture in the
 * knowledge project, and the reconciled capture id would not dedup against the original
 * capture id — without it, one source would get two derivations.</p>
 *
 * <p>The rules that keep this honest: jobs go through the SAME
 * {@link QueueBackedKnowledgeProcessingScheduler} the acceptance hook uses, so every job
 * carries the real session identity — a fake or empty fingerprint is structurally rejected
 * there, never written here. The synthesized capture id is DETERMINISTIC per source, so a
 * re-run (next restart) is idempotent through the queue's key dedup and the worker's
 * already-completed check; the worker's durable capture reader rebuilds the capture from the
 * persisted source record's full text. Sources the knowledge project already processed —
 * under ANY identity — are left alone: migrating an old vector world is deliberately NOT
 * this seam's job.</p>
 */
public final class ProcessingReconciliation {

    /** One accepted source as reconciliation sees it — a neutral view, no repository type. */
    public interface CandidateSource {
        String sourceId();

        /** Whether the persisted record carries full text the segmentation can work on. */
        boolean hasSegmentableText();

        /** Corpus eligibility (not EXCLUDED/DUPLICATE/SUPERSEDED) — never process waste. */
        boolean corpusEligible();
    }

    /** Receives the missing jobs; the caller binds it to the session's REAL scheduler. */
    public interface MissingJobScheduler {
        void enqueue(String captureId, String sourceId);
    }

    private ProcessingReconciliation() {
    }

    /** The deterministic capture id a reconciled derivation runs under — stable per source. */
    public static String reconciledCaptureId(String sourceId) {
        return "import-reconciled-" + sourceId;
    }

    /**
     * Enqueue a job for every candidate that neither the knowledge project nor the queue
     * knows any processing for.
     *
     * @param processedSourceIds        source ids owning at least one capture in the
     *                                  knowledge project (processed under some identity)
     * @param alreadyScheduledSourceIds source ids the queue already holds a job for (see
     *                                  {@link SourceProcessingQueue#sourceIdsWithKnownProcessing()})
     *                                  — scheduled work is respected, never doubled
     * @return how many missing jobs were handed to the scheduler
     */
    public static int reconcile(Iterable<? extends CandidateSource> candidates,
                                Set<String> processedSourceIds,
                                Set<String> alreadyScheduledSourceIds,
                                MissingJobScheduler scheduler) {
        int enqueued = 0;
        for (CandidateSource candidate : candidates) {
            String sourceId = candidate.sourceId();
            if (sourceId == null || sourceId.trim().isEmpty()
                    || !candidate.corpusEligible()
                    || !candidate.hasSegmentableText()
                    || processedSourceIds.contains(sourceId)
                    || alreadyScheduledSourceIds.contains(sourceId)) {
                continue;
            }
            scheduler.enqueue(reconciledCaptureId(sourceId), sourceId);
            enqueued++;
        }
        return enqueued;
    }

    /**
     * PR 04: re-derive captures that were processed only in another vector world (for example before the prompt
     * scheme became part of the fingerprint). Every capture of an eligible source with text that has no passage in
     * the active world and no job for it is enqueued under its OWN capture id, so the worker derives the active
     * world from the same capture.
     *
     * @param captureSourceIds         capture id → source id of every capture in the knowledge project
     * @param currentWorldCaptureIds   capture ids that already own a passage of the active world
     * @param currentWorldJobCaptureIds capture ids with a non-superseded job of the active world
     * @param eligibleSourceIds        sources that are corpus-eligible and have segmentable text
     * @return how many captures were handed to the scheduler
     */
    public static int reembedStale(java.util.Map<String, String> captureSourceIds,
                                   Set<String> currentWorldCaptureIds,
                                   Set<String> currentWorldJobCaptureIds,
                                   Set<String> eligibleSourceIds,
                                   MissingJobScheduler scheduler) {
        int enqueued = 0;
        for (java.util.Map.Entry<String, String> capture : captureSourceIds.entrySet()) {
            String captureId = capture.getKey();
            String sourceId = capture.getValue();
            if (sourceId == null || !eligibleSourceIds.contains(sourceId)
                    || currentWorldCaptureIds.contains(captureId)
                    || currentWorldJobCaptureIds.contains(captureId)) {
                continue;
            }
            scheduler.enqueue(captureId, sourceId);
            enqueued++;
        }
        return enqueued;
    }
}
