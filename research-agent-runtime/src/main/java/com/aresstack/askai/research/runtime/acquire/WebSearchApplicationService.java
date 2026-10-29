package com.aresstack.askai.research.runtime.acquire;

import com.aresstack.askai.research.runtime.loop.ResearchRunActivity;
import com.aresstack.askai.research.runtime.loop.ResearchRunBudget;
import com.aresstack.askai.research.runtime.loop.ResearchRunProgress;
import com.aresstack.askai.research.runtime.loop.ResearchLoopClock;
import com.aresstack.askai.research.runtime.loop.ResearchLoopListener;
import com.aresstack.askai.research.runtime.loop.ResearchStopReason;
import com.aresstack.askai.research.runtime.loop.ToolInvoker;

import com.aresstack.askai.browser.hud.ResearchHudCommand;
import com.aresstack.askai.browser.hud.ResearchHudState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The deterministic web-acquisition engine, extracted VERBATIM from {@code ResearchLoop} (T2a): SearchStrategy
 * execution → mandatory reranking → frontier → redirect/canonical visited handling → search-provider transit
 * filtering → {@code web_open} → deterministic page relevance → {@link SourceAcceptancePort} → {@code web_links}
 * → link relevance/frontier expansion → challenge/deferred-domain handling → budgets → cancellation → terminal
 * acquisition stop reason. Every effect goes through MCP tools; decisions are content-driven.
 *
 * <p>It is caller-agnostic: the autonomous research loop and a user-triggered manual search both consume it.
 * It never signals PHASE_READY and never touches the state machine or the model. When it accepts a source
 * it notifies an {@link AcceptedSourceListener} AT THAT POINT (before {@code web_links}) so a caller can do
 * research-specific work there; since issue #32 both callers are no-ops (no findings artifact anymore).</p>
 */
public final class WebSearchApplicationService {

    /** A source the acquisition accepted, handed to the caller at the acceptance point (before web_links). */
    public static final class AcceptedSource {
        private final String sourceId;
        private final boolean duplicate;
        private final String captureId;
        private final String pageUrl;
        private final String pageHost;
        private final String pageTitle;
        private final String page;

        public AcceptedSource(String sourceId, boolean duplicate, String captureId, String pageUrl,
                              String pageHost, String pageTitle, String page) {
            this.sourceId = sourceId;
            this.duplicate = duplicate;
            this.captureId = captureId;
            this.pageUrl = pageUrl;
            this.pageHost = pageHost;
            this.pageTitle = pageTitle;
            this.page = page;
        }

        public String getSourceId() {
            return sourceId;
        }

        public boolean isDuplicate() {
            return duplicate;
        }

        public String getCaptureId() {
            return captureId;
        }

        public String getPageUrl() {
            return pageUrl;
        }

        public String getPageHost() {
            return pageHost;
        }

        public String getPageTitle() {
            return pageTitle;
        }

        public String getPage() {
            return page;
        }
    }

    /** The single central budget/cancel gate, exposed so the caller's per-source work is budgeted identically. */
    public interface ToolBudget {
        ResearchStopReason beforeToolCall();
    }

    /**
     * Notified for each NEWLY accepted source at the acquisition acceptance point (before {@code web_links}).
     * The caller may perform additional BUDGETED work through {@code budget} and return a stop reason to abort
     * the run, or {@code null} to continue. The service itself knows nothing about findings.
     */
    public interface AcceptedSourceListener {
        ResearchStopReason onAccepted(AcceptedSource source, ToolBudget budget)
                throws ToolInvoker.ToolFailure, ToolInvoker.EndpointUnavailable;
    }

    private static final int INITIAL_SEARCH_RESULT_COUNT = 10;
    /**
     * How many links of one relevant page are worth judging, and how many of them may be opened.
     * A page can carry hundreds of links; scoring all of them would cost more than reading the page,
     * and opening all of them is how a search about rabbit steaks ends up in a noodle recipe.
     */
    private static final int MAXIMUM_ASSESSED_LINKS_PER_PAGE = 60;
    private static final int MAXIMUM_EXPANDED_LINKS_PER_PAGE = 5;
    /** How much of a page is shown to the relevance model — its beginning is what it is about. */
    private static final int PAGE_RELEVANCE_EXCERPT_CHARACTERS = 1_200;

    /**
     * The relevance the run has already committed to: the LOWEST page score among the pages that were
     * opened on the search engine's own selected hits.
     * <p>
     * A cross-encoder logit has no absolute meaning — the selection policy says so explicitly, and it is
     * why there is no global threshold anywhere in this codebase. But a run does not need one: the hits
     * the reranker chose from the SERP are, by that decision, relevant enough to read. Once their pages
     * have been scored with the same model, the same query and the same kind of text, the weakest of
     * them IS this query's answer to "relevant enough" — measured, not guessed. Links discovered later
     * are held to that same bar. Null until the first search hit has been read.
     */
    private Double seedPageRelevanceFloor;
    /**
     * The same idea for the other kind of text: the LOWEST score among the SERP hits the reranker
     * selected, measured on their title+snippet. A link is judged on its anchor text, which is the same
     * shape of document — so this, and not the page floor, is the bar a link has to clear. Comparing a
     * one-line anchor against a floor measured on full page text would compare two different things.
     */
    private Double seedSerpRelevanceFloor;
    /** The query every relevance question in this run is asked against. */
    private String relevanceQuery = "";
    /** The user's escape from ONE page, effective in every stage of that page's visit. */
    private final PageVisitSkip pageSkip;
    /** The generation of the visit currently being worked on; 0 while none is. */
    private long currentVisitGeneration;
    /** How often the HUD is asked for commands while a non-browser inference is running. */
    private static final long SKIP_WATCH_INTERVAL_MILLIS = 250L;

    private final ToolInvoker browser;
    private final ResearchRunBudget budget;
    private final ResearchRunProgress progress;
    private final ResearchLoopClock clock;
    private final ResearchLoopListener listener;
    private final AtomicBoolean cancelled;
    private final com.aresstack.askai.research.runtime.search.SearchStrategy searchStrategy;
    private final String apiProviderLabel;
    private final com.aresstack.askai.research.runtime.rerank.CandidateReranker reranker;
    private final com.aresstack.askai.browser.domain.DomainKeyResolver domainKeys;
    private final SourceAcceptancePort sourceAcceptancePort;
    private final long startedAt;
    private final long challengeProbeIntervalMillis;
    private final AcceptedSourceListener acceptedSourceListener;
    /** true → wait for the user to solve a challenge; false → skip the blocked page (leaves it parked). */
    private final boolean challengeWaitForUser;
    /** Max scan→handle→re-scan cycles to make a concrete page readable; 0 disables the loop (read at once). */
    private final int maxReadinessRetries;
    /** Minimum body text length for a page to count as readable in the readiness probe. */
    private final int minReadableChars;
    /** The readiness verdict seam (default heuristic; a model-backed judge can be set for user searches). */
    private PageReadinessJudge readinessJudge;
    // SC1 search-scope control: the second, ORTHOGONAL funnel decision (query relevance stays
    // with the reranker/relevance model). One immutable snapshot per run — a running search
    // never goes stale; the NEXT run sees the new fence.
    private SearchScopeControlPort scopeControl;
    private String scopeHandle;
    private boolean scopeActive;
    /** SC2a capability split: only the OUT filter is fail-closed; IN affinity degrades. */
    private boolean scopeOutFilter;
    /** Set when an evaluate failed inside a helper that cannot return a stop reason itself. */
    private boolean scopeControlBroken;

    /** SC1: inject the host's scope-control port (null = feature absent, baseline behaviour). */
    public void setScopeControl(SearchScopeControlPort port) {
        this.scopeControl = port;
    }
    /**
     * The completion seam: "fachlich fertig?" is a policy, not service code. Default is the LEGACY
     * autonomous semantics (behaviour-preserving); the manual search injects the deterministic
     * {@link FixedAcceptedSourceCountPolicy}.
     */
    private SearchCompletionPolicy completionPolicy;
    /** Layer 2: expected-content similarity (SERP anchor vs page text); default NONE → no override. */
    private PageContentSimilarity contentSimilarity = PageContentSimilarity.NONE;
    /** SERP anchor (result title + snippet) per seed candidate URL, for the semantic readiness override. */
    /**
     * Ids for the search hits of THIS run, minted in discovery order. They are what makes a hit addressable
     * later ("open #18 again") — a URL is not an identity: the same page can appear on several result pages.
     */
    private final java.util.Map<String, String> candidateIdByUrl = new java.util.LinkedHashMap<String, String>();
    /** Expected-content similarity at/above which an ambiguous verdict is rescued to READABLE. */
    private static final double EXPECTED_CONTENT_HIGH = 0.80;
    /** Authoritative language snapshot (ISO code) for the INITIAL SERP request; null keeps the provider default. */
    private volatile String searchLanguage;

    /** Sites of the search engine(s) used this run — pure TRANSIT: never a page, host, source or link farm. */
    private final Set<String> searchProviderSites = new HashSet<String>();
    /** Domain families with a pending MANUAL challenge: locked (no navigation/retry) until resolved. */
    private final Set<String> challengedFamilies = new HashSet<String>();
    /** Domain families that returned a TERMINAL access block this run: skipped for good (never retried). */
    private final Set<String> blockedFamilies = new HashSet<String>();
    /** Frontier URLs deferred because their family is challenge-locked (QUEUED_DOMAIN_BLOCKED). */
    private final List<FrontierEntry> deferredUrls = new ArrayList<FrontierEntry>();
    /** Time spent waiting for the USER (manual challenge) — never counted against the time budget. */
    private long waitedForUserMillis;
    private long lastChallengeProbeAt;
    /** Research HUD: the user paused autonomous navigation (Pause button); resumed via the overlay. */
    private volatile boolean hudPaused;
    /** Research HUD: user-set inter-page delay (Delay slider), applied before opening each page; NOT budgeted. */
    private volatile long hudDelayMillis;
    /** Upper bound for the inter-page delay slider (mirrors the overlay's max), so a stray value cannot stall a run. */
    private static final long HUD_MAX_DELAY_MILLIS = 30_000L;
    /**
     * Set when a browser call reports the endpoint is gone (the browser was closed / the browser runtime died even after
     * one restart). It makes {@link #stopReasonNow} return MCP_UNAVAILABLE so the run ends TECHNICALLY instead of
     * spinning forever on a dead browser (→ manualSearchFailed → the composer is freed + a red error shows).
     */
    private volatile boolean browserGone;
    /**
     * Set when a browser call fails with the browser runtime's typed BROWSER_CLOSED marker: the USER closed the
     * window. That is their stop signal — the run ends as USER_CANCELLED (their decision, sources kept),
     * never as a technical failure and never by polling a dead browser until a budget gives out.
     */
    private volatile boolean browserClosedByUser;
    /** One-shot: a Skip/Next just fired, so the NEXT inter-page delay is bypassed (don't make the user wait again). */
    private volatile boolean skipNextInterPageDelay;
    /** Research HUD: the user marked the CURRENT page relevant (⭐ toggle). Reset per page; applied at acceptance. */
    private volatile boolean hudRelevantCurrentPage;
    /** Soft cap for an in-page user-wait (consent/challenge) so a stuck wait self-parks; Skip is the immediate escape. */
    private static final long HUD_USER_WAIT_TIMEOUT_MILLIS = 180_000L;
    /**
     * Research HUD master switch. Default ON; {@code -Daskai.research.hud.enabled=false} forces the no-op path
     * so the browser can be A/B tested with the overlay injection entirely off. If the run is stable only with
     * the HUD disabled, the fault is in the overlay/binding; if it dies either way, it is a general browser
     * regression. When off, {@link #renderHud} / {@link #pollHudCommands} never touch the browser.
     */
    private final boolean hudEnabled =
            !"false".equalsIgnoreCase(System.getProperty("askai.research.hud.enabled", "true"));
    /** Throttles identical, per-tick HUD failure logs (render/poll run every probe tick during a user-wait). */
    private String lastHudErrorLine;

