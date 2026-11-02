package com.aresstack.askai.research.runtime.host;

import com.aresstack.askai.mcp.api.InProcessMcpServerRegistry;
import com.aresstack.askai.mcp.api.McpToolCall;
import com.aresstack.askai.mcp.api.McpToolResult;

import java.util.List;
import java.util.Map;

/**
 * The host tool endpoints inside the AskAI JVM (askai #48): a tool call is a direct method call on the
 * handler the host registered in its {@link InProcessMcpServerRegistry}, with no network and no
 * serialisation. URLs have the registry's form {@code inprocess://<endpointId>/<token>}.
 */
public final class InProcessHostToolEndpoints implements HostToolEndpoints {

    private static final String SCHEME = "inprocess://";

    private final InProcessMcpServerRegistry registry;

    public InProcessHostToolEndpoints(InProcessMcpServerRegistry registry) {
        if (registry == null) {
            throw new IllegalArgumentException("registry must not be null");
        }
        this.registry = registry;
    }

    @Override
    public HostToolEndpoint connect(String url, String transport, int requestTimeoutSeconds) {
        if (url == null || !url.startsWith(SCHEME)) {
            throw new IllegalArgumentException("not an in-process endpoint URL: " + url);
        }
        String rest = url.substring(SCHEME.length());
        int slash = rest.lastIndexOf('/');
        if (slash <= 0 || slash == rest.length() - 1) {
            throw new IllegalArgumentException("in-process endpoint URL without token: " + url);
        }
        return new Endpoint(registry, rest.substring(0, slash), rest.substring(slash + 1));
    }

    private static final class Endpoint implements HostToolEndpoint {
        private final InProcessMcpServerRegistry registry;
        private final String endpointId;
        private final String token;
        private volatile boolean closed;

        Endpoint(InProcessMcpServerRegistry registry, String endpointId, String token) {
            this.registry = registry;
            this.endpointId = endpointId;
            this.token = token;
        }

        @Override
        public List<String> toolNames() {
            return registry.listToolNames(endpointId, token);
        }

        @Override
        public HostToolResult callTool(String toolName, Map<String, Object> args) {
            if (closed) {
                throw new IllegalStateException("endpoint closed: " + endpointId);
            }
            if (!registry.isEndpointRegistered(endpointId)) {
                throw new IllegalStateException("Connection to " + endpointId + " is gone");
            }
            McpToolResult result = registry.invoke(endpointId, token, new McpToolCall(toolName, args));
            return new HostToolResult(result.getText(), result.isError());
        }

        @Override
        public String call(String toolName, Map<String, Object> args) throws ToolFailure, EndpointUnavailable {
            HostToolResult result;
            try {
                result = callTool(toolName, args);
            } catch (IllegalStateException gone) {
                throw new EndpointUnavailable(gone.getMessage());
            }
            String text = result.getText();
            if (result.isError() || text.startsWith("Tool failed:") || text.startsWith("Not allowed")) {
                throw new ToolFailure(text);
            }
            return text;
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
