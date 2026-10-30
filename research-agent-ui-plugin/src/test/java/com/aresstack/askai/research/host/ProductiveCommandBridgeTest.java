package com.aresstack.askai.research.host;

import com.aresstack.askai.mcp.api.InProcessMcpServerRegistry;
import com.aresstack.askai.plugin.api.agent.AgentConversationSink;
import com.aresstack.askai.plugin.api.agent.AgentHostContext;
import com.aresstack.askai.plugin.api.service.MarkdownViewFactory;
import com.aresstack.askai.plugin.api.service.NotificationService;
import com.aresstack.askai.plugin.api.service.PluginPathService;
import com.aresstack.askai.plugin.api.service.ThemeService;
import com.aresstack.askai.plugin.api.service.UiExecutor;
import com.aresstack.askai.plugin.api.service.WorkspaceStateStore;
import com.aresstack.askai.research.agent.ResearchAgentSession;
import com.aresstack.askai.research.agent.ResearchArtifactStore;
import com.aresstack.askai.research.backend.ResearchBackendEvent;
import com.aresstack.askai.research.backend.ResearchCommandDispatchResult;
import com.aresstack.askai.research.backend.ResearchProjectRequest;
import com.aresstack.askai.research.backend.ResearchPrompt;
import com.aresstack.askai.research.backend.ResearchSessionBackend;
import com.aresstack.askai.research.backend.ResearchSessionHandle;
import com.aresstack.askai.research.backend.ResearchSessionListener;
import com.aresstack.askai.research.mcp.ResearchControlContext;
import com.aresstack.askai.research.mcp.ResearchControlEndpoint;
import com.aresstack.askai.research.sources.ResearchSourceRepository;
import com.aresstack.askai.research.state.ResearchCommandType;
import com.aresstack.askai.research.state.oo.OoResearchStateMachine;
import com.aresstack.askai.research.state.oo.ResearchStateIds;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The command bridge in isolation: structured actions from the UI port reach the PRODUCTIVE state machine
 * (with tool republication), text stays on submitPrompt, invalid/late commands are rejected with their
 * structured status, observers get the state update (and a broken observer breaks nothing), and the ACP
 * backend itself contains no phase logic (canExecute is always false).
 */
public class ProductiveCommandBridgeTest {

    /** Recording backend standing in for the ACP adapter (prompts + cancels only, like the real one). */
    private static final class RecordingBackend implements ResearchSessionBackend {
        final List<String> prompts = new ArrayList<String>();
        volatile int cancels;

        public ResearchSessionHandle createSession(ResearchProjectRequest request,
                                                   ResearchSessionListener listener) {
            final String sessionId = request.getSessionId();
            final String projectId = request.getProjectId();
            return new ResearchSessionHandle() {
                public String getSessionId() {
                    return sessionId;
                }

                public String getProjectId() {
                    return projectId;
                }
            };
        }

        public boolean canExecute(ResearchSessionHandle handle, ResearchCommandType command) {
            return false; // like InProcessResearchSessionBackend: NO phase logic in the transport adapter
        }

        public void executeCommand(ResearchSessionHandle handle, ResearchCommandType command) {
            throw new AssertionError("the productive bridge must never route through the backend");
        }

        public void submitPrompt(ResearchSessionHandle handle, ResearchPrompt prompt) {
            prompts.add(prompt.getText());
        }

        public void approve(ResearchSessionHandle handle, String approvalId) {
        }

        public void reject(ResearchSessionHandle handle, String approvalId, String reason) {
        }

        public void pause(ResearchSessionHandle handle) {
        }

        public void resume(ResearchSessionHandle handle) {
        }

        public void cancel(ResearchSessionHandle handle) {
            cancels++;
        }

        public void close(ResearchSessionHandle handle) {
        }
    }

    private static final class Fx {
        final InProcessMcpServerRegistry registry = new InProcessMcpServerRegistry();
        final RecordingBackend backend = new RecordingBackend();
        final ProductiveResearchSessionResources resources;
        final ResearchAgentSession session;

