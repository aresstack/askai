package com.aresstack.askai.research.knowledge.processing;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;

/**
 * A project-scoped, restart-safe FIFO of {@link SourceProcessingJob}s (§4, §23). One UTF-8 {@code .properties}
 * file per job under {@code processing/}; FIFO order is the persisted {@code orderSeq}. A crash between
 * acceptance and processing never loses a job: it survives on disk and a stranded PROCESSING job is recovered
 * to QUEUED. Enqueue is idempotent per {@link SourceProcessingRequest#idempotencyKey()} (§4.3): re-delivering
 * the same request returns the existing job, never a duplicate. All operations are {@code synchronized}; the
 * single {@code SourceProcessingWorker} drains it serially.
 */
public final class FileSourceProcessingQueue implements SourceProcessingQueue {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    private final File dir;
    private long nextSeq;

    public FileSourceProcessingQueue(File processingDir) {
        this.dir = processingDir;
        if (!dir.isDirectory()) {
            dir.mkdirs();
        }
        long max = 0L;
        for (Stored stored : loadAllStored()) {
            max = Math.max(max, stored.orderSeq);
        }
        this.nextSeq = max + 1L;
    }

    @Override
    public synchronized SourceProcessingJob enqueue(SourceProcessingRequest request) {
        String key = request.idempotencyKey();
        for (Stored stored : loadAllStored()) {
            if (key.equals(stored.job.getRequest().idempotencyKey())) {
                if (stored.job.getState() == SourceProcessingJob.State.SUPERSEDED) {
                    // A return to this (earlier) embedding world: re-activate the retired job at the TAIL so the
                    // capture is re-derived under the now-active world instead of staying retired.
                    long reactivateSeq = nextSeq++;
                    SourceProcessingJob back =
                            stored.job.withState(SourceProcessingJob.State.QUEUED);
                    write(new Stored(back, reactivateSeq));
                    return back;
                }
                return stored.job; // idempotent: same fachliche processing already known — no duplicate
            }
        }
        long seq = nextSeq++;
        SourceProcessingJob job = SourceProcessingJob.queued("job-" + seq, request,
                System.currentTimeMillis());
        write(new Stored(job, seq));
        return job;
    }

    @Override
    public synchronized SourceProcessingJob takeNext() {
        Stored next = null;
        for (Stored stored : loadAllStored()) {
            if (stored.job.getState() == SourceProcessingJob.State.QUEUED
                    && (next == null || stored.orderSeq < next.orderSeq)) {
                next = stored;
            }
        }
        if (next == null) {
            return null;
        }
        SourceProcessingJob processing = next.job.startedProcessing();
        write(new Stored(processing, next.orderSeq)); // keep the order seq while PROCESSING
        return processing;
    }

    @Override
    public synchronized void markCompleted(SourceProcessingJob job) {
        write(new Stored(job.withState(SourceProcessingJob.State.COMPLETED),
                orderSeqFromDisk(job.getJobId())));
    }

    @Override
    public synchronized void markFailed(SourceProcessingJob job, SourceProcessingFailure failure) {
        write(new Stored(job.failed(failure), orderSeqFromDisk(job.getJobId())));
    }

    @Override
    public synchronized void markSuperseded(SourceProcessingJob job) {
        write(new Stored(job.superseded(), orderSeqFromDisk(job.getJobId())));
    }

    @Override
    public synchronized void requeue(SourceProcessingJob job) {
        long seq = nextSeq++; // TAIL: a retry never preempts jobs enqueued after it
        write(new Stored(job.withState(SourceProcessingJob.State.QUEUED), seq));
    }

    @Override
    public synchronized List<SourceProcessingJob> recoverStrandedJobs() {
        List<SourceProcessingJob> recovered = new ArrayList<SourceProcessingJob>();
        for (Stored stored : loadAllStored()) {
            if (stored.job.getState() == SourceProcessingJob.State.PROCESSING) {
                SourceProcessingJob back = stored.job.withState(SourceProcessingJob.State.QUEUED);
                write(new Stored(back, stored.orderSeq)); // keep original order (§25)
                recovered.add(back);
            }
        }
        return recovered;
    }

