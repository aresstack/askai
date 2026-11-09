package com.aresstack.askai.research.outline;

import java.util.concurrent.atomic.AtomicReference;

/**
 * The one outline job that may run at a time (plan PR 22 and PR 24): a generation, a single-chapter
 * regeneration or a brief run. While a job runs, {@code finish-outline} is refused with {@link #busyReason()}
 * and a second job is refused instead of interleaving its writes with the first.
 */
public final class OutlineJobGuard {

    /** A started job; close it in a {@code finally} block. */
    public interface Job extends AutoCloseable {
        @Override
        void close();
    }

    private final AtomicReference<String> running = new AtomicReference<String>();

    /**
     * Starts {@code label} (shown as "... is running").
     * @throws IllegalStateException while another job runs
     */
    public Job begin(final String label) {
        final String name = label == null || label.trim().isEmpty() ? "An outline job" : label.trim();
        if (!running.compareAndSet(null, name)) {
            throw new IllegalStateException(running.get() + " is running. Wait until it ends or cancel it.");
        }
        return new Job() {
            private boolean closed;

            @Override
            public synchronized void close() {
                if (!closed) {
                    closed = true;
                    running.compareAndSet(name, null);
                }
            }
        };
    }

    /** Whether a job runs now. */
    public boolean isBusy() {
        return running.get() != null;
    }

    /** The refusal of {@code finish-outline} while a job runs, or {@code null}. */
    public String busyReason() {
        String name = running.get();
        return name == null ? null : name + " is running. Finish the outline after it ends.";
    }
}
