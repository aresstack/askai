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
import com.google.gson.Gson;
import org.junit.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Issue #22, from the real Bing page of Angelo's live run ("kaninchen ernährung futter", 2026-10-30).
 * <p>
 * {@code camoufox-capture.json} is what Camoufox captured: ten {@code li.b_algo} blocks under one
 * {@code ol}, all {@code visible=false} although the first three sit fully in the viewport. The saved
 * page shows why: {@code <div id="b_content" style="visibility: hidden;">} — Bing had not revealed its
 * content yet. The first block's signature was {@code li(link,link,...)}: Bing puts its deferred
 * stylesheet {@code <link>}s into it. The two chromium captures are the CURRENT capture script run on
 * that saved page ({@code page.html}) with {@code #b_content} still hidden and after Bing's reveal.
 */
public class BingHiddenContentRegressionTest {

    private static final String DIR = "/serp/bing-kaninchen-2026-10-30/";
    private static final String QUERY = "kaninchen ernährung futter";
    private static final String URL = "https://www.bing.com/search?q=kaninchen+ern%C3%A4hrung+futter";

    private final LegacyBrowserSearchSettings settings = LegacyBrowserSearchDefaults.create();

    @Test
    public void theLiveCaptureHadEveryResultBlockHiddenInsideTheViewport() throws Exception {
        RenderedPageDocument live = new Gson().fromJson(read("camoufox-capture.json"),
                RenderedPageDocument.class);
        List<RenderedContainerDescriptor> blocks = resultBlocks(live);
        assertEquals(10, blocks.size());
        assertEquals("container-0020", blocks.get(0).containerId);
        assertEquals(1.0, blocks.get(0).viewportIntersectionRatio, 0.0);
        for (RenderedContainerDescriptor block : blocks) {
            assertFalse(block.containerId + " was captured invisible", block.visible);
        }
        assertTrue("the first block's signature was polluted by stylesheet links",
                blocks.get(0).structureSignature.value.startsWith("li(link,link"));
        String html = read("page.html");
        assertTrue("the saved page hides its whole content",
                html.contains("<div id=\"b_content\" class=\"\" style=\"visibility: hidden;\">"));
    }

    @Test
    public void capturedWhileBingHidesItsContentNoResultIsReadable() throws Exception {
        RenderedPageDocument hidden = capture("chromium-capture-hidden.json");
        for (RenderedContainerDescriptor block : resultBlocks(hidden)) {
            assertFalse(block.visible);
        }
        SearchResultExtractionResult extraction =
                new LegacySearchResultExtractor(settings).extract(hidden, QUERY);
        assertTrue("no result from a hidden page: " + extraction.candidates.size(),
                extraction.candidates.isEmpty());
    }

    @Test
    public void afterTheRevealAllTenBingResultsAreExtracted() throws Exception {
        RenderedPageDocument revealed = capture("chromium-capture-revealed.json");
        List<RenderedContainerDescriptor> blocks = resultBlocks(revealed);
        assertEquals(10, blocks.size());
        for (RenderedContainerDescriptor block : blocks) {
            assertTrue(block.containerId + " visible", block.visible);
            assertFalse("no stylesheet link in a signature: " + block.structureSignature.value,
                    block.structureSignature.value.contains("link"));
        }

        SearchResultExtractionResult extraction =
                new LegacySearchResultExtractor(settings).extract(revealed, QUERY);

        assertEquals(extraction.diagnostics.toString(),
                SearchPageAnalysisOutcome.ORGANIC_RESULTS, extraction.outcome);
        List<String> targets = new ArrayList<String>();
        for (SearchResultCandidate candidate : extraction.candidates) {
            if (!candidate.resultBlockContainerId.isEmpty()) {
                targets.add(candidate.resolvedTargetUrl);
            }
        }
        assertEquals("all ten result blocks are structured candidates: " + targets, 10,
                targets.size());
        assertTrue(targets.toString(), targets.containsAll(Arrays.asList(
                "https://de.wikipedia.org/wiki/Kaninchen", "https://kaninchenwiese.de/")));
    }

    @Test
    public void theCaptureWaitsUntilBingRevealsItsContent() throws Exception {
        RevealingPage page = new RevealingPage(read("chromium-capture-hidden.json"),
                read("chromium-capture-revealed.json"), 2);
        List<Long> settles = new ArrayList<Long>();
        RenderedPageDocument document = captureWithReadiness(page, settles);

        assertEquals("two polls while hidden", 2, settles.size());
        assertEquals((long) settings.readiness.pollIntervalMillis, (long) settles.get(0));
        assertTrue(document.captureWarnings.toString(), document.captureWarnings.toString()
                .contains("CONTENT_REVEALED: waited " + 2 * settings.readiness.pollIntervalMillis));
        SearchResultExtractionResult extraction =
                new LegacySearchResultExtractor(settings).extract(document, QUERY);
        assertEquals(SearchPageAnalysisOutcome.ORGANIC_RESULTS, extraction.outcome);
        assertTrue(extraction.candidates.size() >= 10);
    }

    @Test
    public void aPageThatNeverRevealsIsCapturedAsHiddenAfterTheDeadline() throws Exception {
        RevealingPage page = new RevealingPage(read("chromium-capture-hidden.json"),
                read("chromium-capture-revealed.json"), Integer.MAX_VALUE);
        List<Long> settles = new ArrayList<Long>();
        RenderedPageDocument document = captureWithReadiness(page, settles);

        long waited = 0;
        for (Long settle : settles) {
            waited += settle;
        }
        assertTrue("bounded by the content-readiness deadline: " + waited,
                waited >= settings.readiness.contentReadinessTimeoutMillis
                        && waited < settings.readiness.contentReadinessTimeoutMillis
                        + settings.readiness.pollIntervalMillis);
        assertTrue(document.captureWarnings.toString(),
                document.captureWarnings.toString().contains("CONTENT_HIDDEN: 85% of the page text"));
        for (RenderedContainerDescriptor block : resultBlocks(document)) {
            assertFalse("never declared visible while hidden", block.visible);
        }
    }

    private RenderedPageDocument captureWithReadiness(RevealingPage page, final List<Long> settles) {
        RenderedPageDocumentCapture capture = new RenderedPageDocumentCapture(settings.analysis,
                settings.readiness, new RenderedPageDocumentCapture.SettleDelay() {
                    public void settle(long millis) {
                        settles.add(millis);
                    }
                });
        return capture.capture(page, new PublicSuffixDomainKeyResolver(), 1L);
    }

    /** Bing's page: hidden (share 0.85, measured on the saved page) for N polls, then revealed. */
    private static final class RevealingPage implements RenderedPageDocumentCapture.PageScriptRunner {
        private final Object hidden;
        private final Object revealed;
        private int hiddenPolls;

        RevealingPage(String hiddenCapture, String revealedCapture, int hiddenPolls) {
            this.hidden = new Gson().fromJson(hiddenCapture, Map.class);
            this.revealed = new Gson().fromJson(revealedCapture, Map.class);
            this.hiddenPolls = hiddenPolls;
        }

        public Object evaluate(String script) {
            if (script.equals(RenderedPageDocumentCapture.FINGERPRINT_SCRIPT)) {
                return "f";
            }
            if (script.equals(RenderedPageDocumentCapture.HIDDEN_SHARE_SCRIPT)) {
                if (hiddenPolls == 0) {
                    return 0.0;
                }
                if (hiddenPolls != Integer.MAX_VALUE) {
                    hiddenPolls--;
                }
                return 0.8457;
            }
            return hiddenPolls == 0 ? revealed : hidden;
        }

        public String url() {
            return URL;
        }

        public String title() {
            return QUERY + " - Suche";
        }
    }

    /** Feeds a raw capture-script result through the real Java side of the capture. */
    private RenderedPageDocument capture(String resource) throws Exception {
        @SuppressWarnings("unchecked")
        final Map<String, Object> raw = new Gson().fromJson(read(resource), Map.class);
        RenderedPageDocumentCapture capture = new RenderedPageDocumentCapture(settings.analysis);
        return capture.capture(new RenderedPageDocumentCapture.PageScriptRunner() {
            public Object evaluate(String script) {
                return script.equals(RenderedPageDocumentCapture.FINGERPRINT_SCRIPT) ? "f" : raw;
            }

            public String url() {
                return URL;
            }

            public String title() {
                return QUERY + " - Suche";
            }
        }, new PublicSuffixDomainKeyResolver(), 1L);
    }

    private static List<RenderedContainerDescriptor> resultBlocks(RenderedPageDocument document) {
        List<RenderedContainerDescriptor> blocks = new ArrayList<RenderedContainerDescriptor>();
        for (RenderedContainerDescriptor container : document.containers) {
            if (container.classTokens.contains("b_algo")) {
                blocks.add(container);
            }
        }
        return blocks;
    }

    private static String read(String name) throws Exception {
        InputStream in = BingHiddenContentRegressionTest.class.getResourceAsStream(DIR + name);
        if (in == null) {
            throw new IllegalStateException("missing test resource " + DIR + name);
        }
        try (InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            StringBuilder sb = new StringBuilder();
            char[] buffer = new char[8192];
            for (int n; (n = reader.read(buffer)) > 0; ) {
                sb.append(buffer, 0, n);
            }
            return sb.toString();
        }
    }
}
