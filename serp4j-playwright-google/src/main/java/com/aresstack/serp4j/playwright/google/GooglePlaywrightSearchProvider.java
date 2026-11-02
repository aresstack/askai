package com.aresstack.serp4j.playwright.google;

import com.aresstack.serp4j.core.error.SearchErrorCategory;
import com.aresstack.serp4j.core.error.SearchProviderException;
import com.aresstack.serp4j.core.model.SearchFeature;
import com.aresstack.serp4j.core.model.SearchRequest;
import com.aresstack.serp4j.core.model.SearchResponse;
import com.aresstack.serp4j.core.model.id.ProviderId;
import com.aresstack.serp4j.core.model.id.SearchEngineId;
import com.aresstack.serp4j.core.model.id.SearchVertical;
import com.aresstack.serp4j.core.model.metadata.SearchWarning;
import com.aresstack.serp4j.core.port.ProviderDescriptor;
import com.aresstack.serp4j.core.port.ProviderKind;
import com.aresstack.serp4j.core.port.SearchContext;
import com.aresstack.serp4j.core.port.capability.CapabilityCheck;
import com.aresstack.serp4j.core.port.capability.CapabilityViolation;
import com.aresstack.serp4j.core.port.capability.ResponseFeature;
import com.aresstack.serp4j.core.port.capability.SearchCapabilities;
import com.aresstack.serp4j.http.HttpTransport;
import com.aresstack.serp4j.http.resolve.GoogleGotoUrlResolver;
import com.aresstack.serp4j.http.resolve.ResultUrlEnricher;
import com.aresstack.serp4j.playwright.PlaywrightSearchFailures;
import com.aresstack.serp4j.playwright.PlaywrightSearchProvider;
import com.aresstack.serp4j.playwright.PlaywrightSearchSupport;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.TimeoutError;
import com.microsoft.playwright.options.WaitForSelectorState;
import com.microsoft.playwright.options.WaitUntilState;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Google web search (Quick Web Search) on a Playwright {@link Browser} that the host hands over with every request
 * (askai #31: module serp4j-playwright-google, built on serp4j-playwright; lives in the AskAI repository and is
 * never published).
 *
 * <ul>
 *   <li>The browser belongs to the host: this provider never launches, closes or replaces it and creates no browser
 *       context of its own. It opens one page in the browser's existing context (a persistent search profile keeps
 *       its consent state), loads {@code https://www.google.com/search?q=…} (never {@code udm=50}) and closes only
 *       that page.</li>
 *   <li>Nothing is clicked: consent, challenge ({@code /sorry/}) and sign-in pages without results fail with
 *       {@code BLOCKED} and the detail {@code google_obstacle}.</li>
 *   <li>Synchronous (serp4j-playwright contract): {@link #search} runs on the calling thread, which must be the thread
 *       that owns the browser, because Playwright objects are not thread-safe.</li>
 * </ul>
 */
public final class GooglePlaywrightSearchProvider implements PlaywrightSearchProvider {

    public static final String PROVIDER_TYPE = "serp4j-playwright-google";
    public static final ProviderId DEFAULT_ID = ProviderId.of(PROVIDER_TYPE);
    public static final int RESULTS_PER_PAGE = 10;
    public static final Duration DEFAULT_NAVIGATION_TIMEOUT = Duration.ofSeconds(30);
    private final ProviderDescriptor descriptor;
    private final GoogleUrlBuilder urlBuilder;
    private final GoogleSerpHtmlParser parser = new GoogleSerpHtmlParser();
    private final GoogleSerpMapper mapper;
    /** Longest wait for Google's result page after the JavaScript start page. */
    static final Duration RESULT_WAIT = Duration.ofSeconds(15);
    /** Result container, challenge form or consent form: whichever Google ends up showing. */
    static final String RESULT_PAGE_SELECTOR =
            "#search, #rso, #captcha-form, form[action*=\"consent.google.\"]";

    private final Duration navigationTimeout;
    private final Clock clock;
    private final ResultUrlEnricher urlEnricher;

    private GooglePlaywrightSearchProvider(Builder builder) {
        this.descriptor = ProviderDescriptor.builder(builder.id, PROVIDER_TYPE, ProviderKind.BROWSER, capabilities())
                .displayName("Google (AskAI search browser)")
                .build();
        this.urlBuilder = new GoogleUrlBuilder(GoogleUrlBuilder.DEFAULT_BASE_URL, builder.defaultLanguage,
                builder.defaultCountry);
        this.mapper = new GoogleSerpMapper(builder.id);
        this.navigationTimeout = builder.navigationTimeout;
        this.clock = builder.clock;
        this.urlEnricher = builder.transport == null ? null : new ResultUrlEnricher(builder.id,
                new GoogleGotoUrlResolver(builder.transport), GoogleGotoUrlResolver.DEFAULT_TIMEOUT, clock);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static SearchCapabilities capabilities() {
        return SearchCapabilities.builder()
                .target(SearchEngineId.GOOGLE, SearchVertical.WEB)
                .features(SearchFeature.QUERY_TEXT, SearchFeature.PAGINATION_OFFSET, SearchFeature.MAX_RESULTS,
                        SearchFeature.COUNTRY, SearchFeature.LANGUAGE, SearchFeature.RAW_RESPONSE)
                .responseFeatures(ResponseFeature.ORGANIC_RESULTS, ResponseFeature.RELATED_QUERIES,
                        ResponseFeature.RAW_PAYLOAD)
                .maxResultsPerRequest(RESULTS_PER_PAGE)
                .build();
    }

    @Override
    public ProviderDescriptor getDescriptor() {
        return descriptor;
    }

    /**
     * Runs one search on the browser the host passed, synchronously on the calling thread (the thread that owns the
     * browser). Blocks until the optional {@code /goto} resolution is done; that resolution is bounded by
     * {@link GoogleGotoUrlResolver#DEFAULT_TIMEOUT} and the context deadline.
     */
    /**
     * Google first answers {@code /search} with a JavaScript start page (its {@code noscript} block points to
     * {@code /httpservice/retry/enablejs}); the results appear only after that script has run. Waits until the
     * result container, a challenge form or a consent form is in the page, at most {@link #RESULT_WAIT} and never
     * beyond the context deadline. When none appears, the page is classified as it is.
     */
    private void awaitResultPage(Page page, SearchContext context) {
        double wait = Math.min(RESULT_WAIT.toMillis(),
                PlaywrightSearchSupport.timeoutMillis(context, navigationTimeout, clock.instant()));
        try {
            page.waitForSelector(RESULT_PAGE_SELECTOR, new Page.WaitForSelectorOptions()
                    .setState(WaitForSelectorState.ATTACHED).setTimeout(wait));
        } catch (TimeoutError e) {
            // classify what is there (JavaScript start page, unknown layout)
        }
    }

    @Override
    public SearchResponse search(SearchRequest request, SearchContext context, Browser browser) {
        ProviderId id = descriptor.getId();
        Instant startedAt = clock.instant();
        CapabilityCheck check = PlaywrightSearchSupport.precheck(id, descriptor.getCapabilities(), request, context,
                browser, startedAt);
        String url;
        try {
            url = urlBuilder.build(request);
        } catch (IllegalArgumentException e) {
            throw new SearchProviderException(id, SearchErrorCategory.INVALID_REQUEST, e.getMessage());
        }
        if (GoogleUrlBuilder.isAiModeUrl(url)) {
            throw new SearchProviderException(id, SearchErrorCategory.INTERNAL, "AI Mode URL must never be requested");
        }
        String finalUrl;
        String html;
        Response response;
        boolean truncated = false;
        GoogleSerpPage parsed;
        Page page = null;
        try {
            page = openPage(browser);
            page.setDefaultTimeout(PlaywrightSearchSupport.timeoutMillis(context, navigationTimeout, clock.instant()));
            response = page.navigate(url, new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
            awaitResultPage(page, context);
            finalUrl = page.url();
            html = page.content();
            if (html.length() > PlaywrightSearchSupport.MAX_RAW_HTML_CHARS) {
                html = html.substring(0, PlaywrightSearchSupport.MAX_RAW_HTML_CHARS);
                truncated = true;
            }
            parsed = parser.parse(html);
            if (!context.getCancellation().isCancelled()) {
                // /goto links redirect only for the session that got the result page: resolve them in its context.
                parsed = BrowserGotoResolver.resolve(page.context().request(), parsed, clock,
                        PlaywrightSearchSupport.timeoutMillis(context, navigationTimeout, clock.instant()));
            }
        } catch (PlaywrightException e) {
            throw PlaywrightSearchSupport.translate(id, e, context, clock.instant());
        } finally {
            closeQuietly(page);
        }
        if (context.getCancellation().isCancelled()) {
            throw new SearchProviderException(id, SearchErrorCategory.CANCELLED, "search was cancelled");
        }
        GoogleObstacle obstacle = GoogleObstacleDetector.fromPage(finalUrl, parsed);
        if (obstacle != GoogleObstacle.NONE) {
            // ADR-016: consent, challenge and sign-in pages are reported, never clicked away.
            throw PlaywrightSearchFailures.obstacle(id, obstacle.name().toLowerCase(Locale.ROOT), finalUrl);
        }
        if (response != null && parsed.getOrganicResults().isEmpty()) {
            SearchProviderException byStatus = GoogleSearchFailures.forStatus(id, response.status());
            if (byStatus != null) {
                throw byStatus;
            }
        }
        if (parsed.getOrganicResults().isEmpty() && !parsed.isStructureRecognized()) {
            throw PlaywrightSearchFailures.unrecognized(id, "no Google result structure", finalUrl);
        }
        List<SearchWarning> warnings = new ArrayList<SearchWarning>();
        for (CapabilityViolation violation : check.getOptionalViolations()) {
            warnings.add(SearchWarning.of(SearchWarning.FEATURE_IGNORED, violation.getMessage(), id));
        }
        if (truncated) {
            warnings.add(SearchWarning.of(SearchWarning.PARTIAL_RESULTS,
                    "HTML snapshot truncated at " + PlaywrightSearchSupport.MAX_RAW_HTML_CHARS + " characters", id));
        }
        SearchResponse mapped = mapper.map(parsed, request, url, html, startedAt, clock.instant(), warnings);
        return urlEnricher == null ? mapped : resolveGotoUrls(mapped, context);
    }

    /** Waits for the bounded {@code /goto} resolution; any failure keeps the unresolved response. */
    private SearchResponse resolveGotoUrls(SearchResponse mapped, SearchContext context) {
        CompletableFuture<SearchResponse> enriched = new CompletableFuture<SearchResponse>();
        urlEnricher.completeWith(enriched, mapped, context);
        try {
            return enriched.get(GoogleGotoUrlResolver.DEFAULT_TIMEOUT.toMillis() + 2000L, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            enriched.complete(mapped);
            return mapped;
        } catch (ExecutionException | TimeoutException e) {
            enriched.complete(mapped);
            return mapped;
        }
    }

    /** A page in the browser's existing context; only a context-less browser gets Playwright's default page. */
    private static Page openPage(Browser browser) {
        List<BrowserContext> contexts = browser.contexts();
        if (!contexts.isEmpty()) {
            return contexts.get(0).newPage();
        }
        return browser.newPage();
    }

    private static void closeQuietly(Page page) {
        if (page == null) {
            return;
        }
        try {
            page.close();
        } catch (RuntimeException ignored) {
            // best effort; the host owns the browser and its contexts
        }
    }

    @Override
    public String toString() {
        return "GooglePlaywrightSearchProvider{id=" + descriptor.getId() + '}';
    }

    public static final class Builder {
        private ProviderId id = DEFAULT_ID;
        private String defaultLanguage;
        private String defaultCountry;
        private HttpTransport transport;
        private Duration navigationTimeout = DEFAULT_NAVIGATION_TIMEOUT;
        private Clock clock = Clock.systemUTC();

        private Builder() {
        }

        public Builder id(ProviderId id) {
            this.id = Objects.requireNonNull(id, "id");
            return this;
        }

        public Builder defaultLanguage(String language) {
            this.defaultLanguage = language;
            return this;
        }

        public Builder defaultCountry(String country) {
            this.defaultCountry = country;
            return this;
        }

        /** Transport for resolving {@code /goto} result URLs; {@code null} (default) leaves them unresolved. */
        public Builder gotoResolutionTransport(HttpTransport transport) {
            this.transport = transport;
            return this;
        }

        public Builder navigationTimeout(Duration timeout) {
            Objects.requireNonNull(timeout, "timeout");
            if (timeout.isNegative() || timeout.isZero()) {
                throw new IllegalArgumentException("timeout must be positive");
            }
            this.navigationTimeout = timeout;
            return this;
        }

        public Builder clock(Clock clock) {
            this.clock = Objects.requireNonNull(clock, "clock");
            return this;
        }

        public GooglePlaywrightSearchProvider build() {
            return new GooglePlaywrightSearchProvider(this);
        }
    }
}
