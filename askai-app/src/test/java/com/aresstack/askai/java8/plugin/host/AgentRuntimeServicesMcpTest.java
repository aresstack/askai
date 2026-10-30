package com.aresstack.askai.java8.plugin.host;

import com.aresstack.askai.mcp.api.McpServerRegistry;
import com.aresstack.askai.mcp.api.McpToolClientFactory;

import org.junit.Test;

import static org.junit.Assert.assertNotNull;

/**
 * MCP stays the tool protocol (ADR 0001): the host publishes both the server registry and the tool-client
 * factory, so a plugin reaches tools only through MCP, also without a local model runtime.
 */
public class AgentRuntimeServicesMcpTest {

    @Test
    public void publishesTheMcpServerRegistryAndTheToolClientFactory() {
        AgentRuntimeServices services = new AgentRuntimeServices(null);
        try {
            assertNotNull(services.asServiceMap().get(McpServerRegistry.class));
            assertNotNull("the MCP tool-client factory is published to plugins",
                    services.asServiceMap().get(McpToolClientFactory.class));
        } finally {
            services.shutdown();
        }
    }
}
