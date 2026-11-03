package com.aresstack.askai.localruntime;

import com.aresstack.askai.localruntime.generation.LoadedGenerationHandle;
import com.aresstack.askai.localruntime.generation.LocalGenerationBackend;
import com.aresstack.askai.localruntime.generation.LocalGenerationLoadRequest;
import com.aresstack.askai.localruntime.generation.LocalGenerationMessage;
import com.aresstack.askai.localruntime.generation.LocalGenerationRequest;
import com.aresstack.askai.localruntime.generation.LocalGenerationResult;
import com.aresstack.askai.localruntime.generation.LocalGenerationRuntimePort;
import com.aresstack.askai.localruntime.generation.LocalGenerationTokenListener;
import com.aresstack.windirectml.runtime.api.Backend;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * /api/generate + /api/chat contract, exercised against a FAKE generation port (no real model): request +
 * role validation, non-streaming and NDJSON streaming shapes, option mapping, keep_alive:0 unload, and the
 * load/unload lifecycle — all without inventing any concrete family adapter.
 */
public class LocalGenerationContractTest {

    private static final String QWEN = "local/Qwen/Qwen2.5-Coder-0.5B-Instruct:latest";

    private Path modelRoot;
    private LocalModelRuntimeServer server;
    private String baseUrl;
    private final AtomicReference<String> lastLoadedTemplate = new AtomicReference<>();
    private final AtomicReference<String> lastClosed = new AtomicReference<>();

    /** Echoes the request as three tokens; records the chat template it was loaded with. */
    private LocalGenerationRuntimePort echoPort() {
        return new LocalGenerationRuntimePort() {
            public LoadedGenerationHandle load(LocalGenerationLoadRequest request) {
                lastLoadedTemplate.set(request.chatTemplate());
                return new LoadedGenerationHandle() {
                    public LocalGenerationResult generate(LocalGenerationRequest req) {
                        return new LocalGenerationResult(render(req), 3, 3, "stop");
                    }

                    public void generate(LocalGenerationRequest req, LocalGenerationTokenListener listener) {
                        String[] parts = {"one ", "two ", render(req)};
                        StringBuilder soFar = new StringBuilder();
                        for (String part : parts) {
                            soFar.append(part);
                            if (!listener.onToken(part, soFar.toString())) {
                                listener.onComplete(new LocalGenerationResult(soFar.toString(), 3, 2,
                                        "cancel"));
                                return;
                            }
                        }
                        listener.onComplete(new LocalGenerationResult(soFar.toString(), 3, 3, "stop"));
                    }

                    public String virtualName() {
                        return request.virtualName();
                    }

                    public void close() {
                        lastClosed.set(request.virtualName());
                    }
                };
            }
        };
    }

    private static String render(LocalGenerationRequest req) {
        if (req.kind() == LocalGenerationRequest.Kind.CHAT) {
            StringBuilder sb = new StringBuilder("chat:");
            for (LocalGenerationMessage message : req.messages()) {
                sb.append(message.role()).append('=').append(message.content()).append(';');
            }
            return sb.append("n=").append(req.numPredict()).toString();
        }
        return "completion:" + req.prompt() + ";n=" + req.numPredict();
    }

    @Before
    public void start() throws IOException {
        modelRoot = Files.createTempDirectory("askai-gen-models");
        Path dir = modelRoot.resolve("qwen2.5-coder-0.5b-directml-int4");
        Files.createDirectories(dir);
        Map<String, Object> manifest = new java.util.LinkedHashMap<>();
        manifest.put("schemaVersion", 2);
        manifest.put("virtualName", QWEN);
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

        server = new LocalModelRuntimeServer(new LocalModelStore(modelRoot),
                new LocalModelEngine(Backend.CPU),
                new LocalGenerationEngine(echoPort(), LocalGenerationBackend.CPU));
        baseUrl = "http://127.0.0.1:" + server.start("127.0.0.1", 0);
    }

    @After
    public void stop() {
        server.stop();
    }

    @Test
    public void nonStreamingGenerateEchoesTheMappedRequest() throws Exception {
        Map<String, Object> response = post("/api/generate",
                "{\"model\":\"" + QWEN + "\",\"prompt\":\"hello\",\"stream\":false,"
                        + "\"options\":{\"num_predict\":42}}", 200);
        assertEquals(QWEN, response.get("model"));
        assertEquals("completion:hello;n=42", response.get("response"));
        assertEquals(Boolean.TRUE, response.get("done"));
        assertEquals("chatml", lastLoadedTemplate.get()); // the Qwen chat template from the catalog
    }

