package com.aresstack.askai.java8.hf.convert;

import com.aresstack.askai.java8.hf.HuggingFaceClient;
import com.aresstack.askai.java8.json.SimpleJson;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Builds a verified {@link RepositoryAnalysis} from a repository's real file tree and, when weight
 * formats that need it are present (Safetensors), its {@code config.json}. This is the authoritative,
 * file-based detection the spec requires (§18) — tags are never the sole signal.
 */
public final class RepositoryAnalyzer {

    private final HuggingFaceClient client;

    public RepositoryAnalyzer(HuggingFaceClient client) {
        this.client = client;
    }

    public RepositoryAnalysis analyze(String modelId) throws IOException {
        List<String> files = client.listAllFiles(modelId);
        RepositoryAnalysis.Builder builder = RepositoryAnalysis.builder(modelId).verified(true);

        boolean hasConfig = false;
        boolean hasTokenizer = false;
        boolean hasMmproj = false;
        for (int i = 0; i < files.size(); i++) {
            String path = files.get(i);
            builder.addFile(path);
            String lower = path.toLowerCase(Locale.ROOT);
            builder.addFormat(ModelFormat.fromFileName(fileName(path)));
            if (lower.equals("config.json")) {
                hasConfig = true;
            }
            if (lower.startsWith("tokenizer") || lower.equals("vocab.json") || lower.equals("spiece.model")
                    || lower.equals("merges.txt")) {
                hasTokenizer = true;
            }
            if (fileName(lower).startsWith("mmproj") && lower.endsWith(".gguf")) {
                hasMmproj = true;
            }
        }
        builder.hasConfigJson(hasConfig).hasTokenizer(hasTokenizer).hasMmproj(hasMmproj);

        // Only read config.json when a format that needs an architecture is present — GGUF carries
        // its own architecture internally and does not need it for classification, so we avoid the
        // extra request for gguf-only repos.
        if (hasConfig && needsConfig(builder)) {
            readConfig(modelId, builder);
        }
        return builder.build();
    }

    private boolean needsConfig(RepositoryAnalysis.Builder builder) {
        RepositoryAnalysis partial = builder.build();
        return partial.hasFormat(ModelFormat.SAFETENSORS) || partial.hasFormat(ModelFormat.PYTORCH_BIN);
    }

    private void readConfig(String modelId, RepositoryAnalysis.Builder builder) {
        try {
            String text = client.fetchFileText(modelId, "config.json");
            Object parsed = SimpleJson.parse(text);
            if (parsed instanceof Map) {
                Map map = (Map) parsed;
                builder.architectures(stringList(map.get("architectures")));
                Object modelType = map.get("model_type");
                if (modelType != null) {
                    builder.modelType(String.valueOf(modelType));
                }
                builder.configReadable(true);
            }
        } catch (IOException ex) {
            // Gated repos answer 401 without a token; leave configReadable=false so the classifier
            // reports "config.json nicht lesbar (gated?)" instead of a wrong architecture verdict.
            builder.configReadable(false);
        }
    }

    private static List<String> stringList(Object value) {
        List<String> result = new ArrayList<String>();
        if (value instanceof List) {
            List values = (List) value;
            for (int i = 0; i < values.size(); i++) {
                Object entry = values.get(i);
                if (entry != null) {
                    result.add(String.valueOf(entry));
                }
            }
        }
        return result;
    }

    private static String fileName(String path) {
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }
}
