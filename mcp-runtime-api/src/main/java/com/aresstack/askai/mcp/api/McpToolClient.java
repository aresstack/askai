package com.aresstack.askai.mcp.api;

import java.util.Map;

/**
 * Neutral client port for calling tools on a remote MCP endpoint (no Solon/MCP-SDK types). The plugin's
 * browser bridge uses this to delegate {@code web_*} calls to the Java-21 Playwright sidecar; the Solon
 * implementation lives in {@code mcp-solon-runtime} (host-provided, like the server side of this API).
 */
public interface McpToolClient {

    /** @return the tool's text result. @throws McpToolCallException for tool errors or a dead endpoint. */
    String callTool(String toolName, Map<String, Object> arguments) throws McpToolCallException;

    /** Idempotent. */
    void close();

    /** Tool failure vs. unreachable endpoint stays distinguishable across the neutral boundary. */
    class McpToolCallException extends Exception {
        private final boolean endpointUnavailable;

        public McpToolCallException(String message, boolean endpointUnavailable) {
            super(message);
            this.endpointUnavailable = endpointUnavailable;
        }

        public boolean isEndpointUnavailable() {
            return endpointUnavailable;
        }
    }
}