    private final ToolBudget budgetGate = new ToolBudget() {
        public ResearchStopReason beforeToolCall() {
            return WebSearchApplicationService.this.beforeToolCall();
        }
    };

    public WebSearchApplicationService(ToolInvoker browser, ResearchRunBudget budget,
                                       ResearchRunProgress progress, ResearchLoopClock clock,
                                       ResearchLoopListener listener, AtomicBoolean cancelled,
                                       com.aresstack.askai.research.runtime.search.SearchStrategy searchStrategy,
                                       String apiProviderLabel,
                                       com.aresstack.askai.research.runtime.rerank.CandidateReranker reranker,
                                       com.aresstack.askai.browser.domain.DomainKeyResolver domainKeys,
                                       SourceAcceptancePort sourceAcceptancePort, long startedAt,
                                       long challengeProbeIntervalMillis,
                                       AcceptedSourceListener acceptedSourceListener,
                                       boolean challengeWaitForUser,
                                       int maxReadinessRetries, int minReadableChars) {
        this.browser = browser;
        this.budget = budget;
        this.progress = progress;
        this.clock = clock;
        this.listener = listener;
        this.cancelled = cancelled;
        this.pageSkip = new PageVisitSkip(cancelled);
        this.searchStrategy = searchStrategy;
        this.apiProviderLabel = apiProviderLabel;
        this.reranker = reranker;
        this.domainKeys = domainKeys;
        this.sourceAcceptancePort = sourceAcceptancePort;
        this.startedAt = startedAt;
        this.challengeProbeIntervalMillis = challengeProbeIntervalMillis;
        this.acceptedSourceListener = acceptedSourceListener;
        this.challengeWaitForUser = challengeWaitForUser;
        this.maxReadinessRetries = maxReadinessRetries;
        this.minReadableChars = minReadableChars;
        this.readinessJudge = new HeuristicPageReadinessJudge(minReadableChars);
        this.completionPolicy = new MinimumEvidenceCompletionPolicy(budget);
    }

    /** Replace the default heuristic readiness judge (e.g. a model-backed one for a user search). No-op on null. */
    public void setReadinessJudge(PageReadinessJudge judge) {
        if (judge != null) {
            this.readinessJudge = judge;
        }
    }

    /** Replace the completion policy (the manual search injects the deterministic fixed-count baseline). */
    public void setCompletionPolicy(SearchCompletionPolicy policy) {
        if (policy != null) {
            this.completionPolicy = policy;
        }
    }

    /** Set the authoritative language snapshot for the initial SERP request (null keeps the provider default). */
    public void setSearchLanguage(String language) {
        this.searchLanguage = language == null || language.trim().isEmpty() ? null : language.trim();
    }

    /** Wire the Layer 2 expected-content similarity (embedding-backed); null keeps the no-op default. */
    public void setContentSimilarity(PageContentSimilarity similarity) {
        if (similarity != null) {
            this.contentSimilarity = similarity;
        }
    }

    /**
     * Layer 2 semantic safety net: rescue an AMBIGUOUS verdict (INTERACTIVE_CHALLENGE / UNREADABLE — the two
     * false-positive-prone outcomes) to READABLE when the page's own text is highly similar to what the SERP
     * result promised. Never touches ACCESS_BLOCKED (terminal), CONSENT_REQUIRED (resolve first) or READABLE.
     * A missing anchor, empty text or an unavailable embedder ({@code NaN}) leaves the base verdict unchanged.
     */
    static PageReadinessJudge.Verdict withExpectedContent(PageReadinessJudge.Verdict base, String expected,
            String actual, PageContentSimilarity similarity, double threshold) {
        if (base != PageReadinessJudge.Verdict.INTERACTIVE_CHALLENGE
                && base != PageReadinessJudge.Verdict.UNREADABLE) {
            return base;
        }
        if (expected == null || expected.trim().isEmpty() || actual == null || actual.trim().isEmpty()) {
            return base;
        }
        double s = similarity.score(expected, actual);
        return !Double.isNaN(s) && s >= threshold ? PageReadinessJudge.Verdict.READABLE : base;
    }

    /**
     * Execute the deterministic acquisition for the user's search text; returns the explicit
     * acquisition stop reason. The ORIGINAL text is what the search engines receive — the derived
     * terms only ever answer relevance questions. Rebuilding the query from the term set once cut
     * umlauts and short words out of the SERP query ("hühner" became "hner").
     */
    public ResearchStopReason execute(String task) {
        ResearchStopReason reason = beginScopeControl();
        if (reason == null) {
            try {
                reason = runAcquisition(task, WebAcquisitionText.queryTerms(task));
            } finally {
                if (scopeActive && scopeHandle != null) {
                    scopeControl.end(scopeHandle); // best effort — host teardown also clears
                }
            }
        }
        // HUD lifecycle: after a terminal outcome the browser may stay open, but the overlay must stop
        // pretending a page is being visited — controls off, state visibly final. Best-effort like every
        // other HUD render; a dead browser is simply skipped.
        // Terminal breadcrumbs: when the completion message never reaches the host, these two lines
        // pin whether the hang sits in the terminal HUD render or after this method returned.
        listener.status("terminal reason=" + reason + " — rendering DONE hud");
        renderTerminalHud(reason);
        listener.status("terminal hud done — reporting completion");
        return reason;
    }

