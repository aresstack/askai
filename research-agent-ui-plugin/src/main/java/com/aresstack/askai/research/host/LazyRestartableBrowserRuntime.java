package com.aresstack.askai.research.host;

import com.aresstack.askai.browser.BrowserException;
import com.aresstack.askai.browser.playwright.PlaywrightBrowserRuntime;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * A lazy, restartable owner of ONE browser generation. It is created STOPPED (no browser, no Playwright, no
 * readiness probe) when the research session starts, so selecting the Research agent never starts a browser.
 * The browser is started on the FIRST browser command ({@link #execute}), stopped again with {@link #stop()}
 * when the browsing phase ends, and can be thrown away + replaced with {@link #restart()} on failure — the
 * TeamAgent survives a browser crash untouched. Since the return to Java 21 a generation is the IN-PROCESS
 * {@link PlaywrightBrowserRuntime} (formerly a separate sidecar JVM reached over MCP).
 *
 * <p>THREADING: every operation runs on a single dedicated owner thread (a one-thread executor), so the
 * generation is never touched concurrently and calls are naturally serialized — two concurrent first commands
 * share the one start. Callers (the MCP bridge handler thread, never the Swing EDT) block on the owner via a
 * future. Each start bumps a generation id so a broken generation is unambiguous.</p>
 *
 * <p>BOUNDED WAITS: every DATA call is bounded by the per-call browser timeout (the settings value the
 * factory hands in; 0 = unbounded, for tests). A call that exceeds it is treated exactly like the former
 * dead sidecar process: the generation is TERMINATED off the owner thread — which is the stuck one; the
 * termination cuts the Playwright connection and thereby fails the stuck call —, the command reports
 * endpoint-unavailable, and {@link #execute} restarts a fresh generation and retries once. Should the owner
 * thread itself stay wedged (a stop/start that never returns), the recovery wait is bounded too: the thread
 * is abandoned (it is a daemon and ends with its call) and a fresh owner takes over, so neither a command
 * nor {@link #close()} can hang the session forever. A FIRST start stays unbounded on purpose: it may
 * legitimately include the one-time Camoufox download.</p>
 */
public final class LazyRestartableBrowserRuntime implements BrowserRuntimePort {

    /** One started browser generation, abstracted so the lifecycle is unit-testable. */
    interface Sidecar {
        String call(String tool, Map<String, Object> arguments) throws BrowserRuntimeException;

        /**
         * CONTROL-PLANE call (HUD poll): runs on the CALLER's thread so it can answer while {@link #call}
         * blocks the owner. The default shares the data path (test fakes).
         */
        default String controlCall(String tool, Map<String, Object> arguments)
                throws BrowserRuntimeException {
            return call(tool, arguments);
        }

        boolean isAlive();

        void close();

        /**
         * EMERGENCY teardown from a FOREIGN thread while {@link #call} is stuck on the owner: must never
         * block on the owner and must make the stuck call fail (the production generation cuts the
         * Playwright connection, which ends the driver child). The default is the regular close (test fakes).
         */
        default void terminate() {
            close();
        }
    }

    /** Starts a fresh browser generation, BLOCKING until it is ready; throws on any start failure. */
    interface SidecarStarter {
        Sidecar start() throws BrowserRuntimeException;
    }

    /** A bounded owner wait ran out — the owner thread did not deliver in time. */
    private static final class OwnerTimeout extends Exception {
        private static final long serialVersionUID = 1L;

        OwnerTimeout(String message) {
            super(message);
        }
    }

    /**
     * Documented DEV/TEST hand-off (like askai.research.runtime.dir): extra browser arguments for local test
     * worlds, e.g. {@code --domain-key-mode=host-port --search-url=http://127.0.0.1:1234/find?q={query}}
     * (host:port domain families, ONE explicit search engine per run). Never a persisted value.
     */
    public static final String BROWSER_ARGS_PROPERTY = "askai.research.browser.args";

    private final SidecarStarter starter;
    /** Bound of one DATA call and of the recovery waits, in milliseconds; 0 = unbounded. */
    private final long callTimeoutMillis;
    /** Replaced only when a wedged owner thread is abandoned (see {@link #abandonOwner}). */
    private volatile ExecutorService owner;
    /** Bumped when an owner is abandoned: tasks of an older epoch must never publish into the new one. */
    private volatile long ownerEpoch;
    private volatile Listener listener = Listener.NONE;

    // Touched on the owner thread in the regular paths; volatile for the fast READY read in
    // isReady/ensureStarted, the out-of-band control-plane snapshot in executeControl and the
    // foreign-thread emergency paths (terminateCurrent/abandonOwner).
    private volatile State state = State.STOPPED;
    private volatile long generation;
    private volatile Sidecar current;
    private volatile boolean closed;
    /** Dedupe for control-plane failure logging (polled ~1/s — never flood stderr). */
    private volatile String lastControlFailureLine;

    /**
     * Production runtime over the in-process Playwright browser.
     *
     * @param browserConfigPath  absolute path of the versioned browser-search config document for this
     *                           session (null = the central defaults)
     * @param callTimeoutSeconds the per-call browser timeout from the settings (0 = unbounded)
     */
    public LazyRestartableBrowserRuntime(ResearchRuntimeConfig config, String browserConfigPath,
                                         int callTimeoutSeconds) {
        this(inProcessStarter(config, browserConfigPath), Math.max(0, callTimeoutSeconds) * 1000L);
    }

    LazyRestartableBrowserRuntime(SidecarStarter starter) {
        this(starter, 0L);
    }

    LazyRestartableBrowserRuntime(SidecarStarter starter, long callTimeoutMillis) {
        this.starter = starter;
        this.callTimeoutMillis = Math.max(0L, callTimeoutMillis);
        this.owner = newOwner();
    }

    private static ExecutorService newOwner() {
        return Executors.newSingleThreadExecutor(new ThreadFactory() {
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
            // A live generation died mid-use (or hung past the timeout and was terminated): throw it away,
            // start a fresh one and retry the command ONCE.
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
        final long epoch = ownerEpoch;
        try {
            // Unbounded on purpose: a first start may include the one-time Camoufox download.
            runOnOwner(new Callable<Void>() {
                public Void call() throws Exception {
                    if (closed) {
                        throw new BrowserRuntimeException("browser runtime is closed", true);
                    }
                    if (state == State.READY && current != null && current.isAlive()) {
                        return null;
                    }
                    startFreshGeneration(epoch);
                    return null;
                }
            }, 0L);
        } catch (OwnerTimeout impossible) {
            throw new BrowserRuntimeException(impossible.getMessage(), true);
        }
    }

    @Override
    public void restart() throws BrowserRuntimeException {
        final long epoch = ownerEpoch;
        try {
            runOnOwner(new Callable<Void>() {
                public Void call() throws Exception {
                    stopCurrentGeneration(epoch);
                    if (closed) {
                        throw new BrowserRuntimeException("browser runtime is closed", true);
                    }
                    startFreshGeneration(epoch);
                    return null;
                }
            }, callTimeoutMillis);
        } catch (OwnerTimeout wedged) {
            // The owner never got to the restart: it is still inside a call the termination could not
            // fail, or a stop/start does not return. Leave that thread behind and continue on a fresh one.
            abandonOwner("the restart did not complete within " + seconds() + " s");
            throw new BrowserRuntimeException("browser runtime did not recover within " + seconds()
                    + " s — the browser owner thread was abandoned", true);
        }
    }

    @Override
    public void stop() {
        if (closed) {
            return;
        }
        final long epoch = ownerEpoch;
        // Fire-and-forget: the RUN_OUTCOME handler calls this on the EDT, so it must NOT block. The owner
        // thread tears the generation down in order (a later start queues behind it).
        try {
            owner.submit(new Runnable() {
                public void run() {
                    stopCurrentGeneration(epoch);
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
        final long epoch = ownerEpoch;
        ExecutorService executor = owner;
        try {
            Future<?> done = executor.submit(new Runnable() {
                public void run() {
                    stopCurrentGeneration(epoch);
                    state = State.CLOSED;
                }
            });
            if (callTimeoutMillis > 0) {
                done.get(callTimeoutMillis, TimeUnit.MILLISECONDS);
            } else {
                done.get();
            }
        } catch (TimeoutException stuck) {
            // The orderly stop queues behind a stuck call: terminate the generation off the owner so the
            // session close returns; the owner thread is a daemon and ends with the failed call.
            terminateCurrent("the close did not complete within " + seconds() + " s");
            state = State.CLOSED;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException ignored) {
            // best-effort teardown
        } catch (RuntimeException rejected) {
            // owner already gone
        } finally {
            executor.shutdownNow();
        }
    }

    // ------------------------------------------------------------------ owner-thread internals

    private void startFreshGeneration(long epoch) throws BrowserRuntimeException {
        stopCurrentGeneration(epoch); // never leak a previous generation
        final long gen = ++generation;
        state = State.STARTING;
        listener.onStarting(gen);
        Sidecar started;
        try {
            started = starter.start();
        } catch (BrowserRuntimeException failure) {
            if (epoch == ownerEpoch) {
                state = State.FAILED;
                this.current = null;
                listener.onFailed(gen, failure.getMessage());
            }
            throw failure;
        }
        if (epoch != ownerEpoch) {
            // This owner was abandoned while the start ran: never publish into the new epoch.
            started.close();
            throw new BrowserRuntimeException("browser start abandoned (owner replaced)", true);
        }
        this.current = started;
        state = State.READY;
        listener.onReady(gen);
    }

    private String callOnOwner(final String tool, final Map<String, Object> arguments)
            throws BrowserRuntimeException {
        try {
            return runOnOwner(new Callable<String>() {
                public String call() throws Exception {
                    if (state != State.READY || current == null) {
                        throw new BrowserRuntimeException("browser runtime is not ready", true);
                    }
                    return current.call(tool, arguments);
                }
            }, callTimeoutMillis);
        } catch (OwnerTimeout hung) {
            // Exactly the former "sidecar process killed on client timeout": the generation is terminated
            // OFF the owner thread (the stuck one) — the cut connection fails the stuck call —, the command
            // reports endpoint-unavailable and execute() restarts + retries once.
            terminateCurrent("browser call timed out after " + seconds() + " s (tool " + tool + ")");
            throw new BrowserRuntimeException("browser call timed out after " + seconds() + " s (tool "
                    + tool + "); the browser generation was terminated", true);
        }
    }

    /** Owner thread: tear the current generation down (idempotent). Stale-epoch tasks touch nothing. */
    private void stopCurrentGeneration(long epoch) {
        if (epoch != ownerEpoch) {
            return; // a task of an abandoned owner — the state belongs to the new epoch now
        }
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

    // ------------------------------------------------------------------ foreign-thread emergency paths

    /** Drop the current generation and terminate it WITHOUT the owner thread (which is the stuck one). */
    private void terminateCurrent(String reason) {
        Sidecar hung = current;
        current = null;
        state = State.FAILED;
        long gen = generation;
        System.err.println("[browser] terminating generation " + gen + ": " + reason);
        if (hung != null) {
            try {
                hung.terminate();
            } catch (RuntimeException ignored) {
                // best effort — the generation is gone for this runtime either way
            }
        }
        listener.onFailed(gen, reason);
    }

    /** The owner thread is wedged: abandon it (daemon, ends with its call) and continue on a fresh owner. */
    private synchronized void abandonOwner(String reason) {
        ownerEpoch++;
        ExecutorService wedged = owner;
        owner = newOwner();
        wedged.shutdownNow();
        System.err.println("[browser] owner thread abandoned: " + reason);
        terminateCurrent(reason);
    }

    private <T> T runOnOwner(Callable<T> task, long timeoutMillis)
            throws BrowserRuntimeException, OwnerTimeout {
        Future<T> future;
        try {
            future = owner.submit(task);
        } catch (RuntimeException rejected) {
            throw new BrowserRuntimeException("browser runtime owner unavailable: " + rejected.getMessage(),
                    true);
        }
        try {
            return timeoutMillis > 0 ? future.get(timeoutMillis, TimeUnit.MILLISECONDS) : future.get();
        } catch (TimeoutException hung) {
            future.cancel(true);
            throw new OwnerTimeout("the browser owner did not answer within " + (timeoutMillis / 1000L) + " s");
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

    private long seconds() {
        return callTimeoutMillis / 1000L;
    }

    // ------------------------------------------------------------------ the productive in-process starter

    /** The productive starter: start the Playwright browser in-process (blocks until it is ready). */
    private static SidecarStarter inProcessStarter(final ResearchRuntimeConfig config,
                                                   final String browserConfigPath) {
        return new SidecarStarter() {
            public Sidecar start() throws BrowserRuntimeException {
                final PlaywrightBrowserRuntime runtime;
                try {
                    String extra = System.getProperty(BROWSER_ARGS_PROPERTY, "").trim();
                    runtime = PlaywrightBrowserRuntime.start(new PlaywrightBrowserRuntime.Options(
                            config.getBrowserChannel(), config.isHeadless(), config.isAllowPrivateNetworks(),
                            argument(extra, "--search-url="),
                            PlaywrightBrowserRuntime.loadBrowserConfig(browserConfigPath),
                            "host-port".equalsIgnoreCase(argument(extra, "--domain-key-mode="))));
                } catch (PlaywrightBrowserRuntime.StartFailure notReady) {
                    throw new BrowserRuntimeException("browser failed to start: " + notReady.readiness(), true);
                } catch (Exception failure) {
                    throw new BrowserRuntimeException("browser failed to start: " + failure.getMessage(), true);
                }
                return new Sidecar() {
                    public String call(String tool, Map<String, Object> arguments)
                            throws BrowserRuntimeException {
                        try {
                            return runtime.call(tool, arguments);
                        } catch (BrowserException ex) {
                            throw new BrowserRuntimeException(ex.getMessage(), generationDead(ex));
                        } catch (RuntimeException ex) {
                            throw new BrowserRuntimeException("browser call failed: " + ex.getMessage(), true);
                        }
                    }

                    public String controlCall(String tool, Map<String, Object> arguments)
                            throws BrowserRuntimeException {
                        try {
                            return runtime.controlCall(tool, arguments);
                        } catch (BrowserException ex) {
                            throw new BrowserRuntimeException(ex.getMessage(), generationDead(ex));
                        } catch (RuntimeException ex) {
                            throw new BrowserRuntimeException("browser call failed: " + ex.getMessage(), true);
                        }
                    }

                    public boolean isAlive() {
                        return runtime.isAlive();
                    }

                    public void close() {
                        runtime.close();
                    }

                    public void terminate() {
                        runtime.terminate();
                    }
                };
            }
        };
    }

    /**
     * A tool failure is a plain error (the loop decides); a generation whose session is gone — the owner
     * thread ended, the user closed the browser window — is "endpoint unavailable": the runtime restarts
     * it and retries the command once, exactly like a dead sidecar process before.
     */
    static boolean generationDead(BrowserException failure) {
        String message = failure.getMessage() == null ? "" : failure.getMessage();
        return message.contains("is closed") || message.contains("BROWSER_CLOSED");
    }

    /** The value of {@code <prefix><value>} among whitespace-separated arguments, or null. */
    static String argument(String arguments, String prefix) {
        if (arguments == null || arguments.isEmpty()) {
            return null;
        }
        for (String token : arguments.split("\\s+")) {
            if (token.startsWith(prefix)) {
                return token.substring(prefix.length());
            }
        }
        return null;
    }
}
