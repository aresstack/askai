package com.aresstack.askai.research.agent;

import com.aresstack.askai.plugin.api.agent.AgentArtifact;
import com.aresstack.askai.plugin.api.agent.AgentSession;
import com.aresstack.askai.plugin.api.agent.AgentSessionContext;
import com.aresstack.askai.plugin.api.agent.command.ChatCommandContribution;
import com.aresstack.askai.plugin.api.agent.command.ChatCommandDescriptor;
import com.aresstack.askai.plugin.api.agent.command.CommandArgumentDescriptor;
import com.aresstack.askai.plugin.api.agent.command.CommandCompletion;
import com.aresstack.askai.plugin.api.agent.command.CommandCompletionRequest;
import com.aresstack.askai.plugin.api.agent.command.CommandCompletionResult;
import com.aresstack.askai.plugin.api.agent.command.CommandExecutionResult;
import com.aresstack.askai.plugin.api.agent.command.CommandInvocation;
import com.aresstack.askai.plugin.api.agent.command.CompletionKind;

import java.util.ArrayList;
import java.util.List;

/**
 * The research agent's slash commands. They are user controls (not agent tools): each translates a parsed
 * invocation into a typed control call on {@link ResearchAgentSession}, so the state machine never sees a raw
 * string. This is a representative set for Commit 10; the full set arrives in Commit 12.
 */
public final class ResearchChatCommands {

    private ResearchChatCommands() {
    }

    public static List<ChatCommandContribution> all() {
        // ONE synchronized command surface: the TEXT adapters — /search (user web search) and /open
        // (artifact navigation) — plus a slash TWIN for exactly the red DECISION tags the user sees
        // ("Fragestellung freigeben & weiter" → /submit-scope, "Neue Quellen auswerten" →
        // /review-sources, approve/changes/continue) and for the reopen of an earlier phase, which the
        // phase pill offers as a click. NOTHING more: the domain's interruption
        // machinery (pause/resume/retry/cancel, block/fail) stays out of the user surface — those
        // states exist for the agent/runtime, not as user furniture. Every twin executes through
        // the session's ONE structured command processor — exactly what a red-tag click runs.
        List<ChatCommandContribution> commands = new ArrayList<ChatCommandContribution>();
        commands.add(new OpenCommand());
        commands.add(new SearchCommand());
        commands.add(new MapCommand());
        // D1: every phase's exit and gate decision, with the labels of the red tags.
        commands.add(new ProcessorCommand("submit-scope",
                "Approve concept & start search"));
        commands.add(new ProcessorCommand("finish-sources",
                "Finish sources: make the sources read-only for review"));
        commands.add(new ProcessorCommand("finish-outline",
                "Finish outline: make the outline read-only for review"));
        commands.add(new ProcessorCommand("finish-document",
                "Finish document: make the document read-only for review"));
        commands.add(new ProcessorCommand("approve",
                "Approve the pending review gate (sources/outline/final book)"));
        commands.add(new ProcessorCommand("request-changes",
                "Request changes at the pending review gate, with an optional comment"));
        commands.add(new ProcessorCommand("continue",
                "Start search or start writing when the chained start was refused"));
        commands.add(new ProcessorCommand("review-sources",
                "Review the newly captured sources (Neue Quellen auswerten)"));
        commands.add(new ProcessorCommand("check-scope",
                "Check the negotiated topic space for gaps (Themenraum pr\u00fcfen)"));
        commands.add(new ProcessorCommand("discover-topics",
                "Show topic areas discovered in the sources as concept suggestions"));
        // The way back (D10): a typed reopen is the person's explicit decision, so it dispatches without
        // the dialog the phase pill shows for a click; the description carries the same warning.
        commands.add(new ProcessorCommand("reopen-concept",
                "Reopen Concept: " + ReopenConfirmation.messageFor("reopen-concept")));
        commands.add(new ProcessorCommand("reopen-sources",
                "Reopen Sources: " + ReopenConfirmation.messageFor("reopen-sources")));
        commands.add(new ProcessorCommand("reopen-outline",
                "Reopen Outline: " + ReopenConfirmation.messageFor("reopen-outline")));
        return commands;
    }

