package com.aresstack.askai.research.host;

import java.util.Map;

/**
 * The neutral seam the browser bridge (and, through it, the TeamAgent) uses to run a browser command. The
 * TeamAgent, its state machine and the bridge know ONLY this port — never a concrete {@code ProcessBuilder},
 * Playwright or browser runtime class. The browser is a short-lived, disposable, RESTARTABLE runtime with a lifetime
 * of a single research/browsing phase, not of the session: it is started lazily on the first command and
 * stopped again when the phase ends, and a broken generation can be thrown away and replaced without
 * disturbing the TeamAgent.
 */
public interface BrowserRuntimePort {

    /** The observable lifecycle of the underlying browser runtime. */
    enum State { STOPPED, STARTING, READY, STOPPING, FAILED, CLOSED }

    /** Observes runtime transitions so the session can surface a transient "Starte Browser…" chat activity. */
    interface Listener {
        void onStarting(long generation);

        void onReady(long generation);

        void onFailed(long generation, String detail);

        void onStopped(long generation);

        Listener NONE = new Listener() {
            public void onStarting(long generation) {
            }

            public void onReady(long generation) {
            }

            public void onFailed(long generation, String detail) {
            }

            public void onStopped(long generation) {
            }
        };
    }

    /** A typed browser failure (the caller decides how to surface it); never a fabricated success. */
    final class BrowserRuntimeException extends Exception {
        private final boolean endpointUnavailable;

        public BrowserRuntimeException(String message, boolean endpointUnavailable) {
            super(message);
            this.endpointUnavailable = endpointUnavailable;
        }

        public boolean isEndpointUnavailable() {
            return endpointUnavailable;
        }
    }

    /** Observe lifecycle transitions (to surface a transient "Starte Browser…" chat activity). */
    void setListener(Listener listener);

    /** Ensure the runtime is started (lazy, off the EDT), then run {@code tool} and return the raw text. */
    String execute(String tool, Map<String, Object> arguments) throws BrowserRuntimeException;

    /**
     * CONTROL-PLANE call (HUD command poll): best-effort, and OUT OF BAND — it must answer even while a
     * data command ({@link #execute}) blocks the runtime, and it never starts or restarts a browser. A
     * runtime without a running generation simply reports nothing. The default (fakes, simple backends)
     * falls back to the data path.
     */
    default String executeControl(String tool, Map<String, Object> arguments)
            throws BrowserRuntimeException {
        return execute(tool, arguments);
    }

    /** Start the runtime if it is not already running (lazy); no-op when READY. */
    void ensureStarted() throws BrowserRuntimeException;

    /** Throw the current (possibly broken) generation away and start a fresh one. */
    void restart() throws BrowserRuntimeException;

    /** Stop the current browser phase: close the browser and end the browser runtime; the runtime returns to STOPPED. */
    void stop();

    boolean isReady();

    /** Terminal shutdown: stop and release the owner thread. Idempotent. */
    void close();
}
