package com.aresstack.askai.plugin.host;

import com.aresstack.askai.plugin.api.agent.AgentHostContext;
import com.aresstack.askai.plugin.api.agent.AgentSession;
import com.aresstack.askai.plugin.api.agent.AgentSessionContext;
import com.aresstack.askai.plugin.api.agent.AgentSessionCreationRequest;
import com.aresstack.askai.plugin.api.agent.SubmissionAvailability;
import com.aresstack.askai.plugin.api.agent.artifact.ArtifactViewContribution;
import com.aresstack.askai.plugin.api.agent.command.ChatCommandContribution;
import com.aresstack.askai.plugin.api.agent.command.ChatCommandDescriptor;
import com.aresstack.askai.plugin.api.agent.command.CommandCompletion;
import com.aresstack.askai.plugin.api.agent.command.CommandCompletionRequest;
import com.aresstack.askai.plugin.api.agent.command.CommandCompletionResult;
import com.aresstack.askai.plugin.api.agent.command.CommandExecutionResult;
import com.aresstack.askai.plugin.api.agent.command.CommandInvocation;
import com.aresstack.askai.plugin.api.agent.command.CompletionKind;
import com.aresstack.askai.plugin.api.service.UiExecutor;
import com.aresstack.askai.plugin.pf4j.api.AgentPluginExtension;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Owns the agent sessions behind the shared chat and is the {@link ChatSubmissionRouter} the composer talks to.
 * It is deliberately Swing-free so the route matrix and lifecycle are unit-testable without a UI.
 *
 * <p>Invariant: exactly one {@link AgentSession} per agent id (created lazily, reused across mode switches).
 * Switching to Yapping only deactivates the active session; it is not closed, so returning to Questing reuses
 * it. A session is closed only when its plugin is disabled/removed, or on shutdown. The active session is the
 * single routing target; {@code null} means "route to Yapping / no agent".</p>
 */