    /**
     * THE dispatcher for a typed slash line outside the composer (MCP clients): the same contributions,
     * the same parse the host applies (name, then whitespace-separated arguments), the same processor.
     * {@code UNKNOWN} when no contribution owns the name.
     */
    public static CommandExecutionResult execute(String slashLine, AgentSessionContext context) {
        String body = slashLine == null ? "" : slashLine.trim();
        body = body.startsWith("/") ? body.substring(1).trim() : body;
        if (body.isEmpty()) {
            return CommandExecutionResult.rejected("a slash command name is required");
        }
        String[] tokens = body.split("\\s+");
        String name = tokens[0].toLowerCase(java.util.Locale.ROOT);
        List<String> arguments = new ArrayList<String>(java.util.Arrays.asList(tokens).subList(1, tokens.length));
        for (ChatCommandContribution contribution : all()) {
            if (contribution.getDescriptor().getName().equals(name)) {
                return contribution.execute(new CommandInvocation(name, arguments, slashLine), context);
            }
        }
        return CommandExecutionResult.unknown();
    }

    /** Every slash command name, for an honest rejection. */
    public static String names() {
        StringBuilder sb = new StringBuilder();
        for (ChatCommandContribution contribution : all()) {
            sb.append(sb.length() == 0 ? "/" : ", /").append(contribution.getDescriptor().getName());
        }
        return sb.toString();
    }

    /** The red-tag twins exposed as slash commands (the test guards the sync). */
    static List<String> processorCommandNames() {
        return java.util.Arrays.asList(
                "submit-scope", "finish-sources", "finish-outline", "finish-document", "approve",
                "request-changes", "continue", "review-sources",
                "check-scope", "discover-topics", "reopen-concept", "reopen-sources", "reopen-outline");
    }

    private static ResearchAgentSession research(AgentSessionContext context) {
        AgentSession session = context == null ? null : context.getSession();
        return session instanceof ResearchAgentSession ? (ResearchAgentSession) session : null;
    }

    /** Base with a no-op completion; subclasses override where arguments exist. */
    private abstract static class Base implements ChatCommandContribution {
        public CommandCompletionResult complete(CommandCompletionRequest request, AgentSessionContext context) {
            return CommandCompletionResult.empty();
        }
    }


    /**
     * {@code /search <query>} — run a USER web search over everything after the command. It is the typed twin of
     * a yellow scoping-suggestion click: the SAME phase-independent {@link ResearchAgentSession#requestManualWebSearch}
     * service, never a chat turn and never a state-machine command. Works in any phase.
     */
    private static final class SearchCommand extends Base {
        public ChatCommandDescriptor getDescriptor() {
            return ChatCommandDescriptor.of("search", "Run a web search for the given text", "/search <query>",
                    new CommandArgumentDescriptor("query", "What to search the web for", true));
        }

        public CommandExecutionResult execute(CommandInvocation invocation, AgentSessionContext context) {
            ResearchAgentSession session = research(context);
            if (session == null) {
                return CommandExecutionResult.unknown();
            }
            String query = String.join(" ", invocation.getArguments()).trim();
            if (query.isEmpty()) {
                return CommandExecutionResult.rejected("Usage: /search <query>");
            }
            session.requestManualWebSearch(query);
            // No command-result line: the visible "Websuche: <query>" breadcrumb is emitted (and persisted)
            // uniformly from the search's 'started' event, so the typed command and a suggestion click match.
            return CommandExecutionResult.handled("");
        }
    }

    /**
     * A slash twin of a red action tag: {@code /<name>} runs the SAME processor command (semantic
     * state command or derived-action service command) through
     * {@link ResearchAgentSession#executeCommand} that a tag click runs — one processor, no side
     * paths. The processor's honest "handled:/rejected:" answer becomes the chat feedback, so a
     * command that is not available right now says so instead of doing nothing.
     */
    private static final class ProcessorCommand extends Base {
        private final String name;
        private final String description;

        ProcessorCommand(String name, String description) {
            this.name = name;
            this.description = description;
        }

