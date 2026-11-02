package com.aresstack.askai.research.host;

import org.junit.Test;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The lazy, restartable browser runtime: nothing starts until the first command, exactly one start is shared
 * by concurrent first commands, a dead generation restarts+retries once, stop ends the phase and a later
 * command restarts, and start failures stay typed — all without a real separate process.
 */
public class LazyRestartableBrowserRuntimeTest {

    private static final Map<String, Object> NO_ARGS = Collections.emptyMap();

    /** A fake browser runtime generation: records calls/closes and can be told to fail its first call once. */
    private static final class FakeBrowserRuntime implements LazyRestartableBrowserRuntime.RunningBrowser {
        private final int generation;
        private final boolean failFirstCall;
        private boolean firstCallDone;
        volatile boolean closed;

        FakeBrowserRuntime(int generation, boolean failFirstCall) {
            this.generation = generation;
            this.failFirstCall = failFirstCall;
        }

        public String call(String tool, Map<String, Object> arguments)
                throws BrowserRuntimePort.BrowserRuntimeException {
            if (failFirstCall && !firstCallDone) {
                firstCallDone = true;
                throw new BrowserRuntimePort.BrowserRuntimeException("browser died", true);
            }
            return "gen" + generation + ":" + tool;
        }

        public boolean isAlive() {
            return !closed;
        }

        public void close() {
            closed = true;
        }
    }

    /** A starter that counts starts and hands out fake generations (optionally failing the very first start). */
    private static final class CountingStarter implements LazyRestartableBrowserRuntime.BrowserStarter {
        final AtomicInteger starts = new AtomicInteger();
        volatile boolean deadOnFirstCall;
        volatile boolean failNextStart;
        volatile FakeBrowserRuntime last;
        private CountDownLatch startGate; // optional: block starts until released

