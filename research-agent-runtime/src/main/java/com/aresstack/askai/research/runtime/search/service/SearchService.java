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
 * ({@link EngineThrottle}) and puts every new page on the job's {@link PageCandidateQueue}. Engine redirect wrappers
 * (Google {@code /goto}) stay unresolved in the queue and are resolved lazily, one at a time, when a worker takes the
 * page ({@link #resolveForVisit}), in the search browser and under the same spacing. Workers never open an engine
 * address themselves.
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
            InitialSearchResult result;
            try {
                // Every external call of the strategy (each result page, each engine) waits for its slot.
                result = strategy.search(new InitialSearchRequest(job.getQuery(), job.getResultCount(),
                        job.getLanguage(), job.getCountry()), cancel, new SearchBudgetGate() {
                    public boolean beforeToolCall() {
                        if (cancel.isCancelled()) {
                            return false;
                        }
                        try {
                            throttle.awaitSlot();
                            return true;
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return false;
                        }
                    }
                });
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("search interrupted while waiting for the engine");
                }
            } catch (InterruptedException e) {
                throw e;
            } catch (Exception e) {
                diagnostics.add("search failed: " + e.getClass().getSimpleName()
                        + (e.getMessage() == null ? "" : ": " + e.getMessage()));
                return SearchServiceOutcome.of(SearchServiceOutcome.Status.TECHNICAL_PROBLEM, "", 0, diagnostics);
            }
            diagnostics.addAll(result.diagnostics);
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
            SearchServiceOutcome outcome = queueHits(job, result, diagnostics);
            if (outcome.getStatus() != SearchServiceOutcome.Status.TECHNICAL_PROBLEM) {
                markRan(job.getProjectKey(), key); // a registry failure leaves the query open for a retry
            }
            return outcome;
        } finally {
            engine.unlock();
        }
    }

    /**
     * The hand-off to the queue; runs under the engine lock. Engine redirect wrappers (Google {@code /goto}) are
     * NOT resolved here: resolving a whole SERP at once is exactly the burst that got AskAI blocked. They are queued
     * unresolved and resolved one by one, only when a worker takes the page ({@link #resolveForVisit}).
     */
    private SearchServiceOutcome queueHits(SearchJob job, InitialSearchResult result, List<String> diagnostics) {
        Set<String> transit = transitHosts(result.providerHosts);
        List<PageCandidate> queued = new ArrayList<PageCandidate>();
        Set<String> seen = new HashSet<String>();
        int known = 0;
        int dropped = 0;
        boolean registryFailure = false;
        for (SearchResultCandidate hit : result.candidates) {
            String url = hit.hasVerifiedTarget() ? hit.resolvedTargetUrl : hit.navigationUrl();
            if (url == null || url.trim().isEmpty()) {
                dropped++;
                continue;
            }
            boolean wrapped = isEngineAddress(url, transit);
            if (wrapped && redirectResolver == null) {
                dropped++;
                continue;
            }
            String key = wrapped ? url.trim() : SearchUrlNormalizer.normalize(url);
            if (!seen.add(key)) {
                dropped++;
                continue;
            }
            PageCandidate page = new PageCandidate(key, hit.title, hit.snippet, job.getQuery(), hit.engineHost,
                    hit.originalRank, wrapped);
            try {
                // Durable PENDING first, then the in-memory queue: a crash in between is recovered by
                // requeuePending, never lost; a page is only "done" once its visit completes it.
                if (!job.getRegistry().enqueue(page, ORIGIN)) {
                    known++;
                    continue;
                }
            } catch (UrlRegistryException e) {
                registryFailure = true;
                diagnostics.add("URL registry not writable, rest of the hits not queued: " + e.getMessage());
                break;
            }
            job.getQueue().offer(page);
            queued.add(page);
        }
        markTransit(transit);
        SearchServiceOutcome.Status status = !queued.isEmpty() ? SearchServiceOutcome.Status.QUEUED
                : registryFailure ? SearchServiceOutcome.Status.TECHNICAL_PROBLEM
                : SearchServiceOutcome.Status.NOTHING_NEW;
        return new SearchServiceOutcome(status, queued, result.candidates.size(), known, dropped,
                "", 0L, diagnostics);
    }

    /**
     * After a restart: put every PENDING page of the project back on the queue (pages whose visit never completed).
     *
     * @return how many pages were queued again
     */
    public static int requeuePending(UrlRegistry registry, PageCandidateQueue queue) {
        int count = 0;
        for (PageCandidate page : registry.pending()) {
            queue.offer(page);
            count++;
        }
        return count;
    }

    /**
     * Resolve ONE queued page right before a worker visits it. A plain page comes back READY unchanged; an engine
     * wrapper is resolved in the search browser, on the same FIFO and with the same spacing and backoff as the
     * searches, so hops never come in bursts. The resolved address is registered PENDING and the wrapper COMPLETED;
     * a target that is already registered comes back ALREADY_KNOWN (its own entry gets visited). The visitor
     * completes the READY page in the registry once its visit has ended for good; a failed visit leaves it PENDING.
     */
    public PageResolution resolveForVisit(PageCandidate page, UrlRegistry registry) throws InterruptedException {
        if (!page.isWrapped()) {
            return new PageResolution(PageResolution.Status.READY, page, "");
        }
        if (redirectResolver == null) {
            return new PageResolution(PageResolution.Status.UNRESOLVED, page, "no redirect resolver");
        }
        engine.lockInterruptibly();
        try {
            if (throttle.isPaused()) {
                return new PageResolution(PageResolution.Status.ENGINE_PAUSED, page, throttle.pauseReason());
            }
            throttle.awaitSlot();
            OpaqueRedirectResolver.Resolution hop = redirectResolver.resolve(page.getUrl());
            if (hop.getStatus() == OpaqueRedirectResolver.Resolution.Status.BLOCKED) {
                throttle.recordBlock("challenge on redirect hop" + (hop.getDetail().isEmpty() ? ""
                        : ": " + hop.getDetail()));
                return new PageResolution(PageResolution.Status.ENGINE_BLOCKED, page, throttle.pauseReason());
            }
            if (hop.getStatus() != OpaqueRedirectResolver.Resolution.Status.RESOLVED
                    || isEngineAddress(hop.getTargetUrl(), transitSnapshot())) {
                return new PageResolution(PageResolution.Status.UNRESOLVED, page, hop.getDetail());
            }
            throttle.recordSuccess();
            String target = SearchUrlNormalizer.normalize(hop.getTargetUrl());
            PageCandidate resolved = page.resolvedTo(target);
            if (registry == null) {
                return new PageResolution(PageResolution.Status.READY, resolved, "");
            }
            try {
                // The target becomes PENDING (the visitor completes it), then the wrapper is done. A crash in
                // between leaves both PENDING: the wrapper resolves again and finds its target already known.
                boolean fresh = registry.enqueue(resolved, ORIGIN);
                registry.complete(page.getUrl(), "resolved to " + target);
                return new PageResolution(fresh ? PageResolution.Status.READY : PageResolution.Status.ALREADY_KNOWN,
                        resolved, "");
            } catch (UrlRegistryException e) {
                return new PageResolution(PageResolution.Status.REGISTRY_FAILED, page, e.getMessage());
            }
        } finally {
            engine.unlock();
        }
    }

    private static Set<String> transitHosts(List<String> hosts) {
        Set<String> transit = new HashSet<String>();
        for (String host : hosts) {
            transit.add(bareHost(host));
        }
        return transit;
    }

    private void markTransit(Set<String> transit) {
        providerHosts.addAll(transit);
    }

    private Set<String> transitSnapshot() {
        synchronized (providerHosts) {
            return new HashSet<String>(providerHosts);
        }
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
