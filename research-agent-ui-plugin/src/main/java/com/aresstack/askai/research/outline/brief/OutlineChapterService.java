package com.aresstack.askai.research.outline.brief;

import com.aresstack.askai.research.outline.Outline;
import com.aresstack.askai.research.outline.OutlineJobGuard;
import com.aresstack.askai.research.outline.OutlineWorkspace;

import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * The session's single-chapter operations of plan PR 24, shared by the Outline tab and the
 * {@code outline_regenerate_chapter} tool: each runs as one job of the session's {@link OutlineJobGuard} (so
 * {@code finish-outline} and a second job are refused meanwhile), reads the working outline, and saves the result
 * with the revision it read. The workspace's write guard refuses writes outside {@code outline/running}.
 */
public final class OutlineChapterService {

    /** An operation's outcome as one line for the user or the model, and the brief failure if any. */
    public static final class Outcome {
        private final String chapterId;
        private final String message;
        private final String briefFailure;

        Outcome(String chapterId, String message, String briefFailure) {
            this.chapterId = chapterId;
            this.message = message;
            this.briefFailure = briefFailure;
        }

        /** The regenerated or the newly adopted chapter's id. */
        public String getChapterId() {
            return chapterId;
        }

        public String getMessage() {
            return message;
        }

        /** "Brief not generated ..." when the default brief stayed, or {@code null}. */
        public String getBriefFailure() {
            return briefFailure;
        }
    }

    private final OutlineWorkspace workspace;
    private final OutlineJobGuard jobs;
    private final ChapterRegenerator regenerator;
    private final Supplier<BriefContext> context;
    private final LongSupplier clock;

    public OutlineChapterService(OutlineWorkspace workspace, OutlineJobGuard jobs, ChapterRegenerator regenerator,
                                 Supplier<BriefContext> context, LongSupplier clock) {
        this.workspace = workspace;
        this.jobs = jobs;
        this.regenerator = regenerator;
        this.context = context;
        this.clock = clock == null ? System::currentTimeMillis : clock;
    }

    /**
     * "Regenerate chapter": {@code sectionCount} 0 keeps the sections and briefs them again, 2 to 6 re-splits a
     * sub-cluster chapter.
     * @throws IllegalArgumentException or {@link IllegalStateException} with a reason for the user
     */
    public Outcome regenerateChapter(String chapterId, int sectionCount, String comment) {
        refuseWhenNotWritable();
        try (OutlineJobGuard.Job ignored = jobs.begin("Regenerating a chapter")) {
            long revision = workspace.workingRevision();
            Outline stored = workspace.current();
            ChapterRegenerator.Result result = regenerator.regenerate(stored, context.get(), chapterId,
                    sectionCount, comment);
            workspace.save(result.getOutline(), revision, clock.getAsLong());
            String title = stored.chapter(chapterId).getTitle();
            return new Outcome(chapterId, result.getBriefFailure() == null ? "Regenerated \"" + title + "\"."
                    : "Regenerated \"" + title + "\". " + result.getBriefFailure(), result.getBriefFailure());
        }
    }

    /** "Add suggested chapter": adopts the stored candidate {@code clusterId} as a new last chapter. */
    public Outcome adoptCandidate(String clusterId) {
        refuseWhenNotWritable();
        try (OutlineJobGuard.Job ignored = jobs.begin("Adding a suggested chapter")) {
            long revision = workspace.workingRevision();
            ChapterRegenerator.Result result = regenerator.adopt(workspace.current(), context.get(), clusterId);
            java.util.List<com.aresstack.askai.research.outline.OutlineChapter> saved =
                    workspace.save(result.getOutline(), revision, clock.getAsLong()).outline.getChapters();
            return new Outcome(saved.get(saved.size() - 1).getId(), result.getBriefFailure() == null ? "Added the suggested chapter."
                    : "Added the suggested chapter. " + result.getBriefFailure(), result.getBriefFailure());
        }
    }

    private void refuseWhenNotWritable() {
        String refusal = workspace.writeRefusal();
        if (refusal != null) {
            throw new IllegalStateException(refusal);
        }
    }
}
