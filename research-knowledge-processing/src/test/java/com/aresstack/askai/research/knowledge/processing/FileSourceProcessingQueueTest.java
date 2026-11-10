package com.aresstack.askai.research.knowledge.processing;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** The persistent FIFO: order, idempotency, already-completed, crash recovery and restart survival (§4, §31). */
public class FileSourceProcessingQueueTest {

    private static File tempDir() throws IOException {
        return java.nio.file.Files.createTempDirectory("askai-proc-queue").toFile();
    }

    private static SourceProcessingRequest req(String captureId) {
        return new SourceProcessingRequest(captureId, "src-shared", "seg-v1", "emb-fp-1");
    }

    @Test
    public void jobsAreTakenInFifoOrder() throws IOException {
        FileSourceProcessingQueue q = new FileSourceProcessingQueue(tempDir());
        q.enqueue(req("cap-1"));
        q.enqueue(req("cap-2"));
        q.enqueue(req("cap-3"));
        assertEquals("cap-1", q.takeNext().getRequest().getCaptureId());
        assertEquals("cap-2", q.takeNext().getRequest().getCaptureId());
        assertEquals("cap-3", q.takeNext().getRequest().getCaptureId());
        assertNull("queue drained", q.takeNext());
    }

    @Test
    public void duplicateEnqueueOfTheSameKeyIsIdempotent() throws IOException {
        FileSourceProcessingQueue q = new FileSourceProcessingQueue(tempDir());
        SourceProcessingJob first = q.enqueue(req("cap-1"));
        SourceProcessingJob again = q.enqueue(req("cap-1")); // same idempotency key
        assertEquals("no duplicate job", first.getJobId(), again.getJobId());
        assertNotNull(q.takeNext());
        assertNull("only ONE job existed for the duplicate enqueue", q.takeNext());
    }

    @Test
    public void aNewCaptureOfTheSameSourceIsItsOwnJob() throws IOException {
        FileSourceProcessingQueue q = new FileSourceProcessingQueue(tempDir());
        SourceProcessingJob a = q.enqueue(req("cap-1"));
        SourceProcessingJob b = q.enqueue(req("cap-2")); // same sourceId, different capture → different key
        assertFalse(a.getJobId().equals(b.getJobId()));
        assertNotNull(q.takeNext());
        assertNotNull(q.takeNext());
        assertNull(q.takeNext());
    }

    @Test
    public void anAlreadyCompletedKeyIsRecognisedAndNotRequeued() throws IOException {
        FileSourceProcessingQueue q = new FileSourceProcessingQueue(tempDir());
        q.enqueue(req("cap-1"));
        SourceProcessingJob taken = q.takeNext();
        q.markCompleted(taken);
        assertTrue(q.isAlreadyCompleted(req("cap-1").idempotencyKey()));
        // Re-enqueuing the same key returns the existing (completed) job, and it is not taken again.
        SourceProcessingJob reEnqueued = q.enqueue(req("cap-1"));
        assertEquals(taken.getJobId(), reEnqueued.getJobId());
        assertNull(q.takeNext());
    }

    @Test
    public void aStrandedProcessingJobIsRecoveredToQueuedAfterRestart() throws IOException {
        File dir = tempDir();
        FileSourceProcessingQueue q = new FileSourceProcessingQueue(dir);
        q.enqueue(req("cap-1"));
        SourceProcessingJob taken = q.takeNext(); // now PROCESSING, then the "process crashes"
        assertEquals(SourceProcessingJob.State.PROCESSING, taken.getState());

        // Restart: a fresh queue over the SAME directory recovers the stranded PROCESSING job.
        FileSourceProcessingQueue restarted = new FileSourceProcessingQueue(dir);
        List<SourceProcessingJob> recovered = restarted.recoverStrandedJobs();
        assertEquals(1, recovered.size());
        assertEquals(SourceProcessingJob.State.QUEUED, recovered.get(0).getState());
        assertEquals("cap-1", restarted.takeNext().getRequest().getCaptureId());
    }

    @Test
    public void queuedJobsSurviveARestart() throws IOException {
        File dir = tempDir();
        new FileSourceProcessingQueue(dir).enqueue(req("cap-1"));
        new FileSourceProcessingQueue(dir).enqueue(req("cap-2"));
        FileSourceProcessingQueue restarted = new FileSourceProcessingQueue(dir);
        assertEquals("cap-1", restarted.takeNext().getRequest().getCaptureId());
        assertEquals("cap-2", restarted.takeNext().getRequest().getCaptureId());
    }

    @Test
    public void pendingSourcesAreQueuedRunningOrRetryableJobs() throws IOException {
        FileSourceProcessingQueue q = new FileSourceProcessingQueue(tempDir());
        q.enqueue(new SourceProcessingRequest("cap-1", "src-a", "seg-v1", "emb-fp-1"));
        q.enqueue(new SourceProcessingRequest("cap-2", "src-b", "seg-v1", "emb-fp-1"));
        assertEquals(new java.util.TreeSet<String>(java.util.Arrays.asList("src-a", "src-b")), q.pendingSourceIds());
        SourceProcessingJob first = q.takeNext();
        assertTrue("a running job is still pending", q.pendingSourceIds().contains("src-a"));
        q.markCompleted(first);
        assertEquals(java.util.Collections.singleton("src-b"), q.pendingSourceIds());
    }
}