        Fx() {
            final ProductiveResearchSessionResources[] holder = new ProductiveResearchSessionResources[1];
            ResearchControlEndpoint control = new ResearchControlEndpoint(registry, "s1", 7L,
                    new ResearchControlContext() {
                        public String currentPhaseId() {
                            return holder[0] == null ? ResearchStateIds.SCOPING
                                    : holder[0].currentState().getPhaseId();
                        }

                        public String currentStateId() {
                            return holder[0] == null ? ResearchStateIds.NEW
                                    : holder[0].currentState().getStateId();
                        }

                        public String statusLine() {
                            return currentPhaseId() + "/" + currentStateId();
                        }

                        public com.aresstack.askai.plugin.api.agent.artifact.AgentArtifactStore artifactStore() {
                            return new ResearchArtifactStore();
                        }

                        public ResearchSourceRepository sourceRepository() {
                            return new com.aresstack.askai.research.sources.InMemoryResearchSourceRepository();
                        }

                        public String acceptCapture(String captureId) {
                            return null;
                        }
                    });
            control.open();
            resources = new ProductiveResearchSessionResources("s1", new OoResearchStateMachine("s1"),
                    null, null, null, tempProjectContext(), control, null, null, null);
            holder[0] = resources;
            session = new ResearchAgentSession(backend, null, new PlainHost(), "s1", "p1", resources);
            session.activate();
        }
    }

    @Test
    public void aUserTurnIsForwardedToTheAgentAndScopeCommitsOnlyOnTheHostValidatedProposal() {
        Fx fx = new Fx();
        // The model-backed TeamAgent now LEADS the conversation: a user turn is FORWARDED to the agent (not
        // swallowed by a host-side scoping dialogue), and a plain turn commits nothing on its own.
        int afterActivate = fx.backend.prompts.size();
        assertEquals("a new chat stays empty: no bootstrap turn, no model call", 0, afterActivate);
        fx.session.submitPrompt("just a question");
        assertEquals("the first message starts the scoping before the turn reaches the agent",
                ResearchStateIds.RUNNING, fx.resources.currentState().getStateId());
        assertEquals("the user turn reaches the agent", afterActivate + 1, fx.backend.prompts.size());
        assertEquals("just a question", fx.backend.prompts.get(fx.backend.prompts.size() - 1));
        assertEquals("a plain turn commits no scope", ResearchStateIds.SCOPING,
                fx.resources.currentState().getPhaseId());

        // Only the HOST executing a VALIDATED agent proposal commits the scope. C5: no outline gate —
        // the commit auto-advances straight into RESEARCH/running and submits the stored question.
        fx.session.onEvent(ResearchBackendEvent.builder(
                com.aresstack.askai.research.backend.ResearchBackendEventType.SCOPE_PROPOSAL)
                .envelope("evt-1", "s1", "p1", 1L, 0L, 1L, null)
                .title("SUBMIT_SCOPE").text("just a question").messages("", "focus on isolation").build());
        assertEquals("the outline slot stays the LIVE projection (scoping writes none)",
                "", fx.resources.getArtifactStore().read("outline").getMarkdown());
        assertEquals(ResearchStateIds.RESEARCH, fx.resources.currentState().getPhaseId());
        assertEquals(ResearchStateIds.RUNNING, fx.resources.currentState().getStateId());
        assertEquals("RESEARCH", fx.session.getState().getPhaseLabel());
        assertEquals("the stored question was auto-continued", "just a question",
                fx.backend.prompts.get(fx.backend.prompts.size() - 1));
    }

