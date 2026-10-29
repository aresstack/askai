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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * C2c real-model smokes for the encoder + reranker families, run STRICTLY through the productive install
 * pipeline (download → catalog compile → package-backed smoke → manifest) and the sidecar HTTP surface.
 *
 * <p>Environment-gated on the assembled sidecar distribution and a Java-21 launcher (infrastructure); it
 * does NOT skip because a model is missing — a missing model is downloaded, and a real infrastructure or
 * inference failure is a visible test failure. Opt in with {@code -Daskai.local.realModelSmoke=true} so a
 * normal build does not fetch gigabytes. E5-large is loaded LAST and each model is unloaded before the
 * next so the four encoders never sit in memory together.</p>
 */
public class LocalEngineRealSmokeTest {

    private static final String QUERY_TEXT = "what is a plugin framework";
    private static final String PASSAGE_A = "PF4J is a lightweight plugin framework for Java applications.";
    private static final String PASSAGE_B = "Simmer the tomatoes with basil and a little cream.";

    @Test
    public void everyEncoderAndTheRerankerRunThroughTheProductiveInstaller() throws Exception {
        String runtimeDir = System.getProperty("askai.local.runtime.dir", "");
        String java21 = System.getProperty("askai.local.runtime.java", "");
        assumeTrue("SKIPPED (infra): -Daskai.local.runtime.dir not set", !runtimeDir.isEmpty());
        assumeTrue("SKIPPED (infra): -Daskai.local.runtime.java (Java 21) not set", !java21.isEmpty());
        assumeTrue("SKIPPED: opt in with -Daskai.local.realModelSmoke=true",
                Boolean.getBoolean("askai.local.realModelSmoke"));

        LocalModelRuntimeManager manager = new LocalModelRuntimeManager();
        try {
            smokeEncoder(manager, "sentence-transformers/all-MiniLM-L6-v2", 384);
            smokeEncoder(manager, "intfloat/e5-small-v2", 384);
            smokeEncoder(manager, "intfloat/e5-base-v2", 768);
            smokeReranker(manager, "cross-encoder/ms-marco-MiniLM-L6-v2");
            // E5-large last (largest download + slowest CPU inference); the others are unloaded by now.
            smokeEncoder(manager, "intfloat/e5-large-v2", 1024);
        } finally {
            manager.stop();
        }
    }

    private void smokeEncoder(LocalModelRuntimeManager manager, String repo, int expectedDimension)
            throws Exception {
        String virtualName = install(manager, repo);
        String baseUrl = manager.getBaseUrl();
        boolean e5 = repo.contains("/e5-");

        // Batch of two: order preserved, correct dimension, finite, distinct vectors.
        List<float[]> batch = embed(baseUrl, virtualName, Arrays.asList(PASSAGE_A, PASSAGE_B), "raw");
        assertEquals(repo + ": batch size preserved", 2, batch.size());
        assertEquals(repo + ": dimension", expectedDimension, batch.get(0).length);
        assertEquals(repo + ": dimension", expectedDimension, batch.get(1).length);
        assertFinite(repo, batch.get(0));
        assertFinite(repo, batch.get(1));
        assertTrue(repo + ": distinct inputs produce distinct vectors",
                !java.util.Arrays.equals(batch.get(0), batch.get(1)));

        if (e5) {
            // The E5 query/passage prefixes are applied ONLY per request: raw != query != passage for the
            // SAME text, proving there is no hidden default prefix and no silent re-interpretation.
            float[] raw = embed(baseUrl, virtualName, Collections.singletonList(QUERY_TEXT), "raw").get(0);
            float[] query = embed(baseUrl, virtualName, Collections.singletonList(QUERY_TEXT), "query").get(0);
            float[] passage = embed(baseUrl, virtualName, Collections.singletonList(QUERY_TEXT), "passage").get(0);
            assertTrue(repo + ": raw must not be silently prefixed as query",
                    !java.util.Arrays.equals(raw, query));
            assertTrue(repo + ": query and passage prefixes differ",
                    !java.util.Arrays.equals(query, passage));
        } else {
            // A non-E5 encoder must reject query/passage rather than silently applying a prefix.
            assertEquals(repo + ": non-E5 rejects query prefix", "INVALID_INPUT_TYPE",
                    embedError(baseUrl, virtualName, QUERY_TEXT, "query"));
        }
        unload(baseUrl, virtualName);
    }

