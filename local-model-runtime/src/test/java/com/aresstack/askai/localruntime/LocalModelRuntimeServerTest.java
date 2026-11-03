package com.aresstack.askai.localruntime;

import com.aresstack.windirectml.runtime.api.Backend;
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
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The Ollama-compatible surface without any real model: manifest scanning publishes only RUNNABLE
 * models, /api/show carries the rerank capability, unsupported operations answer with an
 * Ollama-style capability error (never an empty fake response), the keep_alive:0 unload convention
 * works, and /api/ps is empty until a model was really loaded.
 */
public class LocalModelRuntimeServerTest {

    private Path modelRoot;
    private LocalModelRuntimeServer server;
    private String baseUrl;

    @Before
    public void start() throws IOException {
        modelRoot = Files.createTempDirectory("askai-local-models");
        writeManifest("cross-encoder-ms-marco-MiniLM-L-6-v2",
                "local/cross-encoder/ms-marco-MiniLM-L6-v2:latest", "RUNNABLE");
        writeManifest("broken-model", "local/broken:latest", "FAILED");
        server = new LocalModelRuntimeServer(new LocalModelStore(modelRoot),
                new LocalModelEngine(Backend.CPU),
                new LocalGenerationEngine(
                        new com.aresstack.askai.localruntime.generation.NotLinkedGenerationRuntimePort(),
                        com.aresstack.askai.localruntime.generation.LocalGenerationBackend.CPU));
        int port = server.start("127.0.0.1", 0);
        baseUrl = "http://127.0.0.1:" + port;
    }

    @After
    public void stop() {
        server.stop();
    }

    @Test
    public void versionAndTagsListOnlyRunnableModels() throws Exception {
        Map<String, Object> version = get("/api/version");
        assertEquals("askai-local-1", version.get("version"));
        // features reflect the ACTUAL linked ports: embedding/rerank yes, generation not linked here.
        Map<?, ?> features = (Map<?, ?>) version.get("features");
        assertEquals(Boolean.TRUE, features.get("embedding"));
        assertEquals(Boolean.TRUE, features.get("rerank"));
        assertEquals(Boolean.FALSE, features.get("generation"));

        Map<String, Object> tags = get("/api/tags");
        List<?> models = (List<?>) tags.get("models");
        assertEquals("the FAILED manifest must not be published", 1, models.size());
        Map<?, ?> model = (Map<?, ?>) models.get(0);
        assertEquals("local/cross-encoder/ms-marco-MiniLM-L6-v2:latest", model.get("name"));
        assertEquals("wdmlpack", ((Map<?, ?>) model.get("details")).get("format"));
    }

    @Test
    public void showCarriesTheRerankCapability() throws Exception {
        Map<String, Object> show = post("/api/show",
                "{\"model\":\"local/cross-encoder/ms-marco-MiniLM-L6-v2:latest\"}", 200);
        assertEquals(List.of("rerank"), show.get("capabilities"));
        assertEquals("cross_encoder", ((Map<?, ?>) show.get("details")).get("family"));
    }

    @Test
    public void unsupportedOperationsAnswerWithCapabilityErrorsNeverFakeResponses() throws Exception {
        Map<String, Object> chat = post("/api/chat",
                "{\"model\":\"local/cross-encoder/ms-marco-MiniLM-L6-v2:latest\"}", 400);
        assertEquals("model 'local/cross-encoder/ms-marco-MiniLM-L6-v2:latest' "
                + "does not support chat", chat.get("error"));
        Map<String, Object> embed = post("/api/embed",
                "{\"model\":\"local/cross-encoder/ms-marco-MiniLM-L6-v2:latest\"}", 400);
        assertTrue(String.valueOf(embed.get("error")).contains("does not support embedding"));
        Map<String, Object> pull = post("/api/pull", "{\"model\":\"x\"}", 400);
        assertTrue(String.valueOf(pull.get("error")).contains("Hugging Face pane"));
    }

    @Test
    public void keepAliveZeroIsTheUnloadConventionAndPsStartsEmpty() throws Exception {
        Map<String, Object> ps = get("/api/ps");
        assertTrue("nothing was loaded yet", ((List<?>) ps.get("models")).isEmpty());
        Map<String, Object> unload = post("/api/generate",
                "{\"model\":\"local/cross-encoder/ms-marco-MiniLM-L6-v2:latest\","
                        + "\"keep_alive\":0}", 200);
        assertEquals(Boolean.TRUE, unload.get("done"));
        // A REAL generate attempt stays a typed capability error.
        Map<String, Object> generate = post("/api/generate",
                "{\"model\":\"local/cross-encoder/ms-marco-MiniLM-L6-v2:latest\","
                        + "\"prompt\":\"hi\"}", 400);
        assertTrue(String.valueOf(generate.get("error")).contains("does not support generate"));
    }

