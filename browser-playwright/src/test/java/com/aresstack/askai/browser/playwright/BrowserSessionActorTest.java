package com.aresstack.askai.browser.playwright;

import com.aresstack.askai.browser.BrowserBackendKind;
import com.aresstack.askai.browser.BrowserException;
import com.aresstack.askai.browser.BrowserLink;
import com.aresstack.askai.browser.BrowserPageSnapshot;
import com.aresstack.askai.browser.BrowserSession;
import com.aresstack.askai.browser.WebSearchResult;

import org.junit.Test;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The actor contract WITHOUT a browser: every session call — including creation and close — runs on the
 * single dedicated owner thread, strictly serialized; worker threads only enqueue and wait. This is the
 * pure-Java half of the acceptance; the event-pump half needs a real browser and lives in
 * {@link PlaywrightActorLiveBrowserTest}.
 */
public class BrowserSessionActorTest {

    /** Records which thread executes what; simulates work to expose any parallel execution. */
    static final class RecordingSession implements BrowserSession {
        final Set<String> executionThreads = ConcurrentHashMap.newKeySet();
        final AtomicInteger active = new AtomicInteger();
        final AtomicInteger maxActive = new AtomicInteger();
        volatile String creationThread = "not-created";
        volatile String closeThread = "not-closed";
        volatile boolean closed;

        private BrowserPageSnapshot record() {
            executionThreads.add(Thread.currentThread().getName());
            int now = active.incrementAndGet();
            maxActive.accumulateAndGet(now, Math::max);
            try {
                Thread.sleep(20);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            active.decrementAndGet();
            return new BrowserPageSnapshot("http://example.test/", "t", "text", false);
        }

        public BrowserBackendKind getBackendKind() {
            return BrowserBackendKind.PLAYWRIGHT_SIDECAR;
        }

        public WebSearchResult search(String query) {
            record();
            return new WebSearchResult(Collections.emptyList(), Collections.emptyList(),
                    Collections.emptyList());
        }

        public BrowserPageSnapshot open(String url) {
            return record();
        }

        public BrowserPageSnapshot currentPage() {
            return record();
        }

        public List<BrowserLink> links() {
            record();
            return Collections.emptyList();
        }

        public BrowserPageSnapshot follow(String linkId) throws BrowserException {
            record();
            throw new BrowserException("Unknown link id: " + linkId);
        }

        public BrowserPageSnapshot back() {
            return record();
        }

        public void close() {
            closeThread = Thread.currentThread().getName();
            closed = true;
        }
    }

    private static BrowserSessionActor startWith(final RecordingSession session) {
        return BrowserSessionActor.start(new Supplier<BrowserSession>() {
            public BrowserSession get() {
                session.creationThread = Thread.currentThread().getName();
                return session;
            }
        });
    }

    @Test
    public void creationAndEveryCallRunOnTheSingleOwnerThread() throws Exception {
        final RecordingSession session = new RecordingSession();
        final BrowserSessionActor actor = startWith(session);
        try {
            assertEquals(BrowserSessionActor.OWNER_THREAD_NAME, session.creationThread);

            final CountDownLatch done = new CountDownLatch(4);
            for (int i = 0; i < 4; i++) {
                new Thread(new Runnable() {
                    public void run() {
                        try {
                            actor.currentPage();
                            actor.open("http://example.test/");
                        } catch (BrowserException ex) {
                            // recorded via the thread set staying wrong — nothing to do here
                        } finally {
                            done.countDown();
                        }
                    }
                }, "worker").start();
            }
            assertTrue(done.await(15, TimeUnit.SECONDS));
            assertEquals("all execution must happen on the one owner thread: "
                    + session.executionThreads, 1, session.executionThreads.size());
            assertTrue(session.executionThreads.contains(BrowserSessionActor.OWNER_THREAD_NAME));
        } finally {
            actor.close();
        }
    }

    @Test
    public void callsAreSerializedNeverConcurrent() throws Exception {
        final RecordingSession session = new RecordingSession();
        final BrowserSessionActor actor = startWith(session);
        try {
            final CountDownLatch done = new CountDownLatch(8);
            for (int i = 0; i < 8; i++) {
                new Thread(new Runnable() {
                    public void run() {
                        try {
                            actor.currentPage();
                        } catch (BrowserException ignored) {
                        } finally {
                            done.countDown();
                        }
                    }
                }).start();
            }
            assertTrue(done.await(15, TimeUnit.SECONDS));
            assertEquals("two session calls must never overlap", 1, session.maxActive.get());
        } finally {
            actor.close();
        }
    }

    @Test
    public void browserExceptionsPropagateToTheCallingThread() throws Exception {
        final RecordingSession session = new RecordingSession();
        BrowserSessionActor actor = startWith(session);
        try {
            actor.follow("link-99");
            fail("the session's BrowserException must reach the caller");
        } catch (BrowserException expected) {
            assertTrue(expected.getMessage().contains("link-99"));
        } finally {
            actor.close();
        }
    }

