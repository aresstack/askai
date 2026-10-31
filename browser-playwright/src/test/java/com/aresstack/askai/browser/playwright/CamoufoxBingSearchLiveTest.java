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
 * and the mechanical extraction must take them over as structured candidates with external targets — without any
 * AI repair. Bing's own script hides {@code #b_content}; only its bot-detection module reveals it again, which it
 * did for Argos (no page-world scripts). Since #26/#27 AskAI runs no script in the page's world either (reads in the
 * add-on, real input), so this is the acceptance run of issue #22. The test itself stays clean too: its diagnostic
 * probes are main-world scripts, so they only run with {@code ASKAI_LIVE_BING_DIAG=1} and only AFTER the capture.
 *
 * <p>Opt-in, because it needs the network and the Camoufox download: {@code ASKAI_LIVE_BING=1 gradlew
 * :browser-playwright:test --tests '*CamoufoxBingSearchLiveTest'}; {@code ASKAI_LIVE_BING_QUERY} changes the query,
 * {@code ASKAI_LIVE_CAMOUFOX_HEADED=1} shows the browser, {@code ASKAI_LIVE_BING_ADDON=0} starts without the research
 * add-on, {@code ASKAI_LIVE_BING_QUIET_MS=n} reads nothing from the page for n ms after the navigation,
 * {@code ASKAI_LIVE_BING_DIAG=1} prints the page-world probes after the capture, {@code ASKAI_LIVE_BING_SCROLL=n}
 * turns the real mouse wheel n times (300 px each, human pauses) before the capture, to see whether Bing loads more
 * result blocks while scrolling.</p>
 */
public class CamoufoxBingSearchLiveTest {

    private Playwright4jDriver driver;

    @After
    public void stop() {
        System.clearProperty(Playwright4jDriver.ADDON_PROPERTY);
        if (driver != null) {
            driver.close();
        }
    }

    /** Why the first result block is hidden: every ancestor that is not plainly visible, with its cause. */
    private String probe() throws Exception {
        return String.valueOf(page().evaluate(PROBE));
    }

    private com.microsoft.playwright.Page page() throws Exception {
        java.lang.reflect.Field field = Playwright4jDriver.class.getDeclaredField("page");
        field.setAccessible(true);
        return (com.microsoft.playwright.Page) field.get(driver);
    }

    /** What Bing's bot detection (its BD module) decides on: reveal flags, cookies, activation, our traces. */
    private static final String BOT_PROBE = "() => {\n"
            + "  const c = document.getElementById('b_content');\n"
            + "  const cookie = (name) => (document.cookie.split('; ').find((x) => x.startsWith(name + '=')) || '')\n"
            + "    .substring(0, 120);\n"
            + "  const rp = performance.getEntriesByType('resource').filter((e) => /\\/rp\\/.*\\.js/.test(e.name));\n"
            + "  const ua = navigator.userActivation;\n"
            + "  return 'b_content=' + (c ? c.getAttribute('style') : 'none')\n"
            + "    + ' visibleBlocks=' + [...document.querySelectorAll('li.b_algo')]\n"
            + "        .filter((e) => e.checkVisibility({visibilityProperty: true})).length\n"
            + "    + ' webdriver=' + navigator.webdriver + ' BN=' + (typeof _G !== 'undefined' ? _G.BN : 'no _G')\n"
            + "    + ' state=' + document.visibilityState + ' focus=' + document.hasFocus()\n"
            + "    + ' activation=' + (ua ? ua.hasBeenActive + '/' + ua.isActive : 'n/a')\n"
            + "    + ' rpScripts=' + rp.length + ' url=' + location.href\n"
            + "    + ' SS=' + cookie('_SS') + ' SRCHHPGUSR=' + cookie('SRCHHPGUSR')\n"
            + "    + ' askaiGlobals=' + Object.keys(window).filter((k) => /askai|playwright|__pw/i.test(k)).join(',');\n"
            + "}";

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
        if ("0".equals(System.getenv("ASKAI_LIVE_BING_ADDON"))) {
            System.setProperty(Playwright4jDriver.ADDON_PROPERTY, "false");
        }
        long quietMillis = Long.parseLong(System.getenv().getOrDefault("ASKAI_LIVE_BING_QUIET_MS", "0"));
        driver = Playwright4jDriver.launch(CamoufoxProvisioning.CHANNEL, headless, 60_000, null, null,
                settings.consent, settings.captcha);
        driver.setRenderedCapture(new RenderedPageDocumentCapture(settings.analysis));

        driver.open("https://www.bing.com/search?q=" + URLEncoder.encode(query, "UTF-8"));
        if (quietMillis > 0) {
            // No page read at all until then (Argos' rule): only the Playwright message loop runs.
            driver.pumpEvents(() -> false, quietMillis);
        }
        int wheelTurns = Integer.parseInt(System.getenv().getOrDefault("ASKAI_LIVE_BING_SCROLL", "0"));
        if (wheelTurns > 0) {
            // Real wheel input (WebDriver BiDi input actions), no page script: the pointer rests inside the window.
            com.microsoft.playwright.Page target = page();
            target.mouse().move(300, 300);
            for (int turn = 0; turn < wheelTurns; turn++) {
                target.mouse().wheel(0, 300);
                driver.pumpEvents(() -> false, 350L + (turn % 3) * 120L);
            }
            driver.pumpEvents(() -> false, 1500L);
        }
        boolean diagnostics = "1".equals(System.getenv("ASKAI_LIVE_BING_DIAG"));
        System.err.println("[bing-live] addon=" + (driver.addonBridge() != null) + " quietMs=" + quietMillis
                + " wheelTurns=" + wheelTurns + " diag=" + diagnostics);
        RenderedPageDocument document = driver.captureRenderedPage(new PublicSuffixDomainKeyResolver(), 1L);

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

        if (diagnostics) {
            // Main-world scripts: only after everything above was measured, so they cannot influence it.
            System.err.println("[bing-live] after capture: " + probe());
            System.err.println("[bing-live] after capture: " + page().evaluate(BOT_PROBE));
        }

        assertTrue("Bing delivered result blocks (consent or challenge page?): " + blocks, blocks >= 5);
        assertEquals("every Bing result block is captured visible", blocks, visibleBlocks);
        assertEquals(extraction.diagnostics.toString(), SearchPageAnalysisOutcome.ORGANIC_RESULTS,
                extraction.outcome);
        assertTrue("the result blocks are taken over as structured candidates: " + structured,
                structured >= blocks - 1);
    }
}