    @Test
    public void nonStreamingChatValidatesRolesAndReturnsAMessage() throws Exception {
        Map<String, Object> response = post("/api/chat",
                "{\"model\":\"" + QWEN + "\",\"messages\":[{\"role\":\"system\",\"content\":\"be brief\"},"
                        + "{\"role\":\"user\",\"content\":\"hi\"}],\"stream\":false,"
                        + "\"options\":{\"num_predict\":7}}", 200);
        Map<?, ?> message = (Map<?, ?>) response.get("message");
        assertEquals("assistant", message.get("role"));
        assertEquals("chat:system=be brief;user=hi;n=7", message.get("content"));
        assertEquals(Boolean.TRUE, response.get("done"));
    }

    @Test
    public void streamingGenerateEmitsNdjsonTokensThenDone() throws Exception {
        List<Map<String, Object>> lines = postStream("/api/generate",
                "{\"model\":\"" + QWEN + "\",\"prompt\":\"hi\",\"stream\":true}");
        assertTrue("more than one NDJSON line", lines.size() >= 2);
        // Every line but the last is a non-final token; the last is the terminal done line.
        for (int i = 0; i < lines.size() - 1; i++) {
            assertEquals(Boolean.FALSE, lines.get(i).get("done"));
        }
        Map<String, Object> last = lines.get(lines.size() - 1);
        assertEquals(Boolean.TRUE, last.get("done"));
        assertEquals("stop", last.get("done_reason"));
        // The concatenated deltas reproduce the echoed completion.
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < lines.size() - 1; i++) {
            text.append(String.valueOf(lines.get(i).get("response")));
        }
        assertTrue(text.toString().contains("completion:hi"));
    }

    @Test
    public void keepAliveZeroUnloadsTheGenerationHandle() throws Exception {
        post("/api/generate", "{\"model\":\"" + QWEN + "\",\"prompt\":\"hi\",\"stream\":false}", 200);
        lastClosed.set(null);
        Map<String, Object> unload = post("/api/generate",
                "{\"model\":\"" + QWEN + "\",\"keep_alive\":0}", 200);
        assertEquals("unload", unload.get("done_reason"));
        assertEquals("the warm generation handle was closed", QWEN, lastClosed.get());
    }

    @Test
    public void generateNeedsAPromptAndChatNeedsAUserMessage() throws Exception {
        Map<String, Object> noPrompt = post("/api/generate",
                "{\"model\":\"" + QWEN + "\",\"prompt\":\"\",\"stream\":false,\"keep_alive\":\"5m\"}", 400);
        assertEquals("INVALID_REQUEST", noPrompt.get("code"));
        Map<String, Object> noUser = post("/api/chat",
                "{\"model\":\"" + QWEN + "\",\"messages\":[{\"role\":\"system\",\"content\":\"x\"}]}", 400);
        assertEquals("INVALID_REQUEST", noUser.get("code"));
    }

    // ------------------------------------------------------------------ HTTP helpers

    @SuppressWarnings("unchecked")
    private Map<String, Object> post(String path, String body, int expectedStatus) throws Exception {
        HttpURLConnection connection = open(path);
        write(connection, body);
        int status = connection.getResponseCode();
        String text = readBody(connection, status);
        assertEquals(path + " -> " + text, expectedStatus, status);
        return (Map<String, Object>) LocalJson.parse(text);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> postStream(String path, String body) throws Exception {
        HttpURLConnection connection = open(path);
        write(connection, body);
        assertEquals(200, connection.getResponseCode());
        String text = readBody(connection, 200);
        List<Map<String, Object>> lines = new ArrayList<>();
        for (String line : text.split("\n")) {
            if (!line.trim().isEmpty()) {
                lines.add((Map<String, Object>) LocalJson.parse(line));
            }
        }
        assertFalse("streaming produced at least one line", lines.isEmpty());
        return lines;
    }

    private HttpURLConnection open(String path) throws Exception {
        HttpURLConnection connection =
                (HttpURLConnection) URI.create(baseUrl + path).toURL().openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        return connection;
    }

    private void write(HttpURLConnection connection, String body) throws IOException {
        try (OutputStream out = connection.getOutputStream()) {
            out.write(body.getBytes(StandardCharsets.UTF_8));
        }
    }

    private String readBody(HttpURLConnection connection, int status) throws IOException {
        InputStream in = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}
