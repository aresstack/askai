package com.aresstack.askai.research.runtime.search;

import com.aresstack.askai.browser.search.SearchResultCandidate;
import com.aresstack.askai.browser.search.engine.BrowserSearchEngineCatalog;
import com.aresstack.askai.browser.search.engine.BrowserSearchEngineSelection;
import com.aresstack.askai.browser.search.engine.EngineAcquisitionMode;
import com.aresstack.askai.browser.search.inference.CancellationSignal;
import com.aresstack.askai.browser.search.repair.SearchChallengeState;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The configured browser engines decide which searches run, in which order and how many. */
public class BrowserEnginesSearchStrategyTest {

    private static final InitialSearchRequest REQUEST = new InitialSearchRequest("q", 10, "", "");
    private static final SearchBudgetGate BUDGET = () -> true;
    private static final CancellationSignal NOT_CANCELLED = () -> false;

    @Test
    public void defaultRunsGoogleFirstAndStopsWhenItDelivers() throws Exception {
        Fake google = new Fake("https://a.example/");
        Fake pages = new Fake("https://b.example/");
        BrowserEnginesSearchStrategy strategy = new BrowserEnginesSearchStrategy(
                com.aresstack.askai.browser.search.LegacyBrowserSearchDefaults.create().navigation.engineSelection,
                google, pages);
        assertEquals(Arrays.asList("google", "page-engines"), strategy.stepNames());
        InitialSearchResult result = strategy.search(REQUEST, NOT_CANCELLED, BUDGET);
        assertEquals(1, google.calls);
        assertEquals("FIRST_USABLE: DuckDuckGo is only the fallback", 0, pages.calls);
        assertEquals(InitialSearchStatus.RESULTS, result.status);
    }

    @Test
    public void aFailingGoogleFallsBackToThePageEngines() throws Exception {
        Fake google = new Fake() {
            @Override
            public InitialSearchResult search(InitialSearchRequest r, CancellationSignal c, SearchBudgetGate b) {
                calls++;
                throw new IllegalStateException("blocked");
            }
        };
        Fake pages = new Fake("https://b.example/");
        InitialSearchResult result = new BrowserEnginesSearchStrategy(
                selection(EngineAcquisitionMode.FIRST_USABLE, "google:on", "duckduckgo:on"), google, pages)
                .search(REQUEST, NOT_CANCELLED, BUDGET);
        assertEquals(1, pages.calls);
        assertEquals(1, result.candidates.size());
    }

    @Test
    public void aClosedBrowserIsTheUsersStopAndNeverFallsBack() {
        Fake pages = new Fake() {
            @Override
            public InitialSearchResult search(InitialSearchRequest r, CancellationSignal c, SearchBudgetGate b) {
                calls++;
                throw new IllegalStateException("BROWSER_CLOSED — the browser window was closed by the user.");
            }
        };
        Fake google = new Fake();
        try {
            new BrowserEnginesSearchStrategy(
                    selection(EngineAcquisitionMode.ALL_ENABLED, "duckduckgo:on", "google:on"), google, pages)
                    .search(REQUEST, NOT_CANCELLED, BUDGET);
            org.junit.Assert.fail("the close must reach the caller");
        } catch (Exception closed) {
            assertTrue(closed.getMessage().contains("BROWSER_CLOSED"));
        }
        assertEquals(0, google.calls);
    }

    @Test
    public void allEnabledMergesWithoutDuplicates() throws Exception {
        Fake google = new Fake("https://a.example/", "https://same.example/");
        Fake pages = new Fake("https://same.example/", "https://c.example/");
        InitialSearchResult result = new BrowserEnginesSearchStrategy(
                selection(EngineAcquisitionMode.ALL_ENABLED, "duckduckgo:on", "google:on"), google, pages)
                .search(REQUEST, NOT_CANCELLED, BUDGET);
        assertEquals(3, result.candidates.size());
        assertEquals("the user's order: page engines first", "https://same.example/",
                result.candidates.get(0).resolvedTargetUrl);
    }

    @Test
    public void disabledGoogleNeverRunsAndGoogleAloneIsGoogleOnly() throws Exception {
        Fake google = new Fake("https://a.example/");
        Fake pages = new Fake("https://b.example/");
        BrowserEnginesSearchStrategy noGoogle = new BrowserEnginesSearchStrategy(
                selection(EngineAcquisitionMode.FIRST_USABLE, "google:off", "duckduckgo:on"), google, pages);
        noGoogle.search(REQUEST, NOT_CANCELLED, BUDGET);
        assertEquals(0, google.calls);
        assertFalse(noGoogle.isGoogleOnly());
        assertTrue(new BrowserEnginesSearchStrategy(
                selection(EngineAcquisitionMode.FIRST_USABLE, "google:on", "bing:off"), google, pages).isGoogleOnly());
    }

    private static BrowserSearchEngineSelection selection(EngineAcquisitionMode mode, String... entries) {
        List<BrowserSearchEngineSelection.Entry> list = new ArrayList<BrowserSearchEngineSelection.Entry>();
        for (String entry : entries) {
            String[] parts = entry.split(":");
            list.add(new BrowserSearchEngineSelection.Entry(parts[0], "on".equals(parts[1])));
        }
        assertTrue(BrowserSearchEngineCatalog.byId("google") != null);
        return new BrowserSearchEngineSelection(list, mode);
    }

    private static class Fake implements SearchStrategy {
        private final List<String> urls;
        int calls;

        Fake(String... urls) {
            this.urls = Arrays.asList(urls);
        }

        @Override
        public InitialSearchResult search(InitialSearchRequest request, CancellationSignal cancellation,
                                          SearchBudgetGate budget) {
            calls++;
            List<SearchResultCandidate> candidates = new ArrayList<SearchResultCandidate>();
            int rank = 1;
            for (String url : urls) {
                candidates.add(new SearchResultCandidate("c" + rank, "s", url, url, "t", "", "", rank++, "", "",
                        1.0, 1.0, Collections.emptyList()));
            }
            return new InitialSearchResult(candidates, Collections.<String>emptyList(),
                    Collections.<SearchChallengeState>emptyList(), Collections.<String>emptyList());
        }
    }
}
