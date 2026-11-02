package com.aresstack.askai.localmodels;

import com.aresstack.askai.agent.model.embedding.EmbeddingConfigurationException;

/**
 * Resolves the EXPLICITLY configured embedding model id into its usable identity — never picks a model itself.
 * A seam so the snapshot provider is testable without the concrete model catalog / runtime manager.
 */
public interface EmbeddingModelResolver {

    /** The resolved usable embedding model: its virtual id and its version fingerprint (resolvedRevision). */
    final class ResolvedEmbeddingModel {
        public final String virtualModelId;
        public final String resolvedRevision;

        public ResolvedEmbeddingModel(String virtualModelId, String resolvedRevision) {
            this.virtualModelId = virtualModelId == null ? "" : virtualModelId;
            this.resolvedRevision = resolvedRevision == null ? "" : resolvedRevision;
        }
    }

    /**
     * @param virtualModelId the configured embedding model id (never resolved by guessing)
     * @throws EmbeddingConfigurationException MODEL_NOT_FOUND / MODEL_NOT_EMBEDDING_CAPABLE / MODEL_NOT_RUNNABLE
     */
    ResolvedEmbeddingModel resolve(String virtualModelId) throws EmbeddingConfigurationException;
}
