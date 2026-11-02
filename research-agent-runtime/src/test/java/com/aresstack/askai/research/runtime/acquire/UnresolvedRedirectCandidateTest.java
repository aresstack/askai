package com.aresstack.askai.research.runtime.acquire;

import com.aresstack.askai.browser.search.SearchResultCandidate;
import com.aresstack.askai.browser.search.SearchResultSiteLink;
import com.aresstack.askai.browser.search.UrlResolutionStatus;
import com.aresstack.askai.browser.search.inference.CancellationSignal;
import com.aresstack.askai.research.runtime.loop.ResearchLoopClock;
import com.aresstack.askai.research.runtime.loop.ResearchLoopListener;
import com.aresstack.askai.research.runtime.loop.ResearchRunActivity;
import com.aresstack.askai.research.runtime.loop.ResearchRunBudget;
import com.aresstack.askai.research.runtime.loop.ResearchRunProgress;
import com.aresstack.askai.research.runtime.loop.ResearchStopReason;
import com.aresstack.askai.research.runtime.loop.ToolInvoker;
import com.aresstack.askai.research.runtime.rerank.EngineOrderReranker;
import com.aresstack.askai.research.runtime.search.InitialSearchRequest;
import com.aresstack.askai.research.runtime.search.InitialSearchResult;
import com.aresstack.askai.research.runtime.search.SearchBudgetGate;
import com.aresstack.askai.research.runtime.search.SearchStrategy;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * askai #41, lazy resolution (Angelo 02.11.): an unresolved Google {@code /goto} hit stays in the research pipeline.
 * It reaches the frontier under its provider URL, is never parked under the redirect, and is resolved only when the
 * browser actually opens it: the browser follows the redirect in its normal navigation, the final URL becomes the
 * source identity and the {@code /goto} link stays as provenance. Nothing resolves it before that.
 */
public class UnresolvedRedirectCandidateTest {

    private static final String GOTO = "https://www.google.com/goto?url=CAESAAAA";
    private static final String TARGET = "https://target.example/freertos-scheduling";
    private static final String DIRECT = "https://direct.example/freertos-tasks";

    private static final class RecordingBrowser implements ToolInvoker {
        final List<String> openedUrls = new ArrayList<String>();
        final Map<String, String> finalUrls = new HashMap<String, String>();
        final Map<String, String> failures = new HashMap<String, String>();

        public String call(String tool, Map<String, Object> args) throws ToolFailure {
            if ("web_open".equals(tool)) {
                String url = String.valueOf(args.get("url"));
                openedUrls.add(url);
                if (failures.containsKey(url)) {
                    throw new ToolFailure(failures.get(url));
                }
                String landed = finalUrls.containsKey(url) ? finalUrls.get(url) : url;
                return "URL: " + landed + " capture_id=c" + openedUrls.size()
                        + " title=\"FreeRTOS scheduling\"\nfreertos scheduling tasks priorities preemption";
            }
            return "";
        }
    }

    private static final class RecordingAcceptance implements SourceAcceptancePort {
        final List<String> parked = new ArrayList<String>();
        final List<String> accepted = new ArrayList<String>();

        public String accept(String captureId) {
            accepted.add(captureId);
            return "accepted source_id=s-" + captureId;
        }

        public void park(String url, String title, String excerpt, double score) {
            parked.add(url);
        }
    }

    private static SearchResultCandidate gotoHit(String gotoUrl, int rank) {
        return new SearchResultCandidate("google#" + rank, "", "", gotoUrl,
                "FreeRTOS Scheduling " + rank, "freertos scheduling", "", rank, "", "", 1.0, 1.0,
                Collections.<SearchResultSiteLink>emptyList(), "", UrlResolutionStatus.UNRESOLVED, gotoUrl);
    }

    private static SearchStrategy strategy() {
        return strategy(Arrays.asList(gotoHit(GOTO, 1), new SearchResultCandidate("google#2", "", DIRECT, DIRECT,
                "FreeRTOS Tasks", "freertos tasks", "direct.example", 2, "", "", 1.0, 1.0,
                Collections.<SearchResultSiteLink>emptyList())));
    }

