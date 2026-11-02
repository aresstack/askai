package com.aresstack.askai.research.runtime;

import com.aresstack.askai.acp.AcpAgentConnector;
import com.aresstack.askai.acp.AcpConnection;
import com.aresstack.askai.acp.AcpConnectionState;
import com.aresstack.askai.acp.AcpException;
import com.aresstack.askai.acp.AcpPromptState;
import com.aresstack.askai.acp.AcpSession;
import com.aresstack.askai.acp.AcpSessionState;
import com.aresstack.askai.acp.AcpStates;
import com.aresstack.askai.acp.AcpUpdate;
import com.aresstack.askai.acp.AcpUpdateListener;
import com.aresstack.askai.acp.AgentLaunchSpec;
import com.aresstack.askai.acp.AgentProcessHandle;
import com.aresstack.askai.acp.PromptDispatcher;
import com.aresstack.askai.acp.PromptHandle;
import com.aresstack.askai.research.runtime.host.HostToolEndpoints;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The research agent inside the AskAI JVM (askai #48). Instead of starting {@code java -jar
 * research-agent-runtime} and speaking ACP over its stdio, this connector creates a {@link ResearchAgentMain}
 * and calls it directly: initialize, new session, prompt and cancel are method calls, and every message the
 * agent sends reaches the host's listener without a wire. The launch specification only contributes its
 * configuration map (command and arguments are not used); the host tools are reached through
 * {@link HostToolEndpoints}. The research plugin keeps its neutral {@link AcpAgentConnector} port.
 */
public final class InProcessResearchAgentConnector implements AcpAgentConnector {

    private final HostToolEndpoints hostEndpoints;
    private final ResearchRuntimeShared shared;

    public InProcessResearchAgentConnector(HostToolEndpoints hostEndpoints, ResearchRuntimeShared shared) {
        if (hostEndpoints == null || shared == null) {
            throw new IllegalArgumentException("hostEndpoints and shared must not be null");
        }
        this.hostEndpoints = hostEndpoints;
        this.shared = shared;
    }

    @Override
    public AcpConnection connect(AgentLaunchSpec spec) throws AcpException {
        // The variables the child process used to inherit, overlaid by the ones the host sets explicitly.
        Map<String, String> configuration = new HashMap<String, String>(System.getenv());
        configuration.putAll(spec.getEnv());
        Connection connection = new Connection(new ResearchAgentMain(configuration, hostEndpoints, shared));
        connection.initialize();
        return connection;
    }

    private static final class Connection implements AcpConnection {
        private final ResearchAgentMain agent;
        private final AcpStates.Connection state = new AcpStates.Connection();
        /** One prompt at a time per connection, as over ACP; never on the caller's (UI) thread. */
        private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "research-agent-prompt");
            t.setDaemon(true);
            return t;
        });
        private volatile boolean alive = true;

        Connection(ResearchAgentMain agent) {
            this.agent = agent;
        }

        void initialize() throws AcpException {
            try {
                state.to(AcpConnectionState.INITIALIZING);
                agent.initialize();
                state.to(AcpConnectionState.READY);
            } catch (RuntimeException ex) {
                state.to(AcpConnectionState.FAILED);
                close();
                throw new AcpException(AcpException.Phase.INITIALIZE,
                        "research agent initialize failed: " + ex.getMessage(), ex);
            }
        }

        @Override
        public AcpConnectionState getState() {
            return state.get();
        }

        @Override
        public AgentProcessHandle getProcess() {
            return new AgentProcessHandle() {
                public boolean isAlive() {
                    return alive;
                }

                public void destroyForcibly() {
                    close();
                }
            };
        }

        @Override
        public AcpSession newSession() throws AcpException {
            if (state.get() != AcpConnectionState.READY) {
                throw new AcpException(AcpException.Phase.SESSION,
                        "Connection is not READY (" + state.get() + ").", null);
            }
            try {
                return new Session(this, agent.newSession());
            } catch (RuntimeException ex) {
                throw new AcpException(AcpException.Phase.SESSION, "session/new failed: " + ex.getMessage(), ex);
            }
        }

        @Override
        public void close() {
            if (!alive) {
                return;
            }
            alive = false;
            if (state.get() == AcpConnectionState.READY) {
                state.to(AcpConnectionState.CLOSED);
            }
            try {
                agent.close();
            } catch (RuntimeException ignored) {
                // best effort: closing must never fail the caller
            }
            executor.shutdownNow();
        }
    }

    private static final class Session implements AcpSession {
        private final Connection connection;
        private final String sessionId;
        private final AcpStates.Session state = new AcpStates.Session();

        Session(Connection connection, String sessionId) {
            this.connection = connection;
            this.sessionId = sessionId;
            state.to(AcpSessionState.ACTIVE);
        }

        @Override
        public String getSessionId() {
            return sessionId;
        }

        @Override
        public AcpSessionState getState() {
            return state.get();
        }

        @Override
        public PromptHandle prompt(String text, AcpUpdateListener listener) {
            final String promptId = UUID.randomUUID().toString();
            final PromptDispatcher dispatcher = new PromptDispatcher(sessionId, promptId, listener);
            final String request = text == null ? "" : text;
            final AgentOutput output = new AgentOutput() {
                public void sendMessage(String message) {
                    dispatcher.update(AcpUpdate.Kind.MESSAGE, message);
                }
            };
            connection.executor.execute(new Runnable() {
                public void run() {
                    try {
                        boolean cancelled = connection.agent.prompt(output, request);
                        dispatcher.terminal(cancelled ? AcpPromptState.CANCELLED : AcpPromptState.COMPLETED,
                                cancelled ? "CANCELLED" : "END_TURN");
                    } catch (RuntimeException ex) {
                        dispatcher.terminal(AcpPromptState.FAILED, "prompt failed: " + ex.getMessage());
                    }
                }
            });
            return new PromptHandle() {
                public String getPromptId() {
                    return promptId;
                }

                public AcpPromptState getState() {
                    return dispatcher.getState();
                }

                public void cancel() {
                    if (dispatcher.cancelling()) {
                        connection.agent.cancel();
                    }
                }
            };
        }

        @Override
        public void close() {
            state.to(AcpSessionState.CLOSING);
            state.to(AcpSessionState.CLOSED);
        }
    }
}