    @Test
    public void aValidatedScopeProposalFromTheAgentCommitsScopeAndStartsResearch() {
        Fx fx = new Fx();
        int afterActivate = fx.backend.prompts.size(); // no bootstrap turn: 0
        // The runtime TeamAgent proposes a validated scope (as it arrives over the ACP "scope" wire line and
        // is mapped to a SCOPE_PROPOSAL event): the host RE-VALIDATES against its own state machine, commits
        // the model-confirmed scope and advances to the outline approval gate. The model never confirmed it
        // itself — only the host executing the proposal does.
        ResearchBackendEvent proposal = ResearchBackendEvent.builder(
                com.aresstack.askai.research.backend.ResearchBackendEventType.SCOPE_PROPOSAL)
                .envelope("evt-scope-1", "s1", "p1", 1L, 0L, 1L, null)
                .title("SUBMIT_SCOPE")
                .text("How does pf4j isolate plugins?")
                .messages("", "class isolation\nversioning")
                .build();
        fx.session.onEvent(proposal);

        // C5: the committed scope auto-advances into RESEARCH/running and submits the stored question —
        // there is no outline approval gate anymore (the live outline is a mobile projection).
        assertEquals(ResearchStateIds.RESEARCH, fx.resources.currentState().getPhaseId());
        assertEquals(ResearchStateIds.RUNNING, fx.resources.currentState().getStateId());
        assertEquals("the outline slot stays the LIVE projection (scoping writes none)",
                "", fx.resources.getArtifactStore().read("outline").getMarkdown());
        assertEquals("the stored question started the research turn",
                afterActivate + 1, fx.backend.prompts.size());

        // The host stays the authority: approving advances to RESEARCH/running and auto-continues with the
        // stored question exactly once.
        fx.session.approveCurrent();
        assertEquals(ResearchStateIds.RESEARCH, fx.resources.currentState().getPhaseId());
        assertEquals(ResearchStateIds.RUNNING, fx.resources.currentState().getStateId());
        assertEquals(afterActivate + 1, fx.backend.prompts.size());
        assertEquals("How does pf4j isolate plugins?",
                fx.backend.prompts.get(fx.backend.prompts.size() - 1));
    }

    @Test
    public void aGreetingDoneEventAdvancesTheScopeStateOneStepAndIsIdempotent() {
        Fx fx = new Fx();
        assertEquals(ResearchStateIds.SCOPING, fx.resources.currentState().getPhaseId());
        assertEquals(ResearchStateIds.NEW, fx.resources.currentState().getStateId());

        // The runtime signals a successful greeting: the state advances one step (still SCOPING, now RUNNING)
        // so the greeting depends only on the state and is never repeated on a restart.
        fx.session.onEvent(ResearchBackendEvent.builder(
                com.aresstack.askai.research.backend.ResearchBackendEventType.GREETING_DONE)
                .envelope("evt-greet", "s1", "p1", 1L, 0L, 1L, null).build());
        assertEquals(ResearchStateIds.SCOPING, fx.resources.currentState().getPhaseId());
        assertEquals(ResearchStateIds.RUNNING, fx.resources.currentState().getStateId());

        // A second GREETING_DONE is a no-op — the state is no longer fresh.
        fx.session.onEvent(ResearchBackendEvent.builder(
                com.aresstack.askai.research.backend.ResearchBackendEventType.GREETING_DONE)
                .envelope("evt-greet2", "s1", "p1", 2L, 0L, 2L, null).build());
        assertEquals(ResearchStateIds.RUNNING, fx.resources.currentState().getStateId());
    }

    @Test
    public void aScopeProposalOutsideScopingIsDroppedNotExecuted() {
        Fx fx = new Fx();
        assertTrue(fx.session.dispatch(ResearchCommandType.START, null).isAccepted());
        assertTrue(fx.session.dispatch(ResearchCommandType.SUBMIT_SCOPE, null).isAccepted());
        // Now past SCOPING (in OUTLINE): a late scope proposal must be dropped, never re-committed.
        assertFalse(ResearchStateIds.SCOPING.equals(fx.resources.currentState().getPhaseId()));
        String phaseBefore = fx.resources.currentState().getPhaseId();
        String stateBefore = fx.resources.currentState().getStateId();
        fx.session.onEvent(ResearchBackendEvent.builder(
                com.aresstack.askai.research.backend.ResearchBackendEventType.SCOPE_PROPOSAL)
                .envelope("evt-scope-late", "s1", "p1", 5L, 0L, 5L, null)
                .title("SUBMIT_SCOPE").text("too late").messages("", "").build());
        assertEquals("a late proposal changes nothing", phaseBefore,
                fx.resources.currentState().getPhaseId());
        assertEquals(stateBefore, fx.resources.currentState().getStateId());
    }

