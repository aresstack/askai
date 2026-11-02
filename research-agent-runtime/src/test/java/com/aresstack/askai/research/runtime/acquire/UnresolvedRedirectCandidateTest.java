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
 * askai #41 (Angelo 02.11.): an unresolved Google {@code /goto} hit stays in the research pipeline. It reaches the
 * frontier, is resolved again right before its visit when possible, otherwise opened through the browser; it is never
 * parked under the redirect, and only the page the browser lands on can become a source.
 */
public class UnresolvedRedirectCandidateTest {

    private static final String GOTO = "https://www.google.com/goto?url=CAESAAAA";
    private static final String TARGET = "https://target.example/freertos-scheduling";
    private static final String DIRECT = "https://direct.example/freertos-tasks";

    private static final class RecordingBrowser implements ToolInvoker {
        final List<String> openedUrls = new ArrayList<String>();
        final Map<String, String> finalUrls = new HashMap<String, String>();

        public String call(String tool, Map<String, Object> args) {
            if ("web_open".equals(tool)) {
                String url = String.valueOf(args.get("url"));
                openedUrls.add(url);
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
            return "accepted " + captureId;
        }

        public void park(String url, String title, String excerpt, double score) {
            parked.add(url);
        }
    }

    private static SearchStrategy strategy() {
        final SearchResultCandidate unresolved = new SearchResultCandidate("google#1", "", "", GOTO,
                "FreeRTOS Scheduling", "freertos scheduling", "", 1, "", "", 1.0, 1.0,
                Collections.<SearchResultSiteLink>emptyList(), "", UrlResolutionStatus.UNRESOLVED, GOTO);
        final SearchResultCandidate direct = new SearchResultCandidate("google#2", "", DIRECT, DIRECT,
                "FreeRTOS Tasks", "freertos tasks", "direct.example", 2, "", "", 1.0, 1.0,
                Collections.<SearchResultSiteLink>emptyList());
        return new SearchStrategy() {
            public InitialSearchResult search(InitialSearchRequest request, CancellationSignal cancellation,
                                              SearchBudgetGate budget) {
                return new InitialSearchResult(Arrays.asList(unresolved, direct), new ArrayList<String>(),
                        Collections.<com.aresstack.askai.browser.search.repair.SearchChallengeState>emptyList(),
                        new ArrayList<String>());
            }
        };
    }

    private static WebSearchApplicationService service(RecordingBrowser browser, RecordingAcceptance acceptance,
                                                       final List<String> status) {
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
                new AtomicBoolean(false), strategy(), null, new EngineOrderReranker(),
                new com.aresstack.askai.browser.domain.PublicSuffixDomainKeyResolver(),
                acceptance, 0L, 1L,
                new WebSearchApplicationService.AcceptedSourceListener() {
                    public ResearchStopReason onAccepted(WebSearchApplicationService.AcceptedSource source,
                                                         WebSearchApplicationService.ToolBudget budget) {
                        return null;
                    }
                },
                false, 0 /* readiness loop off: web_open verbatim */, 48);
    }

    @Test
    public void anUnresolvedHitIsResolvedAgainBeforeItsVisitAndNeverParkedUnderTheRedirect() {
        RecordingBrowser browser = new RecordingBrowser();
        RecordingAcceptance acceptance = new RecordingAcceptance();
        List<String> status = new ArrayList<String>();
        List<String> asked = new ArrayList<String>();
        WebSearchApplicationService service = service(browser, acceptance, status);
        service.setRedirectResolver(url -> {
            asked.add(url);
            return GOTO.equals(url) ? TARGET : null;
        });

        service.execute("freertos scheduling");

        assertTrue("the unresolved hit reached the frontier and was resolved before its visit", asked.contains(GOTO));
        assertTrue(browser.openedUrls.toString(), browser.openedUrls.contains(TARGET));
        assertFalse("the redirect itself is not opened once resolved", browser.openedUrls.contains(GOTO));
        assertTrue(browser.openedUrls.contains(DIRECT));
        assertFalse("never parked under the redirect", acceptance.parked.contains(GOTO));
        assertTrue("a verified hit is still parked", acceptance.parked.contains(DIRECT));
        assertEquals("both landing pages were read and judged", 2, acceptance.accepted.size());
    }

    @Test
    public void withoutResolutionTheBrowserOpensTheRedirectAndOnlyTheLandingPageCounts() {
        RecordingBrowser browser = new RecordingBrowser();
        browser.finalUrls.put(GOTO, TARGET); // the browser follows the redirect
        RecordingAcceptance acceptance = new RecordingAcceptance();
        List<String> status = new ArrayList<String>();

        service(browser, acceptance, status).execute("freertos scheduling");

        assertTrue("the hit was not dropped: the browser navigated it", browser.openedUrls.contains(GOTO));
        assertFalse(acceptance.parked.contains(GOTO));
        assertEquals("the landing page was judged like any other page", 2, acceptance.accepted.size());
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
