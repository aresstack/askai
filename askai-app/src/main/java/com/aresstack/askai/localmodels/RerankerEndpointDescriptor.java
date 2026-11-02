package com.aresstack.askai.localmodels;

import java.util.Collections;
import java.util.List;

/**
 * The NEUTRAL hand-off contract for A4: the research agent later receives only this — provider,
 * base URL and virtual model name. No paths, no process access, no knowledge of win-directml-java.
 * A4 implements the reranking dialect against {@code POST <baseUrl>/api/rerank}.
 */
public final class RerankerEndpointDescriptor {

    private final String provider;
    private final String baseUrl;
    private final String modelName;
    private final List<String> capabilities;

    public RerankerEndpointDescriptor(String provider, String baseUrl, String modelName,
                                      List<String> capabilities) {
        this.provider = provider;
        this.baseUrl = baseUrl;
        this.modelName = modelName;
        this.capabilities = Collections.unmodifiableList(capabilities);
    }

    /** The descriptor for a local reranker model served by the (started) local runtime. */
    public static RerankerEndpointDescriptor forLocalReranker(LocalModelRuntimeManager manager,
                                                             String virtualModelName)
            throws java.io.IOException {
        return new RerankerEndpointDescriptor("ollama", manager.ensureStarted(), virtualModelName,
                Collections.singletonList(LocalRuntimeCapability.RERANK.getOllamaTag()));
    }

    public String getProvider() {
        return provider;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public String getModelName() {
        return modelName;
    }

    public List<String> getCapabilities() {
        return capabilities;
    }
}
