package com.aresstack.askai.research.runtime.search.service;

import com.aresstack.askai.browser.search.SearchResultCandidate;
import com.aresstack.askai.browser.search.engine.SearchEngineHosts;
import com.aresstack.askai.browser.search.inference.CancellationSignal;
import com.aresstack.askai.research.runtime.acquire.OpaqueRedirectResolver;
import com.aresstack.askai.research.runtime.search.InitialSearchRequest;
import com.aresstack.askai.research.runtime.search.InitialSearchResult;
import com.aresstack.askai.research.runtime.search.InitialSearchStatus;
import com.aresstack.askai.research.runtime.search.SearchBudgetGate;
import com.aresstack.askai.research.runtime.search.SearchStrategy;
import com.aresstack.askai.research.runtime.search.SearchUrlNormalizer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The ONE path from the research team to a search engine, one instance per AskAI JVM. It takes search jobs from any
 * chat, runs them one at a time in arrival order over the configured {@link SearchStrategy} (the search browser or
 * an API provider), keeps the engine spacing and backs off exponentially after a challenge
 * ({@link EngineThrottle}), resolves the engine's redirect wrappers right after the SERP in one batch through the
 * {@link OpaqueRedirectResolver} (the search browser, on the same FIFO), and puts every new, resolved page on the
 * job's {@link PageCandidateQueue}. Workers therefore never ask the search browser for anything and never see an
 * engine address.
 *
 * <p>Deduplication: a query (lower-case, whitespace collapsed) runs once per project; a page whose normalised URL is
 * already in the project's {@link UrlRegistry} (visited by the chat browser or queued before) is not queued again.
 * A paused engine is reported as {@link SearchServiceOutcome.Status#ENGINE_PAUSED} with its reason, and the query
 * stays open so it can run once the pause lifts.</p>
 */
public final class SearchService {

    /** The origin written to the URL registry for pages this service queued. */
    public static final String ORIGIN = "search-service";

    private final SearchStrategy strategy;
    private final OpaqueRedirectResolver redirectResolver;
    private final EngineThrottle throttle;
    private final SearchServiceClock clock;
    /** Fair: jobs reach the engine in arrival order, from every chat. */
    private final ReentrantLock engine = new ReentrantLock(true);
    private final Map<String, Set<String>> queriesByProject = new HashMap<String, Set<String>>();
    private final Set<String> providerHosts = Collections.synchronizedSet(new LinkedHashSet<String>());

    public SearchService(SearchStrategy strategy, OpaqueRedirectResolver redirectResolver, EngineThrottle throttle,
                         SearchServiceClock clock) {
        if (strategy == null) {
            throw new IllegalArgumentException("The search service needs a search strategy");
        }
        this.strategy = strategy;
        this.redirectResolver = redirectResolver;
        this.clock = clock == null ? SearchServiceClock.SYSTEM : clock;
        this.throttle = throttle == null ? new EngineThrottle(4_000L, 60_000L, 30 * 60_000L, this.clock) : throttle;
    }

    /** Jobs waiting for the engine right now (the benchmark shows whether the search browser is the bottleneck). */
    public int waitingJobs() {
        return engine.getQueueLength();
    }

    /** Why the engines are paused, or empty. */
    public String pauseReason() {
        engine.lock();
        try {
            return throttle.pauseReason();
        } finally {
            engine.unlock();
        }
    }

    /** Engine transit hosts the strategy reported so far (the deny list for worker contexts beyond the engines). */
    public Set<String> providerHosts() {
        synchronized (providerHosts) {
            return Collections.unmodifiableSet(new LinkedHashSet<String>(providerHosts));
        }
    }

    /** Lower-case, whitespace collapsed: the key query dedup compares. */
    public static String normalizeQuery(String query) {
        return query == null ? "" : query.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    /** Run one job; never throws for engine or strategy failures, which come back typed. */
    public SearchServiceOutcome search(SearchJob job, CancellationSignal cancellation) throws InterruptedException {
        final CancellationSignal cancel = cancellation == null ? CancellationSignal.NONE : cancellation;
        List<String> diagnostics = new ArrayList<String>();
        String key = normalizeQuery(job.getQuery());
        if (alreadyRan(job.getProjectKey(), key)) {
            diagnostics.add("query already searched for this project: " + job.getQuery());
            return SearchServiceOutcome.of(SearchServiceOutcome.Status.DUPLICATE_QUERY, "", 0, diagnostics);
        }
        engine.lockInterruptibly();
        try {
            if (alreadyRan(job.getProjectKey(), key)) {
                diagnostics.add("query already searched for this project: " + job.getQuery());
                return SearchServiceOutcome.of(SearchServiceOutcome.Status.DUPLICATE_QUERY, "", 0, diagnostics);
            }
            if (cancel.isCancelled()) {
                return SearchServiceOutcome.of(SearchServiceOutcome.Status.CANCELLED, "", 0, diagnostics);
            }
            if (throttle.isPaused()) {
                diagnostics.add("engines paused: " + throttle.pauseReason());
                return SearchServiceOutcome.of(SearchServiceOutcome.Status.ENGINE_PAUSED, throttle.pauseReason(),
                        throttle.pausedUntil(), diagnostics);
            }
            throttle.awaitSlot();
            InitialSearchResult result;
            try {
                result = strategy.search(new InitialSearchRequest(job.getQuery(), job.getResultCount(),
                        job.getLanguage(), job.getCountry()), cancel, new SearchBudgetGate() {
                    public boolean beforeToolCall() {
                        return !cancel.isCancelled();
                    }
                });
            } catch (Exception e) {
                diagnostics.add("search failed: " + e.getClass().getSimpleName()
                        + (e.getMessage() == null ? "" : ": " + e.getMessage()));
                return SearchServiceOutcome.of(SearchServiceOutcome.Status.TECHNICAL_PROBLEM, "", 0, diagnostics);
            }
            diagnostics.addAll(result.diagnostics);
            providerHosts.addAll(result.providerHosts);
            if (result.candidates.isEmpty() && !result.challenges.isEmpty()) {
                throttle.recordBlock("challenge on " + result.challenges.get(0).family);
                diagnostics.add("engine challenge; engines paused until " + throttle.pausedUntil());
                return SearchServiceOutcome.of(SearchServiceOutcome.Status.ENGINE_BLOCKED, throttle.pauseReason(),
                        throttle.pausedUntil(), diagnostics);
            }
            if (result.status == InitialSearchStatus.TECHNICAL_PROBLEM) {
                return SearchServiceOutcome.of(SearchServiceOutcome.Status.TECHNICAL_PROBLEM, "", 0, diagnostics);
            }
            throttle.recordSuccess();
            markRan(job.getProjectKey(), key);
            return queueHits(job, result, diagnostics);
        } finally {
            engine.unlock();
        }
    }

    /** The redirect batch and the hand-off to the queue; runs under the engine lock (same FIFO as the SERPs). */
    private SearchServiceOutcome queueHits(SearchJob job, InitialSearchResult result, List<String> diagnostics)
            throws InterruptedException {
        Set<String> transit = new HashSet<String>();
        for (String host : result.providerHosts) {
            transit.add(bareHost(host));
        }
        List<PageCandidate> queued = new ArrayList<PageCandidate>();
        Set<String> seen = new HashSet<String>();
        int known = 0;
        int dropped = 0;
        boolean redirectBlocked = false;
        for (SearchResultCandidate hit : result.candidates) {
            if (redirectBlocked) {
                dropped++;
                continue;
            }
            String url = hit.hasVerifiedTarget() ? hit.resolvedTargetUrl : hit.navigationUrl();
            if (url == null || url.trim().isEmpty()) {
                dropped++;
                continue;
            }
            if (isEngineAddress(url, transit)) {
                if (redirectResolver == null) {
                    dropped++;
                    continue;
                }
                throttle.awaitSlot();
                OpaqueRedirectResolver.Resolution hop = redirectResolver.resolve(url);
                if (hop.getStatus() == OpaqueRedirectResolver.Resolution.Status.BLOCKED) {
                    redirectBlocked = true;
                    throttle.recordBlock("challenge on redirect hop" + (hop.getDetail().isEmpty() ? ""
                            : ": " + hop.getDetail()));
                    diagnostics.add("engine blocked a redirect hop; rest of the batch dropped");
                    dropped++;
                    continue;
                }
                if (hop.getStatus() != OpaqueRedirectResolver.Resolution.Status.RESOLVED
                        || isEngineAddress(hop.getTargetUrl(), transit)) {
                    dropped++;
                    continue;
                }
                url = hop.getTargetUrl();
            }
            String normalized = SearchUrlNormalizer.normalize(url);
            if (!seen.add(normalized)) {
                dropped++;
                continue;
            }
            if (!job.getRegistry().register(normalized, ORIGIN)) {
                known++;
                continue;
            }
            PageCandidate page = new PageCandidate(normalized, hit.title, hit.snippet, job.getQuery(),
                    hit.engineHost, hit.originalRank);
            job.getQueue().offer(page);
            queued.add(page);
        }
        SearchServiceOutcome.Status status = queued.isEmpty()
                ? (redirectBlocked ? SearchServiceOutcome.Status.ENGINE_BLOCKED
                : SearchServiceOutcome.Status.NOTHING_NEW)
                : SearchServiceOutcome.Status.QUEUED;
        boolean paused = throttle.isPaused();
        return new SearchServiceOutcome(status, queued, result.candidates.size(), known, dropped, redirectBlocked,
                paused ? throttle.pauseReason() : "", paused ? throttle.pausedUntil() : 0L, diagnostics);
    }

    private static boolean isEngineAddress(String url, Set<String> transit) {
        if (SearchEngineHosts.isEngineHost(url)) {
            return true;
        }
        String host = bareHost(hostOf(url));
        for (String t : transit) {
            if (!t.isEmpty() && (host.equals(t) || host.endsWith("." + t))) {
                return true;
            }
        }
        return false;
    }

    private static String hostOf(String url) {
        String u = url == null ? "" : url.trim().toLowerCase(Locale.ROOT);
        int scheme = u.indexOf("://");
        String rest = scheme < 0 ? u : u.substring(scheme + 3);
        int end = rest.length();
        for (int i = 0; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (c == '/' || c == '?' || c == '#' || c == ':') {
                end = i;
                break;
            }
        }
        String host = rest.substring(0, end);
        int at = host.lastIndexOf('@');
        return at < 0 ? host : host.substring(at + 1);
    }

    private static String bareHost(String host) {
        String h = host == null ? "" : host.trim().toLowerCase(Locale.ROOT);
        return h.startsWith("www.") ? h.substring(4) : h;
    }

    private boolean alreadyRan(String projectKey, String normalizedQuery) {
        synchronized (queriesByProject) {
            Set<String> ran = queriesByProject.get(projectKey);
            return ran != null && ran.contains(normalizedQuery);
        }
    }

    private void markRan(String projectKey, String normalizedQuery) {
        synchronized (queriesByProject) {
            Set<String> ran = queriesByProject.get(projectKey);
            if (ran == null) {
                ran = new HashSet<String>();
                queriesByProject.put(projectKey, ran);
            }
            ran.add(normalizedQuery);
        }
    }
}
