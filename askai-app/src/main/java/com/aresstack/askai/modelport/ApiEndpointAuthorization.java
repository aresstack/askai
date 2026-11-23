package com.aresstack.askai.modelport;

import com.aresstack.askai.agent.model.EndpointAuthorization;

import com.aresstack.askai.config.AppConfigurationRepository;
import com.aresstack.askai.modelport.provider.OpenAiCompatibleModelProvider;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

/**
 * Hands the API key to requests an agent sends directly to the OpenAI-compatible API ({@code /embeddings},
 * {@code /rerank}): only while the API is chosen and only for URLs below its base URL, so the key never reaches
 * Ollama or another host. The key is read per request (stored encrypted or from KeePass) and never kept.
 */
public final class ApiEndpointAuthorization implements EndpointAuthorization.Source {

    private final ModelProviderSettingsRepository repository;
    private final OpenAiCompatibleModelProvider.ConnectionOpener connections;

    public ApiEndpointAuthorization(ModelProviderSettingsRepository repository) {
        this(repository, OpenAiCompatibleModelProvider.PLAIN);
    }

    public ApiEndpointAuthorization(ModelProviderSettingsRepository repository,
                                    OpenAiCompatibleModelProvider.ConnectionOpener connections) {
        this.repository = repository;
        this.connections = connections == null ? OpenAiCompatibleModelProvider.PLAIN : connections;
    }

    /** Installs the shared repository's source with AskAI's network settings (at start). */
    public static void install(AppConfigurationRepository central) {
        EndpointAuthorization.install(new ApiEndpointAuthorization(
                ModelProviderSettingsRepository.inConfigurationDirectory(),
                CentralModelDirectories.apiConnections(central)));
    }

    /** The API's URLs through the network settings (as the chat provider); every other URL plainly. */
    @Override
    public HttpURLConnection open(String url) throws IOException {
        ModelProviderSettings settings = repository.load();
        if (settings.usesApi() && belowBase(url, settings.getApiBaseUrl())) {
            return connections.open(url);
        }
        return (HttpURLConnection) new URL(url).openConnection();
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
