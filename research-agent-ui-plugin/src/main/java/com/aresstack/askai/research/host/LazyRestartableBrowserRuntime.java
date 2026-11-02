package com.aresstack.askai.research.host;


import com.aresstack.askai.browser.BrowserException;
import com.aresstack.askai.browser.sidecar.InProcessBrowser;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;

/**
 * A lazy, restartable owner of ONE browser MCP sidecar generation. It is created STOPPED (no process, no
 * Playwright, no readiness probe) when the research session starts, so selecting the Research agent never
 * spawns a browser. The sidecar is started on the FIRST browser command ({@link #execute}), stopped again
 * with {@link #stop()} when the browsing phase ends, and can be thrown away + replaced with {@link #restart()}
 * on failure — the TeamAgent survives a browser crash untouched.
 *
 * <p>THREADING: every operation runs on a single dedicated owner thread (a one-thread executor), so the
 * generation is never touched concurrently and calls are naturally serialized — two concurrent first commands
 * share the one start. Callers (the MCP bridge handler thread, never the Swing EDT) block on the owner via a
 * future. Each start bumps a generation id so a broken generation is unambiguous.</p>
 */
public final class LazyRestartableBrowserRuntime implements BrowserRuntimePort {

    /** One started sidecar generation (process + client), abstracted so the lifecycle is unit-testable. */
    interface Sidecar {
        String call(String tool, Map<String, Object> arguments) throws BrowserRuntimeException;

        /**
         * CONTROL-PLANE call (HUD poll): runs on the CALLER's thread over its own connection so it can
         * answer while {@link #call} blocks the owner. The default shares the data path (test fakes).
         */
        default String controlCall(String tool, Map<String, Object> arguments)
                throws BrowserRuntimeException {
            return call(tool, arguments);
        }

        boolean isAlive();

        void close();
    }

    /** Starts a fresh sidecar generation, BLOCKING until it is ready; throws on any start failure. */
    interface SidecarStarter {
        Sidecar start() throws BrowserRuntimeException;
    }

    private final SidecarStarter starter;
    private final ExecutorService owner;
    private volatile Listener listener = Listener.NONE;

    // Touched ONLY on the owner thread (state is volatile for the fast READY read in isReady/ensureStarted;
    // current is volatile ONLY for the out-of-band control-plane snapshot in executeControl).
    private volatile State state = State.STOPPED;
    private long generation;
    private volatile Sidecar current;
    private volatile boolean closed;
    /** Dedupe for control-plane failure logging (polled ~1/s — never flood stderr). */
    private volatile String lastControlFailureLine;

    /**
     * Production runtime over the in-process Playwright browser (askai #31: no sidecar process, no MCP).
     *
     * @param browserConfigPath the versioned browser-search config document, or {@code null} for the defaults
     */
    public LazyRestartableBrowserRuntime(ResearchRuntimeConfig config, String browserConfigPath) {
        this(inProcessStarter(config, browserConfigPath));
    }