    @Test
    public void rerankOnAnUninstalledPackageFailsReadablyInsteadOfFaking() throws Exception {
        // The manifest claims RUNNABLE but no wdmlpack exists — loading must fail with a REASON.
        Map<String, Object> response = post("/api/rerank",
                "{\"model\":\"local/cross-encoder/ms-marco-MiniLM-L6-v2:latest\","
                        + "\"query\":\"q\",\"documents\":[\"a\",\"b\"]}", 500);
        assertFalse(String.valueOf(response.get("error")).isEmpty());
    }

    @Test
    public void unknownModelsAndPathsFailTyped() throws Exception {
        Map<String, Object> show = post("/api/show", "{\"model\":\"local/ghost:latest\"}", 404);
        assertEquals("MODEL_NOT_FOUND", show.get("code"));
        assertTrue(String.valueOf(show.get("error")).contains("not found"));
        assertEquals(404, statusOf("/api/nonsense"));
    }

    @Test
    public void installTypedErrorsAreCatalogResolvedNotHostTrusted() throws Exception {
        // Missing required fields.
        Map<String, Object> invalid = post("/internal/install", "{\"repositoryId\":\"\"}", 200);
        assertEquals("INVALID_REQUEST", invalid.get("code"));
        // A repository that is not in the catalog.
        Map<String, Object> missing = post("/internal/install",
                "{\"repositoryId\":\"foo/bar\",\"modelDirectory\":\"" + tmp("foo") + "\"}", 200);
        assertEquals("CATALOG_ENTRY_MISSING", missing.get("code"));
        // A catalogued generation family routes to the generation runtime; this server wires the
        // NOT-linked port, so install reports the typed RUNTIME_NOT_LINKED (the productive DirectML port
        // would compile + smoke it instead).
        Map<String, Object> generation = post("/internal/install",
                "{\"repositoryId\":\"Qwen/Qwen2.5-Coder-0.5B-Instruct\",\"modelDirectory\":\""
                        + tmp("qwen") + "\"}", 200);
        assertEquals("RUNTIME_NOT_LINKED", generation.get("code"));
    }

    @Test
    public void chatAndGenerateAreCapabilityRoutedAndTypedWhenRuntimeNotLinked() throws Exception {
        String reranker = "local/cross-encoder/ms-marco-MiniLM-L6-v2:latest";
        // /api/chat on a reranker is a typed capability mismatch.
        Map<String, Object> chatMismatch = post("/api/chat",
                "{\"model\":\"" + reranker + "\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}", 400);
        assertEquals("MODEL_CAPABILITY_MISMATCH", chatMismatch.get("code"));

        // A catalogued generation model (Qwen) routes to the generation port, which is NOT linked here.
        writeGenerationManifest();
        String qwen = "local/Qwen/Qwen2.5-Coder-0.5B-Instruct:latest";
        Map<String, Object> gen = post("/api/generate",
                "{\"model\":\"" + qwen + "\",\"prompt\":\"hi\",\"stream\":false}", 501);
        assertEquals("RUNTIME_NOT_LINKED", gen.get("code"));
        Map<String, Object> chat = post("/api/chat",
                "{\"model\":\"" + qwen + "\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],"
                        + "\"stream\":false}", 501);
        assertEquals("RUNTIME_NOT_LINKED", chat.get("code"));

        // Role validation happens before the runtime is even consulted.
        Map<String, Object> badRole = post("/api/chat",
                "{\"model\":\"" + qwen + "\",\"messages\":[{\"role\":\"wizard\",\"content\":\"hi\"}]}", 400);
        assertEquals("INVALID_REQUEST", badRole.get("code"));
    }

    @Test
    public void embedInputTypeIsValidatedBeforeAnyLoad() throws Exception {
        // A catalog-valid MiniLM (embedding) manifest; no wdmlpack, but input_type is validated first.
        writeEmbeddingManifest();
        String miniLm = "local/sentence-transformers/all-MiniLM-L6-v2:latest";
        // Unknown input_type is a typed 400 BEFORE loading.
        Map<String, Object> unknown = post("/api/embed",
                "{\"model\":\"" + miniLm + "\",\"input\":\"x\",\"input_type\":\"weird\"}", 400);
        assertEquals("INVALID_INPUT_TYPE", unknown.get("code"));
        // query/passage on a non-E5 encoder is rejected (no silent re-interpretation).
        Map<String, Object> passage = post("/api/embed",
                "{\"model\":\"" + miniLm + "\",\"input\":\"x\",\"input_type\":\"passage\"}", 400);
        assertEquals("INVALID_INPUT_TYPE", passage.get("code"));
        // A valid raw request reaches loading and fails there (no compiled package) — never a fake 200.
        Map<String, Object> raw = post("/api/embed",
                "{\"model\":\"" + miniLm + "\",\"input\":\"x\",\"input_type\":\"raw\"}", 500);
        assertEquals("MODEL_NOT_LOADABLE", raw.get("code"));
    }

