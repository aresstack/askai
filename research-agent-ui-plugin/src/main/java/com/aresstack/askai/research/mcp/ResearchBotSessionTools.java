package com.aresstack.askai.research.mcp;

import com.aresstack.askai.mcp.api.McpToolCall;
import com.aresstack.askai.mcp.api.McpToolContribution;
import com.aresstack.askai.mcp.api.McpToolHandler;
import com.aresstack.askai.mcp.api.McpToolParameter;
import com.aresstack.askai.mcp.api.McpToolResult;

import java.util.Arrays;
import java.util.List;

/**
 * The fixed THREE-tool driving set over ONE {@link ResearchBotSessionGateway} — {@code run_command},
 * {@code session_state}, {@code chat_history}. This is the SINGLE-session catalog: the endpoint that serves
 * it is already bound to its session (by its own token), so no tool here takes a session id.
 * <p>
 * The public multi-session connector deliberately does NOT reuse this catalog; it has its own
 * ({@link ResearchBotDirectoryTools}), because its tools must accept an optional {@code sessionId}.
 */
public final class ResearchBotSessionTools {

    private ResearchBotSessionTools() {
    }

    /** The six driving tools bound to one gateway. */
    public static List<McpToolContribution> of(ResearchBotSessionGateway gateway) {
        return Arrays.asList(runCommandTool(gateway), sessionStateTool(gateway), chatHistoryTool(gateway),
                technicalLogTool(gateway), conceptJsonTool(gateway), scopeSnapshotTool(gateway));
    }

    /** The shared command description — the directory face documents the same commands. */
    static final String RUN_COMMAND_DESCRIPTION =
            "Execute one research command. Always-on commands: search <query> (web search), "
            + "generate-visualization, generate-outline, review-sources, check-scope. Run offers "
            + "(only while session_state lists them): run-plan, retry-query, sources, config, refine. "
            + "Phase changes need the user: a bot cannot run submit-scope, approve, request-changes, "
            + "continue, retry, resume, the reopen and finish commands or the 'end' offer; suggest "
            + "them in your answer instead. Omit 'command' to send the arguments as a plain CHAT "
            + "MESSAGE to the research agent (refused before the first user message). Unknown or "
            + "refused commands are rejected with the currently valid list. Call session_state first.";

    static final String SESSION_STATE_DESCRIPTION =
            "Current research phase and run state, plus the currently valid commands (the same set the "
            + "UI buttons offer), the clickable decision buttons and the search suggestions "
            + "(each directly executable via run_command).";

    static final String CHAT_HISTORY_DESCRIPTION =
            "The chat record of this session, attributed to research phases: finished phases as ONE "
            + "summary (outcome + message count), the current phase in detail. Pass raw=true "
            + "for every recorded message of every phase.";

    /**
     * THE generic driving tool: one structured command + arguments. No command = the arguments are a plain
     * chat message. Unknown / currently-not-allowed commands come back as typed rejections.
     */
    private static McpToolContribution runCommandTool(final ResearchBotSessionGateway gateway) {
        return McpToolContribution.of("run_command", RUN_COMMAND_DESCRIPTION,
                new McpToolHandler() {
                    public McpToolResult invoke(McpToolCall call) {
                        String result = gateway == null ? null
                                : gateway.execute(call.getString("command"), call.getString("arguments"));
                        if (result == null) {
                            return McpToolResult.error(
                                    "No research session is attached to this endpoint yet.");
                        }
                        return result.startsWith("rejected")
                                ? McpToolResult.error(result) : McpToolResult.ok(result);
                    }
                },
                McpToolParameter.string("command", false,
                        "The command name (see session_state); omit for a plain chat message"),
                McpToolParameter.string("arguments", false,
                        "The command arguments, or the chat message when no command is given"));
    }

    /** The structured session state: phase/run state first, then valid commands, buttons, suggestions. */
    private static McpToolContribution sessionStateTool(final ResearchBotSessionGateway gateway) {
        return McpToolContribution.of("session_state", SESSION_STATE_DESCRIPTION,
                new McpToolHandler() {
                    public McpToolResult invoke(McpToolCall call) {
                        String state = gateway == null ? null : gateway.describeState();
                        return state == null
                                ? McpToolResult.error(
                                        "No research session is attached to this endpoint yet.")
                                : McpToolResult.ok(state);
                    }
                });
    }

