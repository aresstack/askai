package com.aresstack.askai.localruntime;

import com.aresstack.askai.localruntime.onnx.OnnxModelCatalog;
import com.aresstack.askai.localruntime.onnx.OnnxModelDescriptor;
import com.aresstack.askai.localruntime.onnx.OnnxRuntimeOptions;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The real ONNX Runtime (CPU) behind the unchanged Ollama facade, with tiny generated graphs from
 * {@code src/test/resources/onnx} (no network): install smoke, {@code /api/embed} with CLS pooling + L2 norm,
 * {@code /api/rerank} ordering, and {@code /api/show} reporting the onnxruntime format.
 */
public class OnnxRuntimeEndToEndTest {

    private Path modelRoot;
    private LocalModelRuntime runtime;

    @Before
    public void start() throws IOException {
        modelRoot = Files.createTempDirectory("askai-onnx-models");
        runtime = LocalModelRuntime.start(modelRoot, "cpu", OnnxRuntimeOptions.builder().intraOpThreads(1).build());
    }

    @After
    public void stop() {
        runtime.close();
    }

    @Test
    public void embeddingModelInstallsAndEmbedsThroughTheOllamaApi() throws Exception {
        OnnxModelDescriptor bge = OnnxModelCatalog.findByRepositoryId("BAAI/bge-m3");
        Path dir = stage(bge, "tiny-encoder.onnx");
        Map<String, Object> install = post("/internal/install",
                Map.of("repositoryId", bge.repositoryId(), "modelDirectory", dir.toString()));
        assertEquals(install.toString(), Boolean.TRUE, install.get("ok"));
        assertEquals("cpu", install.get("backend"));
        commit(bge, dir);

        Map<String, Object> embed = post("/api/embed",
                Map.of("model", bge.virtualModelName(), "input", List.of("helmet rules", "site")));
        List<?> rows = (List<?>) embed.get("embeddings");
        assertEquals(2, rows.size());
        // CLS pooling: the first token is [CLS] (id 2) whose tiny embedding row is [0.8, 0.9, 1.0, 1.1].
        List<?> first = (List<?>) rows.get(0);
        double norm = Math.sqrt(0.64 + 0.81 + 1.0 + 1.21);
        assertEquals(0.8 / norm, ((Number) first.get(0)).doubleValue(), 1e-5);
        assertEquals(1.1 / norm, ((Number) first.get(3)).doubleValue(), 1e-5);

        Map<String, Object> show = post("/api/show", Map.of("model", bge.virtualModelName()));
        Map<?, ?> details = (Map<?, ?>) show.get("details");
        assertEquals("onnx", details.get("format"));
        assertEquals("onnxruntime", details.get("runtime"));
    }

    @Test
    public void crossEncoderRanksThroughTheOllamaApi() throws Exception {
        OnnxModelDescriptor marco = OnnxModelCatalog.findByRepositoryId("cross-encoder/ms-marco-MiniLM-L6-v2");
        Path dir = stage(marco, "tiny-cross-encoder.onnx");
        Map<String, Object> install = post("/internal/install",
                Map.of("repositoryId", marco.repositoryId(), "modelDirectory", dir.toString()));
        assertEquals(install.toString(), Boolean.TRUE, install.get("ok"));
        commit(marco, dir);

        // The tiny graph sums column 0 of each token row (id * 0.4): more and higher token ids score higher.
        Map<String, Object> rerank = post("/api/rerank", Map.of("model", marco.virtualModelName(),
                "query", "helmet", "documents", List.of("rules", "sites sites", "helmet")));
        List<?> results = (List<?>) rerank.get("results");
        assertEquals(3, results.size());
        assertEquals(1, ((Number) ((Map<?, ?>) results.get(0)).get("index")).intValue());
        double top = ((Number) ((Map<?, ?>) results.get(0)).get("score")).doubleValue();
        double last = ((Number) ((Map<?, ?>) results.get(2)).get("score")).doubleValue();
        assertTrue(top > last);
    }

    @Test
    public void modelWithoutExportIsRefusedAtInstall() throws Exception {
        OnnxModelDescriptor e5 = OnnxModelCatalog.findByRepositoryId("danielheinz/e5-base-sts-en-de");
        Map<String, Object> install = post("/internal/install",
                Map.of("repositoryId", e5.repositoryId(), "modelDirectory", modelRoot.resolve("x").toString()));
        assertEquals(Boolean.FALSE, install.get("ok"));
        assertEquals("MODEL_NOT_RUNNABLE", install.get("code"));
    }

    private Path stage(OnnxModelDescriptor descriptor, String graph) throws IOException {
        Path dir = Files.createDirectories(modelRoot.resolve(descriptor.directoryName()));
        copy("onnx/" + graph, dir.resolve(OnnxModelDescriptor.LOCAL_GRAPH));
        copy("onnx/tiny-bert-tokenizer.json", dir.resolve(OnnxModelDescriptor.LOCAL_TOKENIZER));
        Files.writeString(dir.resolve(OnnxModelDescriptor.LOCAL_CONFIG), "{\"pad_token_id\":0}");
        return dir;
    }

    private static void copy(String resource, Path target) throws IOException {
        try (InputStream in = OnnxRuntimeEndToEndTest.class.getClassLoader().getResourceAsStream(resource)) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void commit(OnnxModelDescriptor descriptor, Path dir) throws IOException {
        var m = OnnxManifests.forInstall(descriptor, "cpu", "test", 1L);
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("schemaVersion", m.getSchemaVersion());
        map.put("virtualName", m.getVirtualName());
        map.put("huggingFaceRepository", m.getHuggingFaceRepository());
        map.put("resolvedRevision", m.getResolvedRevision());
        map.put("runtimeModelId", m.getRuntimeModelId());
        map.put("runtimeFamily", m.getRuntimeFamily());
        map.put("runtimePackage", m.getRuntimePackage());
        map.put("capabilities", m.getCapabilities());
        map.put("supportedBackends", m.getSupportedBackends());
        map.put("sourceFormat", m.getSourceFormat());
        map.put("state", m.getState());
        map.put("installedAt", m.getInstalledAt());
        Files.writeString(dir.resolve(SidecarManifests.FILE_NAME), LocalJson.write(map));
    }

    private Map<String, Object> post(String path, Map<String, Object> body) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI.create(runtime.baseUrl() + path).toURL()
                .openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        try (OutputStream out = connection.getOutputStream()) {
            out.write(LocalJson.write(body).getBytes(StandardCharsets.UTF_8));
        }
        InputStream in = connection.getResponseCode() >= 400 ? connection.getErrorStream()
                : connection.getInputStream();
        try (in) {
            return LocalJson.parseObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
}
