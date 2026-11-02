package com.aresstack.askai.research.runtime.loop;

import org.noear.solon.ai.chat.tool.ToolResult;
import org.noear.solon.ai.mcp.client.McpClientProvider;
import org.noear.solon.net.http.HttpTimeout;

import java.time.Duration;
import java.util.Map;

/**
 * Production {@link ToolInvoker}: a real Solon MCP client over a streamable endpoint. Tool-level failures
 * (error results / call exceptions) surface as {@link ToolFailure}; a dead endpoint as
 * {@link EndpointUnavailable}. This is the exact invoker 36C reuses against the live Playwright sidecar.
 */
public final class SolonToolInvoker implements com.aresstack.askai.research.runtime.host.HostToolEndpoint {

    /** Fallback request timeout for quick control channels when the caller names none. */
    public static final int DEFAULT_REQUEST_TIMEOUT_SECONDS = 30;
    /** The MCP initialize handshake and the TCP connect (loopback) — never the per-call budget. */
    static final int INITIALIZATION_TIMEOUT_SECONDS = 15;

    private final McpClientProvider client;

    public SolonToolInvoker(String url, String transport) {
        this(url, transport, DEFAULT_REQUEST_TIMEOUT_SECONDS);
    }

    /**
     * @param requestTimeoutSeconds the per-call MCP timeout — the BROWSER channel must pass the
     * configured value: a multi-page SERP prepare legitimately runs for minutes, and a too-small
     * timeout here was misread as "sidecar dead" and ended every search as a technical failure.
     */
    public SolonToolInvoker(String url, String transport, int requestTimeoutSeconds) {
        int seconds = requestTimeoutSeconds > 0 ? requestTimeoutSeconds : DEFAULT_REQUEST_TIMEOUT_SECONDS;
        this.client = McpClientProvider.builder()
                .apiUrl(url)
                .channel(transport == null || transport.isEmpty() ? "streamable" : transport)
                .cacheSeconds(0)
                .initializationTimeout(Duration.ofSeconds(INITIALIZATION_TIMEOUT_SECONDS))
                .requestTimeout(Duration.ofSeconds(seconds))
                .httpTimeout(httpTimeout(seconds))
                .build();
    }

    /**
     * The HTTP socket timeouts of the streamable transport. Solon's {@code requestTimeout} only bounds the
     * MCP request; the underlying HTTP read timeout stays at Solon's 30 s default unless it is set here, and
     * a blocking tool call (a web_search_prepare over three result pages) then died with
     * "SocketTimeoutException: Read timed out" after 30 s regardless of the configured request timeout.
     * Connect is bounded like the initialize handshake; write and read cover the whole call.
     */
    static HttpTimeout httpTimeout(int requestTimeoutSeconds) {
        return HttpTimeout.of(INITIALIZATION_TIMEOUT_SECONDS, requestTimeoutSeconds, requestTimeoutSeconds);
    }

    @Override
    public String call(String toolName, Map<String, Object> args)
            throws ToolFailure, EndpointUnavailable {
        try {
            ToolResult result = client.callTool(toolName, args);
            String text = String.valueOf(result);
            // The Solon runtime maps handler errors to exceptions client-side; a textual error marker from
            // our own tool contract is still treated as a tool failure.
            if (text.startsWith("Tool failed:") || text.startsWith("Not allowed")) {
                throw new ToolFailure(text);
            }
            return text;
        } catch (ToolFailure ex) {
            throw ex;
        } catch (RuntimeException ex) {
            String message = ex.getMessage() == null ? ex.toString() : ex.getMessage();
            if (message.contains("Connection") || message.contains("connect")
                    || message.contains("Timeout") || message.contains("timeout")) {
                throw new EndpointUnavailable(message);
            }
            throw new ToolFailure(message);
        }
    }

    @Override
    public java.util.List<String> toolNames() {
        java.util.List<String> names = new java.util.ArrayList<String>();
        for (org.noear.solon.ai.chat.tool.FunctionTool tool : client.getTools()) {
            names.add(tool.name());
        }
        return names;
    }

    @Override
    public com.aresstack.askai.research.runtime.host.HostToolResult callTool(String toolName,
                                                                           Map<String, Object> args) {
        ToolResult result = client.callTool(toolName, args);
        return new com.aresstack.askai.research.runtime.host.HostToolResult(String.valueOf(result),
                result != null && result.isError());
    }

    @Override
    public void close() {
        client.close();
    }
}
