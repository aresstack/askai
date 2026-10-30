package com.aresstack.askai.mcp.solon;

import com.aresstack.askai.mcp.api.McpEndpointDefinition;
import com.aresstack.askai.mcp.api.McpEndpointHandle;
import com.aresstack.askai.mcp.api.McpToolCall;
import com.aresstack.askai.mcp.api.McpToolClient;
import com.aresstack.askai.mcp.api.McpToolContribution;
import com.aresstack.askai.mcp.api.McpToolHandler;
import com.aresstack.askai.mcp.api.McpToolParameter;
import com.aresstack.askai.mcp.api.McpToolResult;

import org.junit.AfterClass;
import org.junit.Test;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The per-call timeout handed to {@link SolonMcpToolClientFactory#connect(String, String, int)} must cover
 * the WHOLE blocking call, on the HTTP socket too. Solon's {@code requestTimeout} alone leaves the HTTP read
 * timeout at its 30 s default: a web_search_prepare over three Bing result pages (~30 s on Camoufox) then
 * died with "SocketTimeoutException: Read timed out", the host reported the living sidecar as dead and every
 * search ended as "Websuche technisch fehlgeschlagen". The slow call below deliberately exceeds those 30 s.
 * Sorts alphabetically BEFORE {@code SolonThreadDaemonDiagnosticTest}, which stops the shared server last.
 */
public class SolonMcpToolClientTimeoutTest {

    private static final long OVER_THE_HTTP_DEFAULT_MILLIS = 31_500L;

    private static SolonMcpServerRuntime runtime;

    @AfterClass
    public static void shutdown() {
        if (runtime != null) {
            runtime.shutdown();
        }
    }

    /** {@code slow(millis)}: blocks the handler for the given time, then answers "slept <millis>". */
    private static McpToolContribution slowTool() {
        return McpToolContribution.of("slow", "Sleeps for millis, then answers.",
                new McpToolHandler() {
                    public McpToolResult invoke(McpToolCall call) {
                        long millis = call.getInteger("millis", 0L);
                        try {
                            Thread.sleep(millis);
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                            return McpToolResult.error("interrupted");
                        }
                        return McpToolResult.ok("slept " + millis);
                    }
                },
                McpToolParameter.string("millis", true, "How long to sleep (ms)"));
    }

    private static String endpoint() {
        if (runtime == null) {
            runtime = new SolonMcpServerRuntime();
        }
        McpEndpointHandle handle = runtime.registerEndpoint(
                new McpEndpointDefinition("slow-tools", "Slow tools"));
        runtime.updateTools(handle, Collections.singletonList(slowTool()));
        return runtime.endpointUrl(handle);
    }

    @Test
    public void aCallLongerThanSolonsHttpDefaultSucceedsWithinTheConfiguredTimeout() throws Exception {
        McpToolClient client = new SolonMcpToolClientFactory().connect(endpoint(), "streamable", 90);
        long started = System.nanoTime();
        try {
            String answer = client.callTool("slow",
                    Collections.<String, Object>singletonMap("millis", OVER_THE_HTTP_DEFAULT_MILLIS));
            assertEquals("slept " + OVER_THE_HTTP_DEFAULT_MILLIS, answer);
        } catch (McpToolClient.McpToolCallException ex) {
            fail("a " + OVER_THE_HTTP_DEFAULT_MILLIS + " ms call under a 90 s timeout failed after "
                    + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) + " ms: " + ex.getMessage()
                    + " (endpointUnavailable=" + ex.isEndpointUnavailable() + ")");
        } finally {
            client.close();
        }
        assertTrue("the handler really slept", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
                >= OVER_THE_HTTP_DEFAULT_MILLIS);
    }

    @Test
    public void theConfiguredTimeoutStillBoundsTheCallAsEndpointUnavailable() throws Exception {
        McpToolClient client = new SolonMcpToolClientFactory().connect(endpoint(), "streamable", 2);
        long started = System.nanoTime();
        try {
            client.callTool("slow", Collections.<String, Object>singletonMap("millis", 8_000L));
            fail("a 8 s call must not survive a 2 s timeout");
        } catch (McpToolClient.McpToolCallException ex) {
            assertTrue("a timeout is reported as an unavailable endpoint: " + ex.getMessage(),
                    ex.isEndpointUnavailable());
        } finally {
            client.close();
        }
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertTrue("the timeout fired, not the handler's end (" + elapsedMillis + " ms)",
                elapsedMillis < 8_000L);
    }
}
