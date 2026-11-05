package com.aresstack.askai.research.knowledge.processing.embedding;

import com.aresstack.askai.research.knowledge.EmbeddingPort;

import java.util.Locale;

/**
 * The query and document prompts that an embedding model's card documents, chosen by model id. Ollama models get
 * the prompt as text in front of the input; the local runtime's E5 models get it through {@code input_type}
 * (the runtime adds {@code query: }/{@code passage: } itself). A model without a known scheme is embedded raw in
 * every role ({@link #RAW}), and the benchmark report says so.
 *
 * <p>The scheme's {@link #id()} is part of the vector fingerprint: changing a prompt changes the id, so projects
 * embedded under the old scheme are re-embedded instead of being compared with new vectors.</p>
 */
public enum EmbeddingPromptScheme {

    /** No prompt in any role; also the scope fence's world for every model. */
    RAW("raw", "", "", false),
    /** nomic-embed-text v1/v1.5 and nomic-embed-text-v2-moe (model cards: search_query / search_document). */
    NOMIC("nomic-v1", "search_query: ", "search_document: ", false),
    /** embeddinggemma (model card: retrieval query and document prompts). */
    EMBEDDING_GEMMA("embeddinggemma-v1", "task: search result | query: ", "title: none | text: ", false),
    /** snowflake-arctic-embed2 (model card: query prefix, documents without prompt). */
    ARCTIC("arctic-v1", "query: ", "", false),
    /** mxbai-embed-large (model card: query instruction, documents without prompt). */
    MXBAI("mxbai-v1", "Represent this sentence for searching relevant passages: ", "", false),
    /** The local runtime's E5 models: the runtime applies query:/passage: from {@code input_type}. */
    LOCAL_E5("e5-input-type-v1", "", "", true);

    private final String id;
    private final String queryPrompt;
    private final String documentPrompt;
    private final boolean inputType;

    EmbeddingPromptScheme(String id, String queryPrompt, String documentPrompt, boolean inputType) {
        this.id = id;
        this.queryPrompt = queryPrompt;
        this.documentPrompt = documentPrompt;
        this.inputType = inputType;
    }

    /** The scheme of a model id; {@link #RAW} when its card names no prompts or the model is unknown. */
    public static EmbeddingPromptScheme forModel(String modelId) {
        String m = modelId == null ? "" : modelId.trim().toLowerCase(Locale.ROOT);
        if (m.startsWith("local/")) {
            String repository = m.substring("local/".length());
            return repository.contains("/e5-") || repository.contains("multilingual-e5") || repository.startsWith("e5-")
                    ? LOCAL_E5 : RAW;
        }
        String name = m.contains("/") ? m.substring(m.lastIndexOf('/') + 1) : m;
        if (name.startsWith("nomic-embed-text")) {
            return NOMIC;
        }
        if (name.startsWith("embeddinggemma")) {
            return EMBEDDING_GEMMA;
        }
        if (name.startsWith("snowflake-arctic-embed2")) {
            return ARCTIC;
        }
        if (name.startsWith("mxbai-embed-large")) {
            return MXBAI;
        }
        return RAW; // bge-m3, granite-embedding and unknown models: no documented prompt
    }

    public String id() {
        return id;
    }

    /** True when the model has no prompt in any role (the report lists such candidates as "raw"). */
    public boolean isRaw() {
        return this == RAW;
    }

    /** The text actually sent for one input. */
    public String apply(EmbeddingPort.Role role, String text) {
        String value = text == null ? "" : text;
        if (inputType || role == EmbeddingPort.Role.RAW) {
            return value;
        }
        return (role == EmbeddingPort.Role.QUERY ? queryPrompt : documentPrompt) + value;
    }

    /** The {@code input_type} of the request: query/passage for the local E5 models, raw otherwise. */
    public String inputType(EmbeddingPort.Role role) {
        if (!inputType || role == EmbeddingPort.Role.RAW) {
            return "raw";
        }
        return role == EmbeddingPort.Role.QUERY ? "query" : "passage";
    }

    /** The scheme that is really applied in a role: RAW calls are always raw. */
    public EmbeddingPromptScheme effective(EmbeddingPort.Role role) {
        return role == EmbeddingPort.Role.RAW ? RAW : this;
    }

    /**
     * The vector fingerprint under this scheme: the raw world keeps the descriptor's fingerprint (the scope fence's
     * calibration), every prompted world hashes the scheme id in.
     */
    public String fingerprint(String descriptorFingerprint) {
        String base = descriptorFingerprint == null ? "" : descriptorFingerprint;
        if (this == RAW) {
            return base;
        }
        return sha256Hex(base + " prompt=" + id);
    }

    private static String sha256Hex(String value) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
