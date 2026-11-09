package com.aresstack.askai.research.outline;

/**
 * One outline job at a time per session (plan PR 22, shared with PR 24): a generation and a single-chapter
 * regeneration exclude each other, and {@code finish-outline} is refused while a job runs.
 */
public final class OutlineJobGuard {

    /** The running job; closing it frees the guard. */
    public interface Job extends AutoCloseable {
        @Override
        void close();
    }

    private String running;

    /**
     * Start a job.
     * @throws IllegalStateException with {@link #busyReason()} when another job runs
     */
    public synchronized Job begin(String label) {
        if (running != null) {
            throw new IllegalStateException(busyReason());
        }
        final String mine = label == null || label.trim().isEmpty() ? "an outline job" : label.trim();
        running = mine;
        return new Job() {
            private boolean closed;

            public void close() {
                synchronized (OutlineJobGuard.this) {
                    if (!closed) {
                        closed = true;
                        running = null;
                    }
                }
            }
        };
    }

    public synchronized boolean isBusy() {
        return running != null;
    }

    /** Why the outline is busy, or {@code ""}. */
    public synchronized String busyReason() {
        return running == null ? "" : "The outline is busy: " + running + " is running.";
    }
}
