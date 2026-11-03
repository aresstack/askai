package com.aresstack.askai.research.runtime.search;

import com.aresstack.askai.browser.search.SearchResultCandidate;
import com.aresstack.askai.browser.search.engine.BrowserSearchEngineCatalog;
import com.aresstack.askai.browser.search.engine.BrowserSearchEngineSelection;
import com.aresstack.askai.browser.search.engine.EngineAcquisitionMode;
import com.aresstack.askai.browser.search.inference.CancellationSignal;
import com.aresstack.askai.browser.search.repair.SearchChallengeState;
import com.aresstack.askai.research.runtime.loop.ToolInvoker;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The "Browser (SERP)" search as the user configured it under Search → Engines: the enabled engines in their
 * order, with the configured acquisition mode. Google runs in AskAI's separate search browser
 * ({@link GoogleSearchBrowserStrategy}); DuckDuckGo/Bing run as ONE step in the research browser
 * ({@link LegacyBrowserSearchStrategy}, which keeps their own order and mode among themselves).
 *
 * <ul>
 *   <li>{@link EngineAcquisitionMode#FIRST_USABLE}: the steps run in order until one delivers candidates; a
 *       step that fails technically is recorded and the next one is tried.</li>
 *   <li>{@link EngineAcquisitionMode#ALL_ENABLED}: every step runs; the candidates are merged in step order
 *       (a URL already found is not added twice).</li>
 * </ul>
 * A failure carrying the browser's BROWSER_CLOSED marker (the user closed the window) is rethrown at once, so
 * the caller ends the run as the user's stop instead of trying the next engine.
 * When no step delivers, the result is {@link InitialSearchStatus#NO_RESULTS} if some step searched cleanly,
 * else the last failure is rethrown or reported as {@link InitialSearchStatus#TECHNICAL_PROBLEM}.
 */
public final class BrowserEnginesSearchStrategy implements SearchStrategy {

    private final List<SearchStrategy> steps = new ArrayList<SearchStrategy>();
    private final List<String> stepNames = new ArrayList<String>();
    private final EngineAcquisitionMode mode;

    /**
     * @param selection   the configured engines (order, enabled flags, mode)
     * @param google      the Google search in the search browser, or {@code null} when it is unavailable
     * @param pageEngines the DuckDuckGo/Bing search in the research browser, or {@code null} when unavailable
     */
    public BrowserEnginesSearchStrategy(BrowserSearchEngineSelection selection, SearchStrategy google,
                                        SearchStrategy pageEngines) {
        this.mode = selection == null ? EngineAcquisitionMode.FIRST_USABLE : selection.getMode();
        boolean pageEnginesAdded = false;
        if (selection != null) {
            for (BrowserSearchEngineSelection.Entry entry : selection.getEntries()) {
                if (!entry.isEnabled() || BrowserSearchEngineCatalog.byId(entry.getEngineId()) == null) {
                    continue;
                }
                if (BrowserSearchEngineCatalog.runsInSearchBrowser(entry.getEngineId())) {
                    if (google != null && !stepNames.contains("google")) {
                        steps.add(google);
                        stepNames.add("google");
                    }
                } else if (!pageEnginesAdded && pageEngines != null) {
                    steps.add(pageEngines);
                    stepNames.add("page-engines");
                    pageEnginesAdded = true;
                }
            }
        }
    }

    /** Whether any configured engine can run at all. */
    public boolean hasSteps() {
        return !steps.isEmpty();
    }

    /** Whether Google in the search browser is the only step (the search then shows no research browser). */
    public boolean isGoogleOnly() {
        return stepNames.size() == 1 && "google".equals(stepNames.get(0));
    }

    /** The configured step names in order ("google", "page-engines") — diagnostics and tests. */
    public List<String> stepNames() {
        return new ArrayList<String>(stepNames);
    }

    @Override
    public InitialSearchResult search(InitialSearchRequest request, CancellationSignal cancellation,
                                      SearchBudgetGate budget)
            throws ToolInvoker.ToolFailure, ToolInvoker.EndpointUnavailable {
        if (steps.isEmpty()) {
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
        Exception lastFailure = null;
        for (int i = 0; i < steps.size(); i++) {
            if (cancellation != null && cancellation.isCancelled()) {
                break;
            }
            InitialSearchResult result;
            try {
                result = steps.get(i).search(request, cancellation, budget);
            } catch (ToolInvoker.ToolFailure | ToolInvoker.EndpointUnavailable | RuntimeException failure) {
                if (closedByUser(failure)) {
                    throw failure; // the user's stop: never fall back to another engine or merge it away
                }
                diagnostics.add("browser search: " + stepNames.get(i) + " failed: " + failure.getMessage());
                lastFailure = failure;
                continue;
            }
            for (SearchResultCandidate candidate : result.candidates) {
                String key = candidate.resolvedTargetUrl == null || candidate.resolvedTargetUrl.isEmpty()
                        ? candidate.rawSearchHref : candidate.resolvedTargetUrl;
                if (key == null || seenUrls.add(key)) {
                    candidates.add(candidate);
                }
            }
            hosts.addAll(result.providerHosts);
            challenges.addAll(result.challenges);
            diagnostics.addAll(result.diagnostics);
            cleanSearch |= result.status == InitialSearchStatus.NO_RESULTS;
            if (mode == EngineAcquisitionMode.FIRST_USABLE && result.status == InitialSearchStatus.RESULTS
                    && !result.candidates.isEmpty()) {
                break;
            }
        }
        if (!candidates.isEmpty()) {
            return new InitialSearchResult(candidates, new ArrayList<String>(hosts), challenges, diagnostics,
                    InitialSearchStatus.RESULTS);
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

    /** The browser runtime's typed marker for "the user closed the browser window". */
    private static boolean closedByUser(Exception failure) {
        return failure.getMessage() != null && failure.getMessage().contains("BROWSER_CLOSED");
    }
}
