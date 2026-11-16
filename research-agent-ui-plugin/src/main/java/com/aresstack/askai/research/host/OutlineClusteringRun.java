package com.aresstack.askai.research.host;

import com.aresstack.askai.research.knowledge.ClusteringMonitor;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * PR 20: the one clustering run of a session as the outline drawer sees it — progress, Cancel and the outcome
 * (done with the number of passages left out by the corpus limit, cancelled, or failed with its message). The
 * rebuild step drives it; the drawer reads {@link #status()} and calls {@link #cancel()}. Listeners hear a
 * change at most once per percent of progress, so 6,000 matrix rows do not flood the EDT.
 */
public final class OutlineClusteringRun implements ClusteringMonitor {

    public enum State { IDLE, RUNNING, DONE, CANCELLED, FAILED }

    /** An immutable snapshot for the drawer. */
    public static final class Status {
        public final State state;
        public final String stage;
        public final long done;
        public final long total;
        /** The failure message (FAILED) or "". */
        public final String message;
        /** Passages left out by the corpus limit (DONE). */
        public final int omittedPassages;

        Status(State state, String stage, long done, long total, String message, int omittedPassages) {
            this.state = state;
            this.stage = stage == null ? "" : stage;
            this.done = done;
            this.total = total;
            this.message = message == null ? "" : message;
            this.omittedPassages = omittedPassages;
        }

        public int percent() {
            return total <= 0 ? 0 : (int) Math.min(100L, done * 100L / total);
        }

        /** One English line for the drawer; "" when idle. */
        public String describe() {
            switch (state) {
                case RUNNING:
                    return (stage.isEmpty() ? "Clustering topics" : stage) + " … " + percent() + " %";
                case DONE:
                    return omittedPassages > 0
                            ? "Topics clustered. The corpus limit left out " + omittedPassages + " passages."
                            : "Topics clustered.";
                case CANCELLED:
                    return "Clustering cancelled. The previous topics are kept.";
                case FAILED:
                    return "Clustering failed: " + message;
                default:
                    return "";
            }
        }
    }

    private static final Status IDLE = new Status(State.IDLE, "", 0, 0, "", 0);

    private volatile Status status = IDLE;
    private volatile boolean cancelRequested;
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<Runnable>();

    public Status status() {
        return status;
    }

    public void addListener(Runnable listener) {
        if (listener != null) {
            listeners.addIfAbsent(listener);
        }
    }

    /** Asks a running clustering to stop at its next check; without a running one nothing happens. */
    public void cancel() {
        if (status.state == State.RUNNING) {
            cancelRequested = true;
        }
    }

    public boolean isRunning() {
        return status.state == State.RUNNING;
    }

    void started() {
        cancelRequested = false;
        set(new Status(State.RUNNING, "Reading the corpus", 0, 0, "", 0));
    }

    void finished(int omittedPassages) {
        set(new Status(State.DONE, "", 0, 0, "", omittedPassages));
    }

    void cancelled() {
        set(new Status(State.CANCELLED, "", 0, 0, "", 0));
    }

    void failed(String message) {
        set(new Status(State.FAILED, "", 0, 0, message == null || message.trim().isEmpty()
                ? "unknown error" : message.trim(), 0));
    }

    @Override
    public void progress(String stage, long done, long total) {
        Status before = status;
        Status next = new Status(State.RUNNING, stage, done, total, "", 0);
        status = next;
        if (!next.stage.equals(before.stage) || next.percent() != before.percent()) {
            fire();
        }
    }

    @Override
    public boolean isCancelled() {
        return cancelRequested;
    }

    private void set(Status next) {
        status = next;
        fire();
    }

    private void fire() {
        for (Runnable listener : listeners) {
            try {
                listener.run();
            } catch (RuntimeException ignored) {
                // a broken observer never stops the clustering
            }
        }
    }
}
