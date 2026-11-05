package com.aresstack.askai.research.knowledge.processing;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * #39 reconciliation pins: only never-processed, corpus-eligible sources with segmentable
 * text get their missing job; already-processed sources (any identity) are left alone; the
 * synthesized capture id is deterministic, so a re-run after restart cannot double-enqueue
 * through the queue's idempotency; and every job carries the session's REAL identity because
 * it travels the same QueueBackedKnowledgeProcessingScheduler (which structurally rejects an
 * empty fingerprint — no fake-world jobs, ever).
 */
public class ProcessingReconciliationTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final class Src implements ProcessingReconciliation.CandidateSource {
        final String id;
        final boolean text;
        final boolean eligible;

        Src(String id, boolean text, boolean eligible) {
            this.id = id;
            this.text = text;
            this.eligible = eligible;
        }

        public String sourceId() {
            return id;
        }

        public boolean hasSegmentableText() {
            return text;
        }

        public boolean corpusEligible() {
            return eligible;
        }
    }

    @Test
    public void onlyNeverProcessedEligibleSourcesWithTextGetTheirMissingJob() {
        final List<String> enqueued = new ArrayList<String>();
        Set<String> processed = new HashSet<String>(Collections.singletonList("source-done"));
        Set<String> scheduled = new HashSet<String>(
                Collections.singletonList("source-scheduled"));
        int count = ProcessingReconciliation.reconcile(Arrays.asList(
                        new Src("source-missing", true, true),
                        new Src("source-done", true, true),      // already processed → not our gap
                        new Src("source-scheduled", true, true), // queue knows it → respected
                        new Src("source-parked", false, true),   // no text → nothing to segment
                        new Src("source-excluded", true, false), // corpus-ineligible → never waste
                        new Src("", true, true)),                // blank id → skip
                processed, scheduled,
                new ProcessingReconciliation.MissingJobScheduler() {
                    public void enqueue(String captureId, String sourceId) {
                        enqueued.add(captureId + "->" + sourceId);
                    }
                });
        assertEquals(1, count);
        assertEquals(Collections.singletonList(
                "import-reconciled-source-missing->source-missing"), enqueued);
    }

    @Test
    public void aRerunAfterRestartNeverDoubleEnqueuesThroughTheQueueIdempotency() throws Exception {
        FileSourceProcessingQueue queue =
                new FileSourceProcessingQueue(tmp.newFolder("processing"));
        // The SAME scheduler kind the acceptance hook uses — real identity stamping.
        final QueueBackedKnowledgeProcessingScheduler scheduler =
                new QueueBackedKnowledgeProcessingScheduler(queue, "seg-v1", "fpReal");
        ProcessingReconciliation.MissingJobScheduler adapter =
                new ProcessingReconciliation.MissingJobScheduler() {
                    public void enqueue(String captureId, String sourceId) {
                        scheduler.enqueue(captureId, sourceId, "en");
                    }
                };
        List<Src> candidates =
                Collections.singletonList(new Src("source-1", true, true));
        Set<String> processed = Collections.emptySet();
        // Two session starts (restart) reconcile the same world. The SECOND run reads the
        // queue observation like the host does — the first run's job counts as scheduled;
        // and even ignoring the observation, the deterministic capture id dedups by key.
        ProcessingReconciliation.reconcile(candidates, processed,
                queue.sourceIdsWithKnownProcessing(), adapter);
        ProcessingReconciliation.reconcile(candidates, processed,
                queue.sourceIdsWithKnownProcessing(), adapter);
        SourceProcessingJob job = queue.takeNext();
        assertEquals("import-reconciled-source-1", job.getRequest().getCaptureId());
        assertEquals("source-1", job.getRequest().getSourceId());
        assertEquals("the job names the REAL vector world", "fpReal",
                job.getRequest().getEmbeddingModelFingerprint());
        assertTrue("exactly one job despite two reconciliation runs",
                queue.takeNext() == null);
    }

    /** The crash window: acceptance enqueued, the app died BEFORE the worker ran. */
    @Test
    public void aPendingAcceptanceJobIsRespectedNeverDoubledUnderAReconciledCaptureId()
            throws Exception {
        FileSourceProcessingQueue queue =
                new FileSourceProcessingQueue(tmp.newFolder("processing"));
        final QueueBackedKnowledgeProcessingScheduler scheduler =
                new QueueBackedKnowledgeProcessingScheduler(queue, "seg-v1", "fpReal");
        // The normal acceptance-time job for source-1, persisted QUEUED, never worked:
        // the knowledge project therefore has NO capture for source-1.
        scheduler.enqueue("capture-original", "source-1", "en");

        int enqueued = ProcessingReconciliation.reconcile(
                Collections.singletonList(new Src("source-1", true, true)),
                Collections.<String>emptySet(),
                queue.sourceIdsWithKnownProcessing(),
                new ProcessingReconciliation.MissingJobScheduler() {
                    public void enqueue(String captureId, String sourceId) {
                        scheduler.enqueue(captureId, sourceId, "en");
                    }
                });
        assertEquals("scheduled work is respected — no second derivation", 0, enqueued);
        SourceProcessingJob only = queue.takeNext();
        assertEquals("the ORIGINAL acceptance job survives untouched", "capture-original",
                only.getRequest().getCaptureId());
        assertTrue("exactly one fachliche job for source-1", queue.takeNext() == null);
    }

    /** A retired SUPERSEDED job names a no-longer-active world — it never blocks repair. */
    @Test
    public void aSupersededJobAloneNeverCountsAsCurrentWorldTruth() throws Exception {
        FileSourceProcessingQueue queue =
                new FileSourceProcessingQueue(tmp.newFolder("processing"));
        QueueBackedKnowledgeProcessingScheduler oldWorld =
                new QueueBackedKnowledgeProcessingScheduler(queue, "seg-v1", "fpOld");
        oldWorld.enqueue("capture-old", "source-1", "en");
        queue.markSuperseded(queue.takeNext());
        assertTrue("SUPERSEDED does not read as known processing",
                queue.sourceIdsWithKnownProcessing().isEmpty());
    }

    @Test
    public void aFakeWorldIsStructurallyImpossibleOnTheReconciliationPath() throws Exception {
        FileSourceProcessingQueue queue =
                new FileSourceProcessingQueue(tmp.newFolder("processing"));
        try {
            new QueueBackedKnowledgeProcessingScheduler(queue, "seg-v1", "  ");
            throw new AssertionError("an empty fingerprint must never reach the queue");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("embedding"));
        }
    }

    /** PR 04: captures processed only in an older vector world are re-derived once in the active world. */
    @Test
    public void capturesOfAnOlderWorldAreReEnqueuedUnderTheirOwnCaptureId() throws Exception {
        FileSourceProcessingQueue queue =
                new FileSourceProcessingQueue(tmp.newFolder("processing"));
        final QueueBackedKnowledgeProcessingScheduler scheduler =
                new QueueBackedKnowledgeProcessingScheduler(queue, "seg-v1", "fpNew");
        ProcessingReconciliation.MissingJobScheduler adapter =
                new ProcessingReconciliation.MissingJobScheduler() {
                    public void enqueue(String captureId, String sourceId) {
                        scheduler.enqueue(captureId, sourceId, "de");
                    }
                };
        java.util.Map<String, String> captures = new java.util.LinkedHashMap<String, String>();
        captures.put("cap-old", "source-1");      // only old-world passages → re-derive
        captures.put("cap-current", "source-2");  // already in the new world → leave
        captures.put("cap-excluded", "source-3"); // not eligible → leave
        Set<String> eligible = new HashSet<String>(Arrays.asList("source-1", "source-2"));
        Set<String> current = Collections.singleton("cap-current");

        assertEquals(1, ProcessingReconciliation.reembedStale(captures, current,
                queue.captureIdsWithKnownProcessing("fpNew"), eligible, adapter));
        assertEquals("a restart does not enqueue it twice", 0, ProcessingReconciliation.reembedStale(captures,
                current, queue.captureIdsWithKnownProcessing("fpNew"), eligible, adapter));
        SourceProcessingJob job = queue.takeNext();
        assertEquals("cap-old", job.getRequest().getCaptureId());
        assertEquals("fpNew", job.getRequest().getEmbeddingModelFingerprint());
        assertTrue(queue.takeNext() == null);
    }
}