public final class AgentSessionCoordinator
        implements ChatSubmissionRouter, ActiveAgentCommandRegistry, GenerationSwapHook {

    /** Resolves an agent id to its (selectable) extension, or {@code null} if not available. */
    public interface AgentExtensionResolver {
        AgentPluginExtension resolve(String agentId);
    }

    /** Builds a scoped host context (conversation sink + services) for a new session. */
    public interface AgentHostContextProvider {
        AgentHostContext create(String agentId, String sessionInstanceId);
    }

    /**
     * Supplies the current session SCOPE so a session is identified by agent id AND scope — the active chat
     * tab's {@code ChatSessionId} in the productive wiring — instead of the agent id alone. This is what makes
     * research sessions per-tab: two tabs of the same agent get distinct sessions, ids and project directories.
     * The default scope {@code "session"} preserves the historical single-session-per-agent behaviour.
     */
    public interface SessionScopeProvider {
        String currentScope();

        SessionScopeProvider DEFAULT = new SessionScopeProvider() {
            public String currentScope() {
                return "session";
            }
        };
    }

    /** Host hook to reveal an artifact tab (wired to the artifact area in Commit 13; no-op before that). */
    public interface ArtifactOpener {
        void open(String artifactId);
    }

    /** Host hook to show a closable overlay over the ACTIVE chat's transcript (no-op until wired). */
    public interface TranscriptOverlayHost {
        void show(javax.swing.JComponent content, String title);

        /** Show a Mermaid SOURCE in the host's full diagram viewer. */
        void showDiagram(String mermaidSource, String title);
    }

    private final AgentExtensionResolver resolver;
    private final AgentHostContextProvider hostContextProvider;
    private final UiExecutor uiExecutor;
    private final SessionScopeProvider scopeProvider;
    /** Keyed by the SESSION KEY {@code agentId + "#" + scope} (per-tab), not by agent id alone. */
    private final Map<String, AgentSession> sessions = new LinkedHashMap<String, AgentSession>();
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<Runnable>();
    private final AgentSessionContext commandContext = new ActiveContext();

    private String activeAgentId;
    /** The active session's key {@code agentId + "#" + scope}, so reuse/replace is scope-aware. */
    private String activeSessionKey;
    private AgentSession activeSession;
    private AgentPluginExtension activeExtension;
    private ArtifactOpener artifactOpener;
    private TranscriptOverlayHost transcriptOverlayHost;
    /** Sessions whose close() threw during a generation swap; retried on the next detach and at shutdown so a
     *  live object is never dropped while its plugin classloader is still (correctly) kept loaded. */
    private final List<AgentSession> unclosed =
            java.util.Collections.synchronizedList(new ArrayList<AgentSession>());

    public AgentSessionCoordinator(AgentExtensionResolver resolver,
                                   AgentHostContextProvider hostContextProvider, UiExecutor uiExecutor) {
        this(resolver, hostContextProvider, uiExecutor, SessionScopeProvider.DEFAULT);
    }

    public AgentSessionCoordinator(AgentExtensionResolver resolver,
                                   AgentHostContextProvider hostContextProvider, UiExecutor uiExecutor,
                                   SessionScopeProvider scopeProvider) {
        this.resolver = resolver;
        this.hostContextProvider = hostContextProvider;
        this.uiExecutor = uiExecutor;
        this.scopeProvider = scopeProvider == null ? SessionScopeProvider.DEFAULT : scopeProvider;
    }

    /** The per-tab session key: agent id + the current scope (active chat tab id, or "session" by default). */
    private String sessionKeyFor(String agentId) {
        String scope = scopeProvider.currentScope();
        return agentId + "#" + (scope == null || scope.isEmpty() ? "session" : scope);
    }

    /** The agent id embedded in a session key (everything before the first '#'). */
    private static String agentIdOf(String sessionKey) {
        int hash = sessionKey.indexOf('#');
        return hash < 0 ? sessionKey : sessionKey.substring(0, hash);
    }

    /** The SCOPE embedded in a session key (everything after the first '#'); "" when there is none. */
    private static String scopeOf(String sessionKey) {
        int hash = sessionKey.indexOf('#');
        return hash < 0 ? "" : sessionKey.substring(hash + 1);
    }

    /** Set (or replace) the host hook invoked by {@code /open <artifact>}; may be null (no-op). */
    public void setArtifactOpener(ArtifactOpener opener) {
        this.artifactOpener = opener;
    }

    /** Set (or replace) the host hook a command's {@code showTranscriptOverlay} routes to. */
    public void setTranscriptOverlayHost(TranscriptOverlayHost host) {
        this.transcriptOverlayHost = host;
    }

    /** Reveal an artifact tab programmatically (typed card actions use this — same hook as /open). */
    public void openArtifactView(String artifactId) {
        if (artifactOpener != null) {
            artifactOpener.open(artifactId);
        }
    }

    /** @return whether an agent id currently resolves to a usable agent extension. */
    public boolean canHandle(String agentId) {
        return resolver.resolve(agentId) != null;
    }

    /**
     * Make {@code agentId} the active routing target, creating its session on first use and reactivating an
     * existing one otherwise. The previously active session (if different) is deactivated but kept. A null or
     * unresolvable id deactivates the current session and leaves no active target.
     */
    public void setActiveAgent(String agentId) {
        if (agentId == null) {
            deactivateActive();
            return;
        }
        AgentPluginExtension extension = resolver.resolve(agentId);
        if (extension == null) {
            deactivateActive();
            return;
        }
        String sessionKey = sessionKeyFor(agentId);
        if (sessionKey.equals(activeSessionKey) && activeSession != null) {
            return; // this tab's session is already active; nothing to switch
        }
        if (activeSession != null) {
            activeSession.deactivate();
        }
        AgentSession session = sessions.get(sessionKey);
        // The host context carries the shared conversation sink; build it for a new session (and, only when a
        // failure must be reported, for an existing one too) so a startup error can reach the user as a bubble.
        AgentHostContext host = session == null ? hostContextProvider.create(agentId, sessionKey) : null;
        try {
            if (session == null) {
                session = extension.getSessionFactory().create(
                        // Both ids explicitly: the internal per-tab key AND the host scope (the chat
                        // session id) a plugin needs to publish this session to the outside world.
                        new AgentSessionCreationRequest(sessionKey, scopeOf(sessionKey), "", null), host);
                sessions.put(sessionKey, session);
            }
            session.activate();
        } catch (RuntimeException startupFailure) {
            // GENERIC startup-error mapping: a failure while a session starts (e.g. a mandatory model not
            // selected) must INFORM the user via a chat bubble instead of crashing the mode switch on the EDT.
            // The half-started session is never kept, so the next activation attempt retries from scratch and,
            // if it fails again, shows the same message.
            sessions.remove(sessionKey);
            activeSession = null;
            activeSessionKey = null;
            activeAgentId = null;
            activeExtension = null;
            if (host == null) {
                host = hostContextProvider.create(agentId, sessionKey);
            }
            reportStartupFailure(host, startupFailure);
            fireChange();
            return;
        }
        activeAgentId = agentId;
        activeSessionKey = sessionKey;
        activeSession = session;
        activeExtension = extension;
        fireChange();
    }

    /**
     * Map a session-startup failure to a user-facing chat bubble via the shared conversation sink, so the user
     * learns WHAT to fix (e.g. "select a reranker model") instead of only seeing a stack trace in the terminal.
     * Falls back to STDERR when no sink is reachable — a startup error is never swallowed silently.
     */
    private void reportStartupFailure(AgentHostContext host, Throwable failure) {
        String detail = deepestMessage(failure);
        com.aresstack.askai.plugin.api.agent.AgentConversationSink sink =
                host == null ? null : host.getConversationSink();
        if (sink != null) {
            sink.appendAssistantMessage("agent-start-failed-" + System.identityHashCode(failure),
                    "Ich kann den Agenten gerade nicht starten:\n\n" + detail
                    + "\n\nSobald das behoben ist, wechsle einfach erneut in den Questing-Modus — "
                    + "ich versuche es dann von vorne.");
        } else {
            System.err.println("[agent-host] session startup failed: " + detail);
        }
    }

    /** The deepest non-empty message in the cause chain — the actionable root reason, not the wrapper. */
    private static String deepestMessage(Throwable failure) {
        Throwable current = failure;
        String message = failure.getMessage();
        while (current.getCause() != null) {
            current = current.getCause();
            if (current.getMessage() != null && !current.getMessage().trim().isEmpty()) {
                message = current.getMessage();
            }
        }
        return message == null || message.trim().isEmpty() ? failure.toString() : message;
    }

    /** Deactivate the active session (kept for reuse) and route back to Yapping / no agent. */
    public void deactivateActive() {
        if (activeSession != null) {
            activeSession.deactivate();
        }
        activeSession = null;
        activeAgentId = null;
        activeSessionKey = null;
        activeExtension = null;
        fireChange();
    }

    /** Close and forget ALL of one agent's sessions (plugin disabled/removed), across every tab scope. */
    public void closeAgent(String agentId) {
        List<String> keys = new ArrayList<String>();
        for (String key : sessions.keySet()) {
            if (agentIdOf(key).equals(agentId)) {
                keys.add(key);
            }
        }
        boolean any = false;
        for (String key : keys) {
            any = closeSessionKey(key) || any;
        }
        if (any) {
            fireChange();
        }
    }

    /**
     * Close EVERY session belonging to one chat-tab scope (all agents that ran in that tab), because the tab
     * is closing. Detaching (map removal + active reset) happens on the caller (EDT); the actual
     * {@link AgentSession#close()} — which tears down the agent process and BLOCKS on the browser runtime's
     * owner thread — runs on a dedicated background thread so tab close never freezes the UI. Late callbacks
     * from those sessions are already gated by the session's own {@code disposed} flag.
     */
    public void closeSessionsForScope(String scope) {
        final String suffix = "#" + (scope == null || scope.isEmpty() ? "session" : scope);
        List<String> keys = new ArrayList<String>();
        for (String key : sessions.keySet()) {
            if (key.endsWith(suffix)) {
                keys.add(key);
            }
        }
        final List<AgentSession> toClose = new ArrayList<AgentSession>();
        for (String key : keys) {
            AgentSession session = sessions.remove(key);
            if (session == null) {
                continue;
            }
            if (session == activeSession) {
                activeSession = null;
                activeAgentId = null;
                activeSessionKey = null;
                activeExtension = null;
            }
            toClose.add(session);
        }
        if (toClose.isEmpty()) {
            return;
        }
        fireChange();
        Thread closer = new Thread(new Runnable() {
            public void run() {
                for (AgentSession session : toClose) {
                    try {
                        session.close();
                    } catch (RuntimeException | Error ignored) {
                        // tab is gone: best-effort teardown
                    }
                }
            }
        }, "agent-session-tab-close");
        closer.setDaemon(true);
        closer.start();
    }

    /** Close and forget exactly one session key (a tab-scoped session). Returns whether one was closed. */
    private boolean closeSessionKey(String sessionKey) {
        AgentSession session = sessions.remove(sessionKey);
        if (session == null) {
            return false;
        }
        if (session == activeSession) {
            activeSession = null;
            activeAgentId = null;
            activeSessionKey = null;
            activeExtension = null;
        }
        session.close();
        return true;
    }

    /**
     * Close every session whose agent id is not in {@code keepIds} (e.g. after a catalog refresh removed a
     * plugin). Sessions still present stay open and, if active, keep routing.
     */
    public void retainOnly(Collection<String> keepIds) {
        List<String> toClose = new ArrayList<String>();
        for (String key : sessions.keySet()) {
            if (keepIds == null || !keepIds.contains(agentIdOf(key))) {
                toClose.add(key);
            }
        }
        boolean any = false;
        for (String key : toClose) {
            any = closeSessionKey(key) || any;
        }
        if (any) {
            fireChange();
        }
    }

    /** Close all sessions (process shutdown), including any that failed to close on a previous swap. */
    public void shutdown() {
        List<AgentSession> toClose = detachAllInternal();
        synchronized (unclosed) {
            toClose.addAll(unclosed);
            unclosed.clear();
        }
        for (AgentSession session : toClose) {
            try {
                session.close();
            } catch (RuntimeException | Error ignored) {
                // process exit: best-effort (nothing left to retry against)
            }
        }
    }

    // ------------------------------------------------------------------ GenerationSwapHook

    /**
     * EDT: atomically detach every session of the outgoing plugin generation (plus any that failed to close on a
     * previous swap) from routing, and return a handle that closes them <em>off</em> the EDT. The old generation
     * is retired by the caller only if {@link OutgoingSessions#closeAll()} succeeds; a session whose close throws
     * is <em>kept</em> in {@code unclosed} (not dropped as a mere string) and retried on the next detach, so a
     * live object is never orphaned while its plugin classloader is (correctly) still loaded. Sessions are
     * recreated lazily against the new generation.
     */
    @Override
    public OutgoingSessions detachOutgoing() {
        final List<AgentSession> detached = detachAllInternal();
        synchronized (unclosed) {
            detached.addAll(unclosed); // retry sessions that failed to close on a previous swap
            unclosed.clear();
        }
        return new OutgoingSessions() {
            public SessionCloseResult closeAll() {
                List<String> failures = new ArrayList<String>();
                List<AgentSession> stillOpen = new ArrayList<AgentSession>();
                for (AgentSession session : detached) {
                    try {
                        session.close();
                    } catch (RuntimeException | Error ex) {
                        String message = ex.getClass().getSimpleName();
                        if (ex.getMessage() != null) {
                            message += ": " + ex.getMessage();
                        }
                        failures.add(message);
                        stillOpen.add(session); // keep the reference for a later retry
                    }
                }
                if (!stillOpen.isEmpty()) {
                    unclosed.addAll(stillOpen);
                }
                return SessionCloseResult.of(failures);
            }
        };
    }

    /** @return the number of sessions that failed to close and are pending a retry (0 when clean). */
    public int getUnclosedSessionCount() {
        return unclosed.size();
    }

    /** EDT: clear routing + the session map, returning the detached sessions to be closed elsewhere. */
    private List<AgentSession> detachAllInternal() {
        activeSession = null;
        activeAgentId = null;
        activeSessionKey = null;
        activeExtension = null;
        List<AgentSession> detached = new ArrayList<AgentSession>(sessions.values());
        sessions.clear();
        fireChange();
        return detached;
    }

    public String getActiveAgentId() {
        return activeAgentId;
    }

    /** @return the active session, or {@code null} when routing to Yapping / no agent. */
    public AgentSession getActiveSession() {
        return activeSession;
    }

    /** @return the active agent's specialized artifact-view contributions (empty when no agent is active). */
    public List<ArtifactViewContribution> getActiveArtifactViews() {
        List<ArtifactViewContribution> views =
                activeExtension == null ? null : activeExtension.getArtifactViews();
        return views == null ? Collections.<ArtifactViewContribution>emptyList() : views;
    }

    /** @return the active agent's composer accessories (empty when no agent is active). */
    public List<com.aresstack.askai.plugin.api.agent.composer.ComposerAccessoryContribution>
            getActiveComposerAccessories() {
        List<com.aresstack.askai.plugin.api.agent.composer.ComposerAccessoryContribution> accessories =
                activeExtension == null ? null : activeExtension.getComposerAccessories();
        return accessories == null
                ? Collections.<com.aresstack.askai.plugin.api.agent.composer.ComposerAccessoryContribution>
                        emptyList()
                : accessories;
    }


    /**
     * The active agent's title-bar search, or {@code null} when no agent is active, the agent offers none,
     * or it does not support the active session.
     */
    public com.aresstack.askai.plugin.api.agent.search.AgentSearchContribution getActiveSearchContribution() {
        if (activeExtension == null) {
            return null;
        }
        com.aresstack.askai.plugin.api.agent.search.AgentSearchContribution search =
                activeExtension.getSearchContribution();
        return search != null && search.supports(getActiveSession()) ? search : null;
    }

    /** The active agent's hamburger-replacement glyph, or {@code null} (keep the hamburger). */
    public javax.swing.Icon getActiveMenuIcon() {
        return activeExtension == null ? null : activeExtension.getMenuIcon();
    }

    /** @return the active agent's top-bar toolbar contributions (empty when no agent is active). */
    public List<com.aresstack.askai.plugin.api.agent.toolbar.AgentToolbarContribution>
            getActiveToolbarContributions() {
        List<com.aresstack.askai.plugin.api.agent.toolbar.AgentToolbarContribution> contributions =
                activeExtension == null ? null : activeExtension.getToolbarContributions();
        return contributions == null
                ? Collections.<com.aresstack.askai.plugin.api.agent.toolbar.AgentToolbarContribution>
                        emptyList()
                : contributions;
    }

    /**
     * The settings pages of the ACTIVE agent (empty without one): the gear menu renders them only for
     * the tab whose agent is selected, with that tab's session — session-based by contract.
     */
    public List<com.aresstack.askai.plugin.api.agent.AgentSettingsContribution>
            getActiveSettingsContributions() {
        List<com.aresstack.askai.plugin.api.agent.AgentSettingsContribution> contributions =
                activeExtension == null ? null : activeExtension.getSettingsContributions();
        return contributions == null
                ? Collections.<com.aresstack.askai.plugin.api.agent.AgentSettingsContribution>emptyList()
                : contributions;
    }

    // ------------------------------------------------------------------ per-scope activity read-model

    /**
     * True when ANY session bound to this tab scope reports actual running work — read from the
     * sessions' OWN {@link com.aresstack.askai.plugin.api.agent.AgentStateSnapshot#isBusy() state
     * snapshot} (the authoritative runtime state), never from message texts or timestamps. This is
     * the chat list's green activity dot: a merely EXISTING session is not "busy".
     */
    public boolean isScopeBusy(String scope) {
        if (scope == null) {
            return false;
        }
        for (Map.Entry<String, AgentSession> entry : sessions.entrySet()) {
            if (!scope.equals(scopeOf(entry.getKey()))) {
                continue;
            }
            com.aresstack.askai.plugin.api.agent.AgentStateSnapshot state =
                    entry.getValue().getState();
            if (state != null && state.isBusy()) {
                return true;
            }
        }
        return false;
    }

    /** The first session's phase label for this tab scope ("" when none) — quiet list metadata. */
    public String scopePhaseLabel(String scope) {
        if (scope == null) {
            return "";
        }
        for (Map.Entry<String, AgentSession> entry : sessions.entrySet()) {
            if (!scope.equals(scopeOf(entry.getKey()))) {
                continue;
            }
            com.aresstack.askai.plugin.api.agent.AgentStateSnapshot state =
                    entry.getValue().getState();
            if (state != null && !state.getPhaseLabel().isEmpty()) {
                return state.getPhaseLabel();
            }
        }
        return "";
    }

    // ------------------------------------------------------------------ ChatSubmissionRouter

    @Override
    public boolean isActive() {
        return activeSession != null;
    }

    @Override
    public SubmissionAvailability getAvailability() {
        if (activeSession == null) {
            return SubmissionAvailability.UNAVAILABLE;
        }
        SubmissionAvailability availability = activeSession.getChatTarget().getAvailability();
        return availability == null ? SubmissionAvailability.UNAVAILABLE : availability;
    }

    @Override
    public void submitText(String text) {
        if (text == null || text.trim().isEmpty()) {
            return;
        }
        // The coordinator is shared by ALL chat tabs. The active session must belong to the VISIBLE tab:
        // after deleting/closing/switching chats it could still be another tab's session, which echoed and
        // persisted the prompt into that (invisible) chat — the user's message seemed to vanish. Re-bind to
        // the current tab's session first (creates it on first use).
        String agentId = activeAgentId;
        if (agentId != null && activeSessionKey != null
                && !activeSessionKey.equals(sessionKeyFor(agentId))) {
            setActiveAgent(agentId);
        }
        AgentSession session = activeSession;
        if (session == null) {
            reportUndeliverable(agentId, text, "The agent session is not running for this chat.");
            return;
        }
        SubmissionAvailability availability = session.getChatTarget().getAvailability();
        if (availability == SubmissionAvailability.UNAVAILABLE) {
            reportUndeliverable(agentId, text, "The agent session for this chat has ended or failed to start.");
            return;
        }
        try {
            session.getChatTarget().submitText(text);
        } catch (RuntimeException failed) {
            reportUndeliverable(agentId, text, deepestMessage(failed));
        }
    }

    /**
     * A prompt that cannot reach an agent is NEVER dropped silently: the user's text stays visible in the
     * current chat, followed by the reason, so the user knows what happened and can retry.
     */
    private void reportUndeliverable(String agentId, String text, String reason) {
        String key = sessionKeyFor(agentId == null ? "agent" : agentId);
        AgentHostContext host = null;
        try {
            host = hostContextProvider.create(agentId, key);
        } catch (RuntimeException noHost) {
            // fall through to STDERR
        }
        com.aresstack.askai.plugin.api.agent.AgentConversationSink sink =
                host == null ? null : host.getConversationSink();
        if (sink == null) {
            System.err.println("[agent-host] prompt not delivered: " + reason);
            return;
        }
        long stamp = System.nanoTime();
        sink.appendUserMessage("undelivered-user-" + stamp, text);
        sink.appendAssistantMessage("undelivered-" + stamp,
                "Your message could not be delivered to the agent: " + reason
                + "\n\nSwitch the mode again (or open a new chat) to restart the agent, then resend.");
    }

    @Override
    public void stop() {
        if (activeSession != null) {
            activeSession.getChatTarget().stop();
        }
    }

    @Override
    public void addChangeListener(Runnable listener) {
        if (listener != null) {
            listeners.addIfAbsent(listener);
        }
    }

    @Override
    public void removeChangeListener(Runnable listener) {
        listeners.remove(listener);
    }

    /**
     * Public so the host can nudge the composer to re-read availability as a run progresses. Each listener is
     * isolated: a throwing UI listener must never abort an already-performed lifecycle step (e.g. a detach that
     * already cleared the session map) nor prevent the other listeners from running.
     */
    public void fireChange() {
        for (Runnable listener : listeners) {
            try {
                listener.run();
            } catch (RuntimeException | Error ignored) {
                // a broken listener must not corrupt the lifecycle step that notified it
            }
        }
    }

    // ------------------------------------------------------------------ ActiveAgentCommandRegistry

    @Override
    public List<ChatCommandDescriptor> getCommands() {
        if (activeExtension == null) {
            return Collections.emptyList();
        }
        List<ChatCommandDescriptor> descriptors = new ArrayList<ChatCommandDescriptor>();
        for (ChatCommandContribution contribution : chatContributions()) {
            descriptors.add(contribution.getDescriptor());
        }
        return descriptors;
    }

    @Override
    public boolean isCommandLine(String input) {
        return activeSession != null && activeExtension != null
                && !chatContributions().isEmpty() && Parsed.looksLikeCommand(input);
    }

    @Override
    public CommandCompletionResult complete(String input, int caretPosition) {
        if (activeExtension == null || activeSession == null) {
            return CommandCompletionResult.empty();
        }
        String effective = clampToCaret(input, caretPosition);
        if (!Parsed.looksLikeCommand(effective)) {
            return CommandCompletionResult.empty();
        }
        Parsed parsed = Parsed.of(effective);
        if (parsed.nameStage) {
            List<CommandCompletion> out = new ArrayList<CommandCompletion>();
            for (ChatCommandContribution contribution : chatContributions()) {
                ChatCommandDescriptor descriptor = contribution.getDescriptor();
                if (descriptor.getName().startsWith(parsed.partial)) {
                    boolean takesArgs = !descriptor.getArguments().isEmpty();
                    String insertion = "/" + descriptor.getName() + (takesArgs ? " " : "");
                    out.add(new CommandCompletion(insertion, "/" + descriptor.getName(),
                            descriptor.getDescription(), CompletionKind.COMMAND));
                }
            }
            return new CommandCompletionResult(out);
        }
        ChatCommandContribution contribution = find(parsed.command);
        if (contribution == null) {
            return CommandCompletionResult.empty();
        }
        CommandCompletionRequest request =
                new CommandCompletionRequest(parsed.command, parsed.args, parsed.partial);
        CommandCompletionResult raw = contribution.complete(request, commandContext);
        // Reconstruct each suggestion into a FULL replacement line so the composer just replaces its text.
        List<CommandCompletion> out = new ArrayList<CommandCompletion>();
        String prefix = "/" + parsed.command + " "
                + (parsed.args.isEmpty() ? "" : join(parsed.args) + " ");
        for (CommandCompletion completion : raw.getCompletions()) {
            out.add(new CommandCompletion(prefix + completion.getInsertionText(),
                    completion.getDisplayText(), completion.getDescription(), completion.getKind()));
        }
        return new CommandCompletionResult(out);
    }

    @Override
    public CommandExecutionResult execute(String input) {
        if (activeExtension == null || activeSession == null || !Parsed.looksLikeCommand(input)) {
            return CommandExecutionResult.unknown();
        }
        Parsed parsed = Parsed.of(input);
        String name = parsed.nameStage ? parsed.partial : parsed.command;
        ChatCommandContribution contribution = find(name);
        if (contribution == null) {
            return CommandExecutionResult.unknown();
        }
        List<String> args = parsed.executionArgs();
        return contribution.execute(new CommandInvocation(name, args, input), commandContext);
    }

    private List<ChatCommandContribution> chatContributions() {
        List<ChatCommandContribution> contributions =
                activeExtension == null ? null : activeExtension.getChatCommands();
        return contributions == null ? Collections.<ChatCommandContribution>emptyList() : contributions;
    }

    private ChatCommandContribution find(String name) {
        for (ChatCommandContribution contribution : chatContributions()) {
            if (contribution.getDescriptor().getName().equals(name)) {
                return contribution;
            }
        }
        return null;
    }

    private static String clampToCaret(String input, int caret) {
        if (input == null) {
            return "";
        }
        if (caret < 0 || caret > input.length()) {
            return input;
        }
        return input.substring(0, caret);
    }

    private static String join(List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(parts.get(i));
        }
        return sb.toString();
    }

    /** One reused context; reads the current active session so late execute() calls stay consistent. */
    private final class ActiveContext implements AgentSessionContext {
        public AgentSession getSession() {
            return activeSession;
        }

        public void openArtifact(String artifactId) {
            if (artifactOpener != null) {
                artifactOpener.open(artifactId);
            }
        }

        public UiExecutor getUiExecutor() {
            return uiExecutor;
        }

        @Override
        public void showTranscriptOverlay(javax.swing.JComponent content, String title) {
            if (transcriptOverlayHost != null) {
                transcriptOverlayHost.show(content, title);
            }
        }

        @Override
        public void showDiagramOverlay(String mermaidSource, String title) {
            if (transcriptOverlayHost != null) {
                transcriptOverlayHost.showDiagram(mermaidSource, title);
            }
        }
    }

    /** Minimal slash-command parse: command-name stage vs argument stage, with the fragment being typed. */
    static final class Parsed {
        final boolean nameStage;
        final String command;
        final List<String> args; // completed args before the fragment (completion) or all args (execution)
        final String partial;    // fragment currently being typed

        private Parsed(boolean nameStage, String command, List<String> args, String partial) {
            this.nameStage = nameStage;
            this.command = command;
            this.args = args;
            this.partial = partial;
        }

        static boolean looksLikeCommand(String input) {
            return input != null && input.trim().startsWith("/");
        }

        static Parsed of(String input) {
            String trimmedLeading = input == null ? "" : input.replaceAll("^\\s+", "");
            String body = trimmedLeading.startsWith("/") ? trimmedLeading.substring(1) : trimmedLeading;
            boolean endsWithSpace = body.length() > 0 && Character.isWhitespace(body.charAt(body.length() - 1));
            String[] tokens = body.trim().isEmpty() ? new String[0] : body.trim().split("\\s+");
            if (tokens.length == 0) {
                return new Parsed(true, "", Collections.<String>emptyList(), "");
            }
            if (tokens.length == 1 && !endsWithSpace) {
                return new Parsed(true, "", Collections.<String>emptyList(), tokens[0]);
            }
            String command = tokens[0];
            List<String> args = new ArrayList<String>();
            String partial;
            if (endsWithSpace) {
                for (int i = 1; i < tokens.length; i++) {
                    args.add(tokens[i]);
                }
                partial = "";
            } else {
                for (int i = 1; i < tokens.length - 1; i++) {
                    args.add(tokens[i]);
                }
                partial = tokens[tokens.length - 1];
            }
            return new Parsed(false, command, args, partial);
        }

        /** All typed arguments (fragment included) for execution. */
        List<String> executionArgs() {
            if (nameStage) {
                return Collections.emptyList();
            }
            List<String> all = new ArrayList<String>(args);
            if (!partial.isEmpty()) {
                all.add(partial);
            }
            return all;
        }
    }
}