    LazyRestartableBrowserRuntime(SidecarStarter starter) {
        this.starter = starter;
        this.owner = Executors.newSingleThreadExecutor(new ThreadFactory() {
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "browser-runtime-owner");
                t.setDaemon(true);
                return t;
            }
        });
    }

    @Override
    public void setListener(Listener listener) {
        this.listener = listener == null ? Listener.NONE : listener;
    }

    @Override
    public boolean isReady() {
        return state == State.READY;
    }

    @Override
    public String execute(final String tool, final Map<String, Object> arguments)
            throws BrowserRuntimeException {
        ensureStarted();
        try {
            return callOnOwner(tool, arguments);
        } catch (BrowserRuntimeException firstFailure) {
            // A live generation died mid-use: throw it away, start a fresh one and retry the command ONCE.
            if (firstFailure.isEndpointUnavailable() && !closed) {
                restart();
                return callOnOwner(tool, arguments);
            }
            throw firstFailure;
        }
    }

    @Override
    public String executeControl(String tool, Map<String, Object> arguments) {
        // CONTROL PLANE: never submitted to the owner executor — a Skip poll must answer even while a
        // data call blocks the owner thread — and never starts/restarts a browser just to poll it. A
        // missing or broken generation reports nothing; a genuinely dead browser is detected (and
        // restarted) by the DATA calls, which is where the run's fate is decided.
        Sidecar sidecar = current;
        if (closed || state != State.READY || sidecar == null) {
            return "";
        }
        try {
            return sidecar.controlCall(tool, arguments);
        } catch (BrowserRuntimeException failure) {
            String line = "[browser] control call failed tool=" + tool + ": " + failure.getMessage();
            if (!line.equals(lastControlFailureLine)) {
                lastControlFailureLine = line;
                System.err.println(line);
            }
            return "";
        }
    }

    @Override
    public void ensureStarted() throws BrowserRuntimeException {
        runOnOwner(new Callable<Void>() {
            public Void call() throws Exception {
                if (closed) {
                    throw new BrowserRuntimeException("browser runtime is closed", true);
                }
                if (state == State.READY && current != null && current.isAlive()) {
                    return null;
                }
                startFreshGeneration();
                return null;
            }
        });
    }

    @Override
    public void restart() throws BrowserRuntimeException {
        runOnOwner(new Callable<Void>() {
            public Void call() throws Exception {
                stopCurrentGeneration();
                if (closed) {
                    throw new BrowserRuntimeException("browser runtime is closed", true);
                }
                startFreshGeneration();
                return null;
            }
        });
    }

    @Override
    public void stop() {
        if (closed) {
            return;
        }
        // Fire-and-forget: the RUN_OUTCOME handler calls this on the EDT, so it must NOT block. The owner
        // thread tears the generation down in order (a later start queues behind it).
        try {
            owner.submit(new Runnable() {
                public void run() {
                    stopCurrentGeneration();
                }
            });
        } catch (RuntimeException rejectedOrShutdown) {
            // owner already gone → effectively stopped
        }
    }

    @Override
    public void close() {
        boolean alreadyClosed;
        synchronized (this) {
            alreadyClosed = closed;
            closed = true;
        }
        if (alreadyClosed) {
            return;
        }
        try {
            owner.submit(new Runnable() {
                public void run() {
                    stopCurrentGeneration();
                    state = State.CLOSED;
                }
            }).get();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException ignored) {
            // best-effort teardown
        } finally {
            owner.shutdownNow();
        }
    }

    // ------------------------------------------------------------------ owner-thread internals

    private void startFreshGeneration() throws BrowserRuntimeException {
        stopCurrentGeneration(); // never leak a previous generation
        final long gen = ++generation;
        state = State.STARTING;
        listener.onStarting(gen);
        try {
            this.current = starter.start();
            state = State.READY;
            listener.onReady(gen);
        } catch (BrowserRuntimeException failure) {
            state = State.FAILED;
            this.current = null;
            listener.onFailed(gen, failure.getMessage());
            throw failure;
        }
    }

    private String callOnOwner(final String tool, final Map<String, Object> arguments)
            throws BrowserRuntimeException {
        return runOnOwner(new Callable<String>() {
            public String call() throws Exception {
                if (state != State.READY || current == null) {
                    throw new BrowserRuntimeException("browser runtime is not ready", true);
                }
                return current.call(tool, arguments);
            }
        });
    }

    /** Owner thread: tear the current generation down (idempotent). */
    private void stopCurrentGeneration() {
        long gen = generation;
        if (state == State.READY || state == State.STARTING || state == State.FAILED) {
            state = State.STOPPING;
        }
        Sidecar toClose = current;
        current = null;
        if (toClose != null) {
            toClose.close();
        }
        if (!closed) {
            state = State.STOPPED;
        }
        if (toClose != null) {
            listener.onStopped(gen);
        }
    }

    private <T> T runOnOwner(Callable<T> task) throws BrowserRuntimeException {
        Future<T> future;
        try {
            future = owner.submit(task);
        } catch (RuntimeException rejected) {
            throw new BrowserRuntimeException("browser runtime owner unavailable: " + rejected.getMessage(),
                    true);
        }
        try {
            return future.get();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BrowserRuntimeException("interrupted waiting for the browser runtime", true);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof BrowserRuntimeException) {
                throw (BrowserRuntimeException) cause;
            }
            throw new BrowserRuntimeException(cause == null ? ex.getMessage() : cause.getMessage(), true);
        }
    }

    /** The productive starter: start the Playwright browser in this process (blocks until ready). */
    private static SidecarStarter inProcessStarter(final ResearchRuntimeConfig config,
                                                   final String browserConfigPath) {
        return new SidecarStarter() {
            public Sidecar start() throws BrowserRuntimeException {
                final InProcessBrowser browser;
                try {
                    browser = InProcessBrowser.start(config.getBrowserChannel(), config.isHeadless(),
                            config.isAllowPrivateNetworks(), browserConfigPath);
                } catch (Exception failure) {
                    throw new BrowserRuntimeException("browser failed to start: " + failure.getMessage(), true);
                }
                return new Sidecar() {
                    public String call(String tool, Map<String, Object> arguments)
                            throws BrowserRuntimeException {
                        try {
                            return browser.call(tool, arguments);
                        } catch (BrowserException ex) {
                            // Same text the MCP tool error carried, so every consumer keeps its parsing.
                            throw new BrowserRuntimeException("Tool failed: " + ex.getMessage(), false);
                        } catch (RuntimeException ex) {
                            throw new BrowserRuntimeException("Tool failed: " + ex, false);
                        }
                    }

                    public String controlCall(String tool, Map<String, Object> arguments)
                            throws BrowserRuntimeException {
                        // The HUD inbox is drained directly by the session (never queued behind a data call).
                        return call(tool, arguments);
                    }

                    public boolean isAlive() {
                        return browser.isAlive();
                    }

                    public void close() {
                        browser.close();
                    }
                };
            }
        };
    }
}