    private ResearchStopReason runAcquisition(String task, Set<String> terms) {
        // Seed: search, else nothing to do.
        List<FrontierEntry> frontier = new ArrayList<FrontierEntry>();
        ResearchStopReason seedStop = null;
        // How the INITIAL search concluded — kept so an empty frontier caused by a technical search failure
        // is reported as a technical problem, never as an honest "no relevant results".
        com.aresstack.askai.research.runtime.search.InitialSearchStatus initialStatus =
                com.aresstack.askai.research.runtime.search.InitialSearchStatus.NO_RESULTS;
        String query = task == null ? "" : task.trim();
        relevanceQuery = query; // every relevance question in this run is asked against THIS query
        // SERP attempt loop: a manual challenge ON THE ENGINE PAGES does not end the search. When the
        // engines delivered nothing because a CAPTCHA blocked them, the run waits for the user and
        // then asks the engines AGAIN. Every retry needs a freshly user-solved challenge, so the loop
        // is bounded by the user's own actions — never a hot retry.
        while (true) {
            boolean seedFailed = false;
            try {
                listener.progress(progress, apiProviderLabel == null
                        ? ResearchRunActivity.searching(query)
                        : ResearchRunActivity.searchingViaApi(query, apiProviderLabel));
                // Interchangeable INITIAL search: whether these candidates come from the browser SERP path
                // or an API provider, the code below (reranking → frontier → Playwright) is identical. URLs
                // come straight from typed SearchResultCandidates — no ATTEMPT:/CHALLENGE: text parsing.
                com.aresstack.askai.research.runtime.search.InitialSearchResult result = searchStrategy.search(
                        new com.aresstack.askai.research.runtime.search.InitialSearchRequest(
                                query, INITIAL_SEARCH_RESULT_COUNT, searchLanguage, null),
                        cancellationSignal(),
                        new com.aresstack.askai.research.runtime.search.SearchBudgetGate() {
                            public boolean beforeToolCall() {
                                return WebSearchApplicationService.this.beforeToolCall() == null;
                            }
                        });
                initialStatus = result.status;
                // SERP candidates enter the funnel as discovered links (the reranker then assesses them all).
                progress.linksDiscovered(result.candidates.size());
                // The search itself becomes visible: which engine delivered how many SERP result pages.
                progress.setSerpSummary(serpSummaryOf(result.diagnostics));
                // Tick the card NOW: what the engines delivered is known here, well before reranking ends
                // and long before the first page opens.
                listener.progress(progress, ResearchRunActivity.searching(query));
                for (String providerHost : result.providerHosts) {
                    searchProviderSites.add(familyOf(providerHost));
                }
                applyChallenges(result.challenges);
                // MANDATORY reranking BEFORE anything reaches the frontier: no page is ever opened in raw
                // engine order. Only the selected survivors, in relevance order, become frontier URLs; a
                // reranker failure ends the run with a typed reason and opens nothing.
                seedStop = seedReranking(query, result.candidates, frontier);
            } catch (ToolInvoker.EndpointUnavailable ex) {
                // The browser MCP endpoint is gone (the browser runtime likely died): this is the concrete,
                // retryable technical cause behind a SEARCH_TECHNICAL_PROBLEM — log it, never swallow it.
                listener.status("[web-search] technical failure stage=SEED_SEARCH (endpoint unavailable —"
                        + " browser runtime may be dead) cause=" + describe(ex));
                return ResearchStopReason.MCP_UNAVAILABLE;
            } catch (ToolInvoker.ToolFailure ex) {
                if (noteIfBrowserClosed(ex)) {
                    return ResearchStopReason.USER_CANCELLED; // the user's window close, not a failure
                }
                listener.status("[web-search] technical failure stage=SEED_SEARCH cause=" + describe(ex));
                progress.error();
                seedFailed = true;
                // The THROW left initialStatus on its NO_RESULTS initializer — record the truth, or an
                // empty frontier below reads as an honest "no relevant results": green check, no retry,
                // although not a single page was ever searched.
                initialStatus =
                        com.aresstack.askai.research.runtime.search.InitialSearchStatus.TECHNICAL_PROBLEM;
            } catch (RuntimeException ex) {
                if (noteIfBrowserClosed(ex)) {
                    return ResearchStopReason.USER_CANCELLED;
                }
                // A malformed prepare/apply payload (codec DecodeException) must not crash the loop —
                // it is a tool-level failure. Recorded as a TECHNICAL initial status: with an empty
                // frontier the run then ends as SEARCH_TECHNICAL_PROBLEM (visible failure + retry),
                // never as a fake "no relevant results" success.
                listener.status("[web-search] technical failure stage=SEED_SEARCH_PREPARE cause="
                        + describe(ex));
                progress.error();
                seedFailed = true;
                initialStatus =
                        com.aresstack.askai.research.runtime.search.InitialSearchStatus.TECHNICAL_PROBLEM;
            }
            if (seedFailed || seedStop != null || cancelled.get() || browserClosedByUser
                    || !frontier.isEmpty() || challengedFamilies.isEmpty() || !challengeWaitForUser) {
                break; // an answer, a decision, or nothing a solved challenge could change
            }
            // The engines were CAPTCHA-blocked and delivered nothing usable: wait for the user to solve
            // it, then return to the SEARCH — never leave someone who just solved a challenge stranded.
            ResearchStopReason waited = waitForSerpChallengeResolution(frontier);
            if (waited != null) {
                return waited;
            }
            if (!challengedFamilies.isEmpty()) {
                break; // still blocked — the frontier logic below reports honestly
            }
            listener.status("manual challenge solved — asking the search engines again");
        }
        if (seedStop != null) {
            return seedStop;
        }
        if (browserClosedByUser) {
            return ResearchStopReason.USER_CANCELLED; // never re-labelled as a technical problem below
        }
        // Honest reporting: an INITIAL search that failed technically (SERP layout could not be extracted —
        // e.g. the layout-repair model was unavailable — or every engine was blocked with nothing
        // extractable) produced no candidates and therefore an empty frontier. That is NOT the same as
        // "no relevant results": surface it as a technical problem the user can retry.
        if (frontier.isEmpty()
                && initialStatus
                        == com.aresstack.askai.research.runtime.search.InitialSearchStatus.TECHNICAL_PROBLEM) {
            return ResearchStopReason.SEARCH_TECHNICAL_PROBLEM;
        }
        // The card reflects the SERP as soon as the engine answered: what was found/assessed/selected is
        // known HERE — before the first page opens, not only after it.
        listener.progress(progress, ResearchRunActivity.searching(relevanceQuery));

        while (true) {
            ResearchStopReason gate = stopReasonNow();
            if (gate != null) {
                return gate;
            }
            // HUD: apply Pause/Resume, and if paused, wait (cancel-aware) instead of opening the next page.
            applyHudPauseResume();
            ResearchStopReason pausedStop = awaitResumeIfPaused();
            if (pausedStop != null) {
                return pausedStop;
            }
            probeChallengesIfDue(frontier);
            if (frontier.isEmpty() && !deferredUrls.isEmpty() && !challengedFamilies.isEmpty()) {
                // WAITING_FOR_USER: only challenge-bound work is left. Wait cooperatively (short
                // cancel-aware ticks, ~1/s probes), without failing any navigation or time budget.
                ResearchStopReason wait = waitForManualChallenge(frontier);
                if (wait != null) {
                    return wait;
                }
                continue;
            }
            if (frontier.isEmpty()) {
                return completionPolicy.labelExhaustion(ResearchStopReason.NO_RELEVANT_PATHS, progress);
            }
            FrontierEntry entry = frontier.remove(0);
            String url = entry.getUrl();
            // From here on this page has its own identity: a Skip pressed while it is being worked on
            // belongs to THIS visit, and any of its work that finishes afterwards can tell that it does.
            currentVisitGeneration = pageSkip.beginVisit();
            hudRelevantCurrentPage = false; // ⭐ is per page: start clean for this url
            String canonical = WebAcquisitionText.canonicalish(url);
            if (progress.alreadyVisited(canonical)) {
                continue; // already visited → never navigate again
            }
            if (blockedFamilies.contains(familyOf(url))) {
                // A terminal access block already hit this domain this run: never re-open it (unlike a
                // pending challenge, there is nothing to resolve).
                progress.noteVisitedAlias(canonical);
                listener.status("skipped blocked domain: " + url);
                continue;
            }
            if (challengedFamilies.contains(familyOf(url))) {
                deferredUrls.add(entry); // QUEUED_DOMAIN_BLOCKED: starts only after the challenge resolves
                continue;
            }
            try {
                ResearchStopReason g2 = beforeToolCall();
                if (g2 != null) {
                    return g2;
                }
                // HUD: honour the user-set inter-page delay before opening this page (Next/Skip/cancel interrupt).
                ResearchStopReason delayed = interPageDelay();
                if (delayed != null) {
                    return delayed;
                }
                String page = openWithReadiness(url, entry.getExpectedContent());
                if (page == null) {
                    // The page could not be made readable (CAPTCHA skipped / consent not clearable / too
                    // little text): mark it visited so it is never retried, and leave its parked source with
                    // an empty full text. The score already tells the user this hit still needs reading.
                    progress.noteVisitedAlias(canonical);
                    listener.status("left parked (not readable): " + url);
                    continue;
                }
                progress.success();
                // Host diversity MUST come from the FINAL post-redirect URL the browser actually landed on —
                // counting hostOf(requested) would count "bing.com" for every redirect link and make the
                // ≥2-hosts sufficiency threshold unreachable. Both addresses are marked visited; the page
                // and its host are counted once, under the final canonical URL.
                String finalUrl = WebAcquisitionText.finalUrlOf(page);
                String effectiveUrl = finalUrl == null || finalUrl.isEmpty() ? url : finalUrl;
                String finalCanonical = WebAcquisitionText.canonicalish(effectiveUrl);
                String finalHost = WebAcquisitionText.hostOf(effectiveUrl);
                String pageTitle = WebAcquisitionText.titleOf(page);
                if (isSearchProviderSite(finalHost)) {
                    // The search engine is TRANSIT (its verticals like /videos or /shopping ended up in
                    // the frontier): mark visited so it is never re-opened, but it counts as neither page
                    // nor host, is never a source, and its links are not harvested.
                    progress.noteVisitedAlias(finalCanonical);
                    progress.noteVisitedAlias(canonical);
                    listener.status("skipped search-provider page: " + effectiveUrl);
                    listener.progress(progress,
                            ResearchRunActivity.pageSkipped(effectiveUrl, finalHost, pageTitle));
                    continue;
                }
                progress.pageVisited(finalCanonical, finalHost);
                if (!finalCanonical.equals(canonical)) {
                    // A redirect: the requested address is marked visited too (but never counted).
                    progress.noteVisitedAlias(canonical);
                }
                listener.progress(progress, ResearchRunActivity.readingPage(effectiveUrl, finalHost, pageTitle));
                if (visitSkipped()) {
                    skipCurrentPage(effectiveUrl, finalHost, pageTitle, "after reading it");
                    continue;
                }
                String captureId = WebAcquisitionText.field(page, "capture_id");
                String pageText = page.toLowerCase(Locale.ROOT);

                boolean relevant = isRelevantPage(entry, page, pageTitle, pageText, effectiveUrl, terms);
                if (pageSkip.isCurrentVisitSkipped()) {
                    // The relevance answer belongs to a page the user has meanwhile abandoned. Whatever it
                    // says, it may not make this page a source.
                    skipCurrentPage(effectiveUrl, finalHost, pageTitle, "during the relevance check");
                    continue;
                }
                if (relevant) {
                    // SC1 page gate: the last guard against wrong SERP snippets — a loaded
                    // page whose semantic content is CLEAR canonical OUT is neither accepted
                    // nor link-expanded (corpus contamination stops here).
                    java.util.Map<String, SearchScopeControlPort.Decision> pageVerdicts =
                            scopeVerdicts("page", java.util.Collections.singletonList(
                                    new SearchScopeControlPort.Item(effectiveUrl,
                                            pageRelevanceDocument(pageTitle, page))));
                    if (pageVerdicts == null) {
                        return ResearchStopReason.SCOPE_CONTROL_UNAVAILABLE;
                    }
                    SearchScopeControlPort.Decision pageDecision =
                            pageVerdicts.get(effectiveUrl);
                    if (pageDecision != null && pageDecision.out) {
                        listener.status("search-scope page OUT url=" + effectiveUrl
                                + " nearest=\"" + pageDecision.nearestOutLabel
                                + "\" -> not accepted, not expanded");
                        listener.progress(progress,
                                ResearchRunActivity.pageSkipped(effectiveUrl, finalHost,
                                        pageTitle));
                        continue;
                    }
                    ResearchStopReason g3 = acceptSource(captureId, page, effectiveUrl, finalHost, pageTitle);
                    if (g3 != null) {
                        return g3;
                    }
                } else {
                    listener.status("skipped irrelevant page: " + url);
                    listener.progress(progress, ResearchRunActivity.pageSkipped(effectiveUrl, finalHost, pageTitle));
                    // A page that is not about this query is not a bridge to pages that are. Harvesting
                    // its links is how one off-topic hit turned into a whole site of off-topic hits.
                    // Traversing deliberately chosen bridge pages would be its own policy, not a side
                    // effect of failing the relevance gate.
                    continue;
                }

                // Which of this page's links are worth opening is a relevance question, and it is asked
                // of the relevance model — not of String.contains, which admitted every link whose URL
                // happened to spell a query word.
                ResearchStopReason g4 = beforeToolCall();
                if (g4 != null) {
                    return g4;
                }
                if (visitSkipped()) {
                    skipCurrentPage(effectiveUrl, finalHost, pageTitle, "before its links were read");
                    continue;
                }
                String links = callBrowser("web_links", args());
                progress.success();
                List<String> follow = selectLinksToFollow(links, url, terms);
                if (scopeControlBroken) {
                    return ResearchStopReason.SCOPE_CONTROL_UNAVAILABLE;
                }
                if (pageSkip.isCurrentVisitSkipped()) {
                    // An abandoned page hands nothing on: its links would carry the run onwards from a
                    // page the user has just said they do not want.
                    skipCurrentPage(effectiveUrl, finalHost, pageTitle, "while its links were assessed");
                    continue;
                }
                for (String linkUrl : follow) {
                    frontier.add(FrontierEntry.fromDiscoveredLink(linkUrl, url));
                }
            } catch (ToolInvoker.EndpointUnavailable ex) {
                listener.status("[web-search] technical failure stage=PAGE_OPEN (endpoint unavailable — browser runtime"
                        + " may be dead) url=" + url + " cause=" + describe(ex));
                return ResearchStopReason.MCP_UNAVAILABLE;
            } catch (ToolInvoker.ToolFailure ex) {
                progress.error();
                listener.status("[web-search] technical failure stage=PAGE_OPEN url=" + url
                        + " cause=" + describe(ex));
            }
        }
    }

