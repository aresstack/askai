package com.aresstack.askai.research.drafting;

import com.aresstack.askai.research.outline.Outline;
import com.aresstack.askai.research.transition.PhaseJobRegistry;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Runs the {@link SectionWritingJob} off the UI thread as a job of the Document phase (plan PR 29): it registers
 * in the {@link PhaseJobRegistry}, so a phase change stops it and the section in flight stays pending, and it
 * reports progress and its one outcome to a listener. An outcome of an old run token is dropped.
 */
public final class WritingJobRunner {

    /** What to write. */
    public enum Kind { REMAINING, STALE, SECTION, CHAPTER }

    public interface Listener {
        void progress(String text);

        void finished(WritingOutcome outcome);
    }

    public interface Context {
        /** The approved outline the job writes from, or {@code null} without one. */
        Outline approvedOutline();

        /** The live phase id. */
        String phaseId();

        /** Called before each run (e.g. re-read the active passages). */
        void beforeRun();
    }

    private final SectionWritingJob job;
    private final PhaseJobRegistry registry;
    private final Context context;
    private final Listener listener;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(new ThreadFactory() {
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "research-section-writer");
            thread.setDaemon(true);
            return thread;
        }
    });
    private volatile String status = "";

    public WritingJobRunner(SectionWritingJob job, PhaseJobRegistry registry, Context context, Listener listener) {
        this.job = job;
        this.registry = registry;
        this.context = context;
        this.listener = listener;
    }

    public SectionWritingJob job() {
        return job;
    }

    public boolean isRunning() {
        return job.isRunning();
    }

    /** The current section line ("Writing 3/24: ..."), or "" while idle. */
    public String status() {
        return status;
    }

    /** @return {@code null} when the run started, otherwise why not */
    public String start(final Kind kind, final String id, final String comment) {
        final Outline outline = context.approvedOutline();
        if (outline == null) {
            return "There is no approved outline to write from.";
        }
        String refusal = job.isRunning() ? "The writing job is already running." : null;
        if (refusal != null) {
            return refusal;
        }
        final String phase = context.phaseId();
        final PhaseJobRegistry.Job registered = new PhaseJobRegistry.Job() {
            public void cancel() {
                job.stop();
            }
        };
        final long token = registry.register(phase, registered);
        executor.execute(new Runnable() {
            public void run() {
                WritingOutcome outcome;
                try {
                    context.beforeRun();
                    outcome = WritingJobRunner.this.run(kind, outline, id, comment);
                } catch (RuntimeException failed) {
                    outcome = WritingOutcome.refused("Writing failed: " + failed.getMessage());
                } finally {
                    registry.unregister(registered);
                    status = "";
                }
                if (registry.isCurrent(phase, token, context.phaseId())) {
                    listener.finished(outcome);
                }
            }
        });
        return null;
    }

    /** "Stop writing". */
    public void stop() {
        job.stop();
    }

    /** Called by the job's progress port. */
    public void progress(String text) {
        status = text == null ? "" : text;
        listener.progress(status);
    }

    private WritingOutcome run(Kind kind, Outline outline, String id, String comment) {
        switch (kind) {
            case STALE:
                return job.rewriteStale(outline);
            case SECTION:
                return job.rewriteSection(outline, id, comment);
            case CHAPTER:
                return job.rewriteChapter(outline, id, comment);
            default:
                return job.writeRemaining(outline);
        }
    }

    public void shutdown() {
        job.stop();
        executor.shutdownNow();
    }
}
