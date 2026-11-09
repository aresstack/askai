package com.aresstack.askai.research.runtime.search;

import com.aresstack.askai.browser.search.LegacyBrowserSearchDefaults;
import com.aresstack.askai.browser.search.SearchResultCandidate;
import com.aresstack.askai.browser.search.engine.BrowserSearchEngineCatalog;
import com.aresstack.askai.browser.search.engine.BrowserSearchEngineSelection;
import com.aresstack.askai.browser.search.engine.SearchEngineHosts;
import com.aresstack.askai.browser.search.inference.CancellationSignal;
import com.aresstack.askai.browser.search.repair.SearchChallengeState;
import com.aresstack.askai.research.domain.scope.NonSourceHosts;
import com.aresstack.askai.research.runtime.loop.ToolInvoker;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The "Browser (SERP)" search as the user configured it under Search → Engines: the active rows, grouped by
 * priority. Priority 0 runs first; the rows of one priority form a GROUP that is searched in the same round; the next
 * group runs only when the groups so far have not delivered enough usable results
 * ({@code navigation.minimumUsableResults}). Google runs in AskAI's separate search browser
 * ({@link GoogleSearchBrowserStrategy}); the other engines of a group run together in the research browser
 * ({@link EngineGroupSearchStrategy#forEngines}). Within a group the two browsers work at the same time, each one
 * request after another, so a group never fires a burst at one engine; the engines' own delays and the search
 * browser's pace still apply.
 *
 * <ul>
 *   <li>Usable: a distinct result whose target is no search-result or shop catalogue page
 *       ({@link NonSourceHosts#isNonSourcePage}); an unresolved engine redirect wrapper (Google's goto link) counts,
 *       it is resolved only when the hit is opened.</li>
 *   <li>A step that fails, is challenged or delivers nothing counts nothing: the next group is tried.</li>
 *   <li>An engine already searched in an earlier group of this search is not asked the same query again.</li>
 *   <li>Candidates are merged in group and step order; a URL already found is not added twice.</li>
 * </ul>
 * A failure carrying the browser's BROWSER_CLOSED marker (the user closed the window) is rethrown at once, so
 * the caller ends the run as the user's stop instead of trying the next group.
 * When no step delivers, the result is {@link InitialSearchStatus#NO_RESULTS} if some step searched cleanly,
 * else the last failure is rethrown or reported as {@link InitialSearchStatus#TECHNICAL_PROBLEM}.
 *
 * <p>Thread-safe and one search at a time for the whole AskAI process: every chat, the concept phase and any
 * worker that searches go through this one tool, and a search waits (fair, in arrival order) until the previous
 * one has finished. The engine pace itself (spacing, pause after a challenge, same search answered from memory)
 * lives in the search browser.</p>
 */
public final class BrowserEnginesSearchStrategy implements SearchStrategy {

    private static final String GOOGLE_STEP = "google";
    private static final String PAGE_STEP_PREFIX = "page-engines";

    /** One searchable step of a group: its name, the engines it asks, and the strategy that asks them. */
    private static final class Step {
        final String name;
        final List<String> engineIds;
        final SearchStrategy strategy;

        Step(String name, List<String> engineIds, SearchStrategy strategy) {
            this.name = name;
            this.engineIds = engineIds;
            this.strategy = strategy;
        }
    }

    /** The groups in priority order, each with its steps (one per browser). */
    private final List<List<Step>> groups = new ArrayList<List<Step>>();
    private final int minimumUsableResults;

    /** Group steps run on their own threads (at most one per browser), daemon so they never keep AskAI alive. */
    private static final ExecutorService GROUP_STEPS = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "engine-group-step");
        thread.setDaemon(true);
        return thread;
    });

    /** With the default "enough usable results" of a fresh installation (tests, callers without settings). */
    public BrowserEnginesSearchStrategy(BrowserSearchEngineSelection selection, SearchStrategy google,
                                        SearchStrategy pageEngines) {
        this(selection, LegacyBrowserSearchDefaults.MINIMUM_USABLE_RESULTS, google, pageEngines);
    }

    /**
     * @param selection            the engine table (rows with priorities, active flags)
     * @param minimumUsableResults usable results after which no further group is asked (the setting)
     * @param google               the Google search in the search browser, or {@code null} when it is unavailable
     * @param pageEngines          the research browser's engine search, or {@code null} when unavailable; an
     *                             {@link EngineGroupSearchStrategy} is narrowed to each group's engines, any other
     *                             strategy runs once, in the group of the first page engine
     */
    public BrowserEnginesSearchStrategy(BrowserSearchEngineSelection selection, int minimumUsableResults,
                                        SearchStrategy google, SearchStrategy pageEngines) {
        this.minimumUsableResults = Math.max(1, minimumUsableResults);
        TreeMap<Integer, List<String>> byPriority = new TreeMap<Integer, List<String>>();
        if (selection != null) {
            for (BrowserSearchEngineSelection.Entry entry : selection.getEntries()) {
                if (!entry.isEnabled() || BrowserSearchEngineCatalog.byId(entry.getEngineId()) == null) {
                    continue; // inactive, or an engine AskAI cannot search with (yet)
                }
                List<String> ids = byPriority.get(entry.getPriority());
                if (ids == null) {
                    ids = new ArrayList<String>();
                    byPriority.put(entry.getPriority(), ids);
                }
                ids.add(entry.getEngineId());
            }
        }
        Set<String> planned = new LinkedHashSet<String>(); // engine already asked by an earlier group
        boolean wholePageSearchPlaced = false;
        for (Map.Entry<Integer, List<String>> group : byPriority.entrySet()) {
            List<Step> steps = new ArrayList<Step>();
            List<String> pageIds = new ArrayList<String>();
            for (String id : group.getValue()) {
                if (!planned.add(id)) {
                    continue; // same query to the same engine again: never (languages per row come with PR F)
                }
                if (BrowserSearchEngineCatalog.runsInSearchBrowser(id)) {
                    if (google != null) {
                        steps.add(new Step(GOOGLE_STEP, java.util.Collections.singletonList(id), google));
                    }
                } else {
                    pageIds.add(id);
                }
            }
            if (!pageIds.isEmpty() && pageEngines != null) {
                if (pageEngines instanceof EngineGroupSearchStrategy) {
                    steps.add(new Step(PAGE_STEP_PREFIX + ":" + String.join("+", pageIds), pageIds,
                            ((EngineGroupSearchStrategy) pageEngines).forEngines(pageIds)));
                } else if (!wholePageSearchPlaced) {
                    steps.add(new Step(PAGE_STEP_PREFIX, pageIds, pageEngines));
                    wholePageSearchPlaced = true;
                }
            }
            if (!steps.isEmpty()) {
                groups.add(steps);
            }
        }
    }

    /** Whether any configured engine can run at all. */
    public boolean hasSteps() {
        return !groups.isEmpty();
    }

    /** Whether Google in the search browser is the only step (the search then shows no research browser). */
    public boolean isGoogleOnly() {
        List<String> names = stepNames();
        return names.size() == 1 && GOOGLE_STEP.equals(names.get(0));
    }

    /** The step names in run order ("google", "page-engines:duckduckgo+bing") — diagnostics and tests. */
    public List<String> stepNames() {
        List<String> names = new ArrayList<String>();
        for (List<Step> group : groups) {
            for (Step step : group) {
                names.add(step.name);
            }
        }
        return names;
    }

    /** The groups as step names, in priority order — diagnostics and tests. */
    public List<List<String>> groupNames() {
        List<List<String>> names = new ArrayList<List<String>>();
        for (List<Step> group : groups) {
            List<String> stepNames = new ArrayList<String>();
            for (Step step : group) {
                stepNames.add(step.name);
            }
            names.add(stepNames);
        }
        return names;
    }

    /** One engine search at a time across every chat and thread of the process, in arrival order. */
    private static final ReentrantLock ONE_SEARCH_AT_A_TIME = new ReentrantLock(true);

    /** Searches waiting for the running one to finish (diagnostics). */
    public static int waitingSearches() {
        return ONE_SEARCH_AT_A_TIME.getQueueLength();
    }

    @Override
    public InitialSearchResult search(InitialSearchRequest request, CancellationSignal cancellation,
                                      SearchBudgetGate budget)
            throws ToolInvoker.ToolFailure, ToolInvoker.EndpointUnavailable {
        try {
            ONE_SEARCH_AT_A_TIME.lockInterruptibly();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            List<String> diagnostics = new ArrayList<String>();
            diagnostics.add("browser search: interrupted while waiting for the running search");
            return new InitialSearchResult(new ArrayList<SearchResultCandidate>(), new ArrayList<String>(),
                    new ArrayList<SearchChallengeState>(), diagnostics, InitialSearchStatus.TECHNICAL_PROBLEM);
        }
        try {
            return searchSerially(request, cancellation, budget);
        } finally {
            ONE_SEARCH_AT_A_TIME.unlock();
        }
    }

    private InitialSearchResult searchSerially(InitialSearchRequest request, CancellationSignal cancellation,
                                               SearchBudgetGate budget)
            throws ToolInvoker.ToolFailure, ToolInvoker.EndpointUnavailable {
        if (groups.isEmpty()) {
            List<String> diagnostics = new ArrayList<String>();
            diagnostics.add("browser search: no enabled engine is available");
            return new InitialSearchResult(new ArrayList<SearchResultCandidate>(), new ArrayList<String>(),
                    new ArrayList<SearchChallengeState>(), diagnostics, InitialSearchStatus.TECHNICAL_PROBLEM);
        }
        List<SearchResultCandidate> candidates = new ArrayList<SearchResultCandidate>();
        Set<String> seenUrls = new LinkedHashSet<String>();
        Set<String> hosts = new LinkedHashSet<String>();
        List<SearchChallengeState> challenges = new ArrayList<SearchChallengeState>();
        List<String> diagnostics = new ArrayList<String>();
        boolean cleanSearch = false;
        boolean cancelled = false;
        Exception lastFailure = null;
        int usable = 0;
        SearchBudgetGate sharedBudget = synchronizedBudget(budget);
        for (int g = 0; g < groups.size(); g++) {
            if (cancellation != null && cancellation.isCancelled()) {
                cancelled = true;
                break;
            }
            List<Step> group = groups.get(g);
            Map<Step, Object> outcomes = runGroup(group, request, cancellation, sharedBudget);
            for (Step step : group) {
                Object outcome = outcomes.get(step);
                if (outcome instanceof Exception) {
                    Exception failure = (Exception) outcome;
                    if (closedByUser(failure)) {
                        rethrow(failure); // the user's stop: never fall back to another group or merge it away
                    }
                    diagnostics.add("browser search: " + step.name + " failed: " + failure.getMessage());
                    lastFailure = failure;
                    continue;
                }
                InitialSearchResult result = (InitialSearchResult) outcome;
                if (result == null) {
                    continue;
                }
                for (SearchResultCandidate candidate : result.candidates) {
                    String key = keyOf(candidate);
                    if (key == null || seenUrls.add(key)) {
                        candidates.add(candidate);
                        if (key != null && isUsable(key)) {
                            usable++;
                        }
                    }
                }
                hosts.addAll(result.providerHosts);
                challenges.addAll(result.challenges);
                diagnostics.addAll(result.diagnostics);
                cleanSearch |= result.status == InitialSearchStatus.NO_RESULTS;
            }
            diagnostics.add("browser search: priority group " + (g + 1) + "/" + groups.size() + " "
                    + groupNames().get(g) + " -> " + usable + " usable of " + minimumUsableResults + " wanted");
            if (usable >= minimumUsableResults) {
                break;
            }
        }
        if (!candidates.isEmpty()) {
            return new InitialSearchResult(candidates, new ArrayList<String>(hosts), challenges, diagnostics,
                    InitialSearchStatus.RESULTS);
        }
        if (cancelled) {
            // A neutral empty result: the caller's cancellation gate reports the user's stop, not a failure.
            diagnostics.add("browser search cancelled");
            return InitialSearchResult.empty(diagnostics);
        }
        if (cleanSearch) {
            return new InitialSearchResult(candidates, new ArrayList<String>(hosts), challenges, diagnostics,
                    InitialSearchStatus.NO_RESULTS);
        }
        if (lastFailure instanceof ToolInvoker.ToolFailure) {
            throw (ToolInvoker.ToolFailure) lastFailure;
        }
        if (lastFailure instanceof ToolInvoker.EndpointUnavailable) {
            throw (ToolInvoker.EndpointUnavailable) lastFailure;
        }
        if (lastFailure instanceof RuntimeException) {
            throw (RuntimeException) lastFailure;
        }
        return new InitialSearchResult(candidates, new ArrayList<String>(hosts), challenges, diagnostics,
                InitialSearchStatus.TECHNICAL_PROBLEM);
    }

    /** The group's steps, at the same time when there are several (one per browser); outcome or exception each. */
    private static Map<Step, Object> runGroup(List<Step> group, final InitialSearchRequest request,
                                              final CancellationSignal cancellation, final SearchBudgetGate budget) {
        Map<Step, Object> outcomes = new LinkedHashMap<Step, Object>();
        if (group.size() == 1) {
            Step only = group.get(0);
            outcomes.put(only, runStep(only, request, cancellation, budget));
            return outcomes;
        }
        Map<Step, Future<Object>> running = new LinkedHashMap<Step, Future<Object>>();
        for (final Step step : group) {
            running.put(step, GROUP_STEPS.submit(() -> runStep(step, request, cancellation, budget)));
        }
        for (Map.Entry<Step, Future<Object>> entry : running.entrySet()) {
            try {
                outcomes.put(entry.getKey(), entry.getValue().get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                outcomes.put(entry.getKey(), new IllegalStateException("interrupted while searching"));
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                outcomes.put(entry.getKey(), cause instanceof Exception ? cause
                        : new IllegalStateException(String.valueOf(cause)));
            }
        }
        return outcomes;
    }

    private static Object runStep(Step step, InitialSearchRequest request, CancellationSignal cancellation,
                                  SearchBudgetGate budget) {
        try {
            return step.strategy.search(request, cancellation, budget);
        } catch (ToolInvoker.ToolFailure | ToolInvoker.EndpointUnavailable | RuntimeException failure) {
            return failure;
        }
    }

    /** The budget gate is asked from two browsers at once within a group. */
    private static SearchBudgetGate synchronizedBudget(final SearchBudgetGate budget) {
        if (budget == null) {
            return null;
        }
        final Object lock = new Object();
        return () -> {
            synchronized (lock) {
                return budget.beforeToolCall();
            }
        };
    }

    private static String keyOf(SearchResultCandidate candidate) {
        return candidate.resolvedTargetUrl == null || candidate.resolvedTargetUrl.isEmpty()
                ? candidate.rawSearchHref : candidate.resolvedTargetUrl;
    }

    /** A result that can become a source; an unresolved engine redirect wrapper is judged only when opened. */
    private static boolean isUsable(String url) {
        return SearchEngineHosts.isEngineHost(url) || !NonSourceHosts.isNonSourcePage(url);
    }

    private static void rethrow(Exception failure) throws ToolInvoker.ToolFailure, ToolInvoker.EndpointUnavailable {
        if (failure instanceof ToolInvoker.ToolFailure) {
            throw (ToolInvoker.ToolFailure) failure;
        }
        if (failure instanceof ToolInvoker.EndpointUnavailable) {
            throw (ToolInvoker.EndpointUnavailable) failure;
        }
        if (failure instanceof RuntimeException) {
            throw (RuntimeException) failure;
        }
        throw new IllegalStateException(failure);
    }

    /** The browser runtime's typed marker for "the user closed the browser window". */
    private static boolean closedByUser(Exception failure) {
        return failure.getMessage() != null && failure.getMessage().contains("BROWSER_CLOSED");
    }
}
