package com.aresstack.askai.localmodels;

import com.aresstack.askai.client.AskAiOllamaClient;
import com.aresstack.askai.client.OllamaModelInfo;
import com.aresstack.askai.client.OllamaRequestException;

import java.io.IOException;

/**
 * The productive {@link OllamaEmbeddingConfigurationSnapshotProvider.ModelDigestLookup} over the EXISTING Ollama
 * client: lists installed models ({@code /api/tags}) at the given base URL and returns the requested model's
 * immutable digest, or {@code null} when it is not installed. No second Ollama configuration.
 */
public final class AskAiOllamaModelDigestLookup
        implements OllamaEmbeddingConfigurationSnapshotProvider.ModelDigestLookup {

    @Override
    public String digestOf(String baseUrl, String modelId) throws IOException {
        try {
            for (OllamaModelInfo info : new AskAiOllamaClient(baseUrl).getInstalledModels()) {
                if (modelId.equals(info.getDisplayName()) || modelId.equals(info.getModel())
                        || modelId.equals(info.getName())) {
                    return info.getDigest();
                }
            }
            return null; // not installed
        } catch (OllamaRequestException ex) {
            throw new IOException(ex.getMessage(), ex);
        }
    }
}
