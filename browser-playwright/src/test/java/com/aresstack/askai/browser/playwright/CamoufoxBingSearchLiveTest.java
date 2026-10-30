package com.aresstack.askai.browser.playwright;

import com.aresstack.askai.browser.domain.PublicSuffixDomainKeyResolver;
import com.aresstack.askai.browser.render.RenderedContainerDescriptor;
import com.aresstack.askai.browser.render.RenderedPageDocument;
import com.aresstack.askai.browser.search.LegacyBrowserSearchDefaults;
import com.aresstack.askai.browser.search.LegacyBrowserSearchSettings;
import com.aresstack.askai.browser.search.SearchPageAnalysisOutcome;
import com.aresstack.askai.browser.search.SearchResultCandidate;
import com.aresstack.askai.browser.search.SearchResultExtractionResult;
import com.aresstack.askai.browser.search.analysis.LegacySearchResultExtractor;
import org.junit.After;
import org.junit.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * Issue #22 against the REAL Bing in a real Camoufox: the capture must see the organic result blocks as visible
 * (Bing first keeps {@code #b_content} at {@code visibility:hidden}) and the mechanical extraction must take them
 * over as structured candidates with external targets — without any AI repair.
 *
 * <p>Opt-in, because it needs the network and the Camoufox download: {@code ASKAI_LIVE_BING=1 gradlew
 * :browser-playwright:test --tests '*CamoufoxBingSearchLiveTest'}; {@code ASKAI_LIVE_BING_QUERY} changes the query,
 * {@code ASKAI_LIVE_CAMOUFOX_HEADED=1} shows the browser, {@code -Daskai.serp.snapshotDir=...} dumps the page.</p>
 */
public class CamoufoxBingSearchLiveTest {

    private Playwright4jDriver driver;

    @After
    public void stop() {
        if (driver != null) {
            driver.close();
        }
    }

    /** Why the first result block is hidden: every ancestor that is not plainly visible, with its cause. */
    private String probe() throws Exception {
        java.lang.reflect.Field field = Playwright4jDriver.class.getDeclaredField("page");
        field.setAccessible(true);
        com.microsoft.playwright.Page page = (com.microsoft.playwright.Page) field.get(driver);
        return String.valueOf(page.evaluate(PROBE));
    }

    private static final String PROBE = "() => {\n"
            + "  const first = document.querySelector('li.b_algo');\n"
            + "  if (!first) return 'no li.b_algo';\n"
            + "  const out = [];\n"
            + "  for (let el = first; el && el !== document.documentElement; el = el.parentElement) {\n"
            + "    const st = getComputedStyle(el);\n"
            + "    out.push(el.tagName + (el.id ? '#' + el.id : '') + '[vis=' + st.visibility + ' op=' + st.opacity\n"
            + "      + ' disp=' + st.display + ' style=' + (el.getAttribute('style') || '') + ']');\n"
            + "  }\n"
            + "  const r = first.getBoundingClientRect();\n"
            + "  return 'box=' + Math.round(r.top) + '/' + Math.round(r.height) + ' checkVisibility='\n"
            + "    + (first.checkVisibility ? first.checkVisibility({visibilityProperty: true}) : 'n/a')\n"
            + "    + ' readyState=' + document.readyState + ' sheets=' + document.styleSheets.length\n"
            + "    + ' chain=' + out.join(' < ');\n"
            + "}";

    @Test
    public void bingResultsAreCapturedVisibleAndExtracted() throws Exception {
        assumeTrue("set ASKAI_LIVE_BING=1 to search the real Bing", "1".equals(System.getenv("ASKAI_LIVE_BING")));
        String query = System.getenv().getOrDefault("ASKAI_LIVE_BING_QUERY", "Kaninchen Ernährung Futter");
        LegacyBrowserSearchSettings settings = LegacyBrowserSearchDefaults.create();
        boolean headless = !"1".equals(System.getenv("ASKAI_LIVE_CAMOUFOX_HEADED"));
        driver = Playwright4jDriver.launch(CamoufoxProvisioning.CHANNEL, headless, 60_000, null, null,
                settings.consent, settings.captcha);
        driver.setRenderedCapture(new RenderedPageDocumentCapture(settings.analysis, settings.readiness));

        driver.open("https://www.bing.com/search?q=" + URLEncoder.encode(query, "UTF-8"));
        RenderedPageDocument document = driver.captureRenderedPage(new PublicSuffixDomainKeyResolver(), 1L);
        System.err.println("[bing-live] at capture: " + probe());

        int blocks = 0;
        int visibleBlocks = 0;
        for (RenderedContainerDescriptor container : document.containers) {
            if (container.classTokens.contains("b_algo")) {
                blocks++;
                if (container.visible) {
                    visibleBlocks++;
                }
            }
        }
        SearchResultExtractionResult extraction =
                new LegacySearchResultExtractor(settings).extract(document, query);
        int structured = 0;
        for (SearchResultCandidate candidate : extraction.candidates) {
            System.err.println("[bing-live] " + candidate.originalRank + " " + candidate.resolvedTargetUrl
                    + (candidate.resultBlockContainerId.isEmpty() ? " (harvested)" : " " + candidate.title));
            if (!candidate.resultBlockContainerId.isEmpty()) {
                structured++;
            }
        }
        System.err.println("[bing-live] page=" + document.pageUrl + " warnings=" + document.captureWarnings);
        System.err.println("[bing-live] b_algo=" + blocks + " visible=" + visibleBlocks + " outcome="
                + extraction.outcome + " candidates=" + extraction.candidates.size() + " structured=" + structured);

        driver.pumpEvents(() -> false, 3000L);
        System.err.println("[bing-live] 3 s later: " + probe());

        assertTrue("Bing delivered result blocks (consent or challenge page?): " + blocks, blocks >= 5);
        assertEquals("every Bing result block is captured visible", blocks, visibleBlocks);
        assertEquals(extraction.diagnostics.toString(), SearchPageAnalysisOutcome.ORGANIC_RESULTS,
                extraction.outcome);
        assertTrue("the result blocks are taken over as structured candidates: " + structured,
                structured >= blocks - 1);
    }
}
