package com.aresstack.askai.java8.service;

import com.aresstack.askai.java8.client.AskAiOllamaClient;
import com.aresstack.askai.java8.localmodels.LocalModelNames;
import com.aresstack.askai.java8.localmodels.LocalModelRuntimeManager;

/**
 * AskAI's own local model runtime as a virtual Ollama container; owns every {@code local/} model.
 * {@link #createClient()} starts the local runtime on demand (there is no fixed port — the
 * manager reads the base URL from the in-process runtime).
 */
public final class LocalAskAiContainerSource implements OllamaContainerSource {

    public static final String CONTAINER_ID = "askai-local";

    private final LocalModelRuntimeManager manager;

    public LocalAskAiContainerSource(LocalModelRuntimeManager manager) {
        this.manager = manager;
    }

    public String getContainerId() {
        return CONTAINER_ID;
    }

    public String getDisplayName() {
        return "Local";
    }

    public String getBaseUrl() {
        String baseUrl = manager.getBaseUrl();
        return baseUrl == null ? "" : baseUrl;
    }

    public boolean isLocal() {
        return true;
    }

    /**
     * True when this source should contribute to lists: running, or installed models exist AND the
     * runtime is actually startable. The local runtime is optional — when it is not staged, the source
     * simply contributes nothing and every list loads from the other sources without any wait.
     */
    public boolean hasAnythingToServe() {
        return manager.isRunning() || (manager.hasInstalledModels() && manager.isAvailable());
    }

    public AskAiOllamaClient createClient() throws Exception {
        return new AskAiOllamaClient(manager.ensureStarted());
    }

    public boolean ownsModel(String virtualModelName) {
        return LocalModelNames.isLocalModelName(virtualModelName);
    }
}
