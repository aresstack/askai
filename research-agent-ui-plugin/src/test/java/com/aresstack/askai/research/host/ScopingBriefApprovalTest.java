package com.aresstack.askai.research.host;

import com.aresstack.askai.mcp.api.InProcessMcpServerRegistry;
import com.aresstack.askai.plugin.api.agent.AgentSession;
import com.aresstack.askai.plugin.api.agent.AgentConversationSink;
import com.aresstack.askai.plugin.api.agent.AgentHostContext;
import com.aresstack.askai.plugin.api.agent.composer.ComposerAccessory;
import com.aresstack.askai.plugin.api.agent.composer.ComposerAccessoryContext;
import com.aresstack.askai.plugin.api.service.MarkdownViewFactory;
import com.aresstack.askai.plugin.api.service.NotificationService;
import com.aresstack.askai.plugin.api.service.PluginPathService;
import com.aresstack.askai.plugin.api.service.ThemeService;
import com.aresstack.askai.plugin.api.service.UiExecutor;
import com.aresstack.askai.plugin.api.service.WorkspaceStateStore;
import com.aresstack.askai.research.agent.ResearchAgentSession;
import com.aresstack.askai.research.agent.ResearchArtifactStore;
import com.aresstack.askai.research.agent.ScopingApprovalOutcome;
import com.aresstack.askai.research.agent.ScopingComposerAccessoryContribution;
import com.aresstack.askai.research.agent.ScopingSupportView;
import com.aresstack.askai.research.backend.ResearchBackendEvent;
import com.aresstack.askai.research.backend.ResearchBackendEventType;
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
import com.aresstack.askai.research.store.FileResearchBriefStore;

import org.junit.Test;