    /**
     * The MANDATORY reranking gate between structured extraction and navigation. The call is budgeted
     * like any other tool (against the ResearchRunBudget, NOT the browser MCP) and honours cancellation.
     * On SUCCESS it fills {@code frontier} with the selected candidates in relevance order and returns
     * {@code null}; on any reranker failure (or NO_SEMANTIC_MATCHES) it opens nothing and returns the
     * typed stop reason — never a raw engine-order fallback. NO_CANDIDATES (an empty search) returns
     * {@code null} so the run ends as NO_RELEVANT_PATHS, not as a reranker failure.
     */
    private ResearchStopReason seedReranking(String query,
            List<com.aresstack.askai.browser.search.SearchResultCandidate> candidates,
            List<FrontierEntry> frontier) {
        if (candidates.isEmpty()) {
            return null; // nothing to rerank or open → NO_RELEVANT_PATHS via the main loop
        }
        ResearchStopReason gate = beforeToolCall();
        if (gate != null) {
            return gate; // budget/cancel before the very first tool call
        }
        com.aresstack.askai.research.runtime.rerank.SearchResultRerankingResult result =
                reranker.rerank(query, candidates, cancellationSignal());
        listener.status("reranking [" + result.modelName + "]: " + result.diagnostics);
        switch (result.outcome) {
            case SUCCESS:
                progress.success();
                progress.linksAssessed(candidates.size());
                progress.linksSelected(result.selected.size());
                seedSerpRelevanceFloor = seedFloorOf(result.selected);
                // SC1 SERP gate: AFTER the reranker, BEFORE frontier and park — a CLEAR
                // canonical OUT is never parked, never enqueued, never web_open'd. Survivor
                // order stays exactly the reranker's order.
                java.util.List<SearchScopeControlPort.Item> scopeItems =
                        new java.util.ArrayList<SearchScopeControlPort.Item>();
                for (com.aresstack.askai.research.runtime.rerank.RerankedSearchResultCandidate
                        ranked : result.selected) {
                    if (!ranked.candidate.resolvedTargetUrl.isEmpty()) {
                        scopeItems.add(new SearchScopeControlPort.Item(
                                ranked.candidate.resolvedTargetUrl,
                                (ranked.candidate.title + " " + ranked.candidate.snippet)
                                        .trim()));
                    }
                }
                java.util.Map<String, SearchScopeControlPort.Decision> serpVerdicts =
                        scopeVerdicts("serp", scopeItems);
                if (serpVerdicts == null) {
                    return ResearchStopReason.SCOPE_CONTROL_UNAVAILABLE;
                }
                int scopedOut = 0;
                for (com.aresstack.askai.research.runtime.rerank.RerankedSearchResultCandidate ranked
                        : result.selected) {
                    if (!ranked.candidate.resolvedTargetUrl.isEmpty()) {
                        SearchScopeControlPort.Decision decision =
                                serpVerdicts.get(ranked.candidate.resolvedTargetUrl);
                        if (scopeSkips(decision)) {
                            scopedOut++;
                            listener.status("search-scope skip candidate=\""
                                    + ranked.candidate.title + "\" "
                                    + (decision.unclassified ? "relation=UNCLASSIFIED"
                                            : "relation=LIKELY_OUT authority=CANONICAL_OUT "
                                                    + "nearest=\"" + decision.nearestOutLabel
                                                    + "\""));
                            continue;
                        }
                        // The selected hit keeps its identity: the entry names the candidate it came from
                        // and carries what the SERP promised (the Layer 2 semantic readiness net) instead
                        // of parking that promise in a side map keyed by URL.
                        String candidateId = candidateIdFor(ranked.candidate.resolvedTargetUrl);
                        frontier.add(FrontierEntry.fromSearchResult(ranked.candidate.resolvedTargetUrl,
                                candidateId,
                                (ranked.candidate.title + " " + ranked.candidate.snippet).trim()));
                        // Park the candidate with its reranker score BEFORE it is visited, so every hit is
                        // in the store immediately (score visible) and its full text is filled only on a
                        // successful visit. Best-effort: a park failure never aborts the search.
                        parkCandidate(ranked);
                    }
                }
                if (!serpVerdicts.isEmpty()) {
                    int serpNearIn = 0;
                    for (SearchScopeControlPort.Decision decision : serpVerdicts.values()) {
                        if (decision.nearIn) {
                            serpNearIn++;
                        }
                    }
                    listener.status("search-scope SERP items=" + scopeItems.size()
                            + " kept=" + (scopeItems.size() - scopedOut)
                            + " out=" + scopedOut + " nearIn=" + serpNearIn);
                }
                return null;
            case NO_CANDIDATES:
                return null; // empty search, not a reranker failure
            case NO_SEMANTIC_MATCHES:
                return ResearchStopReason.NO_SEMANTIC_MATCHES;
            case TIMEOUT:
                progress.error();
                return ResearchStopReason.RERANKER_TIMEOUT;
            case INVALID_RESPONSE:
                progress.error();
                return ResearchStopReason.RERANKER_INVALID_RESPONSE;
            case CONFIGURATION_ERROR:
                progress.error();
                return ResearchStopReason.RERANKER_CONFIGURATION_ERROR;
            case CANCELLED:
                return ResearchStopReason.USER_CANCELLED;
            case BUDGET_EXHAUSTED:
                return completionPolicy.labelExhaustion(ResearchStopReason.TOOL_BUDGET_EXHAUSTED, progress);
            case RERANKER_UNAVAILABLE:
            default:
                progress.error();
                return ResearchStopReason.RERANKER_UNAVAILABLE;
        }
    }

    /** SC1 begin: pin the run snapshot. null = proceed; a typed reason ends the run fail-closed. */
    private ResearchStopReason beginScopeControl() {
        if (scopeControl == null) {
            return null; // feature absent → baseline behaviour
        }
        try {
            SearchScopeControlPort.Session scope = scopeControl.begin();
            scopeActive = scope.active;
            scopeHandle = scope.handle;
            scopeOutFilter = scope.outFilter;
            listener.status("search-scope " + (scope.active ? scope.summary
                    : scope.summary.isEmpty() ? "INACTIVE" : scope.summary));
            return null;
        } catch (ToolInvoker.ToolFailure broken) {
            listener.status("search-scope UNAVAILABLE at begin — fail-closed: "
                    + describe(broken));
            return ResearchStopReason.SCOPE_CONTROL_UNAVAILABLE;
        } catch (ToolInvoker.EndpointUnavailable dead) {
            listener.status("search-scope UNAVAILABLE at begin (endpoint) — fail-closed");
            return ResearchStopReason.SCOPE_CONTROL_UNAVAILABLE;
        }
    }

    /**
     * SC1 batch verdicts by candidate id. Empty map = inactive fast path (ZERO scope calls);
     * {@code null} = infrastructure failure → the caller ends the run
     * {@link ResearchStopReason#SCOPE_CONTROL_UNAVAILABLE}, fail-closed.
     */
    private java.util.Map<String, SearchScopeControlPort.Decision> scopeVerdicts(String lane,
            java.util.List<SearchScopeControlPort.Item> items) {
        if (!scopeActive || scopeHandle == null || items.isEmpty()) {
            return java.util.Collections.emptyMap();
        }
        try {
            java.util.Map<String, SearchScopeControlPort.Decision> byId =
                    new java.util.LinkedHashMap<String, SearchScopeControlPort.Decision>();
            for (SearchScopeControlPort.Decision decision
                    : scopeControl.evaluate(scopeHandle, lane, items)) {
                byId.put(decision.id, decision);
            }
            return byId;
        } catch (ToolInvoker.ToolFailure broken) {
            return scopeEvaluateFailed(lane, describe(broken));
        } catch (ToolInvoker.EndpointUnavailable dead) {
            return scopeEvaluateFailed(lane, "endpoint unavailable");
        }
    }

    /**
     * SC2a failure split: with the OUT filter active the run stays FAIL-CLOSED (null → typed
     * stop, SC1's guarantee). A pure IN-affinity failure is a broken measurement, never a
     * boundary — the run degrades to today's baseline and stops asking.
     */
    private java.util.Map<String, SearchScopeControlPort.Decision> scopeEvaluateFailed(
            String lane, String cause) {
        if (scopeOutFilter) {
            listener.status("search-scope UNAVAILABLE at " + lane + " — fail-closed: "
                    + cause);
            return null;
        }
        listener.status("search-scope inAffinity degraded to baseline at " + lane + " ("
                + cause + ")");
        // Release the host snapshot before forgetting the handle — the run's finally block
        // no longer sees it, and the snapshot would otherwise linger until session teardown.
        try {
            scopeControl.end(scopeHandle);
        } catch (Exception alreadyBroken) {
            // best effort on an already-degraded channel; host teardown also clears
        }
        scopeActive = false;
        scopeHandle = null;
        return java.util.Collections.emptyMap();
    }

    /** OUT is the hard reject; UNCLASSIFIED (no usable text) is conservatively not visited. */
    static boolean scopeSkips(SearchScopeControlPort.Decision decision) {
        return decision != null && (decision.out || decision.unclassified);
    }

    /**
     * The link bar: the LOWEST score among the SERP hits the reranker selected — but ONLY when there are
     * at least two of them. A floor is the lower end of a RANGE; a single selected hit has no range, and
     * its score is simultaneously the run's BEST score. Using that as the bar starved every link of a
     * single-candidate SERP (…links assessed → 0 followed) and ended the run right after its first page.
     * One sample → no floor: the ranking and MAXIMUM_EXPANDED_LINKS_PER_PAGE still bound what is followed.
     */
    static Double seedFloorOf(
            List<com.aresstack.askai.research.runtime.rerank.RerankedSearchResultCandidate> selected) {
        if (selected == null || selected.size() < 2) {
            return null;
        }
        double minimum = selected.get(0).score;
        for (com.aresstack.askai.research.runtime.rerank.RerankedSearchResultCandidate ranked : selected) {
            if (ranked.score < minimum) {
                minimum = ranked.score;
            }
        }
        return minimum;
    }

    /** ONE way to leave a page the user abandoned: visibly, with no source, no links, no evidence. */
    private void skipCurrentPage(String url, String host, String title, String when) {
        listener.status("[browser] user skipped this page " + when + ": " + url);
        listener.progress(progress, ResearchRunActivity.pageSkipped(url, host, title));
        skipNextInterPageDelay = true;
    }

    // ------------------------------------------------------------------ relevance, all the way through

    /** What the relevance model is shown of a page: what it calls itself, and how it begins. */
    private static String pageRelevanceDocument(String pageTitle, String page) {
        String excerpt = WebAcquisitionText.field(page, "excerpt");
        String body = excerpt == null || excerpt.trim().isEmpty() ? page : excerpt;
        String trimmed = body.trim();
        if (trimmed.length() > PAGE_RELEVANCE_EXCERPT_CHARACTERS) {
            trimmed = trimmed.substring(0, PAGE_RELEVANCE_EXCERPT_CHARACTERS);
        }
        return "Title: " + (pageTitle == null ? "" : pageTitle) + "\nSnippet: " + trimmed;
    }