    // ------------------------------------------------------------------ HTTP helpers

    private void writeManifest(String directory, String virtualName, String state)
            throws IOException {
        Path dir = modelRoot.resolve(directory);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(SidecarManifests.FILE_NAME), LocalJson.write(Map.of(
                "schemaVersion", 1,
                "virtualName", virtualName,
                "huggingFaceRepository", "cross-encoder/ms-marco-MiniLM-L6-v2",
                "resolvedRevision", "abc123",
                "runtimeModelId", "MS_MARCO_MINILM_L6",
                "capabilities", List.of("rerank"),
                "backendSupport", List.of("cpu", "directml"),
                "state", state)));
    }

    /** A catalog-valid v2 MiniLM embedding manifest (no compiled package present). */
    private void writeEmbeddingManifest() throws IOException {
        Path dir = modelRoot.resolve("all-MiniLM-L6-v2");
        Files.createDirectories(dir);
        java.util.Map<String, Object> manifest = new java.util.LinkedHashMap<>();
        manifest.put("schemaVersion", 2);
        manifest.put("virtualName", "local/sentence-transformers/all-MiniLM-L6-v2:latest");
        manifest.put("huggingFaceRepository", "sentence-transformers/all-MiniLM-L6-v2");
        manifest.put("resolvedRevision", "rev");
        manifest.put("runtimeModelId", "MINILM_L6_V2");
        manifest.put("runtimeFamily", "minilm");
        manifest.put("runtimePackage", "encoder.wdmlpack");
        manifest.put("capabilities", List.of("embedding"));
        manifest.put("supportedBackends", List.of("cpu", "directml"));
        manifest.put("sourceFormat", "safetensors");
        manifest.put("state", "RUNNABLE");
        manifest.put("installedAt", 1L);
        Files.writeString(dir.resolve(SidecarManifests.FILE_NAME), LocalJson.write(manifest));
    }

    /** A catalog-valid v2 Qwen (generation) manifest so /api/chat + /api/generate route to the port. */
    private void writeGenerationManifest() throws IOException {
        Path dir = modelRoot.resolve("qwen2.5-coder-0.5b-directml-int4");
        Files.createDirectories(dir);
        java.util.Map<String, Object> manifest = new java.util.LinkedHashMap<>();
        manifest.put("schemaVersion", 2);
        manifest.put("virtualName", "local/Qwen/Qwen2.5-Coder-0.5B-Instruct:latest");
        manifest.put("huggingFaceRepository", "Qwen/Qwen2.5-Coder-0.5B-Instruct");
        manifest.put("resolvedRevision", "rev");
        manifest.put("runtimeModelId", "QWEN2_5_CODER_0_5B_INSTRUCT");
        manifest.put("runtimeFamily", "qwen");
        manifest.put("runtimePackage", "model_q4f16.wdmlpack");
        manifest.put("capabilities", List.of("completion", "chat"));
        manifest.put("supportedBackends", List.of("warp", "auto", "cpu"));
        manifest.put("sourceFormat", "onnx_int4");
        manifest.put("state", "RUNNABLE");
        manifest.put("installedAt", 1L);
        Files.writeString(dir.resolve(SidecarManifests.FILE_NAME), LocalJson.write(manifest));
    }

    private String tmp(String name) throws IOException {
        Path dir = modelRoot.resolve("_staging_" + name);
        Files.createDirectories(dir);
        return dir.toString().replace("\\", "/");
    }

    private Map<String, Object> get(String path) throws Exception {
        HttpURLConnection connection =
                (HttpURLConnection) URI.create(baseUrl + path).toURL().openConnection();
        return read(connection);
    }

    private int statusOf(String path) throws Exception {
        HttpURLConnection connection =
                (HttpURLConnection) URI.create(baseUrl + path).toURL().openConnection();
        return connection.getResponseCode();
    }

    private Map<String, Object> post(String path, String body, int expectedStatus) throws Exception {
        HttpURLConnection connection =
                (HttpURLConnection) URI.create(baseUrl + path).toURL().openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        try (OutputStream out = connection.getOutputStream()) {
            out.write(body.getBytes(StandardCharsets.UTF_8));
        }
        assertEquals(expectedStatus, connection.getResponseCode());
        return read(connection);
    }

    private Map<String, Object> read(HttpURLConnection connection) throws Exception {
        InputStream in = connection.getResponseCode() >= 400
                ? connection.getErrorStream() : connection.getInputStream();
        String body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        return LocalJson.parseObject(body);
    }
}
