package com.aresstack.askai.research.search.serp4j;

import com.aresstack.askai.research.search.api.SearchEngine;
import com.aresstack.askai.research.search.api.SearchProviderId;
import com.aresstack.askai.research.search.api.WebSearchProvider;
import com.aresstack.askai.research.search.brave.BraveSearchConfiguration;
import com.aresstack.askai.research.search.brightdata.BrightDataSearchConfiguration;
import com.aresstack.askai.research.search.config.HttpTransportConfiguration;
import com.aresstack.askai.research.search.dataforseo.DataForSeoResultFormat;
import com.aresstack.askai.research.search.dataforseo.DataForSeoSearchConfiguration;
import com.aresstack.askai.research.search.googlebrowser.GoogleBrowserSearchConfiguration;
import com.aresstack.askai.research.search.security.SecretValueService;
import com.aresstack.askai.research.search.serpapi.SerpApiSearchConfiguration;
import com.aresstack.serp4j.browser.playwright.PlaywrightBrowserRuntime;
import com.aresstack.serp4j.browser.playwright.PlaywrightBrowserType;
import com.aresstack.serp4j.core.model.id.ProviderId;
import com.aresstack.serp4j.core.port.credential.CredentialKey;
import com.aresstack.serp4j.engine.google.browser.GoogleBrowserSearchProvider;
import com.aresstack.serp4j.http.ahc.AhcHttpTransport;
import com.aresstack.serp4j.http.ahc.AhcHttpTransportOptions;
import com.aresstack.serp4j.provider.brave.BraveSearchOptions;
import com.aresstack.serp4j.provider.brave.BraveSearchProvider;
import com.aresstack.serp4j.provider.brightdata.BrightDataSearchProvider;
import com.aresstack.serp4j.provider.dataforseo.DataForSeoFunction;
import com.aresstack.serp4j.provider.dataforseo.DataForSeoSearchProvider;
import com.aresstack.serp4j.provider.serpapi.SerpApiSearchOptions;
import com.aresstack.serp4j.provider.serpapi.SerpApiSearchProvider;

import java.nio.file.Paths;
import java.time.Duration;

/**
 * Builds AskAI's {@link WebSearchProvider}s from the existing AskAI configuration files, backed by the Serp4J
 * provider modules (WP5). AskAI stays the host: it owns configuration, the AES-GCM secrets (resolved through
 * {@link HostCredentialProvider}) and one {@link AhcHttpTransport} per provider instance, which the returned
 * adapter closes. Credentials travel in HTTP headers, as implemented by the Serp4J providers; the one exception is
 * SerpApi, which only accepts {@code api_key} as URL parameter (masked by Serp4J in every log and error text).
 */
public final class Serp4jProviderFactory {

    static final ProviderId BRAVE_ID = ProviderId.of(BraveSearchProvider.PROVIDER_TYPE);
    static final ProviderId BRIGHT_DATA_ID = ProviderId.of(BrightDataSearchProvider.PROVIDER_TYPE);
    static final ProviderId DATA_FOR_SEO_ID = ProviderId.of(DataForSeoSearchProvider.PROVIDER_TYPE);
    static final ProviderId SERP_API_ID = ProviderId.of(SerpApiSearchProvider.PROVIDER_TYPE);
    static final ProviderId GOOGLE_BROWSER_ID = GoogleBrowserSearchProvider.DEFAULT_ID;

    private final SecretValueService secretValueService;

    public Serp4jProviderFactory(SecretValueService secretValueService) {
        if (secretValueService == null) {
            throw new IllegalArgumentException("secretValueService must not be null");
        }
        this.secretValueService = secretValueService;
    }

