package com.aresstack.askai.research.benchmark;

import com.aresstack.askai.agent.model.reranker.RerankerCapability;
import com.aresstack.askai.agent.model.reranker.RerankerConfigurationDocument;
import com.aresstack.askai.agent.model.reranker.RerankerConfigurationException;
import com.aresstack.askai.agent.model.reranker.RerankerConfigurationSnapshot;
import com.aresstack.askai.agent.model.reranker.RerankerConfigurationSnapshotProvider;
import com.aresstack.askai.agent.model.reranker.RerankerEndpointDescriptor;
import com.aresstack.askai.agent.model.reranker.RerankerEndpointDescriptorCodec;
import com.aresstack.askai.agent.model.reranker.RerankerProvider;
import com.aresstack.askai.agent.model.reranker.RerankerScoreSemantics;
import com.aresstack.askai.agent.model.reranker.RerankerSelectionConfiguration;
import com.aresstack.askai.localruntime.LocalModelRuntime;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

/**
 * The REAL local reranker the baseline needs: the autonomous research loop injects the session's mandatory
 * model reranker (the same prerequisite the MVP end-to-end test has), so the benchmark starts the in-process
 * local model runtime against the installed rerank-capable model, as the host does. Same model selection
 * rule as the end-to-end test: the first RUNNABLE model whose published manifest lists the {@code rerank}
 * capability.
 */
public final class LocalRerankerRuntime implements AutoCloseable {

    private static final String MANIFEST_FILE = "askai-local-model.json";

    private final LocalModelRuntime runtime;
    private final String modelName;

    private LocalRerankerRuntime(LocalModelRuntime runtime, String modelName) {
        this.runtime = runtime;
        this.modelName = modelName;
    }

    /** The model root the host uses: {@code %APPDATA%/.askai/models/local}. */
    public static File defaultModelRoot() {
        String appData = System.getenv("APPDATA");
        File base = appData != null && !appData.trim().isEmpty()
                ? new File(appData) : new File(System.getProperty("user.home"), "AppData/Roaming");
        return new File(new File(new File(base, ".askai"), "models"), "local");
    }

    /** The first installed RUNNABLE rerank-capable model under {@code modelRoot}, or null. */
    public static String rerankModelOrNull(File modelRoot) {
        File[] children = modelRoot == null ? null : modelRoot.listFiles();
        if (children == null) {
            return null;
        }
        Arrays.sort(children);
        for (File child : children) {
            File manifest = new File(child, MANIFEST_FILE);
            if (!child.isDirectory() || !manifest.isFile()) {
                continue;
            }
            JsonObject json;
            try {
                JsonElement parsed = JsonParser.parseString(new String(Files.readAllBytes(manifest.toPath()),
                        StandardCharsets.UTF_8));
                if (!parsed.isJsonObject()) {
                    continue;
                }
                json = parsed.getAsJsonObject();
            } catch (IOException | RuntimeException unreadable) {
                continue;
            }
            if (!"RUNNABLE".equals(string(json, "state")) || string(json, "virtualName") == null
                    || !json.has("capabilities") || !json.get("capabilities").isJsonArray()) {
                continue;
            }
            for (JsonElement capability : json.getAsJsonArray("capabilities")) {
                if (capability.isJsonPrimitive() && "rerank".equals(capability.getAsString())) {
                    return string(json, "virtualName");
                }
            }
        }
        return null;
    }

    /** Start the runtime on the CPU backend; the caller checked {@link #rerankModelOrNull} first. */
    public static LocalRerankerRuntime start(File modelRoot, String modelName) throws IOException {
        return new LocalRerankerRuntime(LocalModelRuntime.start(modelRoot.toPath(), "cpu"), modelName);
    }

    public String getModelName() {
        return modelName;
    }

    /** A host-style snapshot provider pointing at this runtime (explicit selection, never a guess). */
    public RerankerConfigurationSnapshotProvider asProvider(int topN) {
        return (sessionId, sessionDirectory, selectedModel) -> {
            if (selectedModel == null || !modelName.equals(selectedModel.trim())) {
                throw new RerankerConfigurationException("selected reranker model \"" + selectedModel
                        + "\" is not the benchmark's reranker \"" + modelName + "\"");
            }
            RerankerConfigurationDocument document = RerankerConfigurationDocument.current(0L,
                    new RerankerEndpointDescriptor(RerankerProvider.ASKAI_LOCAL, runtime.baseUrl(), modelName,
                            Arrays.asList(RerankerCapability.RERANK), RerankerScoreSemantics.RAW_LOGIT, 30_000L,
                            RerankerSelectionConfiguration.topN(topN)));
            File target = new File(sessionDirectory, "reranker-config.json");
            try {
                Files.write(target.toPath(), RerankerEndpointDescriptorCodec.toJson(document)
                        .getBytes(StandardCharsets.UTF_8));
            } catch (IOException ex) {
                throw new RerankerConfigurationException("cannot write the benchmark reranker snapshot: "
                        + ex.getMessage(), ex);
            }
            return new RerankerConfigurationSnapshot(target.getAbsoluteFile(), document);
        };
    }

    @Override
    public void close() {
        runtime.close();
    }

    private static String string(JsonObject json, String key) {
        JsonElement value = json.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
    }
}
