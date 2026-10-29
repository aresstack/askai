package com.aresstack.askai.java8.localmodels;

import com.aresstack.askai.agent.model.embedding.EmbeddingConfigurationException;

import com.aresstack.askai.java8.json.SimpleJson;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

/**
 * The productive {@link EmbeddingModelResolver}: mirrors the reranker catalog structurally, but is fachlich its
 * own thing — it resolves ONLY the explicitly configured model id and validates it declares the
 * {@code EMBEDDING} capability and a usable ({@code RUNNABLE}) state. It never lists-and-picks and never falls
 * back to a reranker or a "first found" model. The resolved revision (the manifest's {@code resolvedRevision},
 * the model's best available version identity) is returned so the descriptor's world fingerprint is version-aware.
 */
public final class LocalEmbeddingModelCatalog implements EmbeddingModelResolver {

    private static final Charset UTF_8 = Charset.forName("UTF-8");
    private static final String STATE_RUNNABLE = "RUNNABLE";
    private static final String MANIFEST_NAME = "askai-local-model.json";

    private final LocalModelRuntimeManager manager;

    public LocalEmbeddingModelCatalog(LocalModelRuntimeManager manager) {
        this.manager = manager;
    }

    @Override
    public ResolvedEmbeddingModel resolve(String virtualModelId) throws EmbeddingConfigurationException {
        return resolveIn(manager == null ? null : manager.getModelRoot(), virtualModelId);
    }

    /** Testable core: scan {@code modelRoot} for the manifest whose virtualName equals the requested id. */
    static ResolvedEmbeddingModel resolveIn(File modelRoot, String virtualModelId)
            throws EmbeddingConfigurationException {
        Map<String, Object> match = findManifest(modelRoot, virtualModelId);
        if (match == null) {
            throw new EmbeddingConfigurationException(EmbeddingConfigurationException.Reason.MODEL_NOT_FOUND,
                    "no installed local model with id '" + virtualModelId + "'");
        }
        if (!declaresEmbedding(match)) {
            throw new EmbeddingConfigurationException(
                    EmbeddingConfigurationException.Reason.MODEL_NOT_EMBEDDING_CAPABLE,
                    "model '" + virtualModelId + "' is installed but not embedding-capable");
        }
        Object state = match.get("state");
        if (state != null && !STATE_RUNNABLE.equals(state)) {
            throw new EmbeddingConfigurationException(
                    EmbeddingConfigurationException.Reason.MODEL_NOT_RUNNABLE,
                    "embedding model '" + virtualModelId + "' is not runnable (state=" + state + ")");
        }
        Object revision = match.get("resolvedRevision");
        return new ResolvedEmbeddingModel(virtualModelId, revision instanceof String ? (String) revision : "");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> findManifest(File modelRoot, String virtualModelId) {
        File[] children = modelRoot == null ? null : modelRoot.listFiles();
        if (children == null || virtualModelId == null) {
            return null;
        }
        for (File child : children) {
            if (!child.isDirectory()) {
                continue;
            }
            Map<String, Object> manifest = readManifest(new File(child, MANIFEST_NAME));
            if (manifest != null && virtualModelId.equals(manifest.get("virtualName"))) {
                return manifest;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readManifest(File manifestFile) {
        if (!manifestFile.isFile()) {
            return null;
        }
        try {
            Object parsed = SimpleJson.parse(new String(Files.readAllBytes(manifestFile.toPath()), UTF_8));
            return parsed instanceof Map ? (Map<String, Object>) parsed : null;
        } catch (IOException | RuntimeException unreadable) {
            return null; // a corrupt manifest is simply not a usable model
        }
    }

    private static boolean declaresEmbedding(Map<String, Object> manifest) {
        Object capabilities = manifest.get("capabilities");
        if (!(capabilities instanceof List)) {
            return false;
        }
        for (Object capability : (List<?>) capabilities) {
            if (LocalRuntimeCapability.EMBEDDING.getOllamaTag().equals(capability)) {
                return true;
            }
        }
        return false;
    }
}
