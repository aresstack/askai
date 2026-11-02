package com.aresstack.askai.hf.meta;

import java.util.Map;

/**
 * Best-effort read access to a Hugging Face repository's metadata for the install-plan enrichment. Every
 * method is tolerant: a missing file, a gated repo, or a network error yields {@code null} / an empty map
 * rather than an exception, so the loader can degrade gracefully to whatever it could gather.
 */
public interface HuggingFaceMetadataGateway {

    /** @return the text of {@code path} at {@code revision}, or {@code null} when it cannot be read. */
    String fetchFile(String repositoryId, String revision, String path);

    /** @return the parsed model-info JSON, or an empty map when it cannot be read. */
    Map<String, Object> fetchModelInfo(String repositoryId, String revision);
}
