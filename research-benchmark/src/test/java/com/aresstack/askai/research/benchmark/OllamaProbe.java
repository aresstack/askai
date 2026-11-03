package com.aresstack.askai.research.benchmark;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Decides whether the live benchmark can run: is Ollama reachable, which version, which chat models are
 * installed, and are the requested models among them. Every negative answer carries a readable reason, which
 * the live test turns into its skip message.
 */
public final class OllamaProbe {

    private static final int TIMEOUT_MILLIS = 3000;

    private final boolean reachable;
    private final String version;
    private final List<String> chatModels;
    private final String reason;

    private OllamaProbe(boolean reachable, String version, List<String> chatModels, String reason) {
        this.reachable = reachable;
        this.version = version;
        this.chatModels = Collections.unmodifiableList(chatModels);
        this.reason = reason;
    }

    public static OllamaProbe probe(String baseUrl) {
        String tags;
        try {
            tags = get(baseUrl + "/api/tags");
        } catch (IOException unreachable) {
            return new OllamaProbe(false, "", Collections.<String>emptyList(),
                    "no Ollama reachable at " + baseUrl + " (" + unreachable.getMessage() + "); start Ollama or "
                            + "set -D" + BenchmarkSettings.PREFIX + "ollamaUrl=<url>");
        }
        String version = "unknown";
        try {
            JsonElement parsed = JsonParser.parseString(get(baseUrl + "/api/version"));
            if (parsed.isJsonObject() && parsed.getAsJsonObject().has("version")) {
                version = parsed.getAsJsonObject().get("version").getAsString();
            }
        } catch (IOException | RuntimeException noVersion) {
            // an old Ollama without /api/version still serves the benchmark
        }
        return new OllamaProbe(true, version, chatModels(tags), "");
    }

    /** The installed models that can chat (embedding and reranker models are left out). */
    static List<String> chatModels(String tagsJson) {
        List<String> out = new ArrayList<String>();
        JsonElement root;
        try {
            root = JsonParser.parseString(tagsJson);
        } catch (RuntimeException invalid) {
            return out;
        }
        if (!root.isJsonObject() || !root.getAsJsonObject().has("models")) {
            return out;
        }
        JsonArray models = root.getAsJsonObject().getAsJsonArray("models");
        for (JsonElement model : models) {
            if (!model.isJsonObject()) {
                continue;
            }
            JsonObject object = model.getAsJsonObject();
            JsonElement name = object.has("name") ? object.get("name") : object.get("model");
            if (name == null || name.isJsonNull()) {
                continue;
            }
            String value = name.getAsString();
            String lower = value.toLowerCase(Locale.ROOT);
            if (lower.contains("embed") || lower.contains("rerank") || lower.startsWith("bge")
                    || lower.contains("minilm")) {
                continue;
            }
            out.add(value);
        }
        return out;
    }

    /**
     * The models this run benchmarks: the requested ones (all must be installed), or the first installed chat
     * model. Returns an empty list when nothing can run; {@link #modelProblem} then says why.
     */
    public List<String> modelsToRun(List<String> requested) {
        if (!reachable) {
            return Collections.emptyList();
        }
        if (requested.isEmpty()) {
            return chatModels.isEmpty() ? Collections.<String>emptyList()
                    : Collections.singletonList(chatModels.get(0));
        }
        for (String model : requested) {
            if (!isInstalled(model)) {
                return Collections.emptyList();
            }
        }
        return requested;
    }

    /** Why {@link #modelsToRun} is empty, or "" when it is not. */
    public String modelProblem(List<String> requested) {
        if (!reachable) {
            return reason;
        }
        if (requested.isEmpty()) {
            return chatModels.isEmpty() ? "Ollama is reachable but has no chat model installed" : "";
        }
        List<String> missing = new ArrayList<String>();
        for (String model : requested) {
            if (!isInstalled(model)) {
                missing.add(model);
            }
        }
        return missing.isEmpty() ? "" : "requested models are not installed on Ollama: " + missing
                + " (installed chat models: " + chatModels + ")";
    }

    private boolean isInstalled(String model) {
        for (String installed : chatModels) {
            if (installed.equals(model) || installed.equals(model + ":latest")) {
                return true;
            }
        }
        return false;
    }

    public boolean isReachable() {
        return reachable;
    }

    public String getVersion() {
        return version;
    }

    public List<String> getChatModels() {
        return chatModels;
    }

    public String getReason() {
        return reason;
    }

    private static String get(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection(Proxy.NO_PROXY);
        connection.setConnectTimeout(TIMEOUT_MILLIS);
        connection.setReadTimeout(TIMEOUT_MILLIS);
        try {
            int status = connection.getResponseCode();
            if (status != 200) {
                throw new IOException("HTTP " + status + " from " + url);
            }
            try (InputStream in = connection.getInputStream()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } finally {
            connection.disconnect();
        }
    }
}