    @Override
    public synchronized boolean isAlreadyCompleted(String idempotencyKey) {
        if (idempotencyKey == null) {
            return false;
        }
        for (SourceProcessingJob job : loadAll()) {
            if (job.getState() == SourceProcessingJob.State.COMPLETED
                    && idempotencyKey.equals(job.getRequest().idempotencyKey())) {
                return true;
            }
        }
        return false;
    }

    @Override
    public synchronized java.util.Set<String> sourceIdsWithKnownProcessing() {
        java.util.Set<String> sourceIds = new java.util.HashSet<String>();
        for (SourceProcessingJob job : loadAll()) {
            String sourceId = job.getRequest().getSourceId();
            if (sourceId != null && !sourceId.trim().isEmpty()
                    && job.getState() != SourceProcessingJob.State.SUPERSEDED) {
                sourceIds.add(sourceId);
            }
        }
        return sourceIds;
    }

    /**
     * Capture ids with a non-superseded job in ONE vector world (PR 04: a changed prompt scheme moves the world, so
     * captures processed in the old one are re-derived unless the new world already knows them).
     */
    public synchronized java.util.Set<String> captureIdsWithKnownProcessing(String embeddingFingerprint) {
        java.util.Set<String> captureIds = new java.util.HashSet<String>();
        for (SourceProcessingJob job : loadAll()) {
            if (job.getState() != SourceProcessingJob.State.SUPERSEDED
                    && embeddingFingerprint != null
                    && embeddingFingerprint.equals(job.getRequest().getEmbeddingModelFingerprint())) {
                captureIds.add(job.getRequest().getCaptureId());
            }
        }
        return captureIds;
    }

    // ------------------------------------------------------------------ persistence

    private File file(String jobId) {
        return new File(dir, jobId.replaceAll("[^a-zA-Z0-9._-]", "_") + ".properties");
    }

    private void write(Stored stored) {
        SourceProcessingJob j = stored.job;
        SourceProcessingRequest r = j.getRequest();
        StringBuilder sb = new StringBuilder();
        line(sb, "jobId", j.getJobId());
        line(sb, "orderSeq", Long.toString(stored.orderSeq));
        line(sb, "captureId", r.getCaptureId());
        line(sb, "sourceId", r.getSourceId());
        line(sb, "segmentationPipelineVersion", r.getSegmentationPipelineVersion());
        line(sb, "embeddingModelFingerprint", r.getEmbeddingModelFingerprint());
        line(sb, "languageCode", r.getLanguageCode());
        line(sb, "state", j.getState().name());
        line(sb, "attempts", Integer.toString(j.getAttempts()));
        line(sb, "enqueuedAt", Long.toString(j.getEnqueuedAtEpochMillis()));
        SourceProcessingFailure f = j.getLastFailure();
        line(sb, "failureStage", f == null ? "" : f.getStage().name());
        line(sb, "failureReason", f == null ? "" : f.getReason());
        line(sb, "failureRetryable", f == null ? "" : Boolean.toString(f.isRetryable()));
        atomicWrite(file(j.getJobId()), sb.toString());
    }

    private static void line(StringBuilder sb, String key, String value) {
        String v = value == null ? "" : value.replace("\\", "\\\\").replace("\r", "\\r").replace("\n", "\\n");
        sb.append(key).append('=').append(v).append('\n');
    }

    private List<SourceProcessingJob> loadAll() {
        List<SourceProcessingJob> jobs = new ArrayList<SourceProcessingJob>();
        for (Stored stored : loadAllStored()) {
            jobs.add(stored.job);
        }
        return jobs;
    }

