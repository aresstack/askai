package com.aresstack.askai.localruntime.onnx;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The models AskAI's local runtime runs through ONNX Runtime on the CPU. The selection follows the private-cloud
 * model families (BGE-M3, e5-base-sts-en-de, mxbai-embed-large-v1) so local vectors stay compatible with the cloud
 * services; the MiniLM cross-encoder stays the local reranker because Llama Nemotron Rerank 1B v2 has no ONNX export.
 */
public final class OnnxModelCatalog {

    private static final String E5_QUERY = "query: ";
    private static final String MXBAI_QUERY = "Represent this sentence for searching relevant passages: ";

    private static final List<OnnxModelDescriptor> MODELS = Collections.unmodifiableList(build());

    private OnnxModelCatalog() {
    }

    private static List<OnnxModelDescriptor> build() {
        List<OnnxModelDescriptor> models = new ArrayList<OnnxModelDescriptor>();
        // BGE-M3 (cloud: BGE-M3, Ollama: bge-m3). Official export with external weights (~2.3 GB, fp32).
        models.add(OnnxModelDescriptor.builder("BAAI/bge-m3")
                .displayName("BGE-M3 (multilingual embedding, fp32)")
                .capability(OnnxModelDescriptor.Capability.EMBEDDING)
                .architecture("xlm-roberta")
                .pooling(OnnxModelDescriptor.Pooling.CLS)
                .normalize(true)
                .maxTokens(8192)
                .graph("onnx/model.onnx", false, "onnx/model.onnx_data")
                .build());
        // The same weights as an int8-quantized export (~570 MB): the CPU-friendly way to run BGE-M3.
        models.add(OnnxModelDescriptor.builder("Xenova/bge-m3")
                .displayName("BGE-M3 (multilingual embedding, int8 export)")
                .capability(OnnxModelDescriptor.Capability.EMBEDDING)
                .architecture("xlm-roberta")
                .pooling(OnnxModelDescriptor.Pooling.CLS)
                .normalize(true)
                .maxTokens(8192)
                .graph("onnx/model_quantized.onnx", true)
                .graph("onnx/model.onnx", false)
                .build());
        // e5-base-sts-en-de (cloud service): German/English sentence-against-sentence similarity.
        models.add(OnnxModelDescriptor.builder("danielheinz/e5-base-sts-en-de")
                .displayName("e5-base-sts-en-de (sentence similarity DE/EN)")
                .capability(OnnxModelDescriptor.Capability.EMBEDDING)
                .architecture("xlm-roberta")
                .pooling(OnnxModelDescriptor.Pooling.MEAN)
                .normalize(true)
                .prefixes(E5_QUERY, E5_QUERY)
                .maxTokens(512)
                .needsExport("The repository has no ONNX export. Create one with Hugging Face Optimum: "
                        + "'optimum-cli export onnx --model danielheinz/e5-base-sts-en-de --task feature-extraction "
                        + "e5-sts-onnx' and optionally 'optimum-cli onnxruntime quantize --onnx_model e5-sts-onnx "
                        + "--avx2 -o e5-sts-onnx-int8'; publish model.onnx, tokenizer.json and config.json and add "
                        + "the download location here.")
                .build());
        // mxbai-embed-large-v1 (cloud service), English retrieval embedding.
        models.add(OnnxModelDescriptor.builder("mixedbread-ai/mxbai-embed-large-v1")
                .displayName("mxbai-embed-large-v1 (English embedding)")
                .capability(OnnxModelDescriptor.Capability.EMBEDDING)
                .architecture("bert")
                .pooling(OnnxModelDescriptor.Pooling.CLS)
                .normalize(true)
                .prefixes(MXBAI_QUERY, "")
                .maxTokens(512)
                .graph("onnx/model_quantized.onnx", true)
                .graph("onnx/model.onnx", false)
                .build());
        // The local cross-encoder reranker (BM25 stays the default; the AI reranker is opt-in).
        models.add(OnnxModelDescriptor.builder("cross-encoder/ms-marco-MiniLM-L6-v2")
                .displayName("ms-marco-MiniLM-L6-v2 (cross-encoder reranker)")
                .capability(OnnxModelDescriptor.Capability.RERANK)
                .architecture("bert")
                .maxTokens(512)
                .graph("onnx/model_quint8_avx2.onnx", true)
                .graph("onnx/model.onnx", false)
                .build());
        // Llama Nemotron Rerank 1B v2 (cloud reranker): Llama-based, no ONNX export; listed so the gap is visible.
        models.add(OnnxModelDescriptor.builder("nvidia/llama-nemotron-rerank-1b-v2")
                .displayName("Llama Nemotron Rerank 1B v2 (no ONNX export)")
                .capability(OnnxModelDescriptor.Capability.RERANK)
                .architecture("llama")
                .maxTokens(8192)
                .needsExport("Llama-based reranker with custom code and no ONNX export; a local export would need "
                        + "Optimum with trust_remote_code and is too heavy for the CPU path. The local reranker "
                        + "stays cross-encoder/ms-marco-MiniLM-L6-v2.")
                .build());
        return models;
    }

    /** Every catalogued model, runnable or not. */
    public static List<OnnxModelDescriptor> all() {
        return MODELS;
    }

    /** The models the installer can install today. */
    public static List<OnnxModelDescriptor> runnable() {
        List<OnnxModelDescriptor> runnable = new ArrayList<OnnxModelDescriptor>();
        for (OnnxModelDescriptor model : MODELS) {
            if (model.isRunnable()) {
                runnable.add(model);
            }
        }
        return Collections.unmodifiableList(runnable);
    }

    /** @return the descriptor of this repository (case-insensitive), or {@code null}. */
    public static OnnxModelDescriptor findByRepositoryId(String repositoryId) {
        if (repositoryId == null) {
            return null;
        }
        String wanted = repositoryId.trim().toLowerCase(Locale.ROOT);
        for (OnnxModelDescriptor model : MODELS) {
            if (model.repositoryId().toLowerCase(Locale.ROOT).equals(wanted)) {
                return model;
            }
        }
        return null;
    }

    /** @return the descriptor owning this virtual name ({@code local/<repo>:latest}), or {@code null}. */
    public static OnnxModelDescriptor findByVirtualName(String virtualName) {
        if (virtualName == null) {
            return null;
        }
        for (OnnxModelDescriptor model : MODELS) {
            if (model.virtualModelName().equalsIgnoreCase(virtualName.trim())) {
                return model;
            }
        }
        return null;
    }
}
