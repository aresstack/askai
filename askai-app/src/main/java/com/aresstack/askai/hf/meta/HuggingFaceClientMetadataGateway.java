package com.aresstack.askai.hf.meta;

import com.aresstack.askai.hf.HuggingFaceClient;

import java.util.Collections;
import java.util.Map;

/** Adapts {@link HuggingFaceClient} to the tolerant {@link HuggingFaceMetadataGateway} (never throws). */
public final class HuggingFaceClientMetadataGateway implements HuggingFaceMetadataGateway {

    private final HuggingFaceClient client;

    public HuggingFaceClientMetadataGateway(HuggingFaceClient client) {
        if (client == null) {
            throw new IllegalArgumentException("client must not be null");
        }
        this.client = client;
    }

    public String fetchFile(String repositoryId, String revision, String path) {
        try {
            return client.fetchFileText(repositoryId, revision, path);
        } catch (Exception ex) {
            return null; // absent / gated / network — the loader treats this as "not available"
        }
    }

    public Map<String, Object> fetchModelInfo(String repositoryId, String revision) {
        try {
            return client.fetchModelInfo(repositoryId, revision);
        } catch (Exception ex) {
            return Collections.emptyMap();
        }
    }
}
