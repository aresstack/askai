package com.aresstack.askai.java8.localmodels;

import com.aresstack.askai.agent.model.embedding.EmbeddingConfigurationException;

import com.aresstack.askai.java8.json.SimpleJson;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Map;

/**
 * The productive {@link EmbeddingDimensionProbe}: a single {@code POST /api/embed} with
 * {@code {"model":..,"input":["probe"],"input_type":"raw"}} whose response vector length IS the dimension. It
 * validates the response strictly (one vector, non-empty, all finite) and NEVER falls back to a guessed
 * dimension. {@code input_type=raw} is used deliberately — RAW is identity for both E5 and non-E5 models.
 */
public final class HttpEmbeddingDimensionProbe implements EmbeddingDimensionProbe {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    private final int timeoutMillis;

    public HttpEmbeddingDimensionProbe(int timeoutMillis) {
        this.timeoutMillis = timeoutMillis <= 0 ? 15_000 : timeoutMillis;
    }

    @Override
    public int probeDimension(String baseUrl, String virtualModelId) throws EmbeddingConfigurationException {
        String url = stripTrailingSlash(baseUrl) + "/api/embed";
        String body = "{\"model\":" + jsonString(virtualModelId)
                + ",\"input\":[\"probe\"],\"input_type\":\"raw\"}";
        String response;
        int status;
        try {
            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(timeoutMillis);
            connection.setReadTimeout(timeoutMillis);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setDoOutput(true);
            OutputStream out = connection.getOutputStream();
            try {
                out.write(body.getBytes(UTF8));
            } finally {
                out.close();
            }
            status = connection.getResponseCode();
            InputStream in = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            response = readAll(in);
        } catch (IOException ex) {
            throw new EmbeddingConfigurationException(
                    EmbeddingConfigurationException.Reason.DIMENSION_PROBE_FAILED,
                    "embedding dimension probe request to " + url + " failed: " + ex.getMessage(), ex);
        }
        if (status != 200) {
            throw new EmbeddingConfigurationException(
                    EmbeddingConfigurationException.Reason.DIMENSION_PROBE_FAILED,
                    "embedding dimension probe returned HTTP " + status + ": " + response);
        }
        return dimensionOf(response);
    }

    /** Parse + validate a {@code /api/embed} response and return its single vector's length. Testable. */
    @SuppressWarnings("unchecked")
    static int dimensionOf(String responseJson) throws EmbeddingConfigurationException {
        Object parsed;
        try {
            parsed = SimpleJson.parse(responseJson);
        } catch (RuntimeException ex) {
            throw invalid("response is not valid JSON: " + ex.getMessage());
        }
        if (!(parsed instanceof Map)) {
            throw invalid("response is not a JSON object");
        }
        Object embeddings = ((Map<String, Object>) parsed).get("embeddings");
        if (!(embeddings instanceof List)) {
            throw invalid("response has no 'embeddings' array");
        }
        List<?> rows = (List<?>) embeddings;
        if (rows.size() != 1) {
            throw invalid("expected exactly 1 embedding for 1 input, got " + rows.size());
        }
        if (!(rows.get(0) instanceof List)) {
            throw invalid("the embedding is not a numeric array");
        }
        return validateVector((List<?>) rows.get(0));
    }

    /** A vector must be non-empty and entirely finite; returns its dimension. Testable. */
    static int validateVector(List<?> vector) throws EmbeddingConfigurationException {
        if (vector == null || vector.isEmpty()) {
            throw invalid("empty embedding vector");
        }
        for (Object value : vector) {
            if (!(value instanceof Number)) {
                throw invalid("embedding vector holds a non-numeric value");
            }
            double d = ((Number) value).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                throw invalid("embedding vector holds a non-finite value (" + d + ")");
            }
        }
        return vector.size();
    }

    private static EmbeddingConfigurationException invalid(String detail) {
        return new EmbeddingConfigurationException(
                EmbeddingConfigurationException.Reason.INVALID_PROBE_RESPONSE,
                "invalid embedding probe response: " + detail);
    }

    private static String jsonString(String value) {
        String v = value == null ? "" : value;
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == '"' || c == '\\') {
                sb.append('\\').append(c);
            } else if (c < 0x20) {
                sb.append(String.format("\\u%04x", (int) c));
            } else {
                sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    private static String readAll(InputStream in) throws IOException {
        if (in == null) {
            return "";
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int read;
        try {
            while ((read = in.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }
        } finally {
            in.close();
        }
        return new String(buffer.toByteArray(), UTF8);
    }

    private static String stripTrailingSlash(String value) {
        String v = value == null ? "" : value.trim();
        while (v.endsWith("/")) {
            v = v.substring(0, v.length() - 1);
        }
        return v;
    }
}
