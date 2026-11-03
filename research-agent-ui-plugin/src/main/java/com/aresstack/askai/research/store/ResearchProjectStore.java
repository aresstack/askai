package com.aresstack.askai.research.store;

import java.io.File;

/**
 * A single research project on disk. Layout (relative paths under the project root):
 *
 * <pre>
 * project/
 * ├── artifacts/   outline.md, document.md (+ .meta); legacy projects may still hold concept.md,
 * │                research-notes.md, findings.md, draft.md, final.md — kept untouched, never re-tabbed
 * ├── sources/     &lt;sourceId&gt;.properties
 * ├── tasks/       &lt;taskId&gt;.json — the work packages of the task model (ADR 0001, section 4.4)
 * └── state/       research-session.json, post-search-review.properties
 * </pre>
 *
 * Markdown files stay human-readable; sources and state are structured. Multiple projects are isolated by
 * their root directory. Everything is created lazily on first write.
 */
public final class ResearchProjectStore {

    private final File root;
    private final FileArtifactStore artifactStore;
    private final FileResearchSourceRepository sourceRepository;
    private final SessionStateFileStore sessionStateStore;
    private final FilePostSearchReviewStore reviewStore;
    private final FileResearchTaskStore taskStore;

    public ResearchProjectStore(File projectRoot) {
        this.root = projectRoot;
        this.artifactStore = new FileArtifactStore(new File(projectRoot, "artifacts"));
        this.sourceRepository = new FileResearchSourceRepository(new File(projectRoot, "sources"));
        this.sessionStateStore = new SessionStateFileStore(new File(projectRoot, "state"));
        this.reviewStore = new FilePostSearchReviewStore(new File(projectRoot, "state"));
        this.taskStore = new FileResearchTaskStore(new File(projectRoot, "tasks"));
    }

    public File getRoot() {
        return root;
    }

    public FileArtifactStore artifacts() {
        return artifactStore;
    }

    public FileResearchSourceRepository sources() {
        return sourceRepository;
    }

    public SessionStateFileStore sessionState() {
        return sessionStateStore;
    }

    /** How far the agent has reviewed this project's sources — persisted, so a restart keeps the offer. */
    public FilePostSearchReviewStore postSearchReview() {
        return reviewStore;
    }

    /** The persisted task graph of this project: work packages, artifacts, receipts, budgets. */
    public FileResearchTaskStore tasks() {
        return taskStore;
    }
}