    static final String TECHNICAL_LOG_DESCRIPTION =
            "The tail of this session's TECHNICAL detail lines (the collapsed diagnostics of the "
            + "transcript: status lines, concept tool rounds, wire logs, readiness verdicts) — "
            + "the same lines a human reads in the GUI. Pass tail=<n> for more or fewer lines "
            + "(default 200, oldest first).";

    /** Observability for a driving client: re-read the technical diagnostics without the GUI. */
    private static McpToolContribution technicalLogTool(final ResearchBotSessionGateway gateway) {
        return McpToolContribution.of("technical_log", TECHNICAL_LOG_DESCRIPTION,
                new McpToolHandler() {
                    public McpToolResult invoke(McpToolCall call) {
                        String log = gateway == null ? null
                                : gateway.describeTechnicalLog((int) call.getInteger("tail", 0));
                        return log == null
                                ? McpToolResult.error(
                                        "No research session is attached to this endpoint yet.")
                                : McpToolResult.ok(log);
                    }
                },
                McpToolParameter.string("tail", false,
                        "How many trailing lines to return (default 200)"));
    }

    static final String CONCEPT_JSON_DESCRIPTION =
            "The Konzeptpapier as ONE atomic snapshot: a revision=N line followed by the concept "
            + "document JSON. Read-only observability — verify the workpiece itself instead of "
            + "trusting the agent's claims about it.";

    /** The workpiece itself, for drivers verifying state independently of the agent's words. */
    private static McpToolContribution conceptJsonTool(final ResearchBotSessionGateway gateway) {
        return McpToolContribution.of("concept_json", CONCEPT_JSON_DESCRIPTION,
                new McpToolHandler() {
                    public McpToolResult invoke(McpToolCall call) {
                        String snapshot = gateway == null ? null : gateway.describeConceptSnapshot();
                        return snapshot == null
                                ? McpToolResult.error(
                                        "This session has no concept service (or none is attached yet).")
                                : McpToolResult.ok(snapshot);
                    }
                });
    }

    static final String SCOPE_SNAPSHOT_DESCRIPTION =
            "The negotiated research scope as ONE atomic snapshot: a revision=N line followed by "
            + "the scope fence (mission, the BLACKLIST — excluded aspects and exclusions — and "
            + "any remaining bookkeeping facets; the positive working space lives in the concept, "
            + "see concept_json). Read-only observability — verify what the scope actually holds "
            + "instead of trusting the agent's claims about it.";

    /** The scope counterpart of concept_json: what the Weidezaun actually holds. */
    private static McpToolContribution scopeSnapshotTool(final ResearchBotSessionGateway gateway) {
        return McpToolContribution.of("scope_snapshot", SCOPE_SNAPSHOT_DESCRIPTION,
                new McpToolHandler() {
                    public McpToolResult invoke(McpToolCall call) {
                        String snapshot = gateway == null ? null : gateway.describeScopeSnapshot();
                        return snapshot == null
                                ? McpToolResult.error(
                                        "This session has no persisted scope (or none is attached yet).")
                                : McpToolResult.ok(snapshot);
                    }
                });
    }

    /** The phase-attributed chat record: summaries per finished phase by default, raw=true for everything. */
    private static McpToolContribution chatHistoryTool(final ResearchBotSessionGateway gateway) {
        return McpToolContribution.of("chat_history", CHAT_HISTORY_DESCRIPTION,
                new McpToolHandler() {
                    public McpToolResult invoke(McpToolCall call) {
                        boolean raw = call.getBoolean("raw", false);
                        String history = gateway == null ? null : gateway.describeHistory(raw);
                        return history == null
                                ? McpToolResult.error(
                                        "No research session is attached to this endpoint yet.")
                                : McpToolResult.ok(history);
                    }
                },
                McpToolParameter.string("raw", false,
                        "true = every recorded message of every phase (default: phase summaries)"));
    }
}
