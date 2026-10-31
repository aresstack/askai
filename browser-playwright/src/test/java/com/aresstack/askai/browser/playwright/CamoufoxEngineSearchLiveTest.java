package com.aresstack.askai.browser.playwright;

import com.aresstack.askai.browser.BrowserLimits;
import com.aresstack.askai.browser.BrowserSession;
import com.aresstack.askai.browser.LegacySearchEngineAttemptResult;
import com.aresstack.askai.browser.WebSearchItem;
import com.aresstack.askai.browser.WebSearchResult;
import com.aresstack.askai.browser.search.LegacyBrowserSearchDefaults;
import com.aresstack.askai.browser.search.LegacyBrowserSearchSettings;
import org.junit.After;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assume.assumeTrue;

/**
 * Issue #22 live acceptance per engine, through the product path ({@link PlaywrightSessionFactory}, real Camoufox,
 * the shipped settings, real typing into the engine's start page): runs {@code search(query, engines)} and prints
 * every item and every engine attempt. With {@code ASKAI_SERP_SNAPSHOT_DIR} set, the driver writes each result page
 * as {@code <snapshot>.html} (scripts removed) and {@code <snapshot>.capture.json} (the rendered capture the
 * extraction works on), so a failing engine can be replayed offline.
 *
 * <p>Opt-in: {@code ASKAI_LIVE_ENGINES=google} (comma-separated catalog ids), optional {@code ASKAI_LIVE_QUERY}
 * (default "Java 21 LTS release") and {@code ASKAI_LIVE_CAMOUFOX_HEADED=1}. Diagnostic output only, no assertion
 * on the engine's answer: what an engine serves changes from day to day.</p>
 */
public class CamoufoxEngineSearchLiveTest {

    private BrowserSession session;

    @After
    public void stop() {
        if (session != null) {
            session.close();
        }
    }

    @Test
    public void searchesWithTheNamedEnginesAndReportsEveryAttempt() throws Exception {
        String engines = System.getenv("ASKAI_LIVE_ENGINES");
        assumeTrue("set ASKAI_LIVE_ENGINES=<ids> to search real engines", engines != null && !engines.isEmpty());
        String query = System.getenv().getOrDefault("ASKAI_LIVE_QUERY", "Java 21 LTS release");
        LegacyBrowserSearchSettings settings = LegacyBrowserSearchDefaults.create();
        boolean headless = !"1".equals(System.getenv("ASKAI_LIVE_CAMOUFOX_HEADED"));
        session = PlaywrightSessionFactory.create(CamoufoxProvisioning.CHANNEL, headless, false, null,
                BrowserLimits.defaults(), settings);
        List<String> ids = Arrays.asList(engines.split("\\s*,\\s*"));
        long started = System.currentTimeMillis();
        WebSearchResult result = session.search(query, ids);
        System.err.println("[engine-live] engines=" + ids + " query=" + query + " items="
                + result.getItems().size() + " in " + (System.currentTimeMillis() - started) + " ms hosts="
                + result.getProviderHosts());
        for (LegacySearchEngineAttemptResult attempt : result.getAttempts()) {
            System.err.println("[engine-live] attempt " + attempt);
        }
        for (WebSearchItem item : result.getItems()) {
            System.err.println("[engine-live] item " + item.getUrl() + " | " + item.getTitle());
        }
    }
}