    private static SearchStrategy strategy(final List<SearchResultCandidate> hits) {
        return new SearchStrategy() {
            public InitialSearchResult search(InitialSearchRequest request, CancellationSignal cancellation,
                                              SearchBudgetGate budget) {
                return new InitialSearchResult(hits, new ArrayList<String>(),
                        Collections.<com.aresstack.askai.browser.search.repair.SearchChallengeState>emptyList(),
                        new ArrayList<String>());
            }
        };
    }

    private static WebSearchApplicationService service(RecordingBrowser browser, RecordingAcceptance acceptance,
                                                       final List<String> status) {
        return service(browser, acceptance, status, strategy(),
                new ArrayList<WebSearchApplicationService.AcceptedSource>());
    }

    private static WebSearchApplicationService service(RecordingBrowser browser, RecordingAcceptance acceptance,
                                                       final List<String> status, SearchStrategy strategy,
                                                       final List<WebSearchApplicationService.AcceptedSource> sources) {
        return new WebSearchApplicationService(browser, ResearchRunBudget.defaults(), new ResearchRunProgress(),
                new ResearchLoopClock() {
                    long now;

                    public long currentTimeMillis() {
                        return now;
                    }

                    public void sleepMillis(long millis) {
                        now += Math.max(1L, millis);
                    }
                },
                new ResearchLoopListener() {
                    public void status(String message) {
                        status.add(message);
                    }

                    public void progress(ResearchRunProgress p, ResearchRunActivity activity) {
                    }

                    public void phaseReady(ResearchStopReason reason) {
                    }

                    public void attention(String reason, String family, String url, boolean resolved) {
                    }
                },
                new AtomicBoolean(false), strategy, null, new EngineOrderReranker(),
                new com.aresstack.askai.browser.domain.PublicSuffixDomainKeyResolver(),
                acceptance, 0L, 1L,
                new WebSearchApplicationService.AcceptedSourceListener() {
                    public ResearchStopReason onAccepted(WebSearchApplicationService.AcceptedSource source,
                                                         WebSearchApplicationService.ToolBudget budget) {
                        sources.add(source);
                        return null;
                    }
                },
                false, 0 /* readiness loop off: web_open verbatim */, 48);
    }

    @Test
    public void aSelectedGotoHitIsResolvedOnlyByTheBrowserNavigationAndKeepsItsProvenance() {
        RecordingBrowser browser = new RecordingBrowser();
        browser.finalUrls.put(GOTO, TARGET); // the browser follows Google's redirect
        RecordingAcceptance acceptance = new RecordingAcceptance();
        List<String> status = new ArrayList<String>();
        List<WebSearchApplicationService.AcceptedSource> sources =
                new ArrayList<WebSearchApplicationService.AcceptedSource>();

        service(browser, acceptance, status, strategy(), sources).execute("freertos scheduling");

        assertTrue("the hit was not dropped: the browser navigated its /goto link", browser.openedUrls.contains(GOTO));
        assertFalse("nothing resolved it before: the target itself was never requested",
                browser.openedUrls.contains(TARGET));
        assertFalse("never parked under the redirect (no verified target yet)", acceptance.parked.contains(GOTO));
        assertTrue("a verified hit is still parked", acceptance.parked.contains(DIRECT));
        assertEquals("the landing page was judged like any other page", 2, acceptance.accepted.size());
        WebSearchApplicationService.AcceptedSource viaGoto = sourceAt(sources, TARGET);
        assertEquals("the final URL is the source identity", TARGET, viaGoto.getPageUrl());
        assertEquals("the /goto link stays as provenance", GOTO, viaGoto.getOriginalUrl());
        assertEquals(UrlResolutionStatus.RESOLVED, viaGoto.getUrlResolution());
        WebSearchApplicationService.AcceptedSource direct = sourceAt(sources, DIRECT);
        assertEquals(UrlResolutionStatus.DIRECT, direct.getUrlResolution());
        assertEquals("", direct.getOriginalUrl());
        assertTrue(status.toString(), status.toString().contains("redirect resolved by navigation: " + GOTO
                + " -> " + TARGET));
    }

