package com.aresstack.askai.localmodels;

import com.aresstack.askai.agent.model.embedding.EmbeddingConfigurationException;
import com.aresstack.askai.agent.model.embedding.EmbeddingConfigurationSnapshot;
import com.aresstack.askai.agent.model.embedding.EmbeddingConfigurationSnapshotProvider;
import com.aresstack.askai.agent.model.embedding.EmbeddingEndpointDescriptor;

import java.io.File;
import java.io.IOException;

/**
 * The productive host {@link EmbeddingConfigurationSnapshotProvider}: it resolves the EXPLICITLY configured
 * embedding model (never a guess, never a reranker), ensures its runtime is started, PROBES the real vector
 * dimension, and builds an immutable {@link EmbeddingEndpointDescriptor}. It introduces NO new model
 * management — it composes an {@link EmbeddingModelResolver} (over the existing local-model catalog), a
 * {@link LocalEmbeddingRuntime} (over the existing runtime manager) and an {@link EmbeddingDimensionProbe}.
 *
 * <p>Verified, binding rules: endpoint {@code /api/embed}, {@code input_type=raw}, dimension from a real probe,
 * normalization {@code "none"} (the runtime returns raw floats — no L2 claim), model version fingerprint =
 * {@code resolvedRevision} (a missing revision is rejected, never silently "unknown"). Any failure is a typed
 * {@link EmbeddingConfigurationException} — no fallback.</p>
 */
public final class LocalEmbeddingConfigurationSnapshotProvider
        implements EmbeddingConfigurationSnapshotProvider {

    private static final String ENDPOINT_PATH = "/api/embed";
    private static final String NORMALIZATION = "none";

    private final EmbeddingModelResolver resolver;
    private final LocalEmbeddingRuntime runtime;
    private final EmbeddingDimensionProbe dimensionProbe;
    private final long timeoutMillis;

    public LocalEmbeddingConfigurationSnapshotProvider(EmbeddingModelResolver resolver,
                                                       LocalEmbeddingRuntime runtime,
                                                       EmbeddingDimensionProbe dimensionProbe,
                                                       long timeoutMillis) {
        this.resolver = resolver;
        this.runtime = runtime;
        this.dimensionProbe = dimensionProbe;
        this.timeoutMillis = timeoutMillis;
    }

    @Override
    public EmbeddingConfigurationSnapshot prepareForSession(String sessionId, File sessionDirectory,
                                                            String selectedModel)
            throws EmbeddingConfigurationException {
        if (selectedModel == null || selectedModel.trim().isEmpty()) {
            throw new EmbeddingConfigurationException(
                    EmbeddingConfigurationException.Reason.MODEL_NOT_CONFIGURED,
                    "no embedding model is configured for this session");
        }
        // 1. Resolve exactly the configured model (throws NOT_FOUND / NOT_EMBEDDING_CAPABLE / NOT_RUNNABLE).
        EmbeddingModelResolver.ResolvedEmbeddingModel model = resolver.resolve(selectedModel.trim());

        // 2. A usable embedding world needs a version identity; a missing revision is not guessed.
        if (model.resolvedRevision.trim().isEmpty()) {
            throw new EmbeddingConfigurationException(
                    EmbeddingConfigurationException.Reason.MISSING_MODEL_REVISION,
                    "embedding model '" + model.virtualModelId + "' has no resolved revision — cannot "
                            + "guarantee a stable vector world");
        }

        // 3. Start the runtime and obtain the base URL (a restart may change the port — never part of identity).
        String baseUrl;
        try {
            baseUrl = runtime.ensureStarted(model.virtualModelId);
        } catch (IOException ex) {
            throw new EmbeddingConfigurationException(
                    EmbeddingConfigurationException.Reason.RUNTIME_START_FAILED,
                    "the local model runtime for embeddings could not be started: " + ex.getMessage(), ex);
        }
        if (baseUrl == null || baseUrl.trim().isEmpty()) {
            throw new EmbeddingConfigurationException(
                    EmbeddingConfigurationException.Reason.RUNTIME_START_FAILED,
                    "the local model runtime reported no usable base URL for embeddings");
        }

        // 4. The dimension is verified by a REAL probe, never derived from a model-name table.
        int dimension = dimensionProbe.probeDimension(baseUrl, model.virtualModelId);

        EmbeddingEndpointDescriptor descriptor = new EmbeddingEndpointDescriptor(model.virtualModelId,
                baseUrl, ENDPOINT_PATH, dimension, NORMALIZATION, model.resolvedRevision, timeoutMillis);
        return new EmbeddingConfigurationSnapshot("", descriptor);
    }
}
