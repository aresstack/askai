package com.aresstack.askai.modelport;

import com.aresstack.askai.agent.model.EndpointAuthorization;

import java.io.IOException;
import java.util.Locale;

/**
 * Hands the API key to requests an agent sends directly to the OpenAI-compatible API ({@code /embeddings},
 * {@code /rerank}): only while the API is chosen and only for URLs below its base URL, so the key never reaches
 * Ollama or another host. The key is read per request (stored encrypted or from KeePass) and never kept.
 */
public final class ApiEndpointAuthorization implements EndpointAuthorization.Source {

    private final ModelProviderSettingsRepository repository;

    public ApiEndpointAuthorization(ModelProviderSettingsRepository repository) {
        this.repository = repository;
    }

    /** Installs the shared repository's source (at start). */
    public static void install() {
        EndpointAuthorization.install(new ApiEndpointAuthorization(
                ModelProviderSettingsRepository.inConfigurationDirectory()));
    }

    @Override
    public char[] bearerFor(String url) throws IOException {
        ModelProviderSettings settings = repository.load();
        if (!settings.usesApi() || !belowBase(url, settings.getApiBaseUrl())) {
            return null;
        }
        return repository.apiKey();
    }

    /** Whether {@code url} is the base URL or a path below it (same scheme, host and port). */
    static boolean belowBase(String url, String baseUrl) {
        if (url == null || baseUrl == null || baseUrl.trim().isEmpty()) {
            return false;
        }
        String base = baseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        String candidate = url.trim();
        String lowerBase = base.toLowerCase(Locale.ROOT);
        String lowerUrl = candidate.toLowerCase(Locale.ROOT);
        return lowerUrl.equals(lowerBase) || lowerUrl.startsWith(lowerBase + "/");
    }
}
