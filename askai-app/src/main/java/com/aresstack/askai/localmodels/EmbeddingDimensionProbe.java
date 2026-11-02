package com.aresstack.askai.localmodels;

import com.aresstack.askai.agent.model.embedding.EmbeddingConfigurationException;

/**
 * Determines the embedding vector DIMENSION of a running model by a REAL probe request (the dimension is not in
 * the model metadata). A seam so the dimension resolution is isolated and testable; the productive
 * {@link HttpEmbeddingDimensionProbe} POSTs to {@code /api/embed}.
 */
public interface EmbeddingDimensionProbe {

    /**
     * @return the probed vector length (&gt; 0)
     * @throws EmbeddingConfigurationException DIMENSION_PROBE_FAILED (transport/HTTP) or INVALID_PROBE_RESPONSE
     *                                         (missing/empty/non-finite vector) — never a guessed dimension
     */
    int probeDimension(String baseUrl, String virtualModelId) throws EmbeddingConfigurationException;
}
