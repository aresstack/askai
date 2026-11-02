package com.aresstack.askai.speech;

import com.aresstack.askai.client.AskAiOllamaClient;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.function.Supplier;

/**
 * {@link ServerProbe} for a live Ollama server. The version doubles as the reachability check; the
 * endpoint check does a cheap {@code GET /v1/audio/transcriptions} — a 404 means the (POST-only) route
 * is not registered, any other status means it exists — so no real audio is uploaded just to probe.
 */
public final class OllamaServerProbe implements ServerProbe {

    private final Supplier<String> baseUrlSupplier;

    public OllamaServerProbe(Supplier<String> baseUrlSupplier) {
        this.baseUrlSupplier = baseUrlSupplier;
    }

    public String serverKey() {
        return normalize(baseUrlSupplier.get());
    }

    public String version() throws Exception {
        return new AskAiOllamaClient(baseUrlSupplier.get()).getVersion();
    }

    public boolean endpointAvailable() throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(
                normalize(baseUrlSupplier.get()) + "/v1/audio/transcriptions").openConnection();
        try {
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            int status = connection.getResponseCode();
            return status != 404;
        } finally {
            connection.disconnect();
        }
    }

    private static String normalize(String value) {
        String normalized = value == null || value.trim().isEmpty() ? "http://127.0.0.1:11434" : value.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}