    public WebSearchProvider createBrave(BraveSearchConfiguration configuration) {
        CredentialKey apiKey = CredentialKey.of(BRAVE_ID, BraveSearchProvider.API_KEY_SECRET);
        HostCredentialProvider credentials = HostCredentialProvider.builder(secretValueService)
                .encrypted(apiKey, configuration.getApiKey())
                .build();
        BraveSearchOptions.Builder options = BraveSearchOptions.builder()
                .baseUrl(braveBaseUrl(configuration.getEndpoint()))
                .timeout(requestTimeout(configuration.getTransport()));
        if (configuration.getApiVersion() != null && !configuration.getApiVersion().trim().isEmpty()) {
            options.apiVersion(configuration.getApiVersion().trim());
        }
        if (hasText(configuration.getCountry())) {
            options.defaultCountry(configuration.getCountry().trim());
        }
        if (hasText(configuration.getSearchLanguage())) {
            options.defaultSearchLanguage(configuration.getSearchLanguage().trim());
        }
        if (hasText(configuration.getUiLanguage())) {
            options.defaultUiLanguage(configuration.getUiLanguage().trim());
        }
        AhcHttpTransport transport = transport(configuration.getTransport());
        try {
            BraveSearchProvider provider = BraveSearchProvider.builder()
                    .id(BRAVE_ID)
                    .transport(transport)
                    .credentials(credentials)
                    .credentialKey(apiKey)
                    .options(options.build())
                    .build();
            return new Serp4jWebSearchProvider(SearchProviderId.BRAVE, SearchEngine.BRAVE, provider, transport);
        } catch (RuntimeException failure) {
            transport.close();
            throw failure;
        }
    }

    public WebSearchProvider createBrightData(BrightDataSearchConfiguration configuration) {
        CredentialKey apiKey = CredentialKey.of(BRIGHT_DATA_ID, BrightDataSearchProvider.API_KEY);
        HostCredentialProvider credentials = HostCredentialProvider.builder(secretValueService)
                .encrypted(apiKey, configuration.getApiKey())
                .build();
        SearchEngine engine = configuration.getSearchEngine() == null
                ? SearchEngine.GOOGLE : configuration.getSearchEngine().getSearchEngine();
        AhcHttpTransport transport = transport(configuration.getTransport());
        try {
            BrightDataSearchProvider.Builder builder = BrightDataSearchProvider.builder(BRIGHT_DATA_ID)
                    .transport(transport)
                    .credentials(credentials)
                    .apiKey(apiKey)
                    .zone(configuration.getZone())
                    .requestTimeout(requestTimeout(configuration.getTransport()));
            if (hasText(configuration.getSynchronousEndpoint())) {
                builder.apiUrl(configuration.getSynchronousEndpoint().trim());
            }
            if (engine == SearchEngine.GOOGLE && hasText(configuration.getSearchEngineEndpoint())) {
                builder.googleSearchUrl(configuration.getSearchEngineEndpoint().trim());
            }
            // Engines other than Google are passed on unchanged; Serp4J answers them with UNSUPPORTED
            // instead of silently returning Google results under another label.
            return new Serp4jWebSearchProvider(SearchProviderId.BRIGHT_DATA, engine, builder.build(), transport);
        } catch (RuntimeException failure) {
            transport.close();
            throw failure;
        }
    }

    public WebSearchProvider createDataForSeo(DataForSeoSearchConfiguration configuration) {
        CredentialKey login = DataForSeoSearchProvider.loginKey(DATA_FOR_SEO_ID);
        CredentialKey password = DataForSeoSearchProvider.passwordKey(DATA_FOR_SEO_ID);
        HostCredentialProvider credentials = HostCredentialProvider.builder(secretValueService)
                .plain(login, configuration.getUsername())
                .encrypted(password, configuration.getPassword())
                .build();
        SearchEngine engine = configuration.getSearchEngine() == null
                ? SearchEngine.GOOGLE : configuration.getSearchEngine().getSearchEngine();
        AhcHttpTransport transport = transport(configuration.getTransport());
        try {
            DataForSeoSearchProvider.Builder builder = DataForSeoSearchProvider.builder(transport, credentials)
                    .id(DATA_FOR_SEO_ID)
                    .loginKey(login)
                    .passwordKey(password)
                    .function(configuration.getResultFormat() == DataForSeoResultFormat.REGULAR
                            ? DataForSeoFunction.REGULAR : DataForSeoFunction.ADVANCED);
            if (hasText(configuration.getEndpointBase())) {
                builder.baseUrl(configuration.getEndpointBase().trim());
            }
            if (configuration.getLocationCode() != null) {
                builder.defaultLocationCode(configuration.getLocationCode());
            }
            if (hasText(configuration.getLanguageCode())) {
                builder.defaultLanguageCode(configuration.getLanguageCode().trim());
            }
            return new Serp4jWebSearchProvider(SearchProviderId.DATA_FOR_SEO, engine, builder.build(), transport);
        } catch (RuntimeException failure) {
            transport.close();
            throw failure;
        }
    }