import javax.swing.JButton;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The explicit, user-owned SCOPING → OUTLINE transition (RA-P6). Two layers are pinned:
 * <ol>
 *   <li>the session gate ({@link ResearchAgentSession#approveScopingBriefAndContinue()}) returns a TYPED
 *       {@link ScopingApprovalOutcome} — approve-before-transition, no duplicate revision, no chaining, and a
 *       concrete reason on every rejection; and</li>
 *   <li>the REAL Swing path — the accessory built by its host contribution, the genuine approve button
 *       {@code doClick()} → live session → state machine — advances the phase on success and surfaces a
 *       VISIBLE reason on rejection (never a silent no-op).</li>
 * </ol>
 */
public class ScopingBriefApprovalTest {

    // ------------------------------------------------------------------ session gate (typed outcome)

    @Test
    public void explicitApprovalApprovesTheBriefThenAdvancesExactlyOnceToOutline() {
        Fx fx = new Fx();
        toScopingRunningWithBrief(fx, "# Brief\nHow does pf4j isolate plugins?");

        assertTrue(fx.session.canApproveScopingBriefAndContinue());
        assertEquals(ScopingApprovalOutcome.SUCCESS, fx.session.approveScopingBriefAndContinue());

        // The brief was approved into exactly one immutable revision.
        assertEquals(1, fx.session.researchBriefStore().load().getApprovedRevisions().size());
        // C5: the ONE user decision "finish scoping" lands directly in RESEARCH/running (the technical
        // WAITING step belongs to the same decision; there is no outline approval gate anymore).
        assertEquals(ResearchStateIds.RESEARCH, fx.resources.currentState().getPhaseId());
        assertEquals(ResearchStateIds.RUNNING, fx.resources.currentState().getStateId());
        assertEquals("RESEARCH", fx.session.getState().getPhaseLabel());
    }

    @Test
    public void aBriefApprovalFailureLeavesThePhaseUntouched() {
        Fx fx = new Fx();
        toScopingRunningWithBrief(fx, "# Brief\nsomething worth researching");

        // Sabotage the store so approval throws: put a FILE where the revisions directory must be created.
        File briefDir = new File(fx.resources.getProjectContext().getProjectDirectory(), "brief");
        File revisions = new File(briefDir, "revisions");
        try {
            assertTrue("test setup: block the revisions directory", revisions.createNewFile());
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }

        assertEquals(ScopingApprovalOutcome.APPROVAL_FAILED, fx.session.approveScopingBriefAndContinue());
        // Approve-before-transition: the failed artifact approval dispatched no SUBMIT_SCOPE.
        assertEquals(ResearchStateIds.SCOPING, fx.resources.currentState().getPhaseId());
        assertEquals(ResearchStateIds.RUNNING, fx.resources.currentState().getStateId());
        // The failure was surfaced, not swallowed.
        assertTrue(hasApprovalProblem(fx));
    }

    @Test
    public void anAlreadyApprovedIdenticalBriefCreatesNoDuplicateRevisionButStillAdvances() {
        Fx fx = new Fx();
        fx.session.dispatch(ResearchCommandType.START, null);
        completeTurn(fx, 1L);
        FileResearchBriefStore store = fx.session.researchBriefStore();
        store.updateWorkingCopy("# Brief\nstable question", 1000L);
        store.approveCurrent(1000L); // pre-approve: revision 0001, working copy consumed
        assertEquals(1, store.load().getApprovedRevisions().size());

        // The effective brief is now the approved revision (no working copy). The explicit action approves
        // again (ALREADY_CURRENT → no new revision) and STILL performs the single transition.
        assertTrue(fx.session.canApproveScopingBriefAndContinue());
        assertEquals(ScopingApprovalOutcome.SUCCESS, fx.session.approveScopingBriefAndContinue());

        assertEquals("no duplicate revision", 1, store.load().getApprovedRevisions().size());
        assertEquals(ResearchStateIds.RESEARCH, fx.resources.currentState().getPhaseId());
    }

    /**
     * The user owns the state machine. An empty or vague scope is NOT a reason to block submit-scope: when
     * the machine allows the transition, the command must be available and must work — on the very first
     * turn and with nothing written yet. This test previously pinned the opposite ("Es liegt noch keine
     * Fragestellung vor"), which both gated a user command on an assistant's judgement and still treated
     * the scope as one research question.
     */
    @Test
    public void anEmptyOrBlankScopeNeverBlocksTheUsersSubmitScope() {
        Fx fx = new Fx();
        fx.session.dispatch(ResearchCommandType.START, null);
        completeTurn(fx, 1L);

        assertTrue("submit-scope must not be gated by the content of the scope",
                fx.session.canApproveScopingBriefAndContinue());
        assertEquals(ScopingApprovalOutcome.SUCCESS, fx.session.approveScopingBriefAndContinue());
        assertEquals("the user's command moved the phase", ResearchStateIds.RESEARCH,
                fx.resources.currentState().getPhaseId());
    }

    @Test
    public void aWhitespaceOnlyBriefAlsoDoesNotBlockTheUsersCommand() {
        Fx fx = new Fx();
        fx.session.dispatch(ResearchCommandType.START, null);
        completeTurn(fx, 1L);
        fx.session.researchBriefStore().updateWorkingCopy("   \n  \t", 1000L);

        assertTrue(fx.session.canApproveScopingBriefAndContinue());
        assertEquals(ScopingApprovalOutcome.SUCCESS, fx.session.approveScopingBriefAndContinue());
        assertEquals(ResearchStateIds.RESEARCH, fx.resources.currentState().getPhaseId());
    }

    @Test
    public void outsideScopingTheActionReportsWrongPhase() {
        Fx fx = new Fx();
        fx.session.dispatch(ResearchCommandType.START, null);        // SCOPING/RUNNING
        completeTurn(fx, 1L);
        fx.session.researchBriefStore().updateWorkingCopy("# Brief\nx", 1000L);
        fx.session.dispatch(ResearchCommandType.SUBMIT_SCOPE, null); // → RESEARCH (C5)
        assertEquals(ResearchStateIds.RESEARCH, fx.resources.currentState().getPhaseId());

        assertFalse(fx.session.canApproveScopingBriefAndContinue());
        assertEquals(ScopingApprovalOutcome.WRONG_PHASE, fx.session.approveScopingBriefAndContinue());
        assertEquals(ResearchStateIds.RESEARCH, fx.resources.currentState().getPhaseId());
    }

    @Test
    public void aForegroundAgentTurnInFlightReportsBusy() {
        Fx fx = new Fx();
        fx.session.dispatch(ResearchCommandType.START, null); // SCOPING/RUNNING
        fx.session.submitPrompt("Hasenhaltung");
        // Do NOT complete the turn: the user turn's agentTurnInFlight is still set.
        fx.session.researchBriefStore().updateWorkingCopy("# Brief\nx", 1000L);
        assertFalse("a foreground agent turn blocks the action",
                fx.session.canApproveScopingBriefAndContinue());
        assertEquals(ScopingApprovalOutcome.BUSY, fx.session.approveScopingBriefAndContinue());
        assertEquals(ResearchStateIds.SCOPING, fx.resources.currentState().getPhaseId());

        // Once the turn completes, the same brief makes the action legal.
        completeTurn(fx, 1L);
        assertTrue(fx.session.canApproveScopingBriefAndContinue());
    }

    @Test
    public void onlyTheExplicitActionTransitions_modelOutputHasZeroEffect() {
        Fx fx = new Fx();
        fx.session.dispatch(ResearchCommandType.START, null); // SCOPING/RUNNING
        completeTurn(fx, 1L);
        fx.session.researchBriefStore().updateWorkingCopy("# Brief\nx", 1000L);

        // Model output / advice — even a literal "weiter" — reaches the chat but must NOT move the phase.
        fx.session.onEvent(ResearchBackendEvent.builder(ResearchBackendEventType.ASSISTANT_MESSAGE)
                .envelope("evt-advice", "s1", "p1", 2L, 0L, 2L, null).text("weiter").build());
        assertEquals(ResearchStateIds.SCOPING, fx.resources.currentState().getPhaseId());
        assertEquals(ResearchStateIds.RUNNING, fx.resources.currentState().getStateId());

        // The visualizer never ran here (no inference port) — the transition is independent of it: only the
        // explicit click advances the phase, exactly once.
        assertEquals(ScopingApprovalOutcome.SUCCESS, fx.session.approveScopingBriefAndContinue());
        assertEquals(ResearchStateIds.RESEARCH, fx.resources.currentState().getPhaseId());
    }

    @Test
    public void theResultingPhaseComesFromTheStateMachineNotTheUi() {
        Fx fx = new Fx();
        toScopingRunningWithBrief(fx, "# Brief\nx");

        assertEquals(ScopingApprovalOutcome.SUCCESS, fx.session.approveScopingBriefAndContinue());

        // The authoritative, persisted memento IS RESEARCH; the session view-model only mirrors that truth.
        assertEquals(ResearchStateIds.RESEARCH, fx.resources.currentState().getPhaseId());
        assertEquals("RESEARCH", fx.session.getState().getPhaseLabel());
    }

    // ------------------------------------------------------------------ real Swing path (the missing test)

    @Test
    public void theRealAccessoryButtonClickReachesTheLiveSessionAndTransitions() {
        Fx fx = new Fx();
        toScopingRunningWithBrief(fx, "# Brief\nHow does pf4j isolate plugins?");

        ScopingSupportView view = buildAccessoryView(fx); // the genuine host-built accessory + wiring
        JButton approve = view.getApproveButton();
        assertTrue("the accessory enables the action when the brief is ready", approve.isEnabled());
        assertTrue("the scoping accessory is visible during scoping", view.isVisible());

        approve.doClick(); // REAL click → accessory callback → live ResearchAgentSession → state machine

        assertEquals(ResearchStateIds.RESEARCH, fx.resources.currentState().getPhaseId());
        assertEquals(ResearchStateIds.RUNNING, fx.resources.currentState().getStateId());
        // The free-search field lives in the workspace top bar now, so the tag surface is back to
        // demand-driven visibility: no tags after scoping → no surface.
        assertFalse("the scoping-only accessory disappears once the phase leaves scoping", view.isVisible());
    }

    @Test
    public void aStaleEnabledButtonClickIsRejectedVisiblyNotAsASilentNoOp() {
        Fx fx = new Fx();
        toScopingRunningWithBrief(fx, "# Brief\nx");

        ScopingSupportView view = buildAccessoryView(fx);
        JButton approve = view.getApproveButton();
        assertTrue(approve.isEnabled());

        // A foreground turn starts WITHOUT the accessory refreshing — the button's enabled state is now a stale
        // snapshot. This is the real "clickable but nothing happens": the click must be REJECTED, VISIBLY.
        fx.session.submitPrompt("noch eine Frage"); // productive: sets agentTurnInFlight = true, no state event
        assertTrue("the stale snapshot still shows the button enabled", approve.isEnabled());
        int problemsBefore = fx.sink.problems.size();

        approve.doClick();

        assertEquals("no transition on a rejected click", ResearchStateIds.SCOPING,
                fx.resources.currentState().getPhaseId());
        assertTrue("the rejection is surfaced to the user, never a silent no-op",
                fx.sink.problems.size() > problemsBefore);
    }

    // ------------------------------------------------------------------ helpers

    /** Build the accessory exactly as the host does (contribution → accessory → live session wiring). */
    private static ScopingSupportView buildAccessoryView(Fx fx) {
        ComposerAccessory accessory = new ScopingComposerAccessoryContribution()
                .create(new FakeComposerContext(fx.session));
        return (ScopingSupportView) accessory.getComponent();
    }

    private static boolean hasApprovalProblem(Fx fx) {
        for (String problem : fx.sink.problems) {
            if (problem.startsWith("Fragestellung konnte nicht freigegeben werden")) {
                return true;
            }
        }
        return false;
    }

    /** SCOPING/NEW → SCOPING/RUNNING, deliver a terminal event, then store a real brief. */
    private static void toScopingRunningWithBrief(Fx fx, String brief) {
        fx.session.dispatch(ResearchCommandType.START, null);
        completeTurn(fx, 1L);
        fx.session.researchBriefStore().updateWorkingCopy(brief, 1000L);
    }

    /** Deliver a terminal COMPLETED event so a pending {@code agentTurnInFlight} is cleared. */
    private static void completeTurn(Fx fx, long seq) {
        fx.session.onEvent(ResearchBackendEvent.builder(ResearchBackendEventType.COMPLETED)
                .envelope("evt-complete-" + seq, "s1", "p1", seq, 0L, seq, null).build());
    }

    // ------------------------------------------------------------------ fixture (productive resources)

    private static final class Fx {
        final InProcessMcpServerRegistry registry = new InProcessMcpServerRegistry();
        final RecordingBackend backend = new RecordingBackend();
        final RecordingSink sink = new RecordingSink();
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
            session = new ResearchAgentSession(backend, null, new PlainHost(sink), "s1", "p1", resources);
            session.activate();
        }
    }

    /** Recording backend standing in for the ACP adapter (prompts + cancels only, like the real one). */
    private static final class RecordingBackend implements ResearchSessionBackend {
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
            return false;
        }

        public void executeCommand(ResearchSessionHandle handle, ResearchCommandType command) {
            throw new AssertionError("the productive bridge must never route through the backend");
        }

        public void submitPrompt(ResearchSessionHandle handle, ResearchPrompt prompt) {
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
        }

        public void close(ResearchSessionHandle handle) {
        }
    }

    /** A file-backed project context in a fresh temp directory (the productive contract). */
    private static com.aresstack.askai.research.store.ResearchProjectContext tempProjectContext() {
        try {
            File dir = java.nio.file.Files.createTempDirectory("askai-research-test").toFile();
            return com.aresstack.askai.research.store.ResearchProjectContext.open("s1", dir);
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    // ------------------------------------------------------------------ composer accessory context (real wiring)

    private static final class FakeComposerContext implements ComposerAccessoryContext {
        private final AgentSession session;

        FakeComposerContext(AgentSession session) {
            this.session = session;
        }

        public AgentSession getSession() {
            return session;
        }

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
    }

    // ------------------------------------------------------------------ minimal host + problem-recording sink

    private static final class PlainHost implements AgentHostContext {
        private final AgentConversationSink sink;

        PlainHost(AgentConversationSink sink) {
            this.sink = sink;
        }

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
            return sink;
        }
    }

    /** Records the visible problems, so a rejected click can be asserted to be non-silent. */
    private static final class RecordingSink implements AgentConversationSink {
        final List<String> problems = new ArrayList<String>();

        public void appendUserMessage(String messageId, String markdown) {
        }

        public void appendAssistantMessage(String messageId, String markdown) {
        }

        public void startThinking(String activityId, String title) {
        }

        public void updateThinking(String activityId, String text) {
        }

        public void finishThinking(String activityId, String summary) {
        }

        public void startToolActivity(String activityId, String title, String explanation) {
        }

        public void updateToolActivity(String activityId, String title, String explanation) {
        }

        public void completeToolActivity(String activityId, String summary) {
        }

        public void failToolActivity(String activityId, String summary) {
        }

        public void requestApproval(String approvalId, String prompt) {
        }

        public void showProblem(String problemId, String publicMessage) {
            problems.add(publicMessage);
        }
    }
}