    /**
     * Is this loaded page an answer to the query?
     * <p>
     * A hit the SERP reranker selected was already judged relevant, on the strength of what the result
     * page promised. That decision stands: it is evidence, and a page is not disqualified for failing to
     * repeat the query's words — the live case rejected the Wikipedia article on rabbit meat for a
     * search about rabbit steaks precisely that way. Its page score instead CALIBRATES the run: the
     * weakest search hit read so far is the bar every link discovered later has to clear.
     * <p>
     * Without a relevance model there is no semantic answer, and the old lexical test is then all there
     * is. It is named as the fallback it is, and it is said out loud.
     */
    private boolean isRelevantPage(FrontierEntry entry, String page, String pageTitle, String pageText,
                                   String effectiveUrl, Set<String> terms) {
        Double score = assessOne(pageRelevanceDocument(pageTitle, page));
        if (score == null) {
            boolean lexical = WebAcquisitionText.matches(pageText, terms);
            listener.status("relevance unavailable — falling back to the lexical test ("
                    + (lexical ? "kept" : "skipped") + "): " + effectiveUrl);
            return lexical;
        }
        if (entry.getOrigin() != FrontierEntry.Origin.DISCOVERED_LINK) {
            // A selected search hit, or a re-queued one: the reranker already decided. Record what its
            // page actually scores, so the bar for discovered links comes from measured evidence.
            if (seedPageRelevanceFloor == null || score < seedPageRelevanceFloor) {
                seedPageRelevanceFloor = score;
            }
            listener.status("page relevance " + score + " (search hit; floor now "
                    + seedPageRelevanceFloor + "): " + effectiveUrl);
            return true;
        }
        if (seedPageRelevanceFloor == null) {
            // Nothing read yet that the search engine vouched for: there is no measured bar, and
            // inventing one would be the guess this codebase refuses everywhere else.
            listener.status("page relevance " + score + " but no search hit has been read yet — "
                    + "no bar to judge against: " + effectiveUrl);
            return false;
        }
        boolean relevant = score >= seedPageRelevanceFloor;
        listener.status("page relevance " + score + " vs floor " + seedPageRelevanceFloor
                + " → " + (relevant ? "kept" : "skipped") + ": " + effectiveUrl);
        return relevant;
    }

    /**
     * The links of a relevant page, ranked by how well their ANCHOR TEXT answers the query — never their
     * URL. A URL that spells "kulinarische" says nothing about what is behind it; the words a page uses
     * to point somewhere do.
     */
    private List<String> selectLinksToFollow(String links, String parentUrl, Set<String> terms) {
        java.util.LinkedHashMap<String, String> documentsByUrl =
                new java.util.LinkedHashMap<String, String>();
        java.util.List<String> lexicalHints = new ArrayList<String>();
        for (String line : links.split("\n")) {
            String linkUrl = WebAcquisitionText.lastUrl(line);
            if (linkUrl == null || isSearchProviderSite(WebAcquisitionText.hostOf(linkUrl))
                    || progress.alreadyVisited(WebAcquisitionText.canonicalish(linkUrl))
                    || documentsByUrl.containsKey(linkUrl)) {
                continue;
            }
            // Every real, new link on the page counts as DISCOVERED — also the ones beyond the assessment
            // cap. Only what fits the cap is actually assessed; the funnel display shows the difference.
            progress.linksDiscovered(1);
            if (documentsByUrl.size() >= MAXIMUM_ASSESSED_LINKS_PER_PAGE) {
                continue;
            }
            String anchor = WebAcquisitionText.anchorTextOf(line);
            if (anchor.isEmpty()) {
                continue; // a link with nothing to say for itself cannot be judged, so it is not followed
            }
            documentsByUrl.put(linkUrl, "Title: " + anchor + "\nSnippet: ");
            if (WebAcquisitionText.matches(line.toLowerCase(Locale.ROOT), terms)) {
                lexicalHints.add(linkUrl);
            }
        }
        if (documentsByUrl.isEmpty()) {
            return java.util.Collections.emptyList();
        }
        progress.linksAssessed(documentsByUrl.size());
        com.aresstack.askai.research.domain.search.RelevanceAssessment assessment =
                assessWatched(documentsByUrl);
        if (!assessment.isAvailable()) {
            // No semantic answer: the cheap lexical signal is all that is left, and it is bounded like
            // everything else here rather than let loose over every link on the page.
            listener.status("link relevance unavailable (" + assessment.getUnavailableReason()
                    + ") — following the lexical hints only, from " + parentUrl);
            List<String> hints = lexicalHints.size() > MAXIMUM_EXPANDED_LINKS_PER_PAGE
                    ? lexicalHints.subList(0, MAXIMUM_EXPANDED_LINKS_PER_PAGE) : lexicalHints;
            progress.linksSelected(hints.size());
            return scopeFilterLinks(hints, documentsByUrl, parentUrl);
        }
        List<String> selected = new ArrayList<String>();
        for (String rankedUrl : assessment.rankedCandidateIds()) {
            if (selected.size() >= MAXIMUM_EXPANDED_LINKS_PER_PAGE) {
                break;
            }
            Double score = assessment.relevanceOf(rankedUrl);
            if (score == null) {
                continue;
            }
            if (seedSerpRelevanceFloor != null && score < seedSerpRelevanceFloor) {
                break; // ranked best first: everything after this is further below the bar
            }
            selected.add(rankedUrl);
        }
        progress.linksSelected(selected.size());
        listener.status("link relevance: " + documentsByUrl.size() + " links assessed → "
                + selected.size() + " followed (floor " + seedSerpRelevanceFloor + ") from "
                + parentUrl);
        return scopeFilterLinks(selected, documentsByUrl, parentUrl);
    }

    /**
     * SC1 link gate: AFTER query relevance, BEFORE the frontier — judged on the visible
     * anchor text (URLs are never interpreted as semantics). A failure sets
     * {@code scopeControlBroken}; the caller ends the run typed.
     */
    private List<String> scopeFilterLinks(List<String> urls,
            java.util.Map<String, String> documentsByUrl, String parentUrl) {
        if (urls.isEmpty()) {
            return urls;
        }
        java.util.List<SearchScopeControlPort.Item> items =
                new java.util.ArrayList<SearchScopeControlPort.Item>();
        for (String linkUrl : urls) {
            String document = documentsByUrl.get(linkUrl);
            items.add(new SearchScopeControlPort.Item(linkUrl,
                    document == null ? "" : document));
        }
        java.util.Map<String, SearchScopeControlPort.Decision> verdicts =
                scopeVerdicts("links", items);
        if (verdicts == null) {
            scopeControlBroken = true;
            return java.util.Collections.emptyList();
        }
        if (verdicts.isEmpty()) {
            return urls; // inactive fast path
        }
        List<String> kept = new ArrayList<String>();
        for (String linkUrl : urls) {
            if (scopeSkips(verdicts.get(linkUrl))) {
                continue; // never enqueued — the skip line came from the host log already
            }
            kept.add(linkUrl);
        }
        if (kept.size() != urls.size()) {
            listener.status("search-scope links items=" + urls.size() + " kept="
                    + kept.size() + " out=" + (urls.size() - kept.size()) + " from "
                    + parentUrl);
        }
        return kept;
    }

    /** One document's relevance, or {@code null} when the model could not answer at all. */
    private Double assessOne(final String document) {
        final java.util.LinkedHashMap<String, String> one =
                new java.util.LinkedHashMap<String, String>();
        one.put("page", document);
        com.aresstack.askai.research.domain.search.RelevanceAssessment assessment = assessWatched(one);
        return assessment.isAvailable() ? assessment.relevanceOf("page") : null;
    }

    /**
     * A relevance call the user can get out of. It runs under the PAGE's cancellation — the same signal
     * the reranker already honours — while the overlay keeps being polled, so pressing Skip during the
     * inference ends it instead of being noticed once it is over.
     */
    private com.aresstack.askai.research.domain.search.RelevanceAssessment assessWatched(
            final java.util.LinkedHashMap<String, String> documentsById) {
        final long generation = currentVisitGeneration;
        return withSkipWatch(
                new java.util.concurrent.Callable<
                        com.aresstack.askai.research.domain.search.RelevanceAssessment>() {
                    public com.aresstack.askai.research.domain.search.RelevanceAssessment call() {
                        return reranker.assess(relevanceQuery, documentsById,
                                pageSkip.cancellationFor(generation));
                    }
                },
                com.aresstack.askai.research.domain.search.RelevanceAssessment
                        .unavailable("relevance call did not complete"));
    }

    /**
     * Park a single reranked candidate (best-effort). Parking is host-side bookkeeping, so it is deliberately
     * NOT gated by the tool budget and a failure is logged and swallowed — the search continues regardless.
     */
    /** A stable id per URL within this run; the same URL always maps to the same candidate. */
    private String candidateIdFor(String url) {
        String key = WebAcquisitionText.canonicalish(url);
        String existing = candidateIdByUrl.get(key);
        if (existing != null) {
            return existing;
        }
        String minted = "c" + (candidateIdByUrl.size() + 1);
        candidateIdByUrl.put(key, minted);
        return minted;
    }

    private void parkCandidate(com.aresstack.askai.research.runtime.rerank.RerankedSearchResultCandidate ranked) {
        try {
            sourceAcceptancePort.park(ranked.candidate.resolvedTargetUrl, ranked.candidate.title,
                    ranked.candidate.snippet, ranked.score);
        } catch (ToolInvoker.ToolFailure ex) {
            listener.status("park failed: " + ex.getMessage());
        } catch (ToolInvoker.EndpointUnavailable ex) {
            listener.status("park skipped (endpoint unavailable)");
        }
    }

