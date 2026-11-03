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

    /** Grace period for the graceful shutdown before the process is terminated explicitly. */
    static final long DEFAULT_GRACE_MILLIS = 8000L;

    interface Exiter {
        void exit(int status);
    }

    private ExitWatchdog() {
    }

    static Thread start(long graceMillis) {
        return start(graceMillis, new Exiter() {
            public void exit(int status) {
                System.exit(status);
            }
        });
    }

    static Thread start(final long graceMillis, final Exiter exiter) {
        Thread watchdog = new Thread(new Runnable() {
            public void run() {
                long deadline = System.currentTimeMillis() + graceMillis;
                List<Thread> blocking = blockingThreads();
                while (!blocking.isEmpty() && System.currentTimeMillis() < deadline) {
                    try {
                        Thread.sleep(200L);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    blocking = blockingThreads();
                }
                if (blocking.isEmpty()) {
                    return; // the JVM exits on its own
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
