package com.aresstack.askai.java8.localmodels;

import com.aresstack.askai.java8.hf.HuggingFaceClient;
import com.aresstack.askai.java8.json.SimpleJson;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * C8 real generation smoke: a small RUNNABLE generation model driven end to end through the PRODUCTIVE
 * pipeline (download -> generation wdmlpack compile + package-backed smoke -> manifest) and the sidecar
 * generation surface: /api/version reports generation linked, /api/generate and /api/chat return real
 * non-empty output through the DirectML runtime, /api/show carries chat/completion, and keep_alive:0
 * unloads the handle.
 *
 * <p>Opt in with {@code -Daskai.local.realModelSmoke=true} so a normal build does not download a model
 * (the runtime itself runs in-process now, nothing has to be staged).</p>
 */
public class LocalGenerationRealSmokeTest {

    /**
     * One RUNNABLE model per catalogued generation runtime, against the published 0.2.1 stack: SMOLLM2
     * (native safetensors → wdmlpack), QWEN (self-contained INT4 ONNX → model_q4f16.wdmlpack) and T5 — plus
     * CodeT5, which shares the T5 runtime with a distinct tokenizer. The models load one at a time and are
     * unloaded before the next, which also exercises the single-generation-model switch.
     *
     * <p>Qwen requires win-directml 0.2.1: on 0.2.0 its compile lifecycle defaulted the ONNX source to
     * {@code model.onnx} (missing, and — being named {@code model.onnx} — demanding a non-existent
     * {@code model.onnx_data}); 0.2.1 resolves the actual {@code model_q4f16.onnx} the catalog ships. Gemma-3
     * -it and Phi-3 stay UNVERIFIED and absent (no forced tests for non-RUNNABLE models).</p>
     */
    private static final String[] RUNNABLE_GENERATION_MODELS = {
            "HuggingFaceTB/SmolLM2-135M-Instruct",
            "google-t5/t5-small",
            "Salesforce/codet5-small",
            "Qwen/Qwen2.5-Coder-0.5B-Instruct",
    };

    @Test
    public void everyRunnableGenerationFamilyInstallsLoadsAndAnswers() throws Exception {
        assumeTrue("SKIPPED: opt in with -Daskai.local.realModelSmoke=true",
                Boolean.getBoolean("askai.local.realModelSmoke"));

        LocalModelRuntimeManager manager = new LocalModelRuntimeManager();
        try {
            for (String repo : RUNNABLE_GENERATION_MODELS) {
                smokeGenerationModel(manager, repo);
            }
        } finally {
            manager.stop();
        }
    }

    /** Install a generation model through the productive pipeline and drive its real generation surface. */
    private void smokeGenerationModel(LocalModelRuntimeManager manager, final String repo) throws Exception {
        // 1. Install (download -> generation compile + package-backed smoke -> manifest).
        String virtualName = new LocalModelInstaller(new HuggingFaceClient(null, ""), manager)
                .install(repo, new LocalModelInstaller.Listener() {
                    public void onStep(String step) {
                        System.out.println("[install " + repo + "] " + step);
                    }

                    public void onDownloadProgress(String fileName, long completed, long total) {
                    }
                });
        String baseUrl = manager.getBaseUrl();

        // 2. The generation runtime is linked.
        Map<String, Object> version = getJson(baseUrl, "/api/version");
        assertEquals(repo, Boolean.TRUE, ((Map<?, ?>) version.get("features")).get("generation"));

        // 3. /api/show carries the generation capabilities from the validated manifest.
        List<?> capabilities = (List<?>) postJson(baseUrl, "/api/show",
                single("model", virtualName), 200).get("capabilities");
        assertTrue(repo + ": completion capability", capabilities.contains("completion"));

        // 4. /api/generate returns real non-empty output and reports the actual backend used.
        Map<String, Object> options = new LinkedHashMap<String, Object>();
        options.put("num_predict", 24);
        Map<String, Object> genRequest = new LinkedHashMap<String, Object>();
        genRequest.put("model", virtualName);
        genRequest.put("prompt", "Write one short sentence about the Java programming language.");
        genRequest.put("stream", Boolean.FALSE);
        genRequest.put("options", options);
        Map<String, Object> gen = postJson(baseUrl, "/api/generate", genRequest, 200);
        assertFalse(repo + ": generation produced text",
                String.valueOf(gen.get("response")).trim().isEmpty());
        assertFalse(repo + ": the actual backend is reported",
                String.valueOf(gen.get("backend")).trim().isEmpty());

        // 5. If the family advertises chat, /api/chat returns a real assistant message through the SAME port.
        if (capabilities.contains("chat")) {
            Map<String, Object> message = new LinkedHashMap<String, Object>();
            message.put("role", "user");
            message.put("content", "Reply with a short greeting.");
            Map<String, Object> chatRequest = new LinkedHashMap<String, Object>();
            chatRequest.put("model", virtualName);
            chatRequest.put("messages", Collections.singletonList(message));
            chatRequest.put("stream", Boolean.FALSE);
            chatRequest.put("options", options);
            Map<String, Object> chat = postJson(baseUrl, "/api/chat", chatRequest, 200);
            Map<?, ?> reply = (Map<?, ?>) chat.get("message");
            assertEquals(repo + ": assistant role", "assistant", reply.get("role"));
            assertFalse(repo + ": chat produced text",
                    String.valueOf(reply.get("content")).trim().isEmpty());
        }

        // 6. keep_alive:0 unloads this handle (and frees memory for the next family); /api/ps drops it.
        Map<String, Object> unloadRequest = new LinkedHashMap<String, Object>();
        unloadRequest.put("model", virtualName);
        unloadRequest.put("keep_alive", 0);
        assertEquals(repo + ": unload", "unload",
                postJson(baseUrl, "/api/generate", unloadRequest, 200).get("done_reason"));
        List<?> loaded = (List<?>) getJson(baseUrl, "/api/ps").get("models");
        for (Object entry : loaded) {
            assertFalse(repo + ": the model is unloaded",
                    virtualName.equals(String.valueOf(((Map<?, ?>) entry).get("model"))));
        }
    }

    private static Map<String, Object> single(String key, String value) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put(key, value);
        return map;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getJson(String baseUrl, String path) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(baseUrl + path).openConnection();
        connection.setConnectTimeout(30_000);
        connection.setReadTimeout(600_000);
        return (Map<String, Object>) SimpleJson.parse(new String(readAll(connection.getInputStream()),
                StandardCharsets.UTF_8));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> postJson(String baseUrl, String path, Map<String, Object> body,
                                         int expectedStatus) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(baseUrl + path).openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setConnectTimeout(30_000);
        connection.setReadTimeout(600_000);
        try (OutputStream out = connection.getOutputStream()) {
            out.write(SimpleJson.toJson(body).getBytes(StandardCharsets.UTF_8));
        }
        int status = connection.getResponseCode();
        InputStream in = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        String text = new String(readAll(in), StandardCharsets.UTF_8);
        assertEquals(path + " -> " + text, expectedStatus, status);
        return (Map<String, Object>) SimpleJson.parse(text);
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }
}