    /**
     * The two-step "scan then read" visit of a CONCRETE page. Step 1: PROBE the page (web_probe) and, while it
     * is not readable, handle the obstruction — dismiss a consent banner (auto first; if that fails, tell the
     * user where to click and, when configured, wait), or wait for the user to solve a CAPTCHA (unless
     * wait-for-user is off, then skip) — re-probing between attempts, bounded by {@code maxReadinessRetries}
     * consent cycles. Step 2: only once readable, READ the full page (web_read, which assigns the capture id).
     * Returns the read page string (as {@code web_open} did), or {@code null} when the page could not be made
     * readable (its parked source then keeps an empty full text). With {@code maxReadinessRetries <= 0} it
     * degrades to the original single-step {@code web_open}. {@link #isReadable} is the seam an LLM judge can
     * replace.
     */
    private String openWithReadiness(String url, String expectedContent)
            throws ToolInvoker.ToolFailure, ToolInvoker.EndpointUnavailable {
        if (maxReadinessRetries <= 0) {
            return callBrowser("web_open", args("url", url)); // readiness loop disabled: original behaviour
        }
        com.aresstack.askai.browser.BrowserPageReadiness pr;
        try {
            pr = com.aresstack.askai.browser.BrowserPageReadiness.parse(
                    callBrowser("web_probe", args("url", url)));
        } catch (ToolInvoker.ToolFailure ex) {
            // A browser runtime without web_probe: fall back to the single-step open (never fail the visit here).
            return callBrowser("web_open", args("url", url));
        }
        if (visitSkipped()) {
            return null; // abandoned between opening the page and judging it: nothing more is read
        }
        String family = familyOf(url);
        // "Erst auto": proactively clear a standard consent banner on EVERY page before judging — a common
        // cookie wall (OneTrust/Cookiebot/"accept all"/"alle akzeptieren") is dismissed here so its text
        // never bleeds into the read content, even when the DOM markers did not flag it as a banner.
        String clicked = callBrowser("web_dismiss_consent", args());
        if (clicked.startsWith("clicked")) {
            pr = reprobe();
            listener.status("[browser] consent action=" + consentActionOf(clicked)
                    + " resolved=" + !pr.consentPresent + " " + url);
        }
        // ONE classification per page (the model, when set, may recognise an obstruction the DOM selectors
        // missed); the subsequent waiting uses the cheap heuristic so we do not re-invoke the model per tick.
        PageReadinessJudge.Verdict verdict = readinessJudge.judge(pr);
        if (visitSkipped()) {
            return null; // the judgement is about a page the user has meanwhile left behind
        }
        // Layer 2 (additive): a SERP-anchored semantic safety net can rescue an AMBIGUOUS verdict
        // (INTERACTIVE_CHALLENGE / UNREADABLE) to READABLE when the page text closely matches what the search
        // result promised. No-op by default (no embedder → NaN → unchanged); never touches a block/consent verdict.
        PageReadinessJudge.Verdict semantic = withExpectedContent(verdict,
                expectedContent,
                pr.title + " " + pr.excerpt, contentSimilarity, EXPECTED_CONTENT_HIGH);
        if (semantic != verdict) {
            listener.status("readiness override " + verdict + "->" + semantic
                    + " (expected-content match) " + url);
            verdict = semantic;
        }
        String blockReason = AccessBlockSignals.reason(pr);
        listener.status("readiness=" + verdict
                + (verdict == PageReadinessJudge.Verdict.ACCESS_BLOCKED ? " reason=" + blockReason : "")
                + " [text=" + pr.textLength + " challenge=" + pr.challengeVisible
                + (pr.challengePresent && !pr.challengeVisible ? " (artifact hidden)" : "")
                + (pr.challengePresent ? " evidence=" + pr.challengeMarker : "")
                + " consent=" + pr.consentPresent + "] " + url);
        renderHud(verdict.name(), "readiness=" + verdict
                        + (verdict == PageReadinessJudge.Verdict.ACCESS_BLOCKED ? " (" + blockReason + ")" : ""),
                false, ResearchHudState.NO_COUNTDOWN);
        switch (verdict) {
            case READABLE:
                return callBrowser("web_read", args()); // assigns the capture id acceptSource resolves
            case CONSENT_REQUIRED:
                return clearConsentThenRead(url, family, pr);
            case INTERACTIVE_CHALLENGE:
                if (!challengeWaitForUser) {
                    listener.status("skipping interactive-challenge page (wait-for-user disabled): " + url);
                    return null; // leave parked
                }
                listener.attention("CAPTCHA", family, url, false);
                return waitForUserThenRead(url); // a solvable challenge has no business timeout: cooperative wait
            case ACCESS_BLOCKED:
                // TERMINAL block: nothing to solve — never wait for the user, never accept. Skip this URL and
                // remember the domain so no other candidate on it is re-opened this run.
                blockedFamilies.add(family);
                listener.status("skipped blocked page (" + blockReason + "): " + url);
                return null;
            case UNREADABLE:
            default:
                return null; // leave parked (empty full text; the score still tells the user it needs reading)
        }
    }

    /** Cookie/consent: auto-dismiss up to the retry limit; if still blocked, tell the user where to click. */
    private String clearConsentThenRead(String url, String family,
                                        com.aresstack.askai.browser.BrowserPageReadiness pr)
            throws ToolInvoker.ToolFailure, ToolInvoker.EndpointUnavailable {
        String candidate = pr.consentCandidate;
        for (int i = 0; i < maxReadinessRetries; i++) {
            String clicked = callBrowser("web_dismiss_consent", args());
            pr = reprobe();
            if (heuristicReadable(pr)) {
                return callBrowser("web_read", args());
            }
            if (!clicked.startsWith("clicked")) {
                break; // the heuristic can no longer clear it — hand it to the user
            }
        }
        // "erst auto, dann User": auto-dismiss could not clear it.
        if (!challengeWaitForUser) {
            listener.status("consent not clearable, parked: " + url);
            return null;
        }
        listener.attention("COOKIE", family, url + " — click: " + candidate, false);
        return waitForUserThenRead(url);
    }

    /**
     * Wait cooperatively for the user to clear a challenge / consent wall (no business timeout, cancel stays
     * immediate, the wait is compensated), re-probing each tick until the page is heuristically readable, then
     * read it. Returns {@code null} on cancel/stop (the page stays parked).
     */
    private String waitForUserThenRead(String url)
            throws ToolInvoker.ToolFailure, ToolInvoker.EndpointUnavailable {
        long deadline = clock.currentTimeMillis() + HUD_USER_WAIT_TIMEOUT_MILLIS;
        while (true) {
            if (stopReasonNow() != null || cancelled.get()) {
                return null;
            }
            // HUD: show a live countdown and let the user Skip this page (the escape from a stuck wait) or
            // Pause. Skip / a lapsed countdown park the page (empty full text); READABLE-only acceptance holds.
            int remaining = (int) Math.max(0, (deadline - clock.currentTimeMillis()) / 1000L);
            renderHud("WAITING_FOR_USER", "Waiting for you to resolve this page", true, remaining);
            for (ResearchHudCommand command : pollHudCommands()) {
                if (command.type == ResearchHudCommand.Type.SKIP) {
                    listener.status("[browser] user skipped page: " + url);
                    // The same abandonment as anywhere else in the visit: a late result of this page
                    // must not be able to act, however it arrives.
                    pageSkip.requestSkip();
                    skipNextInterPageDelay = true; // don't stall on the delay before the next page
                    return null;
                }
                if (command.type == ResearchHudCommand.Type.NEXT) {
                    // The user marks the obstruction RESOLVED (e.g. a solved CAPTCHA the reprobe hasn't caught,
                    // or a page they accept as-is): read it now — an explicit user override of the READABLE
                    // heuristic. The page still passes the term-match gate before it can be accepted.
                    listener.status("[browser] user marked the page resolved — reading now: " + url);
                    skipNextInterPageDelay = true;
                    return callBrowser("web_read", args());
                }
                applyHudSideEffect(command); // PAUSE/RESUME/SET_DELAY (the slider works during a wait too)
            }
            if (clock.currentTimeMillis() >= deadline) {
                listener.status("[browser] user-wait timed out, parked: " + url);
                return null;
            }
            waitedForUserMillis += tickWait();
            if (heuristicReadable(reprobe())) {
                return callBrowser("web_read", args());
            }
        }
    }

    /**
     * The cheap readable check used to gate every {@code web_read} (and thus every acceptance) on READABLE: a
     * page is only read when it has enough text AND no challenge/consent flag AND is not a terminal access block.
     * The block guard is essential — a Cloudflare 1020 page has plenty of text and its challenge marker clears on
     * reprobe, so without it the block page would be read + accepted (the source-10 bug).
     */
    boolean heuristicReadable(com.aresstack.askai.browser.BrowserPageReadiness pr) {
        return !pr.challengeVisible && !pr.consentPresent && !AccessBlockSignals.isBlocked(pr)
                && pr.textLength >= minReadableChars;
    }

    private com.aresstack.askai.browser.BrowserPageReadiness reprobe()
            throws ToolInvoker.ToolFailure, ToolInvoker.EndpointUnavailable {
        return com.aresstack.askai.browser.BrowserPageReadiness.parse(callBrowser("web_reprobe", args()));
    }

    /**
     * The consent ACTION from a {@code web_dismiss_consent} result: {@code 'clicked:REJECT_ALL:<what>'} →
     * {@code REJECT_ALL} (also ONLY_NECESSARY / ACCEPT_ALL / CLOSE); an older {@code 'clicked:<sel>'} /
     * {@code 'clicked-text:<txt>'} → {@code CLICKED}. Purely for diagnostics.
     */
    private static String consentActionOf(String clicked) {
        if (clicked == null || !clicked.startsWith("clicked:")) {
            return "CLICKED";
        }
        String rest = clicked.substring("clicked:".length());
        int colon = rest.indexOf(':');
        String token = colon < 0 ? rest : rest.substring(0, colon);
        if (token.equals("REJECT_ALL") || token.equals("ONLY_NECESSARY") || token.equals("ACCEPT_ALL")
                || token.equals("CLOSE")) {
            return token;
        }
        return "CLICKED";
    }

    // ------------------------------------------------------------------ Research HUD (optional overlay)

    /**
     * Render a HUD state onto the current page; best-effort — a backend without the overlay simply ignores it.
     * A failure NEVER affects the research run, but it is LOGGED (never swallowed): the HUD is a prime suspect
     * for a browser crash, so its errors must be visible. Off entirely under the kill-switch.
     */
    private void renderHud(String phase, String status, boolean waiting, int countdownSeconds) {
        if (!hudEnabled) {
            return;
        }
        try {
            callBrowser("web_hud_render", args("state",
                    new ResearchHudState(phase, status, waiting, countdownSeconds, hudPaused,
                            (int) (hudDelayMillis / 1000L), hudRelevantCurrentPage).render()));
            lastHudErrorLine = null; // a success clears the throttle so the next distinct failure is logged
        } catch (ToolInvoker.ToolFailure | ToolInvoker.EndpointUnavailable | RuntimeException ex) {
            logHudFailure("render", ex);
        }
    }

    /**
     * Render the terminal HUD state once the run is over: no waiting, no countdown, no controls. The phase
     * distinguishes the user's cancel from a completed/failed run; the reason is shown verbatim so the
     * overlay never claims more than the run reported.
     */
    private void renderTerminalHud(ResearchStopReason reason) {
        if (!hudEnabled || browserGone || browserClosedByUser
                || reason == ResearchStopReason.MCP_UNAVAILABLE) {
            return; // no browser left to render on
        }
        String phase = reason == ResearchStopReason.USER_CANCELLED ? "CANCELLED" : "DONE";
        try {
            callBrowser("web_hud_render", args("state", ResearchHudState.terminal(phase,
                    "Recherche beendet (" + reason + ") — dieser Browser gehört zu keiner aktiven Suche mehr")
                    .render()));
        } catch (ToolInvoker.ToolFailure | ToolInvoker.EndpointUnavailable | RuntimeException ex) {
            logHudFailure("render-terminal", ex);
        }
    }

    /** Drain overlay commands; best-effort. A failure is logged (not swallowed). Off under the kill-switch. */
    private List<ResearchHudCommand> pollHudCommands() {
        if (!hudEnabled) {
            return java.util.Collections.<ResearchHudCommand>emptyList();
        }
        try {
            List<ResearchHudCommand> commands = ResearchHudCommand.parseBatch(callBrowser("web_hud_poll", args()));
            lastHudErrorLine = null;
            return commands;
        } catch (ToolInvoker.ToolFailure | ToolInvoker.EndpointUnavailable | RuntimeException ex) {
            logHudFailure("poll", ex);
            return java.util.Collections.<ResearchHudCommand>emptyList();
        }
    }

