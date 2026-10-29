package com.aresstack.askai.java8.localmodels;

import com.aresstack.askai.java8.json.SimpleJson;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Decides whether a Hugging Face repository is RUNNABLE by AskAI's local model runtime
 * (win-directml-java). The judgement is based on the ACTUAL configuration — never on the
 * repository name alone: required raw files present, {@code model_type=bert} with a
 * BertForSequenceClassification-compatible architecture and one regression label, a WordPiece
 * tokenizer, and an explicit runtime mapping. Anything else (bge-reranker, GTE, XLM-R/SentencePiece
 * models) stays unsupported until a real runtime path is proven by a reference test.
 */
public final class LocalModelCompatibilityAnalyzer {

    /** Required raw model files (the runtime's loader contract). */
    public static final List<String> REQUIRED_FILES =
            java.util.Collections.unmodifiableList(java.util.Arrays.asList(
                    "config.json", "tokenizer.json", "model.safetensors"));

    /** Repositories with a PROVEN runtime mapping: repo id → {runtimeModelId, directoryName}. */
    private static final Map<String, String[]> RUNTIME_MAPPING = buildMapping();

    private static Map<String, String[]> buildMapping() {
        Map<String, String[]> mapping = new LinkedHashMap<String, String[]>();
        // The first productively selectable model type of the local runtime (R0):
        mapping.put("cross-encoder/ms-marco-minilm-l6-v2",
                new String[]{"MS_MARCO_MINILM_L6", "cross-encoder-ms-marco-MiniLM-L-6-v2"});
        return mapping;
    }

    /**
     * @param repositoryId  the Hugging Face repo (owner/name)
     * @param availableFiles file paths present in the repository (or the local staging directory)
     * @param configJson    the content of config.json, or null when unavailable
     * @param tokenizerJson the content of tokenizer.json, or null when unavailable
     */
    public LocalModelCompatibilityResult analyze(String repositoryId,
                                                 Collection<String> availableFiles,
                                                 String configJson, String tokenizerJson) {
        for (String required : REQUIRED_FILES) {
            if (!containsFile(availableFiles, required)) {
                return failure(LocalModelCompatibilityResult.Status.MISSING_REQUIRED_FILES,
                        "required file missing: " + required);
            }
        }
        Map<String, Object> config = parseObject(configJson);
        if (config == null) {
            return failure(LocalModelCompatibilityResult.Status.UNKNOWN_CONFIGURATION,
                    "config.json is missing or unreadable");
        }
        String modelType = str(config.get("model_type"));
        if (!"bert".equalsIgnoreCase(modelType)) {
            return failure(LocalModelCompatibilityResult.Status.UNSUPPORTED_ARCHITECTURE,
                    "model_type is '" + modelType + "', the local runtime supports 'bert'");
        }
        if (!architectureCompatible(config)) {
            return failure(LocalModelCompatibilityResult.Status.UNSUPPORTED_ARCHITECTURE,
                    "architecture is not BertForSequenceClassification-compatible: "
                            + str(config.get("architectures")));
        }
        if (labelCount(config) != 1) {
            return failure(LocalModelCompatibilityResult.Status.UNSUPPORTED_ARCHITECTURE,
                    "cross-encoder reranking needs num_labels=1, found " + labelCount(config));
        }
        Map<String, Object> tokenizer = parseObject(tokenizerJson);
        if (tokenizer == null) {
            return failure(LocalModelCompatibilityResult.Status.UNKNOWN_CONFIGURATION,
                    "tokenizer.json is missing or unreadable");
        }
        String tokenizerType = tokenizerModelType(tokenizer);
        if (!"WordPiece".equalsIgnoreCase(tokenizerType)) {
            return failure(LocalModelCompatibilityResult.Status.UNSUPPORTED_TOKENIZER,
                    "tokenizer is '" + tokenizerType + "', the local runtime supports WordPiece");
        }
        String[] mapping = RUNTIME_MAPPING.get(normalize(repositoryId));
        if (mapping == null) {
            return failure(LocalModelCompatibilityResult.Status.UNKNOWN_CONFIGURATION,
                    "no proven runtime mapping for '" + repositoryId
                            + "' (supported so far: cross-encoder/ms-marco-MiniLM-L6-v2)");
        }
        return new LocalModelCompatibilityResult(LocalModelCompatibilityResult.Status.SUPPORTED,
                LocalRuntimeCapability.RERANK, mapping[0], mapping[1],
                "BERT cross-encoder with WordPiece tokenizer, runtime id " + mapping[0]);
    }

    // ------------------------------------------------------------------ config judgement

    private static boolean architectureCompatible(Map<String, Object> config) {
        Object architectures = config.get("architectures");
        if (!(architectures instanceof List)) {
            return false;
        }
        for (Object architecture : (List<?>) architectures) {
            if ("BertForSequenceClassification".equals(String.valueOf(architecture))) {
                return true;
            }
        }
        return false;
    }

    private static int labelCount(Map<String, Object> config) {
        Object numLabels = config.get("num_labels");
        if (numLabels instanceof Number) {
            return ((Number) numLabels).intValue();
        }
        Object id2label = config.get("id2label");
        if (id2label instanceof Map) {
            return ((Map<?, ?>) id2label).size();
        }
        return -1;
    }

    private static String tokenizerModelType(Map<String, Object> tokenizer) {
        Object model = tokenizer.get("model");
        if (model instanceof Map) {
            return str(((Map<?, ?>) model).get("type"));
        }
        return "";
    }

    // ------------------------------------------------------------------ helpers

    private static LocalModelCompatibilityResult failure(
            LocalModelCompatibilityResult.Status status, String reason) {
        return new LocalModelCompatibilityResult(status, LocalRuntimeCapability.RERANK, "", "",
                reason);
    }

    private static boolean containsFile(Collection<String> files, String required) {
        if (files == null) {
            return false;
        }
        for (String file : files) {
            String name = file == null ? "" : file.trim();
            int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
            if (slash >= 0) {
                name = name.substring(slash + 1);
            }
            if (name.equals(required)) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseObject(String json) {
        if (json == null || json.trim().isEmpty()) {
            return null;
        }
        try {
            Object parsed = SimpleJson.parse(json);
            return parsed instanceof Map ? (Map<String, Object>) parsed : null;
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String normalize(String repositoryId) {
        return repositoryId == null ? "" : repositoryId.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