    @Test
    public void invalidAndLateCommandsAreRejectedStructurally() {
        Fx fx = new Fx();
        ResearchCommandDispatchResult wrongPhase =
                fx.session.dispatch(ResearchCommandType.APPROVE_DRAFT, null);
        assertEquals(ResearchCommandDispatchResult.Status.INVALID_PHASE, wrongPhase.getStatus());

        assertEquals(ResearchCommandDispatchResult.Status.COMMAND_NOT_AVAILABLE,
                fx.session.dispatch(null, null).getStatus());

        // Closed resources (generation retired / session closed) → SESSION_CLOSED, nothing is reached.
        fx.resources.close();
        assertEquals(ResearchCommandDispatchResult.Status.SESSION_CLOSED,
                fx.session.dispatch(ResearchCommandType.START, null).getStatus());
    }

    @Test
    public void pauseCancelAlsoStopTheAgentTurnAndObserversAreIsolated() {
        Fx fx = new Fx();
        assertTrue(fx.session.dispatch(ResearchCommandType.START, null).isAccepted());
        final AtomicInteger updates = new AtomicInteger();
        fx.session.addStateListener(new Runnable() {
            public void run() {
                updates.incrementAndGet();
                throw new IllegalStateException("broken observer");
            }
        });
        final AtomicInteger healthy = new AtomicInteger();
        fx.session.addStateListener(new Runnable() {
            public void run() {
                healthy.incrementAndGet();
            }
        });
        assertTrue(fx.session.dispatch(ResearchCommandType.PAUSE, null).isAccepted());
        // The turn cancel runs OFF the calling thread (EDT protection) — await it briefly.
        long deadline = System.currentTimeMillis() + 2000;
        while (fx.backend.cancels < 1 && System.currentTimeMillis() < deadline) {
            Thread.yield();
        }
        assertEquals("PAUSE also cancels the running agent turn", 1, fx.backend.cancels);
        assertTrue("observers received the state update", updates.get() >= 1);
        assertTrue("a broken observer must not block later observers", healthy.get() >= 1);
        assertTrue(fx.session.dispatch(ResearchCommandType.RESUME, null).isAccepted());
    }

    @Test
    public void backendAdapterHasNoPhaseLogic() {
        RecordingBackend backend = new RecordingBackend();
        assertFalse(backend.canExecute(null, ResearchCommandType.START));
        // And the real ACP adapter behaves identically by contract:
        com.aresstack.askai.research.agentwire.InProcessResearchSessionBackend acp =
                new com.aresstack.askai.research.agentwire.InProcessResearchSessionBackend(
                        null, null, null, null);
        assertFalse(acp.canExecute(null, ResearchCommandType.START));
    }

    // ------------------------------------------------------------------ minimal host

    private static final class PlainHost implements AgentHostContext {
        public UiExecutor getUiExecutor() {
            return new UiExecutor() {
                public void execute(Runnable runnable) {
                    runnable.run();
                }

                public void assertUiThread() {
                }

                public boolean isUiThread() {
                    return true;
                }
            };
        }

        public ThemeService getThemeService() {
            return null;
        }

        public MarkdownViewFactory getMarkdownViewFactory() {
            return null;
        }

        public NotificationService getNotificationService() {
            return null;
        }

        public WorkspaceStateStore getStateStore() {
            return null;
        }

        public PluginPathService getPluginPathService() {
            return null;
        }

        public AgentConversationSink getConversationSink() {
            return null;
        }
    }

    /** A file-backed project context in a fresh temp directory (the productive contract). */
    private static com.aresstack.askai.research.store.ResearchProjectContext tempProjectContext() {
        try {
            java.io.File dir = java.nio.file.Files.createTempDirectory("askai-research-test")
                    .toFile();
            return com.aresstack.askai.research.store.ResearchProjectContext.open("s1", dir);
        } catch (java.io.IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

}
