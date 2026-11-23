package com.aresstack.askai.localmodels;

import com.aresstack.askai.localruntime.onnx.OnnxRuntimeOptions;

/**
 * Settings of AskAI's built-in local runtime (ONNX Runtime on the CPU) and of the sentence-embedding slot. Every value
 * has a default; nothing is a constant in code. Immutable.
 */
public final class LocalRuntimeSettings {

    /**
     * Default model of the sentence-embedding slot (sentence-against-sentence: near repetitions, citation check, topic
     * drift): e5-base-sts-en-de, the private-cloud service. Until it is installed the slot falls back to the main
     * embedding model.
     */
    public static final String DEFAULT_SENTENCE_EMBEDDING_MODEL = "local/danielheinz/e5-base-sts-en-de:latest";

    private final OnnxRuntimeOptions onnx;
    private final String sentenceEmbeddingModel;

    public LocalRuntimeSettings(OnnxRuntimeOptions onnx, String sentenceEmbeddingModel) {
        this.onnx = onnx == null ? OnnxRuntimeOptions.defaults() : onnx;
        this.sentenceEmbeddingModel = sentenceEmbeddingModel == null ? "" : sentenceEmbeddingModel.trim();
    }

    public static LocalRuntimeSettings defaults() {
        return new LocalRuntimeSettings(OnnxRuntimeOptions.defaults(), DEFAULT_SENTENCE_EMBEDDING_MODEL);
    }

    public OnnxRuntimeOptions getOnnx() {
        return onnx;
    }

    /** The configured sentence-embedding model; empty = always the main embedding model. */
    public String getSentenceEmbeddingModel() {
        return sentenceEmbeddingModel;
    }
}
