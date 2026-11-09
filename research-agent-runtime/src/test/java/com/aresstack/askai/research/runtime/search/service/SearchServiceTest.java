package com.aresstack.askai.research.runtime.search.service;

import com.aresstack.askai.browser.search.SearchResultCandidate;
import com.aresstack.askai.browser.search.SearchResultSiteLink;
import com.aresstack.askai.browser.search.UrlResolutionStatus;
import com.aresstack.askai.browser.search.inference.CancellationSignal;
import com.aresstack.askai.browser.search.repair.SearchChallengeState;
import com.aresstack.askai.research.runtime.acquire.OpaqueRedirectResolver;
import com.aresstack.askai.research.runtime.search.InitialSearchRequest;
import com.aresstack.askai.research.runtime.search.InitialSearchResult;
import com.aresstack.askai.research.runtime.search.SearchBudgetGate;
import com.aresstack.askai.research.runtime.search.SearchStrategy;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SearchServiceTest {

    /** Virtual time: sleeping advances the clock. */
    static final class VirtualClock implements SearchServiceClock {
        long now = 1_000_000L;
        final List<Long> sleeps = new ArrayList<Long>();

        public synchronized long nowMillis() {
            return now;
        }

        public synchronized void sleep(long millis) {
            sleeps.add(millis);
            now += millis;
        }
    }

    /** A strategy that answers from a script and counts engine requests. */
    static final class ScriptedStrategy implements SearchStrategy {
        final LinkedList<InitialSearchResult> answers = new LinkedList<InitialSearchResult>();
        final AtomicInteger calls = new AtomicInteger();
        final AtomicInteger inside = new AtomicInteger();
        volatile int maxInside;
        volatile long holdMillis;

        public InitialSearchResult search(InitialSearchRequest request, CancellationSignal cancellation,
                                          SearchBudgetGate budget) {
            if (!budget.beforeToolCall()) {
                return results();
            }
            calls.incrementAndGet();
            int now = inside.incrementAndGet();
            maxInside = Math.max(maxInside, now);
            try {
                if (holdMillis > 0) {
                    try {
                        Thread.sleep(holdMillis);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                synchronized (answers) {
                    return answers.isEmpty() ? results(hit("https://example.org/" + calls.get())) : answers.poll();
                }
            } finally {
                inside.decrementAndGet();
            }
        }
    }

    static SearchResultCandidate hit(String url) {
        return new SearchResultCandidate("c-" + url.hashCode(), "s", url, url, "Title " + url, "snippet", "", 1,
                "", "", 1.0, 1.0, Collections.<SearchResultSiteLink>emptyList(), "www.google.com",
                UrlResolutionStatus.DIRECT, "");
    }

    static SearchResultCandidate gotoHit(String wrapper) {
        return new SearchResultCandidate("g-" + wrapper.hashCode(), "s", "", wrapper, "Wrapped", "snippet", "", 1,
                "", "", 1.0, 1.0, Collections.<SearchResultSiteLink>emptyList(), "www.google.com",
                UrlResolutionStatus.UNRESOLVED, wrapper);
    }

    static InitialSearchResult results(SearchResultCandidate... hits) {
        return new InitialSearchResult(Arrays.asList(hits), Collections.<String>emptyList(),
                Collections.<SearchChallengeState>emptyList(), Collections.<String>emptyList());
    }

    static InitialSearchResult challenge() {
        return new InitialSearchResult(Collections.<SearchResultCandidate>emptyList(),
                Collections.<String>emptyList(),
                Collections.singletonList(new SearchChallengeState("google.com", "https://www.google.com/sorry/")),
                Collections.<String>emptyList());
    }

    final VirtualClock clock = new VirtualClock();
    final ScriptedStrategy strategy = new ScriptedStrategy();
    final InMemoryUrlRegistry registry = new InMemoryUrlRegistry();
    final PageCandidateQueue queue = new PageCandidateQueue();

    SearchService service(OpaqueRedirectResolver resolver) {
        return new SearchService(strategy, resolver, new EngineThrottle(4_000L, 60_000L, 600_000L, clock), clock);
    }

    SearchJob job(String project, String query) {
        return SearchJob.builder(project, query).registry(registry).queue(queue).build();
    }

    @Test
    public void sameQueryTwiceMakesOneEngineRequest() throws Exception {
        SearchService service = service(null);
        strategy.answers.add(results(hit("https://a.org/x"), hit("https://b.org/y")));

        SearchServiceOutcome first = service.search(job("p1", "Wearables  Baustelle"), null);
        SearchServiceOutcome second = service.search(job("p1", "wearables baustelle"), null);

        assertEquals(SearchServiceOutcome.Status.QUEUED, first.getStatus());
        assertEquals(2, first.getQueued().size());
        assertEquals(SearchServiceOutcome.Status.DUPLICATE_QUERY, second.getStatus());
        assertEquals(1, strategy.calls.get());
        assertEquals(2, queue.size());
    }

    @Test
    public void queryDedupIsPerProject() throws Exception {
        SearchService service = service(null);
        service.search(job("p1", "q"), null);
        service.search(SearchJob.builder("p2", "q").registry(new InMemoryUrlRegistry()).queue(queue).build(), null);
        assertEquals(2, strategy.calls.get());
    }

    @Test
    public void urlVisitedByChatBrowserIsNotQueued() throws Exception {
        registry.register("https://a.org/x", "chat-browser");
        strategy.answers.add(results(hit("https://a.org/x/#top"), hit("https://b.org/y?utm_source=g")));

        SearchServiceOutcome outcome = service(null).search(job("p1", "q"), null);

        assertEquals(1, outcome.getQueued().size());
        assertEquals("https://b.org/y", outcome.getQueued().get(0).getUrl());
        assertEquals(1, outcome.getAlreadyKnown());
        assertEquals("https://b.org/y", queue.poll().getUrl());
        assertEquals(null, queue.poll());
    }

    @Test
    public void wrappersAreQueuedUnresolvedAndNoHopRunsWithTheSearch() throws Exception {
        final List<String> hops = new ArrayList<String>();
        OpaqueRedirectResolver resolver = new OpaqueRedirectResolver() {
            public Resolution resolve(String wrapperUrl) {
                hops.add(wrapperUrl);
                return Resolution.resolved("https://study.org/paper");
            }
        };
        strategy.answers.add(results(gotoHit("https://www.google.com/goto?url=abc"),
                gotoHit("https://www.google.com/goto?url=abc"), hit("https://direct.org/a")));

        SearchServiceOutcome outcome = service(resolver).search(job("p1", "q"), null);

        assertTrue("no hop at search time", hops.isEmpty());
        assertEquals(2, outcome.getQueued().size());
        assertTrue(outcome.getQueued().get(0).isWrapped());
        assertFalse(outcome.getQueued().get(1).isWrapped());
        assertEquals(1, outcome.getDropped());
    }

    @Test
    public void wrapperIsResolvedLazilyOneAtATimeWithSpacing() throws Exception {
        final List<String> hops = new ArrayList<String>();
        OpaqueRedirectResolver resolver = new OpaqueRedirectResolver() {
            public Resolution resolve(String wrapperUrl) {
                hops.add(wrapperUrl);
                return wrapperUrl.endsWith("loop") ? Resolution.resolved("https://www.google.com/search?q=x")
                        : Resolution.resolved("https://study.org/paper?utm_source=g");
            }
        };
        strategy.answers.add(results(gotoHit("https://www.google.com/goto?url=1"),
                gotoHit("https://www.google.com/goto?url=2"), gotoHit("https://www.google.com/goto?url=loop")));
        SearchService service = service(resolver);
        service.search(job("p1", "q"), null);

        PageResolution first = service.resolveForVisit(queue.poll(), registry);
        assertEquals(PageResolution.Status.READY, first.getStatus());
        assertEquals("https://study.org/paper", first.getPage().getUrl());
        assertFalse(first.getPage().isWrapped());
        assertEquals(1, hops.size());
        assertEquals(PageResolution.Status.ALREADY_KNOWN, service.resolveForVisit(queue.poll(), registry).getStatus());
        assertEquals(PageResolution.Status.UNRESOLVED, service.resolveForVisit(queue.poll(), registry).getStatus());
        assertEquals(Arrays.asList(4_000L, 4_000L, 4_000L), clock.sleeps);
    }

    @Test
    public void blockedHopPausesTheEnginesAndThePageCanGoBack() throws Exception {
        OpaqueRedirectResolver resolver = new OpaqueRedirectResolver() {
            public Resolution resolve(String wrapperUrl) {
                return Resolution.blocked("sorry page");
            }
        };
        strategy.answers.add(results(gotoHit("https://www.google.com/goto?url=1"),
                gotoHit("https://www.google.com/goto?url=2")));
        SearchService service = service(resolver);
        service.search(job("p1", "q"), null);

        PageResolution blocked = service.resolveForVisit(queue.poll(), registry);
        assertEquals(PageResolution.Status.ENGINE_BLOCKED, blocked.getStatus());
        assertTrue(blocked.getDetail().contains("redirect hop"));
        assertEquals(PageResolution.Status.ENGINE_PAUSED, service.resolveForVisit(queue.poll(), registry).getStatus());
        assertEquals(SearchServiceOutcome.Status.ENGINE_PAUSED, service.search(job("p1", "other"), null).getStatus());
    }

    @Test
    public void challengeBacksOffExponentiallyAndReportsPauseNotNoResults() throws Exception {
        SearchService service = service(null);
        strategy.answers.add(challenge());
        strategy.answers.add(challenge());
        strategy.answers.add(challenge());

        SearchServiceOutcome blocked = service.search(job("p1", "q1"), null);
        assertEquals(SearchServiceOutcome.Status.ENGINE_BLOCKED, blocked.getStatus());
        assertEquals(clock.now + 60_000L, blocked.getPausedUntil());

        SearchServiceOutcome paused = service.search(job("p1", "q1"), null);
        assertEquals(SearchServiceOutcome.Status.ENGINE_PAUSED, paused.getStatus());
        assertTrue(paused.getPauseReason().contains("challenge"));
        assertEquals(1, strategy.calls.get());

        clock.now += 60_000L;
        SearchServiceOutcome again = service.search(job("p1", "q1"), null);
        assertEquals(clock.now + 120_000L, again.getPausedUntil());
        clock.now += 120_000L;
        SearchServiceOutcome third = service.search(job("p1", "q1"), null);
        assertEquals(clock.now + 240_000L, third.getPausedUntil());

        clock.now += 240_000L;
        assertEquals(SearchServiceOutcome.Status.QUEUED, service.search(job("p1", "q1"), null).getStatus());
        assertEquals(4, strategy.calls.get());
    }

    @Test
    public void engineRequestsKeepTheirSpacing() throws Exception {
        SearchService service = service(null);
        service.search(job("p1", "a"), null);
        service.search(job("p1", "b"), null);
        assertEquals(Collections.singletonList(4_000L), clock.sleeps);
    }

    @Test
    public void everyStrategyCallOfOneJobIsSpaced() throws Exception {
        SearchStrategy paging = new SearchStrategy() {
            public InitialSearchResult search(InitialSearchRequest r, CancellationSignal c, SearchBudgetGate b) {
                for (int page = 0; page < 3; page++) {
                    b.beforeToolCall();
                }
                return results(hit("https://a.org/p"));
            }
        };
        new SearchService(paging, null, new EngineThrottle(4_000L, 60_000L, 600_000L, clock), clock)
                .search(job("p1", "q"), null);
        assertEquals(Arrays.asList(4_000L, 4_000L), clock.sleeps);
    }

    @Test
    public void strategyFailureIsATechnicalProblemAndTheQueryStaysOpen() throws Exception {
        final AtomicInteger calls = new AtomicInteger();
        SearchStrategy failing = new SearchStrategy() {
            public InitialSearchResult search(InitialSearchRequest r, CancellationSignal c, SearchBudgetGate b) {
                if (calls.incrementAndGet() == 1) {
                    throw new IllegalStateException("browser closed");
                }
                return results(hit("https://a.org/1"));
            }
        };
        SearchService service = new SearchService(failing, null,
                new EngineThrottle(0L, 60_000L, 600_000L, clock), clock);
        assertEquals(SearchServiceOutcome.Status.TECHNICAL_PROBLEM, service.search(job("p", "q"), null).getStatus());
        assertEquals(SearchServiceOutcome.Status.QUEUED, service.search(job("p", "q"), null).getStatus());
    }

    @Test
    public void twoChatsSearchingAtOnceNeverOverlapOnTheEngine() throws Exception {
        strategy.holdMillis = 30;
        final SearchService service = new SearchService(strategy, null,
                new EngineThrottle(0L, 60_000L, 600_000L, SearchServiceClock.SYSTEM), SearchServiceClock.SYSTEM);
        final CountDownLatch start = new CountDownLatch(1);
        final List<Throwable> errors = Collections.synchronizedList(new ArrayList<Throwable>());
        List<Thread> threads = new ArrayList<Thread>();
        for (int chat = 0; chat < 2; chat++) {
            final String project = "chat-" + chat;
            Thread t = new Thread(new Runnable() {
                public void run() {
                    try {
                        start.await();
                        for (int i = 0; i < 5; i++) {
                            service.search(SearchJob.builder(project, "query " + i)
                                    .registry(new InMemoryUrlRegistry()).queue(new PageCandidateQueue()).build(),
                                    null);
                        }
                    } catch (Throwable e) {
                        errors.add(e);
                    }
                }
            });
            threads.add(t);
            t.start();
        }
        start.countDown();
        for (Thread t : threads) {
            t.join(10_000L);
        }
        assertTrue(errors.toString(), errors.isEmpty());
        assertEquals(10, strategy.calls.get());
        assertEquals(1, strategy.maxInside);
        assertFalse(service.waitingJobs() > 0);
    }
}
