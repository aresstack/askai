package com.aresstack.askai.mcp.solon;

import com.aresstack.askai.mcp.api.McpToolClient;
import com.aresstack.askai.mcp.api.McpToolClientFactory;

import org.noear.solon.ai.chat.tool.ToolResult;
import org.noear.solon.ai.mcp.client.McpClientProvider;
import org.noear.solon.net.http.HttpTimeout;

import java.time.Duration;
import java.util.Map;

/**
 * Host-provided {@link McpToolClientFactory} over the real Solon streamable-HTTP MCP client. Connection and
 * timeout failures surface as {@code endpointUnavailable}; everything else as a plain tool failure. The URL
 * (which carries the token in its path) is never logged.
 */
public final class SolonMcpToolClientFactory implements McpToolClientFactory {

    /** The per-call timeout of {@link #connect(String, String)}: control-style calls, not the browser data lane. */
    public static final int DEFAULT_REQUEST_TIMEOUT_SECONDS = 60;
    /** The MCP initialize handshake and the TCP connect (loopback) — never the per-call budget. */
    static final int INITIALIZATION_TIMEOUT_SECONDS = 15;

    @Override
    public McpToolClient connect(String url, String transport) {
        return connect(url, transport, DEFAULT_REQUEST_TIMEOUT_SECONDS);
    }

    @Override
    public McpToolClient connect(String url, String transport, int requestTimeoutSeconds) {
        int seconds = requestTimeoutSeconds > 0 ? requestTimeoutSeconds : DEFAULT_REQUEST_TIMEOUT_SECONDS;
        final McpClientProvider client = McpClientProvider.builder()
                .apiUrl(url)
                .channel(transport == null || transport.isEmpty() ? "streamable" : transport)
                .cacheSeconds(0)
                .initializationTimeout(Duration.ofSeconds(INITIALIZATION_TIMEOUT_SECONDS))
                .requestTimeout(Duration.ofSeconds(seconds))
                .httpTimeout(httpTimeout(seconds))
                .build();
        return new McpToolClient() {
            public String callTool(String toolName, Map<String, Object> arguments)
                    throws McpToolCallException {
                try {
                    ToolResult result = client.callTool(toolName, arguments);
                    String text = String.valueOf(result);
                    if (text.startsWith("Tool failed:") || text.startsWith("Not allowed")) {
                        throw new McpToolCallException(text, false);
                    }
                    return text;
                } catch (McpToolCallException ex) {
                    throw ex;
                } catch (RuntimeException ex) {
                    String message = ex.getMessage() == null ? ex.toString() : ex.getMessage();
                    boolean unavailable = message.contains("Connection") || message.contains("connect")
                            || message.contains("Timeout") || message.contains("timeout");
                    throw new McpToolCallException(message, unavailable);
                }
            }

            public void close() {
                try {
                    client.close();
                } catch (RuntimeException ignored) {
                }
            }
        };
    }

    /**
     * The HTTP socket timeouts of the streamable transport. Solon's {@code requestTimeout} only bounds the
     * MCP request; the HTTP read timeout stays at Solon's 30 s default unless it is set here, and a blocking
     * sidecar call (web_search_prepare over three result pages) then died with "SocketTimeoutException:
     * Read timed out" after 30 s, reported as a dead sidecar, regardless of the request timeout. Connect is
     * bounded like the initialize handshake; write and read cover the whole call.
     */
    static HttpTimeout httpTimeout(int requestTimeoutSeconds) {
        return HttpTimeout.of(INITIALIZATION_TIMEOUT_SECONDS, requestTimeoutSeconds, requestTimeoutSeconds);
    }
}