    public WebSearchProvider createSerpApi(SerpApiSearchConfiguration configuration) {
        CredentialKey apiKey = CredentialKey.of(SERP_API_ID, SerpApiSearchProvider.API_KEY_SECRET);
        HostCredentialProvider credentials = HostCredentialProvider.builder(secretValueService)
                .encrypted(apiKey, configuration.getApiKey())
                .build();
        SearchEngine engine = configuration.getSearchEngine() == null
                ? SearchEngine.GOOGLE : configuration.getSearchEngine().getSearchEngine();
        SerpApiSearchOptions.Builder options = SerpApiSearchOptions.builder()
                .timeout(requestTimeout(configuration.getTransport()))
                .noCache(configuration.isNoCache())
                .resolveGoogleGotoUrls(configuration.isResolveGoogleGotoUrls());
        if (hasText(configuration.getBaseUrl())) {
            options.baseUrl(configuration.getBaseUrl().trim());
        }
        if (hasText(configuration.getCountry())) {
            options.defaultCountry(configuration.getCountry().trim());
        }
        if (hasText(configuration.getLanguage())) {
            options.defaultLanguage(configuration.getLanguage().trim());
        }
        if (hasText(configuration.getLocation())) {
            options.defaultLocation(configuration.getLocation().trim());
        }
        if (hasText(configuration.getGoogleDomain())) {
            options.googleDomain(configuration.getGoogleDomain().trim());
        }
        if (configuration.getUrlResolveTimeoutMillis() > 0) {
            options.urlResolveTimeout(Duration.ofMillis(configuration.getUrlResolveTimeoutMillis()));
        }
        AhcHttpTransport transport = transport(configuration.getTransport());
        try {
            SerpApiSearchProvider provider = SerpApiSearchProvider.builder()
                    .id(SERP_API_ID)
                    .transport(transport)
                    .credentials(credentials)
                    .credentialKey(apiKey)
                    .options(options.build())
                    .build();
            // The configured engine (google/bing) is sent per request by Serp4jWebSearchProvider.
            return new Serp4jWebSearchProvider(SearchProviderId.SERP_API, engine, provider, transport);
        } catch (RuntimeException failure) {
            transport.close();
            throw failure;
        }
    }

