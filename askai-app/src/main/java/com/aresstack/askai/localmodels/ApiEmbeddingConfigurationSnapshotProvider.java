package com.aresstack.askai.localmodels;

import com.aresstack.askai.agent.model.embedding.EmbeddingConfigurationException;
import com.aresstack.askai.agent.model.embedding.EmbeddingConfigurationSnapshot;
import com.aresstack.askai.agent.model.embedding.EmbeddingConfigurationSnapshotProvider;
import com.aresstack.askai.agent.model.embedding.EmbeddingEndpointDescriptor;
import com.aresstack.askai.modelport.ModelProviderSettings;
import com.aresstack.askai.modelport.provider.OpenAiCompatibleModelProvider;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.function.Supplier;

/**
 * Embeddings on the OpenAI-compatible API ({@code POST <base>/embeddings}) while the API is the chosen provider: the
 * model is the API's {@code embedding} role ({@link ModelProviderSettings#apiRoleModel}, by default AskAI's preference
 * {@code BAAI/bge-m3}); its dimension is measured by one real request. The descriptor stays secret-free; the request
 * carries the key through {@link com.aresstack.askai.agent.model.EndpointAuthorization}. The model id together with
 * {@link #VERSION} makes a vector world of its own, so a stock embedded through Ollama is embedded again.
 */
public final class ApiEmbeddingConfigurationSnapshotProvider implements EmbeddingConfigurationSnapshotProvider {

    /** Marks vectors that came from the API (the model's version is not visible there). */
    static final String VERSION = "openai-compatible-api";
    private static final String NORMALIZATION = "none";

    /** Measures the vector length of {@code model} with one request; the key comes from {@code key}. */
    public interface DimensionProbe {
        int probe(String baseUrl, String model) throws IOException;
    }

    private final Supplier<ModelProviderSettings> settings;
    private final DimensionProbe probe;
    private final long timeoutMillis;

    public ApiEmbeddingConfigurationSnapshotProvider(Supplier<ModelProviderSettings> settings, DimensionProbe probe,
                                                     long timeoutMillis) {
        this.settings = settings;
        this.probe = probe;
        this.timeoutMillis = timeoutMillis;
    }

    @Override
    public EmbeddingConfigurationSnapshot prepareForSession(String sessionId, File sessionDirectory,
                                                            String selectedModel)
            throws EmbeddingConfigurationException {
        ModelProviderSettings current = settings.get();
        String baseUrl = current.getApiBaseUrl();
        if (baseUrl.isEmpty()) {
            throw new EmbeddingConfigurationException(EmbeddingConfigurationException.Reason.RUNTIME_START_FAILED,
                    "the OpenAI-compatible API is chosen but has no base URL");
        }
        String model = current.apiRoleModel(ModelProviderSettings.ROLE_EMBEDDING);
        if (model.isEmpty()) {
            throw new EmbeddingConfigurationException(EmbeddingConfigurationException.Reason.MODEL_NOT_CONFIGURED,
                    "no embedding model is set for the OpenAI-compatible API");
        }
        int dimension;
        try {
            dimension = probe.probe(baseUrl, model);
        } catch (IOException failed) {
            throw new EmbeddingConfigurationException(EmbeddingConfigurationException.Reason.RUNTIME_START_FAILED,
                    "embedding model '" + model + "' on the API did not answer: " + failed.getMessage(), failed);
        }
        if (dimension < 1) {
            throw new EmbeddingConfigurationException(EmbeddingConfigurationException.Reason.MODEL_NOT_FOUND,
                    "embedding model '" + model + "' on the API returned no vector");
        }
        return new EmbeddingConfigurationSnapshot("", new EmbeddingEndpointDescriptor(model, baseUrl,
                EmbeddingEndpointDescriptor.OPENAI_EMBEDDINGS_PATH, dimension, NORMALIZATION, VERSION, timeoutMillis));
    }

    /** The productive probe: one {@code /embeddings} request with the key of {@code key}. */
    public static DimensionProbe overHttp(final OpenAiCompatibleModelProvider.ConnectionOpener connections,
                                          final OpenAiCompatibleModelProvider.KeySource key, final int timeoutMillis) {
        return (baseUrl, model) -> {
            String url = stripSlash(baseUrl) + EmbeddingEndpointDescriptor.OPENAI_EMBEDDINGS_PATH;
            HttpURLConnection connection = connections.open(url);
            connection.setConnectTimeout(timeoutMillis);
            connection.setReadTimeout(timeoutMillis);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            com.aresstack.askai.agent.model.EndpointAuthorization.authorize(connection,
                    key == null ? null : key.apiKey());
            JsonObject request = new JsonObject();
            request.addProperty("model", model);
            request.addProperty("input", "dimension probe");
            connection.setDoOutput(true);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(request.toString().getBytes(StandardCharsets.UTF_8));
            }
            int status = connection.getResponseCode();
            String body = read(status >= 400 ? connection.getErrorStream() : connection.getInputStream());
            if (status != 200) {
                throw new IOException("HTTP " + status + errorCode(body));
            }
            try {
                JsonArray data = JsonParser.parseString(body).getAsJsonObject().getAsJsonArray("data");
                return data.get(0).getAsJsonObject().getAsJsonArray("embedding").size();
            } catch (RuntimeException malformed) {
                throw new IOException("the answer holds no data[0].embedding array");
            }
        };
    }

    /** The server's machine-readable error code (never its free text, which may echo input), or "". */
    static String errorCode(String body) {
        try {
            JsonElement error = JsonParser.parseString(body).getAsJsonObject().get("error");
            JsonElement code = error != null && error.isJsonObject()
                    ? (error.getAsJsonObject().has("code") ? error.getAsJsonObject().get("code")
                    : error.getAsJsonObject().get("type")) : error;
            String value = code == null || !code.isJsonPrimitive() ? "" : code.getAsString();
            return value.matches("[A-Za-z0-9_.:-]{1,64}") ? " (" + value + ")" : "";
        } catch (RuntimeException notJson) {
            return "";
        }
    }

    private static String read(InputStream in) throws IOException {
        if (in == null) {
            return "";
        }
        try (InputStream stream = in) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = stream.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static String stripSlash(String value) {
        String result = value.trim();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
