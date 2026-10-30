package com.aresstack.askai.research.agentwire;

import com.aresstack.askai.research.backend.ResearchActivityKind;
import com.aresstack.askai.research.backend.ResearchBackendEvent;
import com.aresstack.askai.research.backend.ResearchBackendEventType;
import com.aresstack.askai.research.state.ResearchCommandType;
import com.aresstack.askai.research.backend.ResearchProjectRequest;
import com.aresstack.askai.research.backend.ResearchPrompt;
import com.aresstack.askai.research.backend.ResearchSessionBackend;
import com.aresstack.askai.research.backend.ResearchSessionHandle;
import com.aresstack.askai.research.backend.ResearchSessionListener;
import com.aresstack.askai.research.runtime.AgentOutput;
import com.aresstack.askai.research.runtime.AgentTurnOutcome;
import com.aresstack.askai.research.runtime.ResearchAgentEnvironment;
import com.aresstack.askai.research.runtime.ResearchAgentSession;
import com.aresstack.askai.research.runtime.loop.ToolInvoker;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The productive {@link ResearchSessionBackend}: a pure ADAPTER between the research port and the IN-PROCESS
 * {@link ResearchAgentSession} (formerly the ACP adapter over an external agent process). It owns NO research
 * logic — the state machine, project store and research-control endpoint stay in the plugin session; the
 * agent orchestrates its run and can never approve or switch phases (there is no such tool). The agent reaches
 * the host tools through the in-process invoker lanes it is given; the host gets the agent's lines through the
 * turn's {@link AgentOutput} and maps them with {@link ResearchAgentEventMapper} exactly as before.
 *
 * <p>Turn semantics as before: a prompt is a user-visible agent TURN whose terminal ends the busy state; a typed
 * control envelope ({@code #RSC1#}) runs on its own thread with the same mapping but never reports "the turn
 * is done". An agent that fails to open reports ERROR and the session stays down (no automatic restart).</p>
 */
public final class InProcessResearchSessionBackend implements ResearchSessionBackend {

    private final Map<String, String> baseEnvironment;
    private final ToolInvoker research;
    private final ToolInvoker browser;   // null => honestly absent (MCP-P005), never faked
    private final ToolInvoker service;   // null => no internal service lane
    /** The host's model port (ADR 0001, 3.2); null => every model role is honestly unavailable. */
    private final com.aresstack.askai.agent.model.port.ModelPort modelPort;
    private final Map<String, Session> sessions = new ConcurrentHashMap<String, Session>();

    /**
     * @param baseEnvironment the ASKAI_* hand-off of this generation (snapshot paths, settings); the session
     *                        identity is added per session
     * @param research        the research-control lane (mandatory)
     * @param browser         the browser bridge lane, or null when no browser backend exists
     * @param service         the internal service lane, or null when absent
     */
    public InProcessResearchSessionBackend(Map<String, String> baseEnvironment, ToolInvoker research,
                                           ToolInvoker browser, ToolInvoker service) {
        this(baseEnvironment, research, browser, service, null);
    }

    /**
     * @param modelPort the host's model port every model role of the agent calls, or null when the host
     *                  offers none
     */
    public InProcessResearchSessionBackend(Map<String, String> baseEnvironment, ToolInvoker research,
                                           ToolInvoker browser, ToolInvoker service,
                                           com.aresstack.askai.agent.model.port.ModelPort modelPort) {
        this.modelPort = modelPort;
        this.baseEnvironment = baseEnvironment == null
                ? new LinkedHashMap<String, String>() : new LinkedHashMap<String, String>(baseEnvironment);
        this.research = research;
        this.browser = browser;
        this.service = service;
    }

    @Override
    public ResearchSessionHandle createSession(ResearchProjectRequest request,
                                               ResearchSessionListener listener) {
        Session session = new Session(request, listener);
        sessions.put(request.getSessionId(), session);
        session.start();
        return session;
    }

    @Override
    public boolean canExecute(ResearchSessionHandle handle, ResearchCommandType command) {
        return false; // functional transitions stay with the plugin's own state machine, not the adapter
    }

    @Override
    public void executeCommand(ResearchSessionHandle handle, ResearchCommandType command) {
        // Intentionally empty: the adapter never drives the state machine (no second research logic).
    }

    @Override
    public void submitPrompt(ResearchSessionHandle handle, ResearchPrompt prompt) {
        Session session = resolve(handle);
        if (session != null) {
            session.dispatch(prompt.getText(), true);
        }
    }

    @Override
    public void submitServiceCommand(ResearchSessionHandle handle, String controlEnvelope) {
        // A typed control command (e.g. #RSC1# manual_search): the same input channel and the same mapping
        // listener as a turn, but NOT a chat turn — the agent dispatches the envelope before any
        // model/TeamAgent/state logic, and its end never reports "the turn is done".
        Session session = resolve(handle);
        if (session != null) {
            session.dispatch(controlEnvelope, false);
        }
    }

    @Override
    public void approve(ResearchSessionHandle handle, String approvalId) {
        // Approval is a user/state-machine concern handled by the plugin session, not the agent.
    }

    @Override
    public void reject(ResearchSessionHandle handle, String approvalId, String reason) {
        // See approve().
    }

    @Override
    public void pause(ResearchSessionHandle handle) {
        cancel(handle); // pause of an agent turn = cancel the running prompt (the session keeps running)
    }

    @Override
    public void resume(ResearchSessionHandle handle) {
        // No-op for the adapter; the user resumes by prompting again.
    }

    @Override
    public void cancel(ResearchSessionHandle handle) {
        Session session = resolve(handle);
        if (session != null) {
            session.cancelActivePrompt();
        }
    }

    @Override
    public void close(ResearchSessionHandle handle) {
        Session session = handle == null ? null : sessions.remove(handle.getSessionId());
        if (session != null) {
            session.shutdown();
        }
    }

    /** Close every live session (agents included). Used by the owning resources on shutdown. */
    public void closeAllSessions() {
        for (String sessionId : sessions.keySet()) {
            Session session = sessions.remove(sessionId);
            if (session != null) {
                session.shutdown();
            }
        }
    }

    private Session resolve(ResearchSessionHandle handle) {
        return handle == null ? null : sessions.get(handle.getSessionId());
    }

    // ------------------------------------------------------------------ session

    private final class Session implements ResearchSessionHandle {
        private final ResearchProjectRequest request;
        private final ResearchSessionListener listener;
        private final AtomicLong sequence = new AtomicLong();
        private final AtomicLong promptIds = new AtomicLong();
        /** Turns and control commands run off the caller's thread, concurrently like independent requests. */
        private final ExecutorService turns;
        private volatile ResearchAgentSession agent;
        /**
         * Which foreground turn is the one the user waits for. A terminal always belongs to the turn that
         * produced it, possibly long after the user pressed Stop and started the next one — without this
         * counter that late terminal would report "the turn is done" for a turn that is still running.
         */
        private final AtomicLong foregroundGeneration = new AtomicLong();
        private volatile boolean closed;

        private Session(ResearchProjectRequest request, ResearchSessionListener listener) {
            this.request = request;
            this.listener = listener;
            this.turns = Executors.newCachedThreadPool(new ThreadFactory() {
                public Thread newThread(Runnable task) {
                    Thread thread = new Thread(task, "research-agent-" + request.getSessionId());
                    thread.setDaemon(true);
                    return thread;
                }
            });
        }

        public String getSessionId() {
            return request.getSessionId();
        }

        public String getProjectId() {
            return request.getProjectId();
        }

        /**
         * Atomic start: build the agent with its identity and lanes, open it (real readiness check against the
         * research lane). Any failure reports ERROR and leaves no half-started agent behind.
         */
        void start() {
            Map<String, String> environment = new LinkedHashMap<String, String>(baseEnvironment);
            environment.put("ASKAI_SESSION_ID", request.getSessionId());
            environment.put("ASKAI_PROJECT_ID", request.getProjectId());
            try {
                ResearchAgentSession created = new ResearchAgentSession(
                        ResearchAgentEnvironment.from(environment), research, browser, service, modelPort);
                created.open();
                agent = created;
                if (browser == null) {
                    emit(ResearchBackendEvent.builder(ResearchBackendEventType.ACTIVITY)
                            .activity("agent-browser", ResearchActivityKind.TOOL_UPDATE, "Browser",
                                    "BROWSER_NOT_AVAILABLE: no live browser backend; web tools are hidden."));
                }
            } catch (RuntimeException ex) {
                agent = null;
                emit(ResearchBackendEvent.builder(ResearchBackendEventType.ERROR)
                        .messages("The research agent could not be started.",
                                "OPEN: " + ex.getMessage()));
            }
        }

        void dispatch(final String text, final boolean foreground) {
            final ResearchAgentSession current = agent;
            if (closed || current == null) {
                return;
            }
            final long generation = foreground ? foregroundGeneration.incrementAndGet()
                    : foregroundGeneration.get();
            final String promptId = "p" + promptIds.incrementAndGet();
            turns.submit(new Runnable() {
                public void run() {
                    AgentTurnOutcome outcome;
                    try {
                        outcome = current.prompt(text, new AgentOutput() {
                            public void message(String line) {
                                ResearchBackendEvent.Builder builder =
                                        ResearchAgentEventMapper.mapMessage(promptId, line);
                                if (builder != null) {
                                    emit(builder);
                                }
                            }
                        });
                    } catch (RuntimeException failed) {
                        if (foreground && generation == foregroundGeneration.get()) {
                            emit(ResearchAgentEventMapper.mapFailure(failed.getMessage() == null
                                    ? failed.toString() : failed.getMessage()));
                        }
                        return;
                    }
                    if (!foreground) {
                        return; // control traffic never reports "the turn is done"
                    }
                    if (generation != foregroundGeneration.get()) {
                        // A newer turn has started meanwhile (Stop + retype): this terminal belongs to the
                        // abandoned one and must not end the new turn's busy state.
                        return;
                    }
                    ResearchBackendEvent.Builder builder = ResearchAgentEventMapper.mapTerminal(outcome);
                    if (builder != null) {
                        emit(builder);
                    }
                }
            });
        }

        void cancelActivePrompt() {
            ResearchAgentSession current = agent;
            if (current != null) {
                current.cancel(); // cancels the turn in flight; the session stays alive
            }
        }

        void shutdown() {
            closed = true;
            ResearchAgentSession current = agent;
            agent = null;
            if (current != null) {
                try {
                    current.close();
                } catch (RuntimeException ignored) {
                    // best-effort
                }
            }
            turns.shutdownNow();
        }

        private void emit(ResearchBackendEvent.Builder builder) {
            if (closed || listener == null) {
                return;
            }
            listener.onEvent(builder.envelope("agent-" + sequence.get(), request.getSessionId(),
                    request.getProjectId(), 0L, 0L, sequence.incrementAndGet(), null).build());
        }
    }
}