        public ChatCommandDescriptor getDescriptor() {
            if ("request-changes".equals(name)) {
                return ChatCommandDescriptor.of(name, description, "/request-changes [comment]",
                        new CommandArgumentDescriptor("comment", "What should change (optional)",
                                false));
            }
            return ChatCommandDescriptor.of(name, description);
        }

        public CommandExecutionResult execute(CommandInvocation invocation, AgentSessionContext context) {
            ResearchAgentSession session = research(context);
            if (session == null) {
                return CommandExecutionResult.unknown();
            }
            String arguments = String.join(" ", invocation.getArguments()).trim();
            String outcome = session.executeCommand(name, arguments);
            if (outcome == null) {
                return CommandExecutionResult.rejected("The command produced no result.");
            }
            if (outcome.startsWith("handled")) {
                return CommandExecutionResult.handled(stripPrefix(outcome, "handled:"));
            }
            return CommandExecutionResult.rejected(stripPrefix(outcome, "rejected:"));
        }

        private static String stripPrefix(String outcome, String prefix) {
            return outcome.startsWith(prefix) ? outcome.substring(prefix.length()).trim() : outcome;
        }
    }

    /**
     * {@code /map} — the typed twin of the "Quellen visualisieren" toolbar button: build the
     * mechanical sources mindmap and show it as a transcript overlay. Same action, same overlay.
     */
    private static final class MapCommand extends Base {
        public ChatCommandDescriptor getDescriptor() {
            return ChatCommandDescriptor.of("map",
                    "Show the sources mindmap as an overlay (Visualize sources)");
        }

        public CommandExecutionResult execute(CommandInvocation invocation,
                                              final AgentSessionContext context) {
            ResearchAgentSession session = research(context);
            if (session == null) {
                return CommandExecutionResult.unknown();
            }
            ResearchMindmapAction.open(session, new ResearchMindmapAction.OverlayHost() {
                public void showDiagram(String mermaidSource, String title) {
                    context.showDiagramOverlay(mermaidSource, title);
                }

                public void showHint(javax.swing.JComponent content, String title) {
                    context.showTranscriptOverlay(content, title);
                }
            });
            return CommandExecutionResult.handled("");
        }
    }

    /** {@code /open <artifact>} reveals an artifact tab; completes artifact ids. */
    private static final class OpenCommand extends Base {
        public ChatCommandDescriptor getDescriptor() {
            return ChatCommandDescriptor.of("open", "Open a research artifact", "/open <artifact>",
                    new CommandArgumentDescriptor("artifact", "The artifact id to open", true));
        }

        @Override
        public CommandCompletionResult complete(CommandCompletionRequest request, AgentSessionContext context) {
            ResearchAgentSession session = research(context);
            if (session == null || request.getArgumentIndex() != 0) {
                return CommandCompletionResult.empty();
            }
            String partial = request.getPartialToken().toLowerCase();
            List<CommandCompletion> out = new ArrayList<CommandCompletion>();
            for (AgentArtifact artifact : session.getArtifacts()) {
                if (artifact.getId().toLowerCase().startsWith(partial)) {
                    out.add(new CommandCompletion(artifact.getId(), artifact.getId(),
                            artifact.getDisplayName(), CompletionKind.ARGUMENT_VALUE));
                }
            }
            return new CommandCompletionResult(out);
        }

        public CommandExecutionResult execute(CommandInvocation invocation, AgentSessionContext context) {
            ResearchAgentSession session = research(context);
            if (session == null) {
                return CommandExecutionResult.unknown();
            }
            String artifactId = invocation.getArgument(0).trim();
            if (artifactId.isEmpty()) {
                return CommandExecutionResult.rejected("Usage: /open <artifact>");
            }
            for (AgentArtifact artifact : session.getArtifacts()) {
                if (artifact.getId().equalsIgnoreCase(artifactId)) {
                    context.openArtifact(artifact.getId());
                    return CommandExecutionResult.handled("Opened " + artifact.getDisplayName() + ".");
                }
            }
            return CommandExecutionResult.rejected("Unknown artifact: " + artifactId);
        }
    }
}