    /**
     * Google web search through Serp4J's browser provider ({@code google-browser}, Quick Web Search). AskAI only
     * picks the local browser: Serp4J builds the URL (never {@code udm=50}), drives its own search browser and parses
     * the result page; there is no Google HTTP/HTML code in AskAI. The search browser is a separate Playwright
     * process with an ephemeral profile per search and never touches the research browser (MCP sidecar). The
     * returned adapter owns and closes the provider, the browser runtime and the {@code /goto} transport.
     */
    public WebSearchProvider createGoogleBrowser(GoogleBrowserSearchConfiguration configuration) {
        PlaywrightBrowserRuntime.Builder runtimeBuilder = PlaywrightBrowserRuntime.builder()
                .browserType("firefox".equalsIgnoreCase(trimToEmpty(configuration.getBrowserType()))
                        ? PlaywrightBrowserType.FIREFOX : PlaywrightBrowserType.CHROMIUM)
                .headless(configuration.isHeadless());
        if (hasText(configuration.getExecutablePath())) {
            runtimeBuilder.executablePath(Paths.get(configuration.getExecutablePath().trim()));
        } else if (hasText(configuration.getChannel())) {
            runtimeBuilder.channel(configuration.getChannel().trim());
        }
        if (configuration.getMaxConcurrentSessions() > 0) {
            runtimeBuilder.maxConcurrentSessions(configuration.getMaxConcurrentSessions());
        }
        PlaywrightBrowserRuntime runtime = runtimeBuilder.build();
        AhcHttpTransport transport = null;
        try {
            GoogleBrowserSearchProvider.Builder builder = GoogleBrowserSearchProvider.builder(runtime)
                    .id(GOOGLE_BROWSER_ID);
            if (hasText(configuration.getLanguage())) {
                builder.defaultLanguage(configuration.getLanguage().trim());
            }
            if (hasText(configuration.getCountry())) {
                builder.defaultCountry(configuration.getCountry().trim());
            }
            if (configuration.getNavigationTimeoutMillis() > 0) {
                builder.navigationTimeout(Duration.ofMillis(configuration.getNavigationTimeoutMillis()));
            }
            if (configuration.isResolveGotoUrls()) {
                transport = transport(configuration.getTransport());
                builder.gotoResolutionTransport(transport);
            }
            GoogleBrowserSearchProvider provider = builder.build();
            // Close order (ADR-017): provider first (cancels running searches, closes their sessions), then the
            // browser runtime, then the transport.
            return transport == null
                    ? new Serp4jWebSearchProvider(SearchProviderId.GOOGLE_BROWSER, SearchEngine.GOOGLE, provider,
                            runtime)
                    : new Serp4jWebSearchProvider(SearchProviderId.GOOGLE_BROWSER, SearchEngine.GOOGLE, provider,
                            runtime, transport);
        } catch (RuntimeException failure) {
            runtime.close();
            if (transport != null) {
                transport.close();
            }
            throw failure;
        }
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    /** AskAI stores the full {@code …/res/v1/web/search} endpoint; Serp4J wants the API base. */
    static String braveBaseUrl(String endpoint) {
        if (!hasText(endpoint)) {
            return BraveSearchOptions.DEFAULT_BASE_URL;
        }
        String value = endpoint.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        if (value.endsWith("/web/search")) {
            value = value.substring(0, value.length() - "/web/search".length());
        }
        return value;
    }

    private static AhcHttpTransport transport(HttpTransportConfiguration configuration) {
        AhcHttpTransportOptions.Builder options = AhcHttpTransportOptions.builder();
        if (configuration != null) {
            if (configuration.getConnectTimeoutMillis() > 0) {
                options.connectTimeout(Duration.ofMillis(configuration.getConnectTimeoutMillis()));
            }
            if (configuration.getReadTimeoutMillis() > 0) {
                options.readTimeout(Duration.ofMillis(configuration.getReadTimeoutMillis()));
            }
            if (configuration.getRequestTimeoutMillis() > 0) {
                options.requestTimeout(Duration.ofMillis(configuration.getRequestTimeoutMillis()));
            }
            if (configuration.getMaxConnections() > 0) {
                options.maxConnections(configuration.getMaxConnections());
            }
            if (configuration.getMaxConnectionsPerHost() > 0) {
                options.maxConnectionsPerHost(configuration.getMaxConnectionsPerHost());
            }
            options.followRedirects(configuration.isFollowRedirects());
            options.keepAlive(configuration.isKeepAlive());
            options.compressionEnabled(configuration.isCompressionEnabled());
            if (hasText(configuration.getUserAgent())) {
                options.userAgent(configuration.getUserAgent().trim());
            }
        }
        return AhcHttpTransport.create(options.build());
    }

    private static Duration requestTimeout(HttpTransportConfiguration configuration) {
        if (configuration == null || configuration.getRequestTimeoutMillis() <= 0) {
            return BraveSearchOptions.DEFAULT_TIMEOUT;
        }
        return Duration.ofMillis(configuration.getRequestTimeoutMillis());
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
