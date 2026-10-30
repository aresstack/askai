package com.aresstack.askai.research.plugin;

import org.pf4j.Plugin;
import org.pf4j.PluginWrapper;

/**
 * PF4J plugin entry point. It does no work and creates no Swing components on start — the research agent
 * is exposed via {@link ResearchAgentPluginExtension}; its UI (chat activity + artifact views) is built lazily
 * by the host when the agent session is activated.
 * <p>
 * stop() is the one place that OWNS the app-wide teardown: the public ChatGPT connector is a listener on a
 * fixed port held by a singleton of THIS plugin classloader, so a dying generation must release it (and drop
 * its session directory) before the next generation tries to bind the same port.
 */
public final class ResearchPlugin extends Plugin {

    public ResearchPlugin(PluginWrapper wrapper) {
        super(wrapper);
    }

    @Override
    public void stop() {
        com.aresstack.askai.research.connector.ChatGptConnectorRuntime.get().shutdown();
        // The worker browsers of parallel research workers belong to this plugin classloader too.
        com.aresstack.askai.research.host.SharedWorkerBrowserPool.shutdown();
    }
}
