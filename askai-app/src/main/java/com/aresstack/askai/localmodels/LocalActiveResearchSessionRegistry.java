package com.aresstack.askai.localmodels;

import com.aresstack.askai.agent.model.inference.InferenceConfigurationException;
import com.aresstack.askai.agent.model.inference.InferenceConfigurationSnapshotProvider;
import com.aresstack.askai.agent.model.reranker.RerankerConfigurationException;
import com.aresstack.askai.agent.model.reranker.RerankerConfigurationSnapshotProvider;
import com.aresstack.askai.agent.model.session.ActiveResearchSessionRegistry;
import com.aresstack.askai.config.AppConfigurationRepository;

import java.io.File;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The host implementation of {@link ActiveResearchSessionRegistry}: it holds the running research sessions
 * and, when the central model selection changes, re-publishes the AFFECTED per-session descriptor so the
 * agent's descriptor watcher picks up the new model at the next turn — without a session restart. A main
 * model change rewrites {@code inference-config.json}; a reranker/embeddings change rewrites
 * {@code reranker-config.json}. Re-publish failures are logged per session, never thrown (one bad session
 * must not break the others, and a mid-session refresh must never crash the UI thread that triggered it).
 */
public final class LocalActiveResearchSessionRegistry implements ActiveResearchSessionRegistry {

    private final Map<String, File> sessions = new ConcurrentHashMap<String, File>();
    private final InferenceConfigurationSnapshotProvider inferenceProvider; // nullable
    private final RerankerConfigurationSnapshotProvider rerankerProvider;   // nullable
    private final AppConfigurationRepository centralConfig;                 // for the central reranker id

    public LocalActiveResearchSessionRegistry(InferenceConfigurationSnapshotProvider inferenceProvider,
                                              RerankerConfigurationSnapshotProvider rerankerProvider,
                                              AppConfigurationRepository centralConfig) {
        this.inferenceProvider = inferenceProvider;
        this.rerankerProvider = rerankerProvider;
        this.centralConfig = centralConfig;
    }

    @Override
    public void register(String sessionId, File sessionDirectory) {
        if (sessionId != null && sessionDirectory != null) {
            sessions.put(sessionId, sessionDirectory);
        }
    }

    @Override
    public void unregister(String sessionId) {
        if (sessionId != null) {
            sessions.remove(sessionId);
        }
    }

    /** The main (chat) model changed → rewrite each running session's inference descriptor. */
    public void refreshInference() {
        if (inferenceProvider == null) {
            return;
        }
        for (Map.Entry<String, File> session : sessions.entrySet()) {
            try {
                inferenceProvider.prepareForSession(session.getKey(), session.getValue());
            } catch (InferenceConfigurationException ex) {
                System.err.println("[askai] could not refresh inference descriptor for session "
                        + session.getKey() + ": " + ex.getMessage());
            }
        }
    }

    /** The reranker/embeddings selection changed → rewrite each running session's reranker descriptor. */
    public void refreshReranker() {
        if (rerankerProvider == null) {
            return;
        }
        String rerankerModel = centralConfig == null ? ""
                : centralConfig.load().getAiModelSelections().getRerankerModel();
        for (Map.Entry<String, File> session : sessions.entrySet()) {
            try {
                rerankerProvider.prepareForSession(session.getKey(), session.getValue(), rerankerModel);
            } catch (RerankerConfigurationException ex) {
                System.err.println("[askai] could not refresh reranker descriptor for session "
                        + session.getKey() + ": " + ex.getMessage());
            }
        }
    }

    /** Visible for tests / diagnostics: the number of currently registered running sessions. */
    public int activeSessionCount() {
        return sessions.size();
    }
}
