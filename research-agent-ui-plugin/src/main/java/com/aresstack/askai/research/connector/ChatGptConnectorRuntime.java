package com.aresstack.askai.research.connector;

import com.aresstack.askai.mcp.api.McpToolContribution;
import com.aresstack.askai.research.mcp.ResearchBotDirectoryTools;
import com.aresstack.askai.research.mcp.ResearchBotSessionDirectory;

import java.util.List;

/**
 * The process-wide owner of the ChatGPT-connector server: ONE listener for the whole app, while research
 * sessions come and go. It holds NO session itself — the tools resolve through the
 * {@link ResearchBotSessionDirectory} at call time, so a ChatGPT conversation survives session starts,
 * closes and chat switches, and every live session is reachable by its id.
 * <p>
 * Listener lifecycle and session lifecycle are SEPARATE: switching the connector off only stops the HTTP
 * listener, it never empties the directory, so switching it back on immediately serves the sessions that
 * kept running meanwhile.
 */
public final class ChatGptConnectorRuntime {

    private static final ChatGptConnectorRuntime INSTANCE = new ChatGptConnectorRuntime();

    private final ResearchBotSessionDirectory sessions = ResearchBotSessionDirectory.get();
    private ChatGptConnectorServer server;
    private String startFailure;

    private ChatGptConnectorRuntime() {
    }

    public static ChatGptConnectorRuntime get() {
        return INSTANCE;
    }

    /** The directory of live research sessions this connector serves. */
    public ResearchBotSessionDirectory sessions() {
        return sessions;
    }

    /** The ONE canonical refresh-token store, independent of any session/workspace lifecycle. */
    public static java.io.File defaultRefreshStore() {
        String appData = System.getenv("APPDATA");
        java.io.File base = appData == null || appData.trim().isEmpty()
                ? new java.io.File(System.getProperty("user.home"))
                : new java.io.File(appData);
        return new java.io.File(base, ".askai/chatgpt-connector/oauth-refresh-tokens.json");
    }

    private ConnectorConfig runningConfig;

    /**
     * Start the listener (idempotent for the SAME configuration; a changed port/origin/client pair
     * restarts it). A bind failure is remembered and visible, never fatal.
     */
    public synchronized void ensureStarted(ConnectorConfig config) {
        if (server != null && server.isRunning()) {
            if (sameConfig(runningConfig, config)) {
                return;
            }
            server.stop();
            server = null;
        }
        if (!config.isComplete()) {
            startFailure = "incomplete configuration (the public origin is required)";
            System.out.println("[chatgpt-connector] not started: " + startFailure);
            return;
        }
        ConnectorAuthentication authentication = config.isCentralMode()
                ? new CentralJwtAuthentication(config.getCentralIssuer(),
                        config.getPublicOrigin() + "/")
                : new ConnectorOAuthService(config);
        ChatGptConnectorServer created = new ChatGptConnectorServer(config,
                authentication, new ChatGptConnectorServer.ToolProvider() {
                    public List<McpToolContribution> tools() {
                        // STABLE surface: the four tools exist even with no live session (sessions_list
                        // then returns an empty list), so a connected client never sees them vanish.
                        List<McpToolContribution> published = ResearchBotDirectoryTools.of(sessions);
                        logPublishedTools(published);
                        return published;
                    }
                });
        try {
            created.start();
            server = created;
            runningConfig = config;
            startFailure = null;
            System.out.println("[chatgpt-connector] listening on port " + created.boundPort()
                    + " for " + config.getPublicOrigin() + "/ (TLS at the reverse proxy)");
            logGenerationIdentity(created);
        } catch (Exception bindFailure) {
            startFailure = bindFailure.getMessage() == null
                    ? bindFailure.getClass().getSimpleName() : bindFailure.getMessage();
            System.out.println("[chatgpt-connector] start FAILED on port " + config.getPort()
                    + ": " + startFailure + " — an OLDER AskAI instance still holding this port is the "
                    + "usual cause; that instance keeps serving its OWN (stale) tool catalog to clients.");
        }
    }

    /**
     * WHICH build is actually serving the public face. A green local build proves nothing about the JVM a
     * client reaches — a surviving older instance keeps the port and answers with its own catalog. These
     * lines make that visible in one look: the code location, the plugin classloader, the bound port and
     * the tool catalog this generation publishes.
     */
    private void logGenerationIdentity(ChatGptConnectorServer started) {
        String codeSource;
        try {
            java.security.CodeSource source =
                    ChatGptConnectorRuntime.class.getProtectionDomain().getCodeSource();
            codeSource = source == null || source.getLocation() == null
                    ? "<unknown>" : source.getLocation().toString();
        } catch (RuntimeException notAvailable) {
            codeSource = "<unavailable: " + notAvailable + ">";
        }
        System.out.println("[chatgpt-connector] generation: codeSource=" + codeSource);
        System.out.println("[chatgpt-connector] generation: classLoader="
                + ChatGptConnectorRuntime.class.getClassLoader()
                + " boundPort=" + started.boundPort()
                + " toolCatalog=" + ResearchBotDirectoryTools.class.getSimpleName());
        logPublishedTools(ResearchBotDirectoryTools.of(sessions));
    }

    /** The last catalog logged — so a per-request log line appears only when the set actually changes. */
    private volatile String lastLoggedToolNames = "";

    /** The tool names this connector hands out, logged whenever the published set changes. */
    private void logPublishedTools(List<McpToolContribution> published) {
        StringBuilder names = new StringBuilder();
        for (McpToolContribution tool : published) {
            if (names.length() > 0) {
                names.append(", ");
            }
            names.append(tool.getName());
        }
        String current = names.toString();
        if (!current.equals(lastLoggedToolNames)) {
            lastLoggedToolNames = current;
            System.out.println("[chatgpt-connector] public MCP tools: [" + current + "]");
        }
    }

    /**
     * Stop the public LISTENER only (the settings toggle turned it off). Running research sessions stay
     * registered, so switching the connector back on reaches them again without a session restart.
     */
    public synchronized void stopListener() {
        if (server != null) {
            server.stop();
            server = null;
        }
        runningConfig = null;
        startFailure = null;
    }

    /**
     * FINAL teardown, owned by the plugin's stop(): the listener releases its port BEFORE a new plugin
     * generation tries to bind it, and the session directory of this (dying) generation is emptied.
     */
    public synchronized void shutdown() {
        stopListener();
        sessions.clear();
    }

    private static boolean sameConfig(ConnectorConfig a, ConnectorConfig b) {
        return a != null && b != null
                && a.getPort() == b.getPort()
                && a.getPublicOrigin().equals(b.getPublicOrigin())
                && a.getClientId().equals(b.getClientId())
                && a.getClientSecret().equals(b.getClientSecret())
                && a.getCentralIssuer().equals(b.getCentralIssuer());
    }

    public synchronized boolean isRunning() {
        return server != null && server.isRunning();
    }

    public synchronized int runningPort() {
        return server == null ? -1 : server.boundPort();
    }

    /** The last start failure ("" when none) — surfaced in the settings UI. */
    public synchronized String lastStartFailure() {
        return startFailure == null ? "" : startFailure;
    }
}
