package com.aresstack.askai.browser.playwright;

import com.aresstack.askai.browser.BrowserBackendKind;
import com.aresstack.askai.browser.BrowserException;
import com.aresstack.askai.browser.BrowserLimits;
import com.aresstack.askai.browser.BrowserLink;
import com.aresstack.askai.browser.BrowserPageReadiness;
import com.aresstack.askai.browser.BrowserPageSnapshot;
import com.aresstack.askai.browser.BrowserSession;
import com.aresstack.askai.browser.UrlSafetyPolicy;
import com.aresstack.askai.browser.WebSearchResult;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The productive Playwright-backed {@link BrowserSession}. The URL policy is applied TWICE per navigation:
 * before navigating and again on the FINAL URL after redirects — a public URL that redirects to a private or
 * loopback target fails the call and the page is abandoned. Snapshots stay small and deterministic (text
 * capped at {@link BrowserLimits#getMaxTextChars()}, links at {@link BrowserLimits#getMaxLinks()} with
 * per-snapshot stable ids {@code link-1..n}); old link ids are invalidated by the next navigation.
 * {@code web_search} navigates to the configured provider URL — without one it fails honestly.
 */
final class PlaywrightBrowserSession implements BrowserSession {

    private final PlaywrightDriver driver;
    private final UrlSafetyPolicy policy;
    private final BrowserLimits limits;
    private final String searchUrlTemplate;
    private WebSearchProvider searchProvider;
    private List<BrowserLink> currentLinks = Collections.emptyList();
    private boolean hasPage;
    /** MANUAL_CHALLENGE_PENDING: the domain family whose parked challenge waits for the user, or null. */
    private String challengeFamily;
    private String challengeUrl;
    /** Monotonic per navigation — the stale-reference guard of every rendered-page snapshot. */
    private long snapshotGeneration;

    /** The typed configuration contract; defaults come ONLY from LegacyBrowserSearchDefaults. */
    private final com.aresstack.askai.browser.search.LegacyBrowserSearchSettings settings;

    /** Public-suffix aware domain families; tests may inject a fake (e.g. host:port for local worlds). */
    private com.aresstack.askai.browser.domain.DomainKeyResolver domainKeys =
            new com.aresstack.askai.browser.domain.PublicSuffixDomainKeyResolver();

    PlaywrightBrowserSession(PlaywrightDriver driver, UrlSafetyPolicy policy, BrowserLimits limits,
                             String searchUrlTemplate, WebSearchProvider searchProvider) {
        this(driver, policy, limits, searchUrlTemplate, searchProvider,
                com.aresstack.askai.browser.search.LegacyBrowserSearchDefaults.create());
    }

    PlaywrightBrowserSession(PlaywrightDriver driver, UrlSafetyPolicy policy, BrowserLimits limits,
                             String searchUrlTemplate, WebSearchProvider searchProvider,
                             com.aresstack.askai.browser.search.LegacyBrowserSearchSettings settings) {
        this.driver = driver;
        this.policy = policy;
        this.limits = limits;
        this.settings = settings;
        this.searchUrlTemplate = searchUrlTemplate == null || searchUrlTemplate.trim().isEmpty()
                ? null : searchUrlTemplate.trim();
        this.searchProvider = searchProvider == null
                ? new WebSearchProvider.OrganicResultSearchProvider(settings) : searchProvider;
    }

    /**
     * Inject a different {@link com.aresstack.askai.browser.domain.DomainKeyResolver} (tests/dev modes,
     * e.g. host:port keys for local multi-server worlds). The default organic extraction is rebuilt on
     * the same domain semantics; a custom search provider stays untouched.
     */
    void setDomainKeyResolver(com.aresstack.askai.browser.domain.DomainKeyResolver resolver) {
        if (resolver != null) {
            this.domainKeys = resolver; // capture + engine policy pick it up on the next search
        }
    }

    public BrowserBackendKind getBackendKind() {
        return BrowserBackendKind.PLAYWRIGHT_SIDECAR;
    }

    /** Test seam: search with exactly these engines (tests use literal-IP URLs, no DNS). */
    private java.util.List<com.aresstack.askai.browser.search.engine.BrowserSearchEngine> engineOverride;

    void setSearchEngines(java.util.List<com.aresstack.askai.browser.search.engine.BrowserSearchEngine> engines) {
        this.engineOverride = engines;
    }

    /**
     * The engines this search visits, in order. Precedence is the documented one: an explicit
     * {@code --search-url} is a dev/test escape hatch and stands ALONE — it never falls through to
     * public engines, because a local world must stay local. Otherwise the user's enabled engines
     * decide, and nothing here privileges one of them.
     */
    private java.util.List<com.aresstack.askai.browser.search.engine.BrowserSearchEngine> enginesToVisit() {
        if (engineOverride != null) {
            return engineOverride;
        }
        if (searchUrlTemplate != null) {
            return java.util.Collections.singletonList(
                    com.aresstack.askai.browser.search.engine.BrowserSearchEngineCatalog.custom(searchUrlTemplate));
        }
        return settings.navigation.engineSelection.resolvedEnabledEngines();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public WebSearchResult search(String query) throws BrowserException {
        // web_search: run the SHARED engine navigation; the consumer extracts each captured page. With
        // per-engine result-page counts, one engine may deliver SEVERAL pages — the items accumulate
        // (deduplicated by target URL) up to the configured result limit.
        final List<com.aresstack.askai.browser.WebSearchItem>[] organic = new List[]{null};
        final java.util.Set<String> seenTargets = new java.util.HashSet<String>();
        EngineNavigation nav = navigateAndCaptureSearchEngines(query, new CapturedPageConsumer() {
            public CapturedPageVerdict accept(
                    com.aresstack.askai.browser.render.RenderedPageDocument document,
                    String host,
                    List<com.aresstack.askai.browser.LegacySearchEngineAttemptResult> attempts) {
                com.aresstack.askai.browser.search.SearchResultExtractionResult extraction =
                        searchProvider.extract(document);
                switch (extraction.outcome) {
                    case ORGANIC_RESULTS:
                        List<com.aresstack.askai.browser.WebSearchItem> items =
                                organic[0] != null ? organic[0]
                                        : new ArrayList<com.aresstack.askai.browser.WebSearchItem>();
                        int before = items.size();
                        for (com.aresstack.askai.browser.search.SearchResultCandidate candidate
                                : extraction.candidates) {
                            if (items.size() >= settings.navigation.searchResultLimit) {
                                break;
                            }
                            if (!seenTargets.add(candidate.resolvedTargetUrl)) {
                                continue; // engines repeat hits across result pages — carried once
                            }
                            items.add(new com.aresstack.askai.browser.WebSearchItem(
                                    String.valueOf(items.size() + 1), candidate.title,
                                    candidate.resolvedTargetUrl, candidate.snippet));
                        }
                        attempts.add(attempt(host, com.aresstack.askai.browser
                                .LegacySearchAttemptOutcome.ORGANIC_RESULTS,
                                (items.size() - before) + " candidates"));
                        organic[0] = items;
                        return CapturedPageVerdict.DELIVERED;
                    case NO_ORGANIC_RESULTS:
                        attempts.add(attempt(host, com.aresstack.askai.browser
                                .LegacySearchAttemptOutcome.NO_ORGANIC_RESULTS,
                                bounded(firstDiagnostic(extraction))));
                        return CapturedPageVerdict.EMPTY;
                    default:
                        // An ununderstood layout is an extraction FAILURE, never an empty engine.
                        attempts.add(attempt(host, com.aresstack.askai.browser
                                .LegacySearchAttemptOutcome.EXTRACTION_FAILED,
                                bounded(firstDiagnostic(extraction))));
                        return CapturedPageVerdict.UNUSABLE;
                }
            }
        });
        if (organic[0] != null) {
            return new WebSearchResult(organic[0], nav.providerHosts, nav.attempts);
        }
        if (!nav.anyEngineReached && challengeFamily == null && nav.lastFailure != null) {
            throw nav.lastFailure; // nothing was reachable at all — a plain technical failure
        }
        // HARD INVARIANT: no path ever returns the SERP's raw anchors as results.
        return new WebSearchResult(
                Collections.<com.aresstack.askai.browser.WebSearchItem>emptyList(),
                nav.providerHosts, nav.attempts);
    }

    /**
     * What one captured result page turned out to be. DELIVERED and EMPTY are answers; UNUSABLE is the
     * page's problem, not the engine's — a layout the mechanical analysis did not understand may still
     * be rescued downstream (AI layout repair, link harvest), so it must not end the pagination the
     * user configured.
     */
    enum CapturedPageVerdict {
        /** Usable organic results — the engine delivered; deeper pages widen the same answer. */
        DELIVERED,
        /** EXPLICITLY no results ("no results found" markers) — deeper pages cannot have more. */
        EMPTY,
        /** Not understood/not extracted here — keep fetching the configured pages regardless. */
        UNUSABLE
    }

    /**
     * The captured-page hook for the shared engine navigation. It answers ONE question: what did this
     * page deliver? Whether that ends the search is the acquisition mode's decision, not the consumer's.
     */
    interface CapturedPageConsumer {
        CapturedPageVerdict accept(com.aresstack.askai.browser.render.RenderedPageDocument document,
                       String host,
                       List<com.aresstack.askai.browser.LegacySearchEngineAttemptResult> attempts);
    }

    /** The shared outcome of one engine-navigation pass. */
    static final class EngineNavigation {
        final List<String> providerHosts;
        final List<com.aresstack.askai.browser.LegacySearchEngineAttemptResult> attempts;
        final List<com.aresstack.askai.browser.search.repair.SearchChallengeState> challenges;
        final boolean anyEngineReached;
        final BrowserException lastFailure;

        EngineNavigation(List<String> providerHosts,
                List<com.aresstack.askai.browser.LegacySearchEngineAttemptResult> attempts,
                List<com.aresstack.askai.browser.search.repair.SearchChallengeState> challenges,
                boolean anyEngineReached, BrowserException lastFailure) {
            this.providerHosts = providerHosts;
            this.attempts = attempts;
            this.challenges = challenges;
            this.anyEngineReached = anyEngineReached;
            this.lastFailure = lastFailure;
        }
    }

    /**
     * The SINGLE engine-navigation loop (engine order, endpoint alternatives, domain-family locks,
     * consent, challenge detection, provider hosts, capture, per-engine attempts). web_search and
     * web_search_prepare both drive it. The consumer says whether a captured page DELIVERED; the
     * acquisition mode says whether that ends the run.
     */
    EngineNavigation navigateAndCaptureSearchEngines(String query, CapturedPageConsumer consumer)
            throws BrowserException {
        java.util.List<com.aresstack.askai.browser.search.engine.BrowserSearchEngine> engines = enginesToVisit();
        if (engines.isEmpty()) {
            throw new BrowserException("No search engine is enabled for the Playwright backend "
                    + "(enable one in the search settings, or start the browser runtime with "
                    + "--search-url=<template containing {query}>).");
        }
        if (query == null || query.trim().isEmpty()) {
            throw new BrowserException("Empty search query.");
        }
        String encoded = encode(query.trim());
        com.aresstack.askai.browser.search.engine.EngineAcquisitionMode mode = settings.navigation.engineSelection.getMode();
        // WHICH engines this search is really using, and why. A configured order that silently loses to
        // a leftover override is invisible in the result — the provider hosts only ever show what was
        // actually opened, never what was supposed to be.
        System.err.println("[engines] searchUrlOverride=" + (searchUrlTemplate == null ? "empty" : "set")
                + " engineOverride=" + (engineOverride == null ? "empty" : "set")
                + " acquisitionMode=" + mode + " resolvedEngines=" + describe(engines));
        List<String> providerHosts = new ArrayList<String>();
        List<com.aresstack.askai.browser.LegacySearchEngineAttemptResult> attempts =
                new ArrayList<com.aresstack.askai.browser.LegacySearchEngineAttemptResult>();
        List<com.aresstack.askai.browser.search.repair.SearchChallengeState> challenges =
                new ArrayList<com.aresstack.askai.browser.search.repair.SearchChallengeState>();
        boolean anyEngineReached = false;
        BrowserException lastFailure = null;
        int endpointsOpened = 0;
        for (com.aresstack.askai.browser.search.engine.BrowserSearchEngine engine : engines) {
            // An engine's endpoints are alternative ways to ask the SAME provider: the first one that
            // answers ends this engine's turn, whatever the acquisition mode is. Only whether the NEXT
            // engine is visited at all is a policy question.
            boolean engineDelivered = false;
            java.util.List<String> endpointTemplates = engine.getEndpointTemplates();
            // The per-engine RESULT-PAGE count is the user's setting (default 3); pages do NOT burn the
            // endpoint budget — maximumEngineAttempts keeps counting transports, not depth.
            int resultPages = settings.navigation.engineSelection == null
                    ? com.aresstack.askai.browser.search.engine.BrowserSearchEngineSelection
                            .Entry.DEFAULT_RESULT_PAGES
                    : settings.navigation.engineSelection.resultPagesFor(engine.getId());
            // The per-engine request delay is the user's setting (seconds, default 0 = off): an extra
            // pause before every request to this engine AFTER the first — on top of the natural
            // evaluation time — so a touchy provider never sees rapid-fire clicks.
            int engineDelayMillis = settings.navigation.engineSelection == null
                    ? com.aresstack.askai.browser.search.engine.BrowserSearchEngineSelection
                            .Entry.DEFAULT_DELAY_MILLIS
                    : settings.navigation.engineSelection.delayMillisFor(engine.getId());
            boolean engineRequestedBefore = false;
            for (int endpointIndex = 0; endpointIndex < endpointTemplates.size(); endpointIndex++) {
                String template = endpointTemplates.get(endpointIndex);
                if (endpointsOpened >= settings.navigation.maximumEngineAttempts) {
                    break;
                }
                String engineHost = com.aresstack.askai.browser.domain.PublicSuffixDomainKeyResolver
                        .hostOf(template);
                String engineFamily = domainKeys.resolve(template).getRegistrableDomain();
                if (engineFamily.equals(challengeFamily)) {
                    // MANUAL_CHALLENGE_PENDING: no new search, no reload, no retry on this family.
                    attempts.add(attempt(engineHost,
                            com.aresstack.askai.browser.LegacySearchAttemptOutcome.CHALLENGE_PENDING,
                            "family locked"));
                    continue;
                }
                endpointsOpened++;
                boolean endpointReached = false;
                // DELIBERATELY SEQUENTIAL: each result page is fetched, captured and fully EVALUATED
                // (extraction — and behind prepare, the AI judgement) before the NEXT page is requested.
                // That evaluation time is the natural pacing between clicks, so the engine never sees
                // rapid-fire page requests and answers with a CAPTCHA. Never prefetch result pages.
                for (int resultPage = 1; resultPage <= resultPages; resultPage++) {
                    String pageUrl = engine.pageUrl(endpointIndex, encoded, resultPage);
                    if (pageUrl == null) {
                        break; // this endpoint cannot address deeper result pages
                    }
                    if (engineRequestedBefore && engineDelayMillis > 0) {
                        paceBeforeRepeatRequest(engineDelayMillis);
                    }
                    engineRequestedBefore = true;
                    BrowserPageSnapshot page;
                    try {
                        page = open(pageUrl);
                    } catch (BrowserException engineUnreachable) {
                        if (engineUnreachable.getMessage() != null
                                && engineUnreachable.getMessage().contains("BROWSER_CLOSED")) {
                            // The USER closed the window: no other engine can help, and degrading
                            // their stop into an ATTEMPT line would launder it into a technical end.
                            throw engineUnreachable;
                        }
                        lastFailure = engineUnreachable;
                        attempts.add(attempt(engineHost,
                                com.aresstack.askai.browser.LegacySearchAttemptOutcome.NAVIGATION_FAILED,
                                engineUnreachable.getMessage()));
                        break; // page 1: transport down (try the next); page n: out of pages
                    }
                    anyEngineReached = true;
                    endpointReached = true;
                    // SERP guards, encapsulated here (never in the research loop): consent first, then
                    // the challenge check — a challenge page is never read as a result page.
                    if (driver.tryDismissConsent().startsWith("clicked")) {
                        page = checkedSnapshot(driver.current()); // re-read without the consent overlay
                    }
                    com.aresstack.askai.browser.domain.DomainIdentity pageIdentity =
                            domainKeys.resolve(page.getUrl());
                    String host = pageIdentity.getHost();
                    if (driver.challengePresent()) {
                        if (challengeFamily == null && driver.parkChallenge()) {
                            challengeFamily = pageIdentity.getRegistrableDomain();
                            challengeUrl = page.getUrl();
                        }
                        challenges.add(new com.aresstack.askai.browser.search.repair.SearchChallengeState(
                                pageIdentity.getRegistrableDomain(), page.getUrl()));
                        attempts.add(attempt(host,
                                com.aresstack.askai.browser.LegacySearchAttemptOutcome.CHALLENGE_PENDING,
                                "manual challenge"));
                        break; // the user solves it manually; no deeper pages on a challenged family
                    }
                    // Transit semantics only exist for PUBLIC engines; an IP/localhost dev world has no
                    // engine navigation to hide, so it never marks itself as transit.
                    if (!host.isEmpty() && !providerHosts.contains(host)
                            && pageIdentity.getHostKind()
                                    == com.aresstack.askai.browser.domain.HostKind.REGISTERED_NAME) {
                        providerHosts.add(host);
                    }
                    // A3: judge the STRUCTURED rendered page — container hierarchy, repeated result
                    // blocks, primary links, snippets. Navigation targets are the RESOLVED direct URLs.
                    com.aresstack.askai.browser.render.RenderedPageDocument document;
                    try {
                        document = driver.captureRenderedPage(domainKeys, ++snapshotGeneration);
                    } catch (BrowserException captureFailed) {
                        attempts.add(attempt(host,
                                com.aresstack.askai.browser.LegacySearchAttemptOutcome.EXTRACTION_FAILED,
                                bounded(captureFailed.getMessage())));
                        break;
                    }
                    if (document == null) {
                        attempts.add(attempt(host,
                                com.aresstack.askai.browser.LegacySearchAttemptOutcome.EXTRACTION_FAILED,
                                "structured page capture unavailable"));
                        break;
                    }
                    // The consumer states a FACT — what did this page deliver — and the policy below
                    // decides what that means for the run. A delivering page widens the same answer
                    // with the NEXT page; only an EXPLICITLY empty page ends this engine's pagination.
                    // An ununderstood layout does NOT: the user configured N pages, and downstream
                    // rescue (AI layout repair, link harvest) works per captured page.
                    CapturedPageVerdict verdict = consumer.accept(document, host, attempts);
                    if (verdict == CapturedPageVerdict.DELIVERED) {
                        engineDelivered = true;
                    } else if (verdict == CapturedPageVerdict.EMPTY) {
                        break;
                    }
                }
                if (endpointReached && engineDelivered) {
                    break; // this transport answered — the remaining endpoints are its fallbacks
                }
            }
            if (engineDelivered && mode == com.aresstack.askai.browser.search.engine.EngineAcquisitionMode.FIRST_USABLE) {
                break; // an engine answered; the ones behind it are the safety net, not extra work
            }
            if (endpointsOpened >= settings.navigation.maximumEngineAttempts) {
                break;
            }
        }
        return new EngineNavigation(providerHosts, attempts, challenges, anyEngineReached,
                lastFailure);
    }

    private static String firstDiagnostic(
            com.aresstack.askai.browser.search.SearchResultExtractionResult extraction) {
        return extraction.diagnostics.isEmpty() ? "" : extraction.diagnostics.get(
                extraction.diagnostics.size() - 1);
    }

    /** {@code [duckduckgo: html.duckduckgo.com, lite.duckduckgo.com][bing: www.bing.com]} */
    private static String describe(java.util.List<com.aresstack.askai.browser.search.engine.BrowserSearchEngine> engines) {
        StringBuilder sb = new StringBuilder();
        for (com.aresstack.askai.browser.search.engine.BrowserSearchEngine engine : engines) {
            sb.append('[').append(engine.getId()).append(": ");
            for (int i = 0; i < engine.getEndpointTemplates().size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(com.aresstack.askai.browser.domain.PublicSuffixDomainKeyResolver
                        .hostOf(engine.getEndpointTemplates().get(i)));
            }
            sb.append(']');
        }
        return sb.length() == 0 ? "[]" : sb.toString();
    }

    /** Attempt diagnostics are bounded by the diagnostics settings — never unbounded dumps. */
    private String bounded(String text) {
        String value = text == null ? "" : text;
        int limit = settings.diagnostics.maximumTextExcerptCharacters;
        return value.length() > limit ? value.substring(0, limit) : value;
    }

    private static com.aresstack.askai.browser.LegacySearchEngineAttemptResult attempt(
            String engineHost, com.aresstack.askai.browser.LegacySearchAttemptOutcome outcome,
            String diagnostic) {
        return new com.aresstack.askai.browser.LegacySearchEngineAttemptResult(engineHost, outcome,
                diagnostic == null ? "" : diagnostic);
    }

    public BrowserPageSnapshot open(String url) throws BrowserException {
        policy.check(url);
        return checkedSnapshot(driver.open(url));
    }

    public BrowserPageSnapshot currentPage() throws BrowserException {
        requirePage();
        return checkedSnapshot(driver.current());
    }

    @Override
    public BrowserPageReadiness probe(String url) throws BrowserException {
        policy.check(url);
        return readinessFrom(checkedSnapshot(driver.open(url)));
    }

    @Override
    public BrowserPageReadiness probeCurrent() throws BrowserException {
        requirePage();
        return readinessFrom(checkedSnapshot(driver.current()));
    }

    @Override
    public String dismissConsent() throws BrowserException {
        requirePage();
        return driver.tryDismissConsent();
    }

    @Override
    public String renderHud(String stateLine) throws BrowserException {
        requirePage();
        return driver.renderHud(stateLine);
    }

    @Override
    public String pollHudCommands() throws BrowserException {
        requirePage();
        return driver.pollHudCommands();
    }

    /** Compose a readability probe from a snapshot plus the driver's consent/challenge guards. */
    private BrowserPageReadiness readinessFrom(BrowserPageSnapshot s) {
        String marker = driver.challengeMarker(); // 'visible:…' | 'hidden:…' | 'none'
        boolean present = marker != null && !marker.isEmpty() && !"none".equals(marker);
        boolean visible = marker != null && marker.startsWith("visible");
        String consent = driver.consentCandidate();
        boolean consentPresent = consent != null && consent.startsWith("candidate");
        int len = s.getText() == null ? 0 : s.getText().length();
        return new BrowserPageReadiness(s.getUrl(), s.getTitle(), len,
                BrowserPageReadiness.excerptOf(s.getText()),
                present, visible, present ? marker : "",
                consentPresent, consentPresent ? consent : "");
    }

    public List<BrowserLink> links() throws BrowserException {
        requirePage();
        return currentLinks;
    }

    public BrowserPageSnapshot follow(String linkId) throws BrowserException {
        requirePage();
        for (BrowserLink link : currentLinks) {
            if (link.getId().equals(linkId)) {
                return open(link.getUrl());
            }
        }
        throw new BrowserException("Unknown link id: " + linkId + " (link ids are only valid for the "
                + "current page; call web_links first).");
    }

    public BrowserPageSnapshot back() throws BrowserException {
        requirePage();
        return checkedSnapshot(driver.back());
    }

    /**
     * Poll the parked manual challenge WITHOUT reloading or focusing it: while present → CHALLENGE line;
     * on first disappearance → RESOLVED line (challenge tab closed, family unlocked); otherwise NONE.
     */
    public List<String> challengeStatus() {
        // The user closing the window must NEVER read as "challenge resolved" — a dead challenge tab
        // and a dead browser look identical to the poll below, so the browser is checked first.
        if (driver.browserClosedByUser()) {
            return Collections.singletonList("BROWSER_CLOSED");
        }
        if (challengeFamily == null) {
            return Collections.singletonList("NONE");
        }
        if (driver.parkedChallengeStillPresent()) {
            return Collections.singletonList("CHALLENGE: " + challengeFamily + " " + challengeUrl);
        }
        String resolved = challengeFamily;
        driver.closeParkedChallenge();
        challengeFamily = null;
        challengeUrl = null;
        return Collections.singletonList("RESOLVED: " + resolved);
    }

    public void close() {
        driver.close();
    }

    /**
     * Keep the driver's event loop alive while the owner thread has no command to run (route interception,
     * HUD binding, popup close). @return false when the driver has no event loop to pump.
     */
    boolean pumpEvents(java.util.function.BooleanSupplier wake, long timeoutMillis) {
        return driver.pumpEvents(wake, timeoutMillis);
    }

    /** Slice length of one pacing pump round — granularity only; the DURATION is the user's setting. */
    private static final long PACING_PUMP_SLICE_MILLIS = 250L;

    /**
     * The user's per-engine request delay: wait, but keep PUMPING — a plain sleep on the owner thread
     * would freeze route interception, the HUD and close detection for the whole pause. A driver
     * without an event loop (tests, teardown) falls back to a plain sleep slice.
     */
    private void paceBeforeRepeatRequest(long delayMillis) {
        long remaining = delayMillis;
        while (remaining > 0) {
            long slice = Math.min(remaining, PACING_PUMP_SLICE_MILLIS);
            if (!driver.pumpEvents(() -> false, slice)) {
                try {
                    Thread.sleep(slice);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            remaining -= slice;
        }
    }

    /** The driver's control-plane HUD inbox (or null) — drained OUTSIDE the actor's command queue. */
    HudCommandInbox hudInbox() {
        return driver.hudInbox();
    }

    /** The post-redirect gate: the FINAL url must pass the policy or the page is abandoned. */
    private BrowserPageSnapshot checkedSnapshot(PlaywrightPageState state) throws BrowserException {
        try {
            policy.check(state.url);
        } catch (BrowserException blocked) {
            abandonPage();
            throw new BrowserException("Blocked after redirect — final URL is not allowed: "
                    + blocked.getMessage());
        }
        String text = state.text;
        boolean truncated = false;
        if (text.length() > limits.getMaxTextChars()) {
            text = text.substring(0, limits.getMaxTextChars());
            truncated = true;
        }
        List<BrowserLink> links = new ArrayList<BrowserLink>();
        int id = 0;
        for (PlaywrightPageState.Anchor anchor : state.anchors) {
            if (anchor.href.isEmpty()) {
                continue;
            }
            if (links.size() >= limits.getMaxLinks()) {
                truncated = true;
                break;
            }
            id++;
            links.add(new BrowserLink("link-" + id, anchor.text, anchor.href));
        }
        currentLinks = Collections.unmodifiableList(links);
        hasPage = true;
        return new BrowserPageSnapshot(state.url, state.title, text, truncated);
    }

    /** After a blocked redirect the offending page must not stay current. */
    private void abandonPage() {
        currentLinks = Collections.emptyList();
        hasPage = false;
        try {
            driver.back();
        } catch (BrowserException ignored) {
            // No history to fall back to — the session simply has no current page anymore.
        }
    }

    private void requirePage() throws BrowserException {
        if (!hasPage) {
            throw new BrowserException("No page is open yet — call web_open or web_search first.");
        }
    }

    private static String encode(String value) throws BrowserException {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (UnsupportedEncodingException ex) {
            throw new BrowserException("Cannot encode query.");
        }
    }
}
