package com.aresstack.askai.service;

import com.aresstack.askai.AskAiModel;
import com.aresstack.askai.client.AskAiOllamaClient;
import com.aresstack.askai.localmodels.LocalModelNames;

/** The configured remote Ollama server; owns every model WITHOUT the {@code local/} namespace. */
public final class RemoteOllamaContainerSource implements OllamaContainerSource {

    public static final String CONTAINER_ID = "ollama";

    private final AskAiModel model;

    public RemoteOllamaContainerSource(AskAiModel model) {
        this.model = model;
    }

    public String getContainerId() {
        return CONTAINER_ID;
    }

    public String getDisplayName() {
        return "Ollama";
    }

    public String getBaseUrl() {
        return model.getOllamaBaseUrl();
    }

    public boolean isLocal() {
        return false;
    }

    public AskAiOllamaClient createClient() {
        return new AskAiOllamaClient(model.getOllamaBaseUrl());
    }

    public boolean ownsModel(String virtualModelName) {
        return !LocalModelNames.isLocalModelName(virtualModelName);
    }
}
