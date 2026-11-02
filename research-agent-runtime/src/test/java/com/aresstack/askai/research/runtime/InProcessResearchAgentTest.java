package com.aresstack.askai.research.runtime;

import com.aresstack.askai.acp.AcpConnection;
import com.aresstack.askai.acp.AcpException;
import com.aresstack.askai.acp.AcpPromptState;
import com.aresstack.askai.acp.AcpSession;
import com.aresstack.askai.acp.AcpUpdate;
import com.aresstack.askai.acp.AcpUpdateListener;
import com.aresstack.askai.acp.AgentLaunchSpec;
import com.aresstack.askai.mcp.api.InProcessMcpServerRegistry;
import com.aresstack.askai.mcp.api.McpEndpointDefinition;
import com.aresstack.askai.mcp.api.McpEndpointHandle;
import com.aresstack.askai.mcp.api.McpToolCall;
import com.aresstack.askai.mcp.api.McpToolContribution;
import com.aresstack.askai.mcp.api.McpToolHandler;
import com.aresstack.askai.mcp.api.McpToolResult;
import com.aresstack.askai.research.runtime.host.InProcessHostToolEndpoints;
import org.junit.After;
import org.junit.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * askai #48: the same boundary walk as {@link ResearchAgentRoundTripTest}, but with the agent inside this JVM.
 * No agent process is started, no stdio, no MCP transport: the host tool is called directly in the in-process
 * registry, and the agent's messages reach the listener directly.
 */
public class InProcessResearchAgentTest {

    private final InProcessMcpServerRegistry registry = new InProcessMcpServerRegistry();
    private final ResearchRuntimeShared shared = new ResearchRuntimeShared("chrome");

    @After
    public void close() {
        shared.close();
        registry.shutdown();
    }

    private static final class Collecting implements AcpUpdateListener {
        final List<AcpUpdate> updates = new CopyOnWriteArrayList<AcpUpdate>();
        final AtomicReference<AcpPromptState> terminal = new AtomicReference<AcpPromptState>();
        final CountDownLatch terminated = new CountDownLatch(1);

        public void onUpdate(AcpUpdate update) {
            updates.add(update);
        }

        public void onTerminal(String promptId, AcpPromptState state, String detail) {
            terminal.set(state);
            terminated.countDown();
        }

        boolean sawMessage(String needle) {
            for (AcpUpdate u : updates) {
                if (u.getText().contains(needle)) {
                    return true;
                }
            }
            return false;
        }
    }

    private Set<String> agentThreads() {
        Set<String> names = new java.util.HashSet<String>();
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            names.add(t.getName());
        }
        return names;
    }

    @Test
    public void agentRunsInThisJvmAndCallsTheHostToolDirectly() throws Exception {
        final AtomicInteger statusCalls = new AtomicInteger();
        final AtomicReference<String> callingThread = new AtomicReference<String>();
        McpEndpointHandle handle = registry.registerEndpoint(
                new McpEndpointDefinition("research.inprocess", "Research Control"));
        registry.updateTools(handle, Collections.singletonList(
                McpToolContribution.of("research_status", "Current research state.", new McpToolHandler() {
                    public McpToolResult invoke(McpToolCall call) {
                        statusCalls.incrementAndGet();
                        callingThread.set(Thread.currentThread().getName());
                        return McpToolResult.ok("RESEARCH/running rev=7");
                    }
                })));
        String url = registry.endpointUrl(handle);
        assertTrue(url, url.startsWith("inprocess://"));

        Map<String, String> env = new LinkedHashMap<String, String>();
        env.put("ASKAI_SESSION_ID", "inprocess-1");
        env.put("ASKAI_PROJECT_ID", "p1");
        env.put("ASKAI_RESEARCH_MCP_URL", url);
        env.put("ASKAI_RESEARCH_MCP_TRANSPORT", "streamable");
        // Command and arguments are not used in-process; nothing may be started from them.
        AgentLaunchSpec spec = new AgentLaunchSpec("does-not-exist", Collections.<String>emptyList(), env);

        AcpConnection connection = new InProcessResearchAgentConnector(
                new InProcessHostToolEndpoints(registry), shared).connect(spec);
        try {
            AcpSession session = connection.newSession();
            assertTrue("session/new reads research_status directly", statusCalls.get() >= 1);

            Collecting first = new Collecting();
            session.prompt("investigate pf4j", first);
            assertTrue(first.terminated.await(45, TimeUnit.SECONDS));
            assertEquals(AcpPromptState.COMPLETED, first.terminal.get());
            assertTrue(first.sawMessage("RESEARCH_MCP_READY"));
            assertTrue(first.sawMessage("BROWSER_NOT_AVAILABLE"));
            assertTrue("research_status mirrored", first.sawMessage("RESEARCH/running rev=7"));
            assertEquals("the host tool runs on the agent's prompt thread in this JVM",
                    "research-agent-prompt", callingThread.get());

            Collecting second = new Collecting();
            session.prompt("next step", second);
            assertTrue(second.terminated.await(45, TimeUnit.SECONDS));
            assertEquals(AcpPromptState.COMPLETED, second.terminal.get());
            session.close();
        } finally {
            connection.close();
        }
        assertFalse(connection.getProcess().isAlive());
    }

    @Test
    public void wrongTokenFailsSessionCreation() throws Exception {
        McpEndpointHandle handle = registry.registerEndpoint(
                new McpEndpointDefinition("research.inprocess2", "Research Control"));
        registry.updateTools(handle, Collections.singletonList(
                McpToolContribution.of("research_status", "x", new McpToolHandler() {
                    public McpToolResult invoke(McpToolCall call) {
                        return McpToolResult.ok("ok");
                    }
                })));
        Map<String, String> env = new LinkedHashMap<String, String>();
        env.put("ASKAI_RESEARCH_MCP_URL", "inprocess://research.inprocess2/deadbeef");
        AcpConnection connection = new InProcessResearchAgentConnector(
                new InProcessHostToolEndpoints(registry), shared)
                .connect(new AgentLaunchSpec("unused", Collections.<String>emptyList(), env));
        try {
            connection.newSession();
            fail("session/new must fail when the research endpoint refuses the token");
        } catch (AcpException expected) {
            assertEquals(AcpException.Phase.SESSION, expected.getPhase());
        } finally {
            connection.close();
        }
    }
}
