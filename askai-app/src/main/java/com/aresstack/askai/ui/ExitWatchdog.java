package com.aresstack.askai.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Safety net for closing AskAI: once the main window is closed, the JVM must terminate even if some library
 * (JGroups, HTTP clients, audio, Playwright, executors, ...) left a non-daemon thread running or the graceful
 * shutdown itself hangs. The watchdog is a daemon thread that waits for the process to wind down on its own;
 * if non-daemon threads are still alive after the grace period, it logs their names and stacks (so the culprit
 * can be fixed) and then calls {@link System#exit(int)}. Shutdown hooks still run on that exit.
 */
final class ExitWatchdog {

    /** Grace period before forcing the exit; longer than the recorder finalization bound (10 s). */
    static final long DEFAULT_GRACE_MILLIS = 12000L;

    /** Time {@link System#exit} (i.e. the shutdown hooks) may take before the JVM is halted. */
    static final long HALT_AFTER_EXIT_MILLIS = 3000L;

    private static final java.util.concurrent.atomic.AtomicBoolean ARMED =
            new java.util.concurrent.atomic.AtomicBoolean();

    /** Starts the production watchdog exactly once (window closing and performShutdown both call this). */
    static void arm() {
        if (ARMED.compareAndSet(false, true)) {
            start(DEFAULT_GRACE_MILLIS);
        }
    }

    interface Exiter {
        void exit(int status);
    }

    private ExitWatchdog() {
    }

    static Thread start(long graceMillis) {
        return start(graceMillis, new Exiter() {
            public void exit(final int status) {
                exitOrHalt(status, HALT_AFTER_EXIT_MILLIS);
            }
        });
    }

    static Thread start(final long graceMillis, final Exiter exiter) {
        Thread watchdog = new Thread(new Runnable() {
            public void run() {
                long deadline = System.currentTimeMillis() + graceMillis;
                // Keep waiting for the whole grace period even when no thread blocks right now: the JVM may
                // already be running (possibly hanging) shutdown hooks, which are non-daemon threads too.
                while (System.currentTimeMillis() < deadline) {
                    try {
                        Thread.sleep(200L);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                List<Thread> blocking = blockingThreads();
                if (blocking.isEmpty()) {
                    return; // the JVM is exiting on its own
                }
                System.err.println("[shutdown] " + blocking.size()
                        + " non-daemon thread(s) still alive after window close; forcing exit:");
                Map<Thread, StackTraceElement[]> stacks = Thread.getAllStackTraces();
                for (Thread thread : blocking) {
                    System.err.println("[shutdown]   \"" + thread.getName() + "\" " + thread.getState());
                    StackTraceElement[] stack = stacks.get(thread);
                    if (stack != null) {
                        for (int i = 0; i < Math.min(stack.length, 8); i++) {
                            System.err.println("[shutdown]       at " + stack[i]);
                        }
                    }
                }
                exiter.exit(0);
            }
        }, "askai-exit-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
        return watchdog;
    }

    /**
     * {@link System#exit} runs the shutdown hooks and blocks until they finish; a hook that hangs (runtime
     * stop, Solon, browser close) would keep the process alive forever. So exit runs on its own daemon thread
     * and the JVM is halted if it has not terminated after {@code haltAfterMillis}.
     */
    static void exitOrHalt(final int status, long haltAfterMillis) {
        Thread exiter = new Thread(new Runnable() {
            public void run() {
                System.exit(status);
            }
        }, "askai-exit");
        exiter.setDaemon(true);
        exiter.start();
        try {
            Thread.sleep(haltAfterMillis);
        } catch (InterruptedException ignored) {
            // fall through to halt
        }
        System.err.println("[shutdown] shutdown hooks did not finish within " + haltAfterMillis + " ms; halting");
        System.err.flush();
        Runtime.getRuntime().halt(status);
    }

    /** Live non-daemon threads that keep the JVM running (the JVM's own DestroyJavaVM excluded). */
    static List<Thread> blockingThreads() {
        List<Thread> result = new ArrayList<Thread>();
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread.isAlive() && !thread.isDaemon() && !"DestroyJavaVM".equals(thread.getName())
                    && thread != Thread.currentThread()) {
                result.add(thread);
            }
        }
        return result;
    }
}