    private void smokeReranker(LocalModelRuntimeManager manager, String repo) throws Exception {
        String virtualName = install(manager, repo);
        String baseUrl = manager.getBaseUrl();
        Map<String, Object> request = new LinkedHashMap<String, Object>();
        request.put("model", virtualName);
        request.put("query", QUERY_TEXT);
        request.put("documents", Arrays.asList(PASSAGE_A, PASSAGE_B)); // index 0 = pf4j, 1 = tomato soup
        Map<String, Object> response = postJson(baseUrl, "/api/rerank", request, 200);
        double pf4jScore = Double.NEGATIVE_INFINITY;
        double soupScore = Double.NEGATIVE_INFINITY;
        for (Object entry : (List<?>) response.get("results")) {
            Map<?, ?> result = (Map<?, ?>) entry;
            int index = ((Number) result.get("index")).intValue();
            double score = ((Number) result.get("score")).doubleValue();
            if (index == 0) {
                pf4jScore = score;
            } else if (index == 1) {
                soupScore = score;
            }
        }
        assertTrue(repo + ": the PF4J document must outrank tomato soup (RAW_LOGIT, no threshold)",
                pf4jScore > soupScore);
        unload(baseUrl, virtualName);
    }

    // ------------------------------------------------------------------ pipeline + HTTP

    private String install(LocalModelRuntimeManager manager, String repo) throws Exception {
        LocalModelInstaller installer = new LocalModelInstaller(new HuggingFaceClient(null, ""), manager);
        return installer.install(repo, new LocalModelInstaller.Listener() {
            public void onStep(String step) {
                System.out.println("[install " + repo + "] " + step);
            }

            public void onDownloadProgress(String fileName, long completed, long total) {
            }
        });
    }

    private List<float[]> embed(String baseUrl, String model, List<String> inputs, String inputType)
            throws Exception {
        Map<String, Object> request = new LinkedHashMap<String, Object>();
        request.put("model", model);
        request.put("input", inputs);
        request.put("input_type", inputType);
        Map<String, Object> response = postJson(baseUrl, "/api/embed", request, 200);
        List<float[]> vectors = new ArrayList<float[]>();
        for (Object row : (List<?>) response.get("embeddings")) {
            List<?> values = (List<?>) row;
            float[] vector = new float[values.size()];
            for (int i = 0; i < values.size(); i++) {
                vector[i] = ((Number) values.get(i)).floatValue();
            }
            vectors.add(vector);
        }
        return vectors;
    }

    private String embedError(String baseUrl, String model, String input, String inputType)
            throws Exception {
        Map<String, Object> request = new LinkedHashMap<String, Object>();
        request.put("model", model);
        request.put("input", input);
        request.put("input_type", inputType);
        Map<String, Object> response = postJson(baseUrl, "/api/embed", request, 400);
        return String.valueOf(response.get("code"));
    }

    private void unload(String baseUrl, String model) throws Exception {
        Map<String, Object> request = new LinkedHashMap<String, Object>();
        request.put("model", model);
        request.put("keep_alive", 0);
        postJson(baseUrl, "/api/generate", request, 200);
    }

    private static void assertFinite(String repo, float[] vector) {
        for (float value : vector) {
            assertTrue(repo + ": embedding values must be finite", Float.isFinite(value));
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> postJson(String baseUrl, String path, Map<String, Object> body,
                                         int expectedStatus) throws Exception {
        HttpURLConnection connection =
                (HttpURLConnection) new URL(baseUrl + path).openConnection();
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
