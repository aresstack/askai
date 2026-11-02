package com.aresstack.askai.research.host;

import com.aresstack.askai.acp.AcpAgentConnector;
import com.aresstack.askai.mcp.api.InProcessMcpServerRegistry;
import com.aresstack.askai.research.runtime.InProcessResearchAgentConnector;
import com.aresstack.askai.research.runtime.ResearchRuntimeShared;
import com.aresstack.askai.research.runtime.host.InProcessHostToolEndpoints;

/**
 * The research runtime inside the AskAI JVM (askai #48): one tool registry the sessions' agent-facing
 * endpoints register in and the agents call directly, one shared search browser and provider registry, and
 * the connector that creates the agents in this JVM. Created once per AskAI process and closed at its end.
 */
public final class InProcessResearchRuntime implements AutoCloseable {

    private static InProcessResearchRuntime instance;

    private final InProcessMcpServerRegistry registry = new InProcessMcpServerRegistry();
    private final ResearchRuntimeShared shared;
    private final AcpAgentConnector connector;

    public InProcessResearchRuntime(String searchBrowserChannel) {
        this.shared = new ResearchRuntimeShared(searchBrowserChannel);
        this.connector = new InProcessResearchAgentConnector(new InProcessHostToolEndpoints(registry), shared);
    }

    /**
     * The AskAI-wide instance; created on first use and closed when the JVM ends. The search browser channel
     * comes from {@code ASKAI_SEARCH_BROWSER_CHANNEL} (default camoufox).
     */
    public static synchronized InProcessResearchRuntime shared() {
        if (instance == null) {
            final InProcessResearchRuntime created =
                    new InProcessResearchRuntime(System.getenv("ASKAI_SEARCH_BROWSER_CHANNEL"));
            Runtime.getRuntime().addShutdownHook(new Thread(created::close, "research-runtime-close"));
            instance = created;
        }
        return instance;
    }

    InProcessMcpServerRegistry registry() {
        return registry;
    }

    AcpAgentConnector connector() {
        return connector;
    }

    @Override
    public void close() {
        shared.close();
        registry.shutdown();
    }
}