    /** Log a HUD failure once per distinct cause (render/poll run every tick — do not flood the log). */
    private void logHudFailure(String stage, Throwable ex) {
        boolean endpoint = ex instanceof ToolInvoker.EndpointUnavailable;
        if (endpoint) {
            // The overlay call reaches the browser through the SAME restart-and-retry bridge as every other
            // browser call, so an EndpointUnavailable here means the browser is genuinely gone (a restart did
            // not recover it). End the run technically instead of silently swallowing it and looping forever.
            browserGone = true;
        }
        String line = "[browser-hud] " + stage + " failed"
                + (endpoint ? " (endpoint unavailable — browser runtime may be dead)" : "") + " cause=" + describe(ex);
        if (!line.equals(lastHudErrorLine)) {
            lastHudErrorLine = line;
            listener.status(line);
        }
    }

    /** Compact type: message (&lt;- cause …) chain for diagnostics; bounded so a deep chain cannot flood a line. */
    private static String describe(Throwable ex) {
        StringBuilder sb = new StringBuilder();
        for (Throwable t = ex; t != null && sb.length() < 400; t = t.getCause()) {
            if (sb.length() > 0) {
                sb.append(" <- ");
            }
            sb.append(t.getClass().getSimpleName());
            if (t.getMessage() != null) {
                sb.append(": ").append(t.getMessage());
            }
            if (t.getCause() == t) {
                break; // self-referential cause guard
            }
        }
        return sb.toString();
    }

    /**
     * Read the overlay's commands and act on them HERE, wherever "here" happens to be. PAUSE/RESUME/
     * SET_DELAY are run-wide; SKIP abandons the page currently being worked on, whatever stage it is in.
     * <p>
     * Between pages there is no page to abandon, so a SKIP arriving at the top of the loop can only mean
     * "get on with it" — it must NOT skip the page that has not started yet.
     */
    private void applyHudCommands(boolean aPageIsBeingVisited) {
        for (ResearchHudCommand command : pollHudCommands()) {
            if (command.type == ResearchHudCommand.Type.NEXT
                    || command.type == ResearchHudCommand.Type.SKIP) {
                skipNextInterPageDelay = true; // "proceed now" → do not stall on the delay before the next page
            }
            if (command.type == ResearchHudCommand.Type.SKIP && aPageIsBeingVisited) {
                pageSkip.requestSkip();
            }
            applyHudSideEffect(command);
        }
    }

    /** Between pages: no page to abandon. */
    private void applyHudPauseResume() {
        applyHudCommands(false);
    }

    /**
     * Has the user abandoned the page being worked on? Asks the overlay first, so a Skip pressed a moment
     * ago counts — the command is buffered in the browser and only becomes real when someone reads it.
     */
    private boolean visitSkipped() {
        if (pageSkip.isCurrentVisitSkipped()) {
            return true;
        }
        applyHudCommands(true);
        return pageSkip.isCurrentVisitSkipped();
    }

