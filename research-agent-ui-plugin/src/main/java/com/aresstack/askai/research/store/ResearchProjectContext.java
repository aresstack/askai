package com.aresstack.askai.research.store;

import com.aresstack.askai.plugin.api.agent.artifact.AgentArtifactStore;
import com.aresstack.askai.research.sources.ResearchSourceRepository;

import java.io.File;

/**
 * THE persistent project context of one research project (Commit 1 of the guided artifact flow):
 * exactly one {@link AgentArtifactStore}, one {@link ResearchSourceRepository}, one
 * {@link SessionStateFileStore} and one {@link ResearchProjectMetadataStore} per project — all
 * file-backed under one project directory, all shared by the session resources, the MCP control
 * endpoint, the session and the UI. The invariant is: one project → one store each; the productive
 * path never constructs an in-memory artifact store.
 */
public final class ResearchProjectContext {

    private final String projectId;
    private final File projectDirectory;
    private final ResearchProjectStore store;
    private final ResearchProjectMetadataStore metadataStore;
    private final FileResearchScopeDraftStore scopeDraftStore;

    private ResearchProjectContext(String projectId, File projectDirectory,
                                   ResearchProjectStore store,
                                   ResearchProjectMetadataStore metadataStore,
                                   FileResearchScopeDraftStore scopeDraftStore) {
        this.projectId = projectId;
        this.projectDirectory = projectDirectory;
        this.store = store;
        this.metadataStore = metadataStore;
        this.scopeDraftStore = scopeDraftStore;
    }

    /** Open (or lazily create on first write) the persistent context of one project directory. */
    public static ResearchProjectContext open(String projectId, File projectDirectory) {
        return new ResearchProjectContext(projectId, projectDirectory,
                new ResearchProjectStore(projectDirectory),
                new ResearchProjectMetadataStore(projectDirectory),
                new FileResearchScopeDraftStore(projectDirectory));
    }

    public String getProjectId() {
        return projectId;
    }

    public File getProjectDirectory() {
        return projectDirectory;
    }

    public AgentArtifactStore getArtifactStore() {
        return store.artifacts();
    }

    public ResearchSourceRepository getSourceRepository() {
        return store.sources();
    }

    /** The concrete file repository (needed by services that persist records themselves). */
    public FileResearchSourceRepository getFileSourceRepository() {
        return store.sources();
    }

    public SessionStateFileStore getSessionStateStore() {
        return store.sessionState();
    }

    /** The persisted review watermark of this project (which sources the agent has already reviewed). */
    public FilePostSearchReviewStore getPostSearchReviewStore() {
        return store.postSearchReview();
    }

    public ResearchProjectMetadataStore getMetadataStore() {
        return metadataStore;
    }

    /** The structured, versioned scope draft of this project — the working result of the scoping phase. */
    public FileResearchScopeDraftStore getScopeDraftStore() {
        return scopeDraftStore;
    }

    /** The persisted task model of this project (ADR 0001, section 4.4). */
    public FileResearchTaskStore getTaskStore() {
        return store.tasks();
    }
}
