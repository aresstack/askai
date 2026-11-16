package com.aresstack.askai.localmodels;

import java.io.File;

/**
 * Resolves the sentence-embedding slot (sentence-against-sentence comparisons: near repetitions, citation check, topic
 * drift): the configured model when it is usable, otherwise the main embedding model. A {@code local/} model is usable
 * once it is installed and catalog-valid; an Ollama model is taken as configured.
 */
public final class SentenceEmbeddingModelResolver {

    private final LocalRuntimeSettingsRepository settings;
    private final File localModelRoot;

    public SentenceEmbeddingModelResolver(LocalRuntimeSettingsRepository settings, File localModelRoot) {
        this.settings = settings;
        this.localModelRoot = localModelRoot;
    }

    /** @param mainEmbeddingModel the configured main embedding model (fallback) */
    public String resolve(String mainEmbeddingModel) {
        String main = mainEmbeddingModel == null ? "" : mainEmbeddingModel.trim();
        String configured = settings == null ? "" : settings.load().getSentenceEmbeddingModel();
        if (configured.isEmpty()) {
            return main;
        }
        if (LocalModelNames.isLocalModelName(configured)
                && LocalInstalledModels.readByVirtualName(localModelRoot, configured) == null) {
            return main;
        }
        return configured;
    }
}