    @Test
    public void aFailedNavigationEndsOnlyThatVisitAndKeepsTheHit() {
        RecordingBrowser browser = new RecordingBrowser();
        browser.failures.put(GOTO, "navigation timeout");
        RecordingAcceptance acceptance = new RecordingAcceptance();
        List<String> status = new ArrayList<String>();

        service(browser, acceptance, status).execute("freertos scheduling");

        assertTrue(browser.openedUrls.contains(GOTO));
        assertTrue("the run went on with the other hit", browser.openedUrls.contains(DIRECT));
        assertEquals(1, acceptance.accepted.size());
        assertTrue(status.toString(), status.toString().contains("url=" + GOTO + " (search hit kept)"));
    }

    @Test
    public void aRedirectToAForbiddenTargetIsNeverTakenOver() {
        // The browser's post-redirect URL policy (PlaywrightBrowserSession.checkedSnapshot) refuses the final URL.
        RecordingBrowser browser = new RecordingBrowser();
        browser.failures.put(GOTO, "Blocked after redirect — final URL is not allowed: private network");
        RecordingAcceptance acceptance = new RecordingAcceptance();
        List<String> status = new ArrayList<String>();
        List<WebSearchApplicationService.AcceptedSource> sources =
                new ArrayList<WebSearchApplicationService.AcceptedSource>();

        service(browser, acceptance, status, strategy(), sources).execute("freertos scheduling");

        assertEquals("only the direct hit became a source", 1, sources.size());
        assertEquals(DIRECT, sources.get(0).getPageUrl());
        assertTrue(status.toString(), status.toString().contains("Blocked after redirect"));
    }

    @Test
    public void twoGotoHitsLandingOnTheSameTargetAreDedupedOnTheFinalUrl() {
        String otherGoto = "https://www.google.com/goto?url=CAESBBBB";
        RecordingBrowser browser = new RecordingBrowser();
        browser.finalUrls.put(GOTO, TARGET);
        browser.finalUrls.put(otherGoto, TARGET);
        RecordingAcceptance acceptance = new RecordingAcceptance();
        List<String> status = new ArrayList<String>();

        service(browser, acceptance, status, strategy(Arrays.asList(gotoHit(GOTO, 1), gotoHit(otherGoto, 2))),
                new ArrayList<WebSearchApplicationService.AcceptedSource>()).execute("freertos scheduling");

        assertTrue(browser.openedUrls.containsAll(Arrays.asList(GOTO, otherGoto)));
        assertEquals("one source for one target", 1, acceptance.accepted.size());
        assertTrue(status.toString(), status.toString().contains("skipped duplicate target: " + TARGET));
    }

    private static WebSearchApplicationService.AcceptedSource sourceAt(
            List<WebSearchApplicationService.AcceptedSource> sources, String url) {
        for (WebSearchApplicationService.AcceptedSource source : sources) {
            if (url.equals(source.getPageUrl())) {
                return source;
            }
        }
        throw new AssertionError("no source " + url + " in " + sources.size());
    }

    @Test
    public void aRedirectTheBrowserCannotLeaveIsTransitAndNeverASource() {
        RecordingBrowser browser = new RecordingBrowser(); // final_url stays the google.com redirect
        RecordingAcceptance acceptance = new RecordingAcceptance();
        List<String> status = new ArrayList<String>();

        service(browser, acceptance, status).execute("freertos scheduling");

        assertTrue(browser.openedUrls.contains(GOTO));
        assertEquals("only the direct hit became a source", 1, acceptance.accepted.size());
        assertTrue(status.toString(), status.toString().contains("skipped search-provider page: " + GOTO));
    }
}
