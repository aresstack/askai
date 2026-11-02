package com.aresstack.askai.localmodels;

import com.aresstack.askai.agent.model.embedding.EmbeddingConfigurationException;
import com.aresstack.askai.agent.model.embedding.EmbeddingConfigurationSnapshot;
import com.aresstack.askai.agent.model.embedding.EmbeddingConfigurationSnapshotProvider;
import com.aresstack.askai.agent.model.embedding.EmbeddingEndpointDescriptor;

import java.io.File;
import java.io.IOException;

/**
 * Resolves a configured OLLAMA embedding model (e.g. {@code nomic-embed-text:latest}) into an
 * {@link EmbeddingEndpointDescriptor} pointing at the EXISTING Ollama endpoint's {@code /api/embed}. It reuses the
 * central Ollama base URL and the installed-model metadata (digest) — it does NOT touch the AskAI local-model
 * runtime/catalog, and it never falls back to another model. This is the Ollama arm of the provider-crossing host
 * resolver; {@code research-knowledge-processing} still sees only the neutral descriptor.
 *
 * <p>Fingerprint identity = model id + Ollama DIGEST (the immutable manifest sha256 from {@code /api/tags}, not a
 * local {@code resolvedRevision}) + probed dimension + normalization {@code "none"}. The base URL/port are NOT part
 * of the vector world, so an Ollama restart on another port keeps the same fingerprint.</p>
 */
public final class OllamaEmbeddingConfigurationSnapshotProvider
        implements EmbeddingConfigurationSnapshotProvider {

    private static final String ENDPOINT_PATH = "/api/embed";
    private static final String NORMALIZATION = "none";

    /** The current central Ollama base URL (read per call so a config change is reflected). */
    public interface OllamaEndpoint {
        String baseUrl();
    }

    /** Looks up an installed Ollama model's immutable digest via {@code /api/tags}; null when not installed. */
    public interface ModelDigestLookup {
        String digestOf(String baseUrl, String modelId) throws IOException;
    }

    private final OllamaEndpoint endpoint;
    private final ModelDigestLookup digests;
    private final EmbeddingDimensionProbe dimensionProbe;
    private final long timeoutMillis;

    public OllamaEmbeddingConfigurationSnapshotProvider(OllamaEndpoint endpoint, ModelDigestLookup digests,
                                                        EmbeddingDimensionProbe dimensionProbe,
                                                        long timeoutMillis) {
        this.endpoint = endpoint;
        this.digests = digests;
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
        String model = selectedModel.trim();
        String baseUrl = endpoint == null ? "" : endpoint.baseUrl();
        if (baseUrl == null || baseUrl.trim().isEmpty()) {
            throw new EmbeddingConfigurationException(
                    EmbeddingConfigurationException.Reason.RUNTIME_START_FAILED,
                    "no Ollama endpoint is configured for embeddings");
        }
        baseUrl = baseUrl.trim();

        // Existence + immutable identity from /api/tags — never a fallback to the local catalog or another model.
        String digest;
        try {
            digest = digests.digestOf(baseUrl, model);
        } catch (IOException ex) {
            throw new EmbeddingConfigurationException(
                    EmbeddingConfigurationException.Reason.RUNTIME_START_FAILED,
                    "the Ollama endpoint at " + baseUrl + " could not be reached: " + ex.getMessage(), ex);
        }
        if (digest == null) {
            throw new EmbeddingConfigurationException(
                    EmbeddingConfigurationException.Reason.MODEL_NOT_FOUND,
                    "embedding model '" + model + "' is not an installed Ollama model at " + baseUrl);
        }

        // The dimension is verified by a REAL /api/embed probe (also validates embedding capability implicitly).
        int dimension = dimensionProbe.probeDimension(baseUrl, model);

        EmbeddingEndpointDescriptor descriptor = new EmbeddingEndpointDescriptor(model, baseUrl, ENDPOINT_PATH,
                dimension, NORMALIZATION, digest, timeoutMillis);
        return new EmbeddingConfigurationSnapshot("", descriptor);
    }
}
