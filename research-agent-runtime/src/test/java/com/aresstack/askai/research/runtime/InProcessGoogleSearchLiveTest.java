package com.aresstack.askai.research.runtime;

import com.aresstack.askai.browser.search.SearchResultCandidate;
import com.aresstack.askai.browser.search.inference.CancellationSignal;
import com.aresstack.askai.research.runtime.search.GoogleSearchBrowserStrategy;
import com.aresstack.askai.research.runtime.search.InitialSearchRequest;
import com.aresstack.askai.research.runtime.search.InitialSearchResult;
import com.aresstack.askai.research.runtime.search.SearchBudgetGate;
import com.aresstack.askai.research.runtime.search.SearchStrategy;
import org.junit.Test;

import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assume.assumeTrue;

/**
 * askai #48 live check: the research runtime's shared search browser runs in THIS JVM, a real Google search
 * goes through Serp4J ({@code serp4j-playwright-google}) on it, and a second search reuses the same browser.
 * Opt-in only ({@code -Daskai.live.google=camoufox}); it never runs in CI.
 */
public class InProcessGoogleSearchLiveTest {

    private static final SearchBudgetGate ALLOW = new SearchBudgetGate() {
        public boolean beforeToolCall() {
            return true;
        }
    };

    @Test
    public void realGoogleSearchRunsOnTheSharedSearchBrowserInThisJvm() throws Exception {
        String channel = System.getProperty("askai.live.google", "");
        assumeTrue("SKIPPED (opt-in live test): -Daskai.live.google=<channel>", !channel.isEmpty());
        ResearchRuntimeShared shared = new ResearchRuntimeShared(channel);
        try {
            SearchStrategy strategy = GoogleSearchBrowserStrategy.create(shared.searchBrowser(), Locale.GERMANY);
            for (String query : new String[]{"pf4j plugin framework", "playwright java"}) {
                InitialSearchResult result = strategy.search(new InitialSearchRequest(query, 10, "de", "de"),
                        CancellationSignal.NONE, ALLOW);
                System.out.println("[live] query=" + query + " status=" + result.status
                        + " candidates=" + result.candidates.size() + " diagnostics=" + result.diagnostics);
                for (SearchResultCandidate c : result.candidates) {
                    System.out.println("[live]   #" + c.originalRank + " " + c.resolvedTargetUrl + " | " + c.title);
                }
                assertFalse("real Google hits for " + query, result.candidates.isEmpty());
            }
            assertEquals("one search browser for both searches", 1, shared.searchBrowser().getLaunchCount());
        } finally {
            shared.close();
        }
    }
}