        public LazyRestartableBrowserRuntime.RunningBrowser start()
                throws BrowserRuntimePort.BrowserRuntimeException {
            if (startGate != null) {
                try {
                    startGate.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            int n = starts.incrementAndGet();
            if (failNextStart) {
                throw new BrowserRuntimePort.BrowserRuntimeException("start failed", true);
            }
            FakeBrowserRuntime running = new FakeBrowserRuntime(n, deadOnFirstCall && n == 1);
            last = running;
            return running;
        }
    }

    @Test
    public void isCreatedStoppedAndStartsNothing() {
        CountingStarter starter = new CountingStarter();
        LazyRestartableBrowserRuntime runtime = new LazyRestartableBrowserRuntime(starter);
        try {
            assertFalse(runtime.isReady());
            assertEquals("nothing starts until the first command", 0, starter.starts.get());
        } finally {
            runtime.close();
        }
    }

    @Test
    public void theFirstCommandStartsExactlyOnceAndReusesTheGeneration() throws Exception {
        CountingStarter starter = new CountingStarter();
        LazyRestartableBrowserRuntime runtime = new LazyRestartableBrowserRuntime(starter);
        try {
            assertEquals("gen1:web_search", runtime.execute("web_search", NO_ARGS));
            assertTrue(runtime.isReady());
            assertEquals("gen1:web_open", runtime.execute("web_open", NO_ARGS));
            assertEquals("a running generation is reused — only one start", 1, starter.starts.get());
        } finally {
            runtime.close();
        }
    }

    @Test
    public void concurrentFirstCommandsShareASingleStart() throws Exception {
        final CountingStarter starter = new CountingStarter();
        starter.startGate = new CountDownLatch(1);
        final LazyRestartableBrowserRuntime runtime = new LazyRestartableBrowserRuntime(starter);
        final CountDownLatch ready = new CountDownLatch(2);
        final AtomicInteger failures = new AtomicInteger();
        try {
            Runnable command = new Runnable() {
                public void run() {
                    ready.countDown();
                    try {
                        runtime.execute("web_search", NO_ARGS);
                    } catch (Exception e) {
                        failures.incrementAndGet();
                    }
                }
            };
            Thread a = new Thread(command);
            Thread b = new Thread(command);
            a.start();
            b.start();
            ready.await(5, TimeUnit.SECONDS);
            Thread.sleep(50); // let both submit onto the owner queue
            starter.startGate.countDown();
            a.join(5000);
            b.join(5000);
            assertEquals(0, failures.get());
            assertEquals("two concurrent first commands share ONE start", 1, starter.starts.get());
        } finally {
            runtime.close();
        }
    }

    @Test
    public void aDeadGenerationRestartsAndRetriesTheCommandOnce() throws Exception {
        CountingStarter starter = new CountingStarter();
        starter.deadOnFirstCall = true;
        LazyRestartableBrowserRuntime runtime = new LazyRestartableBrowserRuntime(starter);
        try {
            // gen1's first call throws endpoint-unavailable → runtime restarts (gen2) and retries → success.
            assertEquals("gen2:web_open", runtime.execute("web_open", NO_ARGS));
            assertEquals(2, starter.starts.get());
        } finally {
            runtime.close();
        }
    }

    @Test
    public void stopEndsThePhaseAndALaterCommandStartsAFreshGeneration() throws Exception {
        CountingStarter starter = new CountingStarter();
        LazyRestartableBrowserRuntime runtime = new LazyRestartableBrowserRuntime(starter);
        try {
            runtime.execute("web_search", NO_ARGS);
            FakeBrowserRuntime first = starter.last;
            runtime.stop();
            // stop() is async; the next execute serializes behind it and starts a fresh generation.
            assertEquals("gen2:web_search", runtime.execute("web_search", NO_ARGS));
            assertEquals(2, starter.starts.get());
            assertTrue("the first generation was closed on stop", first.closed);
        } finally {
            runtime.close();
        }
    }

    @Test
    public void aStartFailureStaysTypedAndDoesNotFabricateSuccess() {
        CountingStarter starter = new CountingStarter();
        starter.failNextStart = true;
        LazyRestartableBrowserRuntime runtime = new LazyRestartableBrowserRuntime(starter);
        try {
            runtime.execute("web_search", NO_ARGS);
            fail("expected a typed browser failure");
        } catch (BrowserRuntimePort.BrowserRuntimeException expected) {
            assertTrue(expected.getMessage(), expected.isEndpointUnavailable());
            assertFalse(runtime.isReady());
        } finally {
            runtime.close();
        }
    }

    @Test
    public void controlCallsAnswerWhileADataCallBlocksTheOwner() throws Exception {
        // The live-bug shape: a data call (hung probe/read) occupies the single owner thread; the user's
        // Skip poll must still get through — out of band, on the caller's thread, via controlCall.
        final CountDownLatch dataEntered = new CountDownLatch(1);
        final CountDownLatch releaseData = new CountDownLatch(1);
        final LazyRestartableBrowserRuntime.RunningBrowser running =
                new LazyRestartableBrowserRuntime.RunningBrowser() {
                    public String call(String tool, Map<String, Object> arguments) {
                        if ("web_read".equals(tool)) {
                            dataEntered.countDown();
                            try {
                                releaseData.await(10, TimeUnit.SECONDS);
                            } catch (InterruptedException ignored) {
                                Thread.currentThread().interrupt();
                            }
                        }
                        return "data:" + tool;
                    }

                    public String controlCall(String tool, Map<String, Object> arguments) {
                        return "SKIP";
                    }

                    public boolean isAlive() {
                        return true;
                    }

                    public void close() {
                        releaseData.countDown();
                    }
                };
        final LazyRestartableBrowserRuntime runtime = new LazyRestartableBrowserRuntime(
                new LazyRestartableBrowserRuntime.BrowserStarter() {
                    public LazyRestartableBrowserRuntime.RunningBrowser start() {
                        return running;
                    }
                });
        try {
            runtime.execute("web_open", NO_ARGS); // start the generation
            Thread dataCall = new Thread(new Runnable() {
                public void run() {
                    try {
                        runtime.execute("web_read", NO_ARGS);
                    } catch (Exception ignored) {
                    }
                }
            }, "blocked-data-call");
            dataCall.start();
            assertTrue(dataEntered.await(5, TimeUnit.SECONDS));

            long before = System.currentTimeMillis();
            String polled = runtime.executeControl("web_hud_poll", NO_ARGS);
            long elapsed = System.currentTimeMillis() - before;
            assertEquals("SKIP", polled);
            assertTrue("control must not queue behind the blocked owner (took " + elapsed + "ms)",
                    elapsed < 2000);

            releaseData.countDown();
            dataCall.join(5000);
        } finally {
            runtime.close();
        }
    }

    @Test
    public void controlOnAStoppedRuntimeStartsNothingAndReportsNothing() {
        CountingStarter starter = new CountingStarter();
        LazyRestartableBrowserRuntime runtime = new LazyRestartableBrowserRuntime(starter);
        try {
            assertEquals("no generation → nothing to poll", "", runtime.executeControl("web_hud_poll", NO_ARGS));
            assertEquals("a control poll must NEVER start a browser", 0, starter.starts.get());
        } finally {
            runtime.close();
        }
    }

    @Test
    public void afterCloseNoFurtherCommandRuns() {
        CountingStarter starter = new CountingStarter();
        LazyRestartableBrowserRuntime runtime = new LazyRestartableBrowserRuntime(starter);
        runtime.close();
        try {
            runtime.execute("web_search", NO_ARGS);
            fail("a closed runtime must not run commands");
        } catch (BrowserRuntimePort.BrowserRuntimeException expected) {
            // expected
        }
        assertEquals("no browser is started after close", 0, starter.starts.get());
    }
}