    /**
     * Run non-browser work (a relevance inference) while the overlay stays answerable.
     * <p>
     * The browser MCP is idle for the whole of such a call, so a small watcher can keep asking it for
     * commands and turn a Skip into a cancellation the inference itself understands. Without this, Skip
     * would be sampled only between stages — and the stages that take the longest are exactly the ones a
     * user wants out of. The watcher is stopped before the caller touches the browser again, so the two
     * never use the invoker at the same time.
     */
    private <T> T withSkipWatch(java.util.concurrent.Callable<T> work, T onFailure) {
        final java.util.concurrent.atomic.AtomicBoolean running =
                new java.util.concurrent.atomic.AtomicBoolean(true);
        Thread watcher = new Thread(new Runnable() {
            public void run() {
                while (running.get() && !pageSkip.isCurrentVisitSkipped()) {
                    try {
                        Thread.sleep(SKIP_WATCH_INTERVAL_MILLIS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    if (!running.get()) {
                        return;
                    }
                    try {
                        applyHudCommands(true);
                    } catch (RuntimeException pollFailed) {
                        return; // a HUD that cannot be polled must never break the work it watches
                    }
                }
            }
        }, "hud-skip-watch");
        watcher.setDaemon(true);
        watcher.start();
        try {
            return work.call();
        } catch (Exception failed) {
            return onFailure;
        } finally {
            running.set(false);
            try {
                watcher.join(SKIP_WATCH_INTERVAL_MILLIS * 4);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Apply the non-contextual side effects of a HUD command (pause state + delay); ignores SKIP/NEXT. */
    private void applyHudSideEffect(ResearchHudCommand command) {
        if (command.type == ResearchHudCommand.Type.PAUSE) {
            hudPaused = true;
        } else if (command.type == ResearchHudCommand.Type.RESUME) {
            hudPaused = false;
        } else if (command.type == ResearchHudCommand.Type.SET_DELAY) {
            setHudDelayFrom(command.arg);
        } else if (command.type == ResearchHudCommand.Type.SET_RELEVANCE) {
            hudRelevantCurrentPage = "on".equalsIgnoreCase(command.arg == null ? "" : command.arg.trim());
        }
    }

    /** Parse a slider value (seconds) into the clamped inter-page delay; a malformed value leaves it unchanged. */
    private void setHudDelayFrom(String arg) {
        if (arg == null || arg.trim().isEmpty()) {
            return;
        }
        try {
            double seconds = Double.parseDouble(arg.trim());
            long millis = (long) (Math.max(0.0, seconds) * 1000.0);
            hudDelayMillis = Math.max(0L, Math.min(HUD_MAX_DELAY_MILLIS, millis));
        } catch (NumberFormatException ignored) {
            // keep the current delay
        }
    }

    /**
     * Wait the user-set inter-page delay before opening the next page. The delay is a deliberate slow-down for a
     * watching user, so it is NOT counted against the time budget (accumulated into {@code waitedForUserMillis},
     * like a manual wait). Cancel/stop, the NEXT button and the SKIP button all end it immediately; the slider can
     * be adjusted live. Returns a stop reason on cancel/stop, else {@code null} to proceed.
     */
    private ResearchStopReason interPageDelay() {
        if (skipNextInterPageDelay) {
            skipNextInterPageDelay = false; // a Skip/Next just fired — do not make the user wait the delay again
            return null;
        }
        if (hudDelayMillis <= 0) {
            return null;
        }
        long endAt = clock.currentTimeMillis() + hudDelayMillis;
        while (true) {
            if (cancelled.get()) {
                return ResearchStopReason.USER_CANCELLED;
            }
            ResearchStopReason gate = stopReasonNow();
            if (gate != null) {
                return gate;
            }
            long now = clock.currentTimeMillis();
            if (now >= endAt) {
                return null;
            }
            int remaining = (int) Math.max(1, (endAt - now + 999L) / 1000L);
            renderHud("DELAY", "Waiting " + remaining + "s before the next page (Next skips)", false,
                    ResearchHudState.NO_COUNTDOWN);
            for (ResearchHudCommand command : pollHudCommands()) {
                if (command.type == ResearchHudCommand.Type.NEXT
                        || command.type == ResearchHudCommand.Type.SKIP) {
                    return null; // proceed to the next page now
                }
                applyHudSideEffect(command);
                if (command.type == ResearchHudCommand.Type.SET_DELAY) {
                    endAt = clock.currentTimeMillis() + hudDelayMillis; // live re-target
                }
            }
            if (hudPaused) {
                return null; // a pause during the delay abandons it; the loop-top pause gate takes over
            }
            waitedForUserMillis += tickWait(); // excluded from the time budget
        }
    }

    /** While the user paused navigation, wait (cancel-aware) instead of opening the next page. */
    private ResearchStopReason awaitResumeIfPaused() {
        boolean announced = false;
        while (hudPaused) {
            if (cancelled.get()) {
                return ResearchStopReason.USER_CANCELLED;
            }
            ResearchStopReason gate = stopReasonNow();
            if (gate != null) {
                return gate;
            }
            if (!announced) {
                listener.status("[browser] paused by user");
                announced = true;
            }
            renderHud("PAUSED", "Paused — resume to continue", false, ResearchHudState.NO_COUNTDOWN);
            tickWait();
            applyHudPauseResume();
        }
        if (announced) {
            listener.status("[browser] resumed");
        }
        return null;
    }

    /** Sleep one probe interval (cancel stays immediate via the loop's checks); returns the time waited. */
    private long tickWait() {
        long tick = clock.currentTimeMillis();
        clock.sleepMillis(challengeProbeIntervalMillis);
        return Math.max(0, clock.currentTimeMillis() - tick);
    }

    /**
     * Accept the capture as a source (via {@link SourceAcceptancePort}) and, for a NEW source, notify the
     * caller AT THIS POINT (before web_links) so it can do its own budgeted per-source work. Duplicates are
     * NOT errors. A rejected write (state changed under us) ends the run explicitly, not as a crash.
     */
    private ResearchStopReason acceptSource(String captureId, String page, String pageUrl, String pageHost,
                                            String pageTitle)
            throws ToolInvoker.EndpointUnavailable {
        if (captureId == null) {
            return null;
        }
        try {
            ResearchStopReason gate = beforeToolCall();
            if (gate != null) {
                return gate;
            }
            String accepted = sourceAcceptancePort.accept(captureId, hudRelevantCurrentPage,
                    searchLanguage == null ? "" : searchLanguage);
            progress.success();
            String sourceId = WebAcquisitionText.field(accepted, "source_id");
            boolean duplicate = accepted.contains("duplicate=true");
            if (sourceId == null || "-".equals(sourceId)) {
                return null;
            }
            if (accepted.contains("ALREADY_ACCEPTED")) {
                return null; // idempotent outcome, no new source, no repeated claim
            }
            progress.sourceAccepted();
            listener.status("accepted " + sourceId + (duplicate ? " (duplicate content)" : ""));
            listener.progress(progress, ResearchRunActivity.sourceAccepted(pageUrl, pageHost, pageTitle));
            // Research-specific per-source work happens in the caller's listener at THIS exact point,
            // budgeted via budgetGate (since issue #32 both callers are no-ops here).
            return acceptedSourceListener.onAccepted(
                    new AcceptedSource(sourceId, duplicate, captureId, pageUrl, pageHost, pageTitle, page),
                    budgetGate);
        } catch (ToolInvoker.ToolFailure ex) {
            // A rejected write (state changed under us) ends the run explicitly, not as a crash.
            if (ex.getMessage() != null && ex.getMessage().contains("Not allowed in the current state")) {
                return ex.getMessage().contains("waiting_approval")
                        ? ResearchStopReason.APPROVAL_REQUIRED : ResearchStopReason.STATE_CHANGED;
            }
            progress.error();
        }
        return null;
    }

    // ------------------------------------------------------------------ manual challenge cooperation

    private com.aresstack.askai.browser.search.inference.CancellationSignal cancellationSignal() {
        return new com.aresstack.askai.browser.search.inference.CancellationSignal() {
            public boolean isCancelled() {
                return cancelled.get();
            }
        };
    }

    /** Apply the TYPED challenge states carried by a prepared search — no CHALLENGE: text parsing. */
    private void applyChallenges(
            List<com.aresstack.askai.browser.search.repair.SearchChallengeState> challenges) {
        for (com.aresstack.askai.browser.search.repair.SearchChallengeState challenge : challenges) {
            String family = familyOf(challenge.family);
            if (!family.isEmpty() && challengedFamilies.add(family)) {
                listener.status("manual challenge pending on " + family);
                listener.attention("CAPTCHA", family, challenge.url, false);
            }
        }
    }

    /** Parse typed CHALLENGE/RESOLVED lines (from web_challenge_status) and apply them. */
    private void applyChallengeLines(String text) {
        for (String line : (text == null ? "" : text).split("\n")) {
            if (line.startsWith("BROWSER_CLOSED")) {
                // The user closed the window while a challenge was parked: their stop — never
                // "challenge resolved", never a requeue of the deferred work.
                browserClosedByUser = true;
                return;
            }
            if (line.startsWith("CHALLENGE: ")) {
                String rest = line.substring("CHALLENGE: ".length()).trim();
                int space = rest.indexOf(' ');
                String family = familyOf(space < 0 ? rest : rest.substring(0, space));
                String url = space < 0 ? "" : rest.substring(space + 1).trim();
                if (!family.isEmpty() && challengedFamilies.add(family)) {
                    listener.status("manual challenge pending on " + family);
                    listener.attention("CAPTCHA", family, url, false);
                }
            } else if (line.startsWith("RESOLVED: ")) {
                unlockFamily(familyOf(line.substring("RESOLVED: ".length()).trim()));
            } else if (line.equals("NONE")) {
                // The browser has no pending challenge (it may have been consumed elsewhere): unlock all.
                for (String family : new ArrayList<String>(challengedFamilies)) {
                    unlockFamily(family);
                }
            }
        }
    }

    private void unlockFamily(String family) {
        if (challengedFamilies.remove(family)) {
            listener.status("manual challenge resolved on " + family);
            listener.attention("CAPTCHA", family, "", true);
        }
    }

    /**
     * The cooperative wait while the SERP itself is challenge-blocked (seed phase, nothing usable
     * yet): cancel stays immediate, the waited time is compensated (never a budget failure), the
     * challenge is probed about once per second, and the card shows WAITING_FOR_USER. Returns a stop
     * reason, or {@code null} when the challenge resolved and the engines are worth asking again.
     */
    private ResearchStopReason waitForSerpChallengeResolution(List<FrontierEntry> frontier) {
        String family = challengedFamilies.isEmpty() ? "" : challengedFamilies.iterator().next();
        listener.progress(progress, ResearchRunActivity.waitingForUser(family, ""));
        while (!challengedFamilies.isEmpty()) {
            if (cancelled.get() || browserClosedByUser) {
                return ResearchStopReason.USER_CANCELLED;
            }
            long tickStart = clock.currentTimeMillis();
            clock.sleepMillis(challengeProbeIntervalMillis);
            waitedForUserMillis += Math.max(0, clock.currentTimeMillis() - tickStart);
            probeChallengesIfDue(frontier);
        }
        return browserClosedByUser ? ResearchStopReason.USER_CANCELLED : null;
    }

    /** Probe the parked challenge at most once per second; unlocked work returns to the frontier. */
    private void probeChallengesIfDue(List<FrontierEntry> frontier) {
        if (challengedFamilies.isEmpty()) {
            return;
        }
        long now = clock.currentTimeMillis();
        if (now - lastChallengeProbeAt < challengeProbeIntervalMillis) {
            return;
        }
        lastChallengeProbeAt = now;
        try {
            // Deliberately WITHOUT the budget gate: polling the user's pending challenge must never
            // exhaust a tool budget or count as research work.
            applyChallengeLines(callBrowser("web_challenge_status", args()));
        } catch (ToolInvoker.EndpointUnavailable | ToolInvoker.ToolFailure ignored) {
            // The probe is best-effort; the next tick retries. (A BROWSER_CLOSED failure has already
            // flipped browserClosedByUser inside callBrowser — the gates end the run as the user's.)
        }
        if (!deferredUrls.isEmpty()) {
            // Re-queue everything whose family is unlocked again (still-locked URLs re-defer on pull).
            List<FrontierEntry> requeue = new ArrayList<FrontierEntry>();
            for (FrontierEntry deferred : deferredUrls) {
                if (!challengedFamilies.contains(familyOf(deferred.getUrl()))) {
                    requeue.add(deferred.requeued());
                }
            }
            deferredUrls.removeAll(requeue);
            frontier.addAll(requeue);
        }
    }

    /**
     * The cooperative wait while ONLY challenge-bound work remains: cancel stays immediate, the time
     * budget is compensated (waiting for the user is never a budget failure), the challenge is probed
     * about once per second, and the card shows WAITING_FOR_USER. Returns a stop reason or {@code null}
     * when the frontier has work again.
     */
    private ResearchStopReason waitForManualChallenge(List<FrontierEntry> frontier) {
        String family = challengedFamilies.isEmpty() ? "" : challengedFamilies.iterator().next();
        if (!challengeWaitForUser) {
            // Uniform "skip on challenge" choice: do NOT wait for the user. The challenge-blocked URLs stay
            // deferred and are simply left behind (their parked sources keep an empty full text).
            listener.status("skipping challenge-blocked pages (wait-for-user disabled): " + family);
            return completionPolicy.labelExhaustion(ResearchStopReason.NO_RELEVANT_PATHS, progress);
        }
        listener.progress(progress, ResearchRunActivity.waitingForUser(family, ""));
        while (frontier.isEmpty() && !deferredUrls.isEmpty() && !challengedFamilies.isEmpty()) {
            if (cancelled.get()) {
                return ResearchStopReason.USER_CANCELLED;
            }
            long tickStart = clock.currentTimeMillis();
            clock.sleepMillis(challengeProbeIntervalMillis);
            waitedForUserMillis += Math.max(0, clock.currentTimeMillis() - tickStart);
            probeChallengesIfDue(frontier);
            if (browserClosedByUser) {
                return ResearchStopReason.USER_CANCELLED; // the window close IS the user's answer
            }
        }
        return cancelled.get() ? ResearchStopReason.USER_CANCELLED : null;
    }

    // ------------------------------------------------------------------ central budget gate

    /** Checked before EVERY tool call — the single budget gate (no scattered ifs). */
    private ResearchStopReason beforeToolCall() {
        ResearchStopReason now = stopReasonNow();
        if (now != null) {
            return now;
        }
        progress.toolCall();
        return null;
    }

    private ResearchStopReason stopReasonNow() {
        if (cancelled.get()) {
            return ResearchStopReason.USER_CANCELLED;
        }
        if (browserGone) {
            return ResearchStopReason.MCP_UNAVAILABLE; // browser runtime dead → end technically, never hang
        }
        if (browserClosedByUser) {
            // Closing the window IS the user's stop: their decision, not a failure — sources stay.
            return ResearchStopReason.USER_CANCELLED;
        }
        // The NORMAL end, decided by the injected policy alone — checked before any safety limit so a
        // completed run never reads as an exhaustion that happened to coincide.
        if (completionPolicy.isComplete(progress)) {
            return ResearchStopReason.SUFFICIENT_EVIDENCE;
        }
        if (progress.getToolCalls() >= budget.getMaxToolCalls()) {
            return completionPolicy.labelExhaustion(ResearchStopReason.TOOL_BUDGET_EXHAUSTED, progress);
        }
        if (progress.getPagesVisited() >= budget.getMaxPagesVisited()) {
            return completionPolicy.labelExhaustion(ResearchStopReason.PAGE_BUDGET_EXHAUSTED, progress);
        }
        if (progress.getAcceptedSources() >= budget.getMaxAcceptedSources()) {
            return ResearchStopReason.SOURCE_BUDGET_EXHAUSTED;
        }
        if (progress.getConsecutiveErrors() >= budget.getMaxConsecutiveErrors()) {
            return ResearchStopReason.ERROR_BUDGET_EXHAUSTED;
        }
        // Waiting for the USER (manual challenge) is never budgeted time.
        if (clock.currentTimeMillis() - startedAt - waitedForUserMillis >= budget.getMaxDurationMillis()) {
            return completionPolicy.labelExhaustion(ResearchStopReason.TIME_BUDGET_EXHAUSTED, progress);
        }
        return null;
    }

    // ------------------------------------------------------------------ tool plumbing

    private String callBrowser(String tool, Map<String, Object> a)
            throws ToolInvoker.ToolFailure, ToolInvoker.EndpointUnavailable {
        try {
            return browser.call(tool, a);
        } catch (ToolInvoker.ToolFailure failure) {
            noteIfBrowserClosed(failure);
            throw failure;
        }
    }

    /**
     * The browser runtime's {@code serp-pages: host=n, host=n} diagnostics line, rendered for the funnel card
     * ({@code "html.duckduckgo.com 3 Seiten"}). Empty when the search carried none (API provider).
     */
    static String serpSummaryOf(List<String> diagnostics) {
        for (String line : diagnostics == null ? java.util.Collections.<String>emptyList()
                : diagnostics) {
            if (line == null || !line.startsWith("serp-pages:")) {
                continue;
            }
            StringBuilder sb = new StringBuilder();
            for (String pair : line.substring("serp-pages:".length()).split(",")) {
                String[] parts = pair.trim().split("=");
                if (parts.length != 2 || parts[0].isEmpty()) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(parts[0]).append(' ').append(parts[1])
                        .append("1".equals(parts[1]) ? " Seite" : " Seiten");
            }
            return sb.toString();
        }
        return "";
    }

    /**
     * The ONE recognizer of the browser runtime's typed "the user closed the window" marker. Every path a tool
     * failure can take (page visit, SERP prepare, challenge poll) funnels through here, so the user's
     * stop can never be laundered into a technical failure by the catch that happened to see it first.
     */
    private boolean noteIfBrowserClosed(Throwable failure) {
        if (failure != null && failure.getMessage() != null
                && failure.getMessage().contains("BROWSER_CLOSED")) {
            browserClosedByUser = true; // the user's stop — the next gate ends the run as theirs
            return true;
        }
        return false;
    }

    private static Map<String, Object> args(Object... kv) {
        Map<String, Object> m = new HashMap<String, Object>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    private boolean isSearchProviderSite(String host) {
        return !host.isEmpty() && searchProviderSites.contains(familyOf(host));
    }

    /** The public-suffix aware domain family ({@code news.bbc.co.uk} → {@code bbc.co.uk}). */
    private String familyOf(String urlOrHost) {
        return domainKeys.resolve(urlOrHost).getRegistrableDomain();
    }
}
