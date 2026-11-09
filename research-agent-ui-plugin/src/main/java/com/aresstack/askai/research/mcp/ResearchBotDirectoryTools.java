package com.aresstack.askai.research.mcp;

import com.aresstack.askai.mcp.api.McpToolCall;
import com.aresstack.askai.mcp.api.McpToolContribution;
import com.aresstack.askai.mcp.api.McpToolHandler;
import com.aresstack.askai.mcp.api.McpToolParameter;
import com.aresstack.askai.mcp.api.McpToolResult;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.Arrays;
import java.util.List;

/**
 * The PUBLIC connector's tool catalog: the same three driving tools, but over the whole
 * {@link ResearchBotSessionDirectory} plus {@code sessions_list}. Every driving tool takes an OPTIONAL
 * {@code sessionId}; without one the call addresses the research session of the chat that is currently
 * SELECTED in the UI, which keeps every existing single-session call working unchanged.
 * <p>
 * Addressing a session is explicitly NOT the same as selecting it: driving session B never switches the
 * user's visible chat away from A. The catalog is STABLE — with no live session at all the four tools still
 * exist ({@code sessions_list} simply returns an empty list), so a connected client never sees the tool
 * surface appear and disappear.
 */
public final class ResearchBotDirectoryTools {

    private ResearchBotDirectoryTools() {
    }

    /** The usage guide handed to public clients (tools/list descriptions). */
    public static final String USAGE =
            "AskAI Research control across ALL running research sessions. Workflow: 1) call sessions_list "
            + "to see every live session with its sessionId (a stable UUID), title and state — titles are "
            + "NOT unique, the sessionId is the identity. 2) call session_state/chat_history/run_command "
            + "with that sessionId; omitting sessionId addresses the session of the chat currently selected "
            + "in the AskAI window. Driving another session does NOT switch the user's visible chat.";

    public static List<McpToolContribution> of(ResearchBotSessionDirectory directory) {
        return Arrays.asList(sessionsListTool(directory), sessionCreateTool(directory),
                runCommandTool(directory), sessionStateTool(directory), chatHistoryTool(directory),
                technicalLogTool(directory), conceptJsonTool(directory), scopeSnapshotTool(directory));
    }

    /** How long a create may take before it is reported as not confirmed (agent start spawns processes). */
    private static final long CREATE_CONFIRM_TIMEOUT_MILLIS = 30_000L;

    /**
     * Open a NEW research chat. This is the ONE tool that deliberately changes what the user sees: creating
     * a chat means opening it. Everything else stays addressing-only.
     */
    private static McpToolContribution sessionCreateTool(final ResearchBotSessionDirectory directory) {
        return McpToolContribution.of("session_create",
                "Open a NEW research chat in AskAI and return its sessionId, ready to be driven with the "
                        + "other tools. Unlike every other tool this one is visible to the user: the new "
                        + "chat is brought to the foreground. Use it to start a fresh research; to work "
                        + "with an existing one call sessions_list instead.",
                new McpToolHandler() {
                    public McpToolResult invoke(McpToolCall call) {
                        com.aresstack.askai.plugin.api.service.ChatSessionLauncher launcher =
                                directory.launcher();
                        if (launcher == null) {
                            return McpToolResult.error("This AskAI instance does not allow creating chats "
                                    + "from outside. Open a research chat in the app; sessions_list will "
                                    + "then show it.");
                        }
                        String title = call.getString("title");
                        String sessionId = launcher.createChatSession(
                                com.aresstack.askai.research.plugin.ResearchPluginDescriptor.PLUGIN_ID,
                                title == null ? "" : title);
                        if (sessionId == null || sessionId.trim().isEmpty()) {
                            return McpToolResult.error(
                                    "AskAI could not open a new research chat (see its log for the reason).");
                        }
                        return awaitRegistration(directory, sessionId.trim());
                    }
                },
                McpToolParameter.string("title", false,
                        "An optional title for the new chat; it becomes visible once the chat has content"));
    }