    @Test
    public void closeRunsOnTheOwnerThreadIsIdempotentAndFailsFurtherCalls() throws Exception {
        final RecordingSession session = new RecordingSession();
        BrowserSessionActor actor = startWith(session);
        actor.close();
        assertTrue(session.closed);
        assertEquals("teardown must run on the owner thread too",
                BrowserSessionActor.OWNER_THREAD_NAME, session.closeThread);
        actor.close(); // idempotent
        try {
            actor.currentPage();
            fail("calls after close must fail readably");
        } catch (BrowserException expected) {
            assertTrue(expected.getMessage().contains("closed"));
        }
        // challengeStatus must not throw after close (tool contract: NONE)
        assertEquals(Collections.singletonList("NONE"), actor.challengeStatus());
    }

    @Test
    public void hudPollIsControlPlaneAndAnswersWhileADataCallBlocksTheActor() throws Exception {
        // A Playwright-backed session over a fake driver whose open() blocks until released — the shape of
        // a hung probe/read. The Skip in the HUD inbox must still be drainable, WITHOUT the actor queue.
        final CountDownLatch openEntered = new CountDownLatch(1);
        final CountDownLatch releaseOpen = new CountDownLatch(1);
        final HudCommandInbox inbox = new HudCommandInbox();
        final PlaywrightDriver blockingDriver = new PlaywrightDriver() {
            public PlaywrightPageState open(String url) {
                openEntered.countDown();
                try {
                    releaseOpen.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
                return new PlaywrightPageState("http://127.0.0.1:9/x", "t", "text",
                        Collections.<PlaywrightPageState.Anchor>emptyList());
            }

            public PlaywrightPageState current() {
                return new PlaywrightPageState("http://127.0.0.1:9/x", "t", "text",
                        Collections.<PlaywrightPageState.Anchor>emptyList());
            }

            public PlaywrightPageState back() {
                return current();
            }

            public HudCommandInbox hudInbox() {
                return inbox;
            }

            public void close() {
            }
        };
        final BrowserSessionActor actor = BrowserSessionActor.start(new Supplier<BrowserSession>() {
            public BrowserSession get() {
                return new PlaywrightBrowserSession(blockingDriver,
                        com.aresstack.askai.browser.UrlSafetyPolicy.allowingPrivateNetworks(),
                        com.aresstack.askai.browser.BrowserLimits.defaults(), null,
                        new WebSearchProvider.OrganicResultSearchProvider(
                                com.aresstack.askai.browser.search.LegacyBrowserSearchDefaults.create()));
            }
        });
        try {
            Thread dataCall = new Thread(new Runnable() {
                public void run() {
                    try {
                        actor.open("http://127.0.0.1:9/x");
                    } catch (BrowserException ignored) {
                    }
                }
            }, "blocked-data-call");
            dataCall.start();
            assertTrue("the data call must be inside the driver", openEntered.await(5, TimeUnit.SECONDS));

            inbox.add("SKIP"); // the HUD binding would do this during the event pump
            long before = System.currentTimeMillis();
            String drained = actor.pollHudCommands();
            long elapsed = System.currentTimeMillis() - before;
            assertEquals("SKIP", drained);
            assertTrue("the control-plane drain must not queue behind the blocked data call (took "
                    + elapsed + "ms)", elapsed < 2000);

            releaseOpen.countDown();
            dataCall.join(5000);
        } finally {
            releaseOpen.countDown();
            actor.close();
        }
    }

    @Test
    public void factoryFailurePropagatesFromStart() {
        try {
            BrowserSessionActor.start(new Supplier<BrowserSession>() {
                public BrowserSession get() {
                    throw new IllegalStateException("boom at launch");
                }
            });
            fail("a factory failure must reach the starter");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("boom at launch"));
        }
    }

    @Test
    public void nonPlaywrightBackedActorReportsItAndRefusesPlaywrightTasks() throws Exception {
        final RecordingSession session = new RecordingSession();
        BrowserSessionActor actor = startWith(session);
        try {
            assertFalse(actor.isPlaywrightBacked());
            final AtomicReference<String> ran = new AtomicReference<String>();
            try {
                actor.onPlaywrightSession(
                        new BrowserSessionActor.PlaywrightSessionTask<Void>() {
                            public Void run(PlaywrightBrowserSession s) {
                                ran.set("must not run");
                                return null;
                            }
                        });
                fail("a non-Playwright backend must refuse Playwright tasks");
            } catch (BrowserException expected) {
                assertTrue(expected.getMessage().contains("unavailable"));
            }
            assertEquals(null, ran.get());
        } finally {
            actor.close();
        }
    }
}
