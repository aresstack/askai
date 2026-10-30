package com.aresstack.askai.mcp.solon;

import com.aresstack.askai.mcp.api.McpEndpointDefinition;
import com.aresstack.askai.mcp.api.McpEndpointHandle;
import com.aresstack.askai.mcp.api.McpTestTools;

import org.junit.Test;

import java.util.Collections;

/**
 * Regression guard for the "app hangs on close" bug: booting Solon spawns a NON-daemon {@code HTTP-Dispatcher}
 * thread that survives the per-instance {@link SolonMcpServerRuntime#shutdown()} and keeps the JVM alive.
 * {@link SolonMcpServerRuntime#stopSharedServer()} (the FINAL JVM-teardown call) must release it so the process
 * can exit naturally. Sorts alphabetically AFTER {@code SolonMcpServerRuntimeTest}, so it stops the shared
 * server last — nothing re-boots Solon afterwards in the same JVM (the stop-then-restart is unreliable).
 */
public class SolonThreadDaemonDiagnosticTest {

    @Test
    public void listNonDaemonThreadsAfterBoot() {
        System.out.println("=== NON-DAEMON before boot ===");
        dump();

        SolonMcpServerRuntime runtime = new SolonMcpServerRuntime();
        McpEndpointHandle handle = runtime.registerEndpoint(
                new McpEndpointDefinition("research", "Research Control"));
        runtime.updateTools(handle, Collections.singletonList(McpTestTools.ping()));

        System.out.println("=== NON-DAEMON after boot ===");
        dump();

        // CLIENT lifecycle: a used Solon MCP tool client spawns a NON-daemon unnamed scheduler
        // ("pool-N-thread-1"). McpToolClient.close() must release it — the GUI leaked exactly these
        // (LazyRestartableBrowserRuntime closed the client only when it was java.io.Closeable, which
        // McpToolClient is not) and the JVM could not exit after browser-sidecar use.
        java.util.Set<String> poolsBefore = aliveNonDaemonPoolThreads();
        com.aresstack.askai.mcp.api.McpToolClient client = new SolonMcpToolClientFactory()
                .connect(runtime.endpointUrl(handle), "streamable");
        try {
            client.callTool("ping", new java.util.HashMap<String, Object>());
        } catch (com.aresstack.askai.mcp.api.McpToolClient.McpToolCallException ex) {
            throw new AssertionError("ping over the tool client failed: " + ex.getMessage(), ex);
        }
        java.util.Set<String> poolsDuring = aliveNonDaemonPoolThreads();
        poolsDuring.removeAll(poolsBefore);
        System.out.println("=== client pool threads spawned: " + poolsDuring + " ===");
        client.close();
        for (int i = 0; i < 50 && !aliveNonDaemonPoolThreads(poolsDuring).isEmpty(); i++) {
            try { Thread.sleep(100); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
        org.junit.Assert.assertTrue("McpToolClient.close() must release the client's non-daemon scheduler "
                        + "threads, still alive: " + aliveNonDaemonPoolThreads(poolsDuring),
                aliveNonDaemonPoolThreads(poolsDuring).isEmpty());

        runtime.unregisterEndpoint(handle);
        runtime.shutdown();

        System.out.println("=== NON-DAEMON after shutdown (HTTP-Dispatcher still alive — this is the hang) ===");
        boolean dispatcherAfterShutdown = hasHttpDispatcher();
        dump();
        org.junit.Assert.assertTrue(
                "shutdown() must NOT stop the shared server (test restart safety)", dispatcherAfterShutdown);

        // FINAL teardown: stopSharedServer() must release EVERY non-daemon server thread so the JVM can exit:
        // the HTTP-Dispatcher (via Solon.stopBlock) AND the jdkhttp-N worker pool (via the reflective
        // executor shutdown — Solon itself never stops it; verified as the exact cause of the GUI exit hang).
        SolonMcpServerRuntime.stopSharedServer();
        // Give the dispatcher and the interrupted pool workers a brief moment to unwind.
        for (int i = 0; i < 50 && (hasHttpDispatcher() || hasJdkHttpWorker()); i++) {
            try { Thread.sleep(100); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
        System.out.println("=== NON-DAEMON after stopSharedServer (HTTP-Dispatcher + jdkhttp-* must be gone) ===");
        dump();
        org.junit.Assert.assertFalse(
                "stopSharedServer() must release the non-daemon HTTP-Dispatcher", hasHttpDispatcher());
        org.junit.Assert.assertFalse(
                "stopSharedServer() must shut down the non-daemon jdkhttp worker pool", hasJdkHttpWorker());
    }

    /**
     * The JDK HttpServer dispatcher thread. It is NAMED "HTTP-Dispatcher" only on JDK 9+; on JDK 8 (the
     * CI JVM) it is a generic "Thread-N", so the name check alone made this guard pass locally and fail
     * in Actions. The stable signal across JDK 8..21 is the dispatcher's run-loop frame
     * {@code sun.net.httpserver.ServerImpl$Dispatcher} — only the dedicated dispatcher thread runs it
     * (workers run {@code ServerImpl$Exchange}), so workers can never satisfy this check.
     */
    private static boolean hasHttpDispatcher() {
        for (java.util.Map.Entry<Thread, StackTraceElement[]> entry
                : Thread.getAllStackTraces().entrySet()) {
            Thread t = entry.getKey();
            if (t == null || !t.isAlive() || t.isDaemon()) {
                continue;
            }
            if ("HTTP-Dispatcher".equals(t.getName())) {
                return true;
            }
            for (StackTraceElement frame : entry.getValue()) {
                if ("sun.net.httpserver.ServerImpl$Dispatcher".equals(frame.getClassName())) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Names of all ALIVE NON-daemon default-named executor threads ("pool-N-thread-M"). */
    private static java.util.Set<String> aliveNonDaemonPoolThreads() {
        java.util.Set<String> names = new java.util.HashSet<String>();
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t != null && t.isAlive() && !t.isDaemon() && t.getName().startsWith("pool-")) {
                names.add(t.getName());
            }
        }
        return names;
    }

    /** The subset of {@code candidates} that is still alive (non-daemon "pool-" threads). */
    private static java.util.Set<String> aliveNonDaemonPoolThreads(java.util.Set<String> candidates) {
        java.util.Set<String> alive = aliveNonDaemonPoolThreads();
        alive.retainAll(candidates);
        return alive;
    }

    private static boolean hasJdkHttpWorker() {
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t != null && t.isAlive() && !t.isDaemon() && t.getName().startsWith("jdkhttp-")) {
                return true;
            }
        }
        return false;
    }

    private static void dump() {
        int n = 0;
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t == null || !t.isAlive() || t.isDaemon()) continue;
            StackTraceElement[] s = t.getStackTrace();
            String top = s.length > 0 ? s[0].toString() : "(no frame)";
            System.out.println("  NONDAEMON '" + t.getName() + "' state=" + t.getState()
                    + " group=" + (t.getThreadGroup() == null ? "?" : t.getThreadGroup().getName())
                    + " @ " + top);
            n++;
        }
        System.out.println("  total non-daemon = " + n);
    }
}