    /**
     * The chat exists — now wait for ITS research session to be registered. The agent is activated
     * synchronously while the chat is created, so this normally returns at once; a productive start that
     * spawns processes may lag, and a start that FAILED must be reported as such instead of handing back an
     * id nothing answers on.
     */
    private static McpToolResult awaitRegistration(ResearchBotSessionDirectory directory, String sessionId) {
        long deadline = System.currentTimeMillis() + CREATE_CONFIRM_TIMEOUT_MILLIS;
        while (true) {
            ResearchBotSessionRegistration registration = directory.find(sessionId);
            if (registration != null) {
                String state = registration.getGateway().describeState();
                return McpToolResult.ok("sessionId=" + sessionId + "\n" + (state == null ? "" : state));
            }
            if (System.currentTimeMillis() >= deadline) {
                return McpToolResult.error("The chat " + sessionId + " was opened, but no research session "
                        + "came up in it (the agent may have failed to start — the chat shows the reason). "
                        + "Call sessions_list to check.");
            }
            try {
                Thread.sleep(100L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return McpToolResult.error("Interrupted while waiting for the new session " + sessionId);
            }
        }
    }

    private static final String SESSION_ID_PARAMETER =
            "The target session's UUID from sessions_list; omit to address the chat currently selected in "
            + "the AskAI window";

    /** Every live session with its stable id, its title and its own state line. */
    private static McpToolContribution sessionsListTool(final ResearchBotSessionDirectory directory) {
        return McpToolContribution.of("sessions_list",
                "Every research session that is currently running, as JSON: sessionId (the stable UUID to "
                        + "pass to the other tools), title (display only - NOT unique), current (true for "
                        + "the chat selected in the UI) and state (the same text session_state returns). "
                        + "Call this FIRST when you want to work with a specific session.",
                new McpToolHandler() {
                    public McpToolResult invoke(McpToolCall call) {
                        String currentChat = directory.currentChatSessionId();
                        JsonArray entries = new JsonArray();
                        for (ResearchBotSessionRegistration registration : directory.list()) {
                            JsonObject entry = new JsonObject();
                            entry.addProperty("sessionId", registration.getPublicSessionId());
                            entry.addProperty("title", directory.titleOf(registration.getPublicSessionId()));
                            entry.addProperty("current",
                                    registration.getPublicSessionId().equals(currentChat));
                            // ONE source of truth for the state text: the session's own describeState().
                            String state = registration.getGateway().describeState();
                            entry.addProperty("state", state == null ? "" : state);
                            entries.add(entry);
                        }
                        JsonObject result = new JsonObject();
                        result.add("sessions", entries);
                        return McpToolResult.ok(result.toString());
                    }
                });
    }

    private static McpToolContribution runCommandTool(final ResearchBotSessionDirectory directory) {
        return McpToolContribution.of("run_command",
                ResearchBotSessionTools.RUN_COMMAND_DESCRIPTION
                        + " Pass sessionId to drive a specific session (see sessions_list).",
                new McpToolHandler() {
                    public McpToolResult invoke(McpToolCall call) {
                        Resolution resolution = resolve(directory, call);
                        if (resolution.problem != null) {
                            return McpToolResult.error(resolution.problem);
                        }
                        String result = resolution.gateway.execute(
                                call.getString("command"), call.getString("arguments"));
                        if (result == null) {
                            return McpToolResult.error("This research session cannot accept commands "
                                    + "right now (it is starting up or already closing).");
                        }
                        return result.startsWith("rejected")
                                ? McpToolResult.error(result) : McpToolResult.ok(result);
                    }
                },
                McpToolParameter.string("sessionId", false, SESSION_ID_PARAMETER),
                McpToolParameter.string("command", false,
                        "The command name (see session_state); omit for a plain chat message"),
                McpToolParameter.string("arguments", false,
                        "The command arguments, or the chat message when no command is given"));
    }

    private static McpToolContribution sessionStateTool(final ResearchBotSessionDirectory directory) {
        return McpToolContribution.of("session_state",
                ResearchBotSessionTools.SESSION_STATE_DESCRIPTION
                        + " Pass sessionId to read a specific session (see sessions_list).",
                new McpToolHandler() {
                    public McpToolResult invoke(McpToolCall call) {
                        Resolution resolution = resolve(directory, call);
                        if (resolution.problem != null) {
                            return McpToolResult.error(resolution.problem);
                        }
                        String state = resolution.gateway.describeState();
                        return state == null
                                ? McpToolResult.error("This research session cannot report its state "
                                        + "right now (it is starting up or already closing).")
                                : McpToolResult.ok(state);
                    }
                },
                McpToolParameter.string("sessionId", false, SESSION_ID_PARAMETER));
    }

    private static McpToolContribution chatHistoryTool(final ResearchBotSessionDirectory directory) {
        return McpToolContribution.of("chat_history",
                ResearchBotSessionTools.CHAT_HISTORY_DESCRIPTION
                        + " Pass sessionId to read a specific session (see sessions_list).",
                new McpToolHandler() {
                    public McpToolResult invoke(McpToolCall call) {
                        Resolution resolution = resolve(directory, call);
                        if (resolution.problem != null) {
                            return McpToolResult.error(resolution.problem);
                        }
                        String history = resolution.gateway.describeHistory(call.getBoolean("raw", false));
                        return history == null
                                ? McpToolResult.error("This research session cannot report its history "
                                        + "right now (it is starting up or already closing).")
                                : McpToolResult.ok(history);
                    }
                },
                McpToolParameter.string("sessionId", false, SESSION_ID_PARAMETER),
                McpToolParameter.string("raw", false,
                        "true = every recorded message of every phase (default: phase summaries)"));
    }

    private static McpToolContribution technicalLogTool(final ResearchBotSessionDirectory directory) {
        return McpToolContribution.of("technical_log",
                ResearchBotSessionTools.TECHNICAL_LOG_DESCRIPTION
                        + " Pass sessionId to read a specific session (see sessions_list).",
                new McpToolHandler() {
                    public McpToolResult invoke(McpToolCall call) {
                        Resolution resolution = resolve(directory, call);
                        if (resolution.problem != null) {
                            return McpToolResult.error(resolution.problem);
                        }
                        String log = resolution.gateway.describeTechnicalLog(
                                (int) call.getInteger("tail", 0));
                        return log == null
                                ? McpToolResult.error("This research session cannot report its "
                                        + "technical log right now.")
                                : McpToolResult.ok(log);
                    }
                },
                McpToolParameter.string("sessionId", false, SESSION_ID_PARAMETER),
                McpToolParameter.string("tail", false,
                        "How many trailing lines to return (default 200)"));
    }

    private static McpToolContribution conceptJsonTool(final ResearchBotSessionDirectory directory) {
        return McpToolContribution.of("concept_json",
                ResearchBotSessionTools.CONCEPT_JSON_DESCRIPTION
                        + " Pass sessionId to read a specific session (see sessions_list).",
                new McpToolHandler() {
                    public McpToolResult invoke(McpToolCall call) {
                        Resolution resolution = resolve(directory, call);
                        if (resolution.problem != null) {
                            return McpToolResult.error(resolution.problem);
                        }
                        String snapshot = resolution.gateway.describeConceptSnapshot();
                        return snapshot == null
                                ? McpToolResult.error("This session has no concept service (or "
                                        + "none is attached yet).")
                                : McpToolResult.ok(snapshot);
                    }
                },
                McpToolParameter.string("sessionId", false, SESSION_ID_PARAMETER));
    }

    private static McpToolContribution scopeSnapshotTool(final ResearchBotSessionDirectory directory) {
        return McpToolContribution.of("scope_snapshot",
                ResearchBotSessionTools.SCOPE_SNAPSHOT_DESCRIPTION
                        + " Pass sessionId to read a specific session (see sessions_list).",
                new McpToolHandler() {
                    public McpToolResult invoke(McpToolCall call) {
                        Resolution resolution = resolve(directory, call);
                        if (resolution.problem != null) {
                            return McpToolResult.error(resolution.problem);
                        }
                        String snapshot = resolution.gateway.describeScopeSnapshot();
                        return snapshot == null
                                ? McpToolResult.error("This session has no persisted scope (or "
                                        + "none is attached yet).")
                                : McpToolResult.ok(snapshot);
                    }
                },
                McpToolParameter.string("sessionId", false, SESSION_ID_PARAMETER));
    }

    /** Either a gateway or the concrete reason no session could be addressed — never both. */
    private static final class Resolution {
        private final ResearchBotSessionGateway gateway;
        private final String problem;

        private Resolution(ResearchBotSessionGateway gateway, String problem) {
            this.gateway = gateway;
            this.problem = problem;
        }
    }

    /**
     * THE one resolution rule for all three driving tools: an explicit {@code sessionId} addresses exactly
     * that session (a stale/unknown id is an error, never a silent redirect), an omitted one addresses the
     * chat selected in the UI.
     */
    private static Resolution resolve(ResearchBotSessionDirectory directory, McpToolCall call) {
        String requested = call.getString("sessionId");
        if (requested != null && !requested.trim().isEmpty()) {
            ResearchBotSessionRegistration registration = directory.find(requested);
            return registration != null
                    ? new Resolution(registration.getGateway(), null)
                    : new Resolution(null, "No live research session with sessionId '" + requested.trim()
                            + "'. It may have been closed. Call sessions_list for the current ids.");
        }
        ResearchBotSessionRegistration current = directory.currentSession();
        if (current != null) {
            return new Resolution(current.getGateway(), null);
        }
        return new Resolution(null, directory.list().isEmpty()
                ? "No research session is running in AskAI right now."
                : "The chat currently selected in AskAI runs no research session (or several sessions are "
                        + "live and none is selected). Call sessions_list and pass sessionId explicitly.");
    }
}
