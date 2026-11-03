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
 * command restarts, and start failures stay typed — all without a real sidecar process.
 */
public class LazyRestartableBrowserRuntimeTest {

    private static final Map<String, Object> NO_ARGS = Collections.emptyMap();

    /** A fake sidecar generation: records calls/closes and can be told to fail its first call once. */
    private static final class FakeSidecar implements LazyRestartableBrowserRuntime.Sidecar {
        private final int generation;
        private final boolean failFirstCall;
        private boolean firstCallDone;
        volatile boolean closed;

        FakeSidecar(int generation, boolean failFirstCall) {
            this.generation = generation;
            this.failFirstCall = failFirstCall;
        }

        public String call(String tool, Map<String, Object> arguments)
                throws BrowserRuntimePort.BrowserRuntimeException {
            if (failFirstCall && !firstCallDone) {
                firstCallDone = true;
                throw new BrowserRuntimePort.BrowserRuntimeException("sidecar died", true);
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
    private static final class CountingStarter implements LazyRestartableBrowserRuntime.SidecarStarter {
        final AtomicInteger starts = new AtomicInteger();
        volatile boolean deadOnFirstCall;
        volatile boolean failNextStart;
        volatile FakeSidecar last;
        private CountDownLatch startGate; // optional: block starts until released

        public LazyRestartableBrowserRuntime.Sidecar start()
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
            FakeSidecar sidecar = new FakeSidecar(n, deadOnFirstCall && n == 1);
            last = sidecar;
            return sidecar;
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
            FakeSidecar first = starter.last;
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
        final LazyRestartableBrowserRuntime.Sidecar sidecar =
                new LazyRestartableBrowserRuntime.Sidecar() {
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
                new LazyRestartableBrowserRuntime.SidecarStarter() {
                    public LazyRestartableBrowserRuntime.Sidecar start() {
                        return sidecar;
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
        assertEquals("no sidecar is started after close", 0, starter.starts.get());
    }

    /**
     * A generation whose data call for {@code hangOn} never returns on its own. Honouring fakes fail the call
     * when terminated (the real one: the cut Playwright connection fails it); wedged fakes ignore both the
     * termination and interrupts, like a call stuck outside Playwright.
     */
    private static final class HangingSidecar implements LazyRestartableBrowserRuntime.Sidecar {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch released = new CountDownLatch(1);
        final String hangOn;
        final boolean honoursTerminate;
        volatile boolean terminated;
        volatile boolean closed;

        HangingSidecar(String hangOn, boolean honoursTerminate) {
            this.hangOn = hangOn;
            this.honoursTerminate = honoursTerminate;
        }

        public String call(String tool, Map<String, Object> arguments)
                throws BrowserRuntimePort.BrowserRuntimeException {
            if (!hangOn.equals(tool)) {
                return "gen1:" + tool;
            }
            entered.countDown();
            long deadline = System.currentTimeMillis() + 20_000L;
            while (System.currentTimeMillis() < deadline) {
                try {
                    if (released.await(deadline - System.currentTimeMillis(), TimeUnit.MILLISECONDS)) {
                        break;
                    }
                } catch (InterruptedException ignored) {
                    // a stuck call ignores interrupts (keep waiting)
                }
            }
            throw new BrowserRuntimePort.BrowserRuntimeException("connection closed", true);
        }

        public boolean isAlive() {
            return !closed && !terminated;
        }

        public void close() {
            closed = true;
            released.countDown();
        }

        public void terminate() {
            terminated = true;
            if (honoursTerminate) {
                released.countDown();
            }
        }
    }

    private static LazyRestartableBrowserRuntime.SidecarStarter firstThenFresh(
            final LazyRestartableBrowserRuntime.Sidecar first, final AtomicInteger starts) {
        return new LazyRestartableBrowserRuntime.SidecarStarter() {
            public LazyRestartableBrowserRuntime.Sidecar start() {
                int n = starts.incrementAndGet();
                return n == 1 ? first : new FakeSidecar(n, false);
            }
        };
    }

    @Test
    public void aHungDataCallTimesOutTerminatesTheGenerationAndRetriesOnAFreshOne() throws Exception {
        final HangingSidecar hanging = new HangingSidecar("web_read", true);
        final AtomicInteger starts = new AtomicInteger();
        LazyRestartableBrowserRuntime runtime =
                new LazyRestartableBrowserRuntime(firstThenFresh(hanging, starts), 300L);
        try {
            assertEquals("gen1:web_open", runtime.execute("web_open", NO_ARGS));
            long before = System.currentTimeMillis();
            // The hung call hits the per-call timeout: the generation is terminated OFF the owner thread
            // (the real termination cuts the Playwright connection), a fresh one starts, the command is
            // retried once — the caller never waits for the hang itself.
            assertEquals("gen2:web_read", runtime.execute("web_read", NO_ARGS));
            long elapsed = System.currentTimeMillis() - before;
            assertTrue("the hung generation was terminated", hanging.terminated);
            assertEquals("restart + retry on a fresh generation", 2, starts.get());
            assertTrue("bounded by the timeout, not by the hang (took " + elapsed + " ms)", elapsed < 5000);
            assertTrue(runtime.isReady());
        } finally {
            hanging.released.countDown();
            runtime.close();
        }
    }

    @Test
    public void aWedgedOwnerIsAbandonedAndTheNextCommandRunsOnAFreshOwner() throws Exception {
        final HangingSidecar wedged = new HangingSidecar("web_read", false); // ignores terminate + interrupts
        final AtomicInteger starts = new AtomicInteger();
        LazyRestartableBrowserRuntime runtime =
                new LazyRestartableBrowserRuntime(firstThenFresh(wedged, starts), 200L);
        try {
            assertEquals("gen1:web_open", runtime.execute("web_open", NO_ARGS));
            long before = System.currentTimeMillis();
            try {
                runtime.execute("web_read", NO_ARGS);
                fail("a generation that survives its termination must surface as a typed failure");
            } catch (BrowserRuntimePort.BrowserRuntimeException expected) {
                assertTrue(expected.getMessage(), expected.isEndpointUnavailable());
            }
            long elapsed = System.currentTimeMillis() - before;
            assertTrue("timeout + bounded recovery, never the hang (took " + elapsed + " ms)", elapsed < 5000);
            assertTrue(wedged.terminated);
            // The wedged owner thread is left behind (daemon); a fresh owner serves the next command.
            assertEquals("gen2:web_search", runtime.execute("web_search", NO_ARGS));
            assertEquals(2, starts.get());
        } finally {
            wedged.released.countDown();
            runtime.close();
        }
    }

    @Test
    public void closeOnAStuckGenerationReturnsWithinTheBound() throws Exception {
        final HangingSidecar wedged = new HangingSidecar("web_read", false);
        final AtomicInteger starts = new AtomicInteger();
        final LazyRestartableBrowserRuntime runtime =
                new LazyRestartableBrowserRuntime(firstThenFresh(wedged, starts), 200L);
        Thread dataCall = null;
        try {
            runtime.execute("web_open", NO_ARGS);
            dataCall = new Thread(new Runnable() {
                public void run() {
                    try {
                        runtime.execute("web_read", NO_ARGS);
                    } catch (Exception ignored) {
                        // the stuck call ends as a typed failure — not under test here
                    }
                }
            }, "stuck-data-call");
            dataCall.start();
            assertTrue(wedged.entered.await(5, TimeUnit.SECONDS));
            long before = System.currentTimeMillis();
            runtime.close(); // queues behind the stuck call → bounded → terminates off the owner
            long elapsed = System.currentTimeMillis() - before;
            assertTrue("close must not hang on a stuck generation (took " + elapsed + " ms)", elapsed < 5000);
            assertTrue(wedged.terminated);
            assertFalse(runtime.isReady());
        } finally {
            wedged.released.countDown();
            if (dataCall != null) {
                dataCall.join(5000);
            }
        }
    }

    @Test
    public void stopWithAnIdleKeepAliveKeepsTheGenerationWarmForTheNextCommand() throws Exception {
        CountingStarter starter = new CountingStarter();
        LazyRestartableBrowserRuntime runtime = new LazyRestartableBrowserRuntime(starter, 0L, 10_000L);
        try {
            runtime.execute("web_search", NO_ARGS);
            FakeSidecar first = starter.last;
            runtime.stop(); // the browsing phase ends — the generation stays warm
            Thread.sleep(100);
            assertTrue("warm: still READY", runtime.isReady());
            assertFalse(first.closed);
            assertEquals("the next search skips the browser start", "gen1:web_open",
                    runtime.execute("web_open", NO_ARGS));
            assertEquals(1, starter.starts.get());
        } finally {
            runtime.close();
        }
    }

    @Test
    public void anIdleGenerationIsStoppedAfterTheKeepAliveAndTheNextCommandStartsFresh() throws Exception {
        CountingStarter starter = new CountingStarter();
        LazyRestartableBrowserRuntime runtime = new LazyRestartableBrowserRuntime(starter, 0L, 200L);
        try {
            runtime.execute("web_search", NO_ARGS);
            FakeSidecar first = starter.last;
            runtime.stop();
            long deadline = System.currentTimeMillis() + 5000L;
            while (!first.closed && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            assertTrue("stopped after the idle period", first.closed);
            assertFalse(runtime.isReady());
            assertEquals("gen2:web_search", runtime.execute("web_search", NO_ARGS));
        } finally {
            runtime.close();
        }
    }

    @Test
    public void aCommandDuringTheKeepAliveVoidsThePendingIdleStop() throws Exception {
        CountingStarter starter = new CountingStarter();
        LazyRestartableBrowserRuntime runtime = new LazyRestartableBrowserRuntime(starter, 0L, 400L);
        try {
            runtime.execute("web_search", NO_ARGS);
            FakeSidecar first = starter.last;
            runtime.stop();
            Thread.sleep(200);
            runtime.execute("web_open", NO_ARGS); // activity: the armed idle stop is void
            Thread.sleep(400); // well past the ORIGINAL idle deadline
            assertFalse("the generation survived the voided idle stop", first.closed);
            assertTrue(runtime.isReady());
            assertEquals(1, starter.starts.get());
        } finally {
            runtime.close();
        }
    }
}