    private List<Stored> loadAllStored() {
        List<Stored> result = new ArrayList<Stored>();
        File[] files = dir.listFiles();
        if (files == null) {
            return result;
        }
        for (File f : files) {
            if (!f.getName().endsWith(".properties")) {
                continue;
            }
            Stored stored = read(f);
            if (stored != null) {
                result.add(stored);
            }
        }
        // Stable FIFO order for callers that iterate (takeNext re-derives the minimum explicitly).
        Collections.sort(result, new Comparator<Stored>() {
            public int compare(Stored a, Stored b) {
                return Long.compare(a.orderSeq, b.orderSeq);
            }
        });
        return result;
    }

    private Stored read(File f) {
        try {
            Properties p = new Properties();
            p.putAll(Utf8Properties.load(f));
            String jobId = p.getProperty("jobId");
            String captureId = p.getProperty("captureId");
            if (jobId == null || jobId.trim().isEmpty() || captureId == null || captureId.trim().isEmpty()) {
                return null; // corrupt: cannot rebuild the fachliche identity
            }
            SourceProcessingRequest request = new SourceProcessingRequest(captureId,
                    p.getProperty("sourceId", ""), p.getProperty("segmentationPipelineVersion", ""),
                    p.getProperty("embeddingModelFingerprint", ""),
                    p.getProperty("languageCode", "en")); // legacy entries (pre-language) default to en
            SourceProcessingFailure failure = null;
            String stage = p.getProperty("failureStage", "");
            if (!stage.trim().isEmpty()) {
                failure = new SourceProcessingFailure(
                        parseStage(stage), p.getProperty("failureReason", ""),
                        Boolean.parseBoolean(p.getProperty("failureRetryable", "false")));
            }
            SourceProcessingJob job = new SourceProcessingJob(jobId, request,
                    parseState(p.getProperty("state")), parseInt(p.getProperty("attempts")),
                    parseLong(p.getProperty("enqueuedAt")), failure);
            return new Stored(job, parseLong(p.getProperty("orderSeq")));
        } catch (Exception corrupt) {
            return null;
        }
    }

    private static SourceProcessingJob.State parseState(String v) {
        try {
            return v == null ? SourceProcessingJob.State.QUEUED : SourceProcessingJob.State.valueOf(v.trim());
        } catch (IllegalArgumentException ex) {
            return SourceProcessingJob.State.QUEUED;
        }
    }

    private static SourceProcessingStage parseStage(String v) {
        try {
            return SourceProcessingStage.valueOf(v.trim());
        } catch (IllegalArgumentException ex) {
            return SourceProcessingStage.EXTRACTION;
        }
    }

    private static int parseInt(String v) {
        try {
            return v == null ? 0 : Integer.parseInt(v.trim());
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private static long parseLong(String v) {
        try {
            return v == null ? 0L : Long.parseLong(v.trim());
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }

    /** The persisted FIFO order seq of an on-disk job, preserved across a state rewrite (0 if it is gone). */
    private long orderSeqFromDisk(String jobId) {
        File f = file(jobId);
        if (f.isFile()) {
            Stored stored = read(f);
            if (stored != null) {
                return stored.orderSeq;
            }
        }
        return 0L;
    }

    private static void atomicWrite(File target, String content) {
        try {
            File parent = target.getParentFile();
            if (parent != null) {
                Files.createDirectories(parent.toPath());
            }
            File tmp = new File(parent, target.getName() + ".tmp");
            Files.write(tmp.toPath(), content.getBytes(UTF8));
            try {
                Files.move(tmp.toPath(), target.toPath(),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException atomicUnsupported) {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot persist processing job " + target.getName(), ex);
        }
    }

    /** A job together with its persisted FIFO order sequence. */
    private static final class Stored {
        final SourceProcessingJob job;
        final long orderSeq;

        Stored(SourceProcessingJob job, long orderSeq) {
            this.job = job;
            this.orderSeq = orderSeq;
        }
    }
}
