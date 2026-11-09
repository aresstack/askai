package com.aresstack.askai.research.drafting;

import com.aresstack.askai.research.domain.task.ResearchTask;
import com.aresstack.askai.research.domain.task.TaskBudget;
import com.aresstack.askai.research.domain.task.TaskInputs;
import com.aresstack.askai.research.domain.task.TaskState;
import com.aresstack.askai.research.manuscript.ManuscriptSection;
import com.aresstack.askai.research.manuscript.ManuscriptWorkspace;
import com.aresstack.askai.research.manuscript.OutlineFingerprint;
import com.aresstack.askai.research.manuscript.SectionRevision;
import com.aresstack.askai.research.manuscript.SectionStatus;
import com.aresstack.askai.research.outline.Outline;
import com.aresstack.askai.research.outline.OutlineChapter;
import com.aresstack.askai.research.outline.OutlineSection;
import com.aresstack.askai.research.runtime.orchestrator.TaskBoard;
import com.aresstack.askai.research.store.FileManuscriptStore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The writing job of the Document phase (plan PR 29). It works through the sections of the approved outline in
 * order as tasks on the project's {@link TaskBoard} and saves each section through the {@link ManuscriptWorkspace}
 * as soon as it is written, so a kill loses at most the section in flight: {@link #resume()} returns a task left
 * {@code WORKING} to the queue and the next run writes that section again without touching the written ones.
 * A section whose writer fails twice is marked failed and listed; the job goes on with the next section.
 *
 * <p>The job never dispatches a phase command: it ends with one {@link WritingOutcome} and the workflow stays in
 * {@code draft/running}. Its caller runs it off the UI thread and registers {@link #stop()} as a job of the
 * Document phase, so a phase change cancels the section call in flight and leaves that section pending.</p>
 */
public final class SectionWritingJob {

    public static final String PHASE = "draft";
    public static final String ROLE_WRITE = "section-writer";
    public static final String ROLE_REWRITE = "section-rewriter";
    static final String ENDPOINT = "local";
    /** Attempts per section and job before it is listed as failed. */
    static final int ATTEMPTS = 2;
    /** Re-queues a task may take: the failed attempt plus resumes after app kills. */
    static final int RETRY_LIMIT = 8;

    /** Shown while the job runs, e.g. "Writing 3/24: Methods". */
    public interface Progress {
        void progress(String text);
    }

    public interface Clock {
        long now();
    }

    private final TaskBoard board;
    private final ManuscriptWorkspace manuscript;
    private final SectionWriter writer;
    private final Progress progress;
    private final Clock clock;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile AtomicBoolean cancelled = new AtomicBoolean();

    public SectionWritingJob(TaskBoard board, ManuscriptWorkspace manuscript, SectionWriter writer,
                             Progress progress, Clock clock) {
        this.board = board;
        this.manuscript = manuscript;
        this.writer = writer;
        this.progress = progress == null ? new Progress() {
            public void progress(String text) {
            }
        } : progress;
        this.clock = clock == null ? new Clock() {
            public long now() {
                return System.currentTimeMillis();
            }
        } : clock;
    }

    public boolean isRunning() {
        return running.get();
    }

    /** "Stop writing" and a phase change: cancel the call in flight; that section stays pending. Idempotent. */
    public void stop() {
        cancelled.set(true);
    }

    /**
     * The resume pass on session open: every writer task left {@code WORKING} by a killed app goes back to the
     * queue (or, without retries left, is cancelled so the next run submits a fresh one).
     * @return how many tasks were returned
     */
    public int resume() {
        int returned = 0;
        long now = clock.now();
        for (ResearchTask task : board.tasks()) {
            if (!isWriterTask(task) || task.getState() != TaskState.WORKING) {
                continue;
            }
            board.save(task.hasRetriesLeft() ? task.requeue("interrupted", now) : task.cancel("interrupted", now));
            returned++;
        }
        return returned;
    }

    /** {@code write_sections} and "Write remaining sections": every section without text and without "Skip". */
    public WritingOutcome writeRemaining(Outline approved) {
        List<Target> targets = new ArrayList<Target>();
        for (Target target : targets(approved)) {
            SectionStatus status = statusOf(target.section.getId());
            if (status == SectionStatus.PENDING || status == SectionStatus.FAILED) {
                targets.add(target);
            }
        }
        return run(approved, targets, ROLE_WRITE, null);
    }

    /** "Rewrite stale sections": a new revision of each stale section. */
    public WritingOutcome rewriteStale(Outline approved) {
        List<Target> targets = new ArrayList<Target>();
        for (Target target : targets(approved)) {
            if (statusOf(target.section.getId()) == SectionStatus.STALE) {
                targets.add(target);
            }
        }
        return run(approved, targets, ROLE_REWRITE, "");
    }

    /** "Rewrite section" with an optional comment: a new revision of exactly this one section. */
    public WritingOutcome rewriteSection(Outline approved, String sectionId, String comment) {
        for (Target target : targets(approved)) {
            if (target.section.getId().equals(sectionId)) {
                return run(approved, Collections.singletonList(target), ROLE_REWRITE, comment);
            }
        }
        return WritingOutcome.refused("The section " + sectionId + " is not in the approved outline.");
    }

    /** "Rewrite chapter": every section of one chapter with one comment, one section per call. */
    public WritingOutcome rewriteChapter(Outline approved, String chapterId, String comment) {
        List<Target> targets = new ArrayList<Target>();
        for (Target target : targets(approved)) {
            if (target.chapter.getId().equals(chapterId)) {
                targets.add(target);
            }
        }
        if (targets.isEmpty()) {
            return WritingOutcome.refused("The chapter " + chapterId + " has no sections in the approved outline.");
        }
        return run(approved, targets, ROLE_REWRITE, comment);
    }

    /** Titles of the stale sections in outline order, for "Rewrite stale sections". */
    public List<String> staleSections(Outline approved) {
        List<String> titles = new ArrayList<String>();
        for (Target target : targets(approved)) {
            if (statusOf(target.section.getId()) == SectionStatus.STALE) {
                titles.add(target.section.getTitle());
            }
        }
        return titles;
    }

    /** Titles of the sections that have neither text nor "Skip", in outline order. */
    public List<String> openSections(Outline approved) {
        List<String> titles = new ArrayList<String>();
        for (Target target : targets(approved)) {
            ManuscriptSection state = manuscript.store().section(target.section.getId());
            if (state == null || (!state.hasText() && state.getStatus() != SectionStatus.SKIPPED)) {
                titles.add(target.section.getTitle());
            }
        }
        return titles;
    }

    // ------------------------------------------------------------------ the run

    private WritingOutcome run(Outline approved, List<Target> targets, String role, String comment) {
        String refusal = manuscript.writeRefusal();
        if (refusal != null) {
            return WritingOutcome.refused(refusal);
        }
        if (!running.compareAndSet(false, true)) {
            return WritingOutcome.refused("The writing job is already running.");
        }
        final AtomicBoolean cancel = new AtomicBoolean();
        cancelled = cancel;
        List<String> written = new ArrayList<String>();
        Map<String, String> failed = new LinkedHashMap<String, String>();
        Map<String, String> differences = new LinkedHashMap<String, String>();
        try {
            resume();
            int index = 0;
            for (Target target : targets) {
                index++;
                if (cancel.get()) {
                    break;
                }
                progress.progress((ROLE_WRITE.equals(role) ? "Writing " : "Rewriting ") + index + "/"
                        + targets.size() + ": " + target.section.getTitle());
                String failure = writeOne(target, role, comment, cancel, differences);
                if (failure == null) {
                    written.add(target.section.getId());
                } else if (!cancel.get()) {
                    failed.put(target.section.getTitle(), failure);
                    if (manuscript.writeRefusal() != null) {
                        break;
                    }
                }
            }
        } finally {
            running.set(false);
        }
        return new WritingOutcome(written, failed, openSections(approved), cancel.get(), null, differences);
    }

    /** @return {@code null} when the section was saved, otherwise why not */
    private String writeOne(Target target, String role, String comment, final AtomicBoolean cancel,
                            Map<String, String> differences) {
        String sectionId = target.section.getId();
        ResearchTask task = openTask(sectionId, role);
        SectionWriter.Cancellation cancellation = new SectionWriter.Cancellation() {
            public boolean isCancelled() {
                return cancel.get();
            }
        };
        String reason = "";
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            task = task.start(ENDPOINT, clock.now());
            board.save(task);
            ManuscriptSection state = manuscript.store().section(sectionId);
            int expected = state == null ? 0 : state.getRevision();
            SectionRevision current = state != null && state.hasText()
                    ? manuscript.store().revision(sectionId, state.getRevision()) : null;
            SectionWriteRequest request = comment == null || current == null
                    ? SectionWriteRequest.draft(target.chapter, target.section)
                    : SectionWriteRequest.rewrite(target.chapter, target.section, comment, current.getText());
            try {
                SectionRevision.Builder content = writer.write(request, cancellation);
                if (cancel.get()) {
                    throw new SectionWriter.Cancelled();
                }
                content.sectionFingerprint(OutlineFingerprint.of(target.section))
                        .chapterFingerprint(OutlineFingerprint.of(target.chapter));
                FileManuscriptStore.Write saved = manuscript.save(sectionId, target.chapter.getId(), expected,
                        content, clock.now());
                board.save(task.complete(clock.now()));
                if (current != null) {
                    differences.put(sectionId, TextDiff.lines(current.getText(), saved.revision.getText()));
                }
                return null;
            } catch (SectionWriter.Cancelled stopped) {
                board.save(task.cancel("stopped", clock.now()));
                return "stopped";
            } catch (ManuscriptWorkspace.WriteRefusedException refused) {
                board.save(task.cancel(refused.getMessage(), clock.now()));
                return refused.getMessage();
            } catch (Exception failedAttempt) {
                if (cancel.get()) {
                    board.save(task.cancel("stopped", clock.now()));
                    return "stopped";
                }
                reason = failedAttempt.getMessage() == null ? failedAttempt.getClass().getSimpleName()
                        : failedAttempt.getMessage();
                if (attempt < ATTEMPTS && task.hasRetriesLeft()) {
                    task = task.requeue("failed: " + reason, clock.now());
                    board.save(task);
                } else {
                    break;
                }
            }
        }
        board.save(task.fail(reason, clock.now()));
        if (ROLE_WRITE.equals(role)) {
            try {
                manuscript.markFailed(sectionId, target.chapter.getId(), reason, clock.now());
            } catch (ManuscriptWorkspace.WriteRefusedException ignored) {
                // the outcome names the failure; the guard closed meanwhile
            }
        }
        return reason;
    }

    /** The queued task of this section and role (left by a resume), or a new one. */
    private ResearchTask openTask(String sectionId, String role) {
        String prefix = taskPrefix(role, sectionId);
        int count = 0;
        ResearchTask open = null;
        for (ResearchTask task : board.tasks()) {
            if (task.getTaskId().startsWith(prefix) && task.getRole().equals(role)) {
                count++;
                if (task.getState() == TaskState.SUBMITTED) {
                    open = task;
                }
            }
        }
        if (open != null) {
            return open;
        }
        ResearchTask task = ResearchTask.submit(prefix + (count + 1), PHASE, role,
                Collections.<String>emptyList(), new TaskInputs("section " + sectionId,
                        Collections.singletonList("section:" + sectionId)), "phase.draft", 0,
                TaskBudget.unlimited(), RETRY_LIMIT, clock.now());
        board.save(task);
        return task;
    }

    static String taskPrefix(String role, String sectionId) {
        return (ROLE_WRITE.equals(role) ? "write-" : "rewrite-") + sectionId + "-";
    }

    private static boolean isWriterTask(ResearchTask task) {
        return ROLE_WRITE.equals(task.getRole()) || ROLE_REWRITE.equals(task.getRole());
    }

    private SectionStatus statusOf(String sectionId) {
        ManuscriptSection state = manuscript.store().section(sectionId);
        return state == null ? SectionStatus.PENDING : state.getStatus();
    }

    private static List<Target> targets(Outline approved) {
        List<Target> targets = new ArrayList<Target>();
        if (approved == null) {
            return targets;
        }
        for (OutlineChapter chapter : approved.getChapters()) {
            for (OutlineSection section : chapter.getSections()) {
                targets.add(new Target(chapter, section));
            }
        }
        return targets;
    }

    private static final class Target {
        final OutlineChapter chapter;
        final OutlineSection section;

        Target(OutlineChapter chapter, OutlineSection section) {
            this.chapter = chapter;
            this.section = section;
        }
    }
}
