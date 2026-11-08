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
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * PR 05 walk-through: drives {@link ResearchAgentSession#executeCommand} over the productive resources with
 * a recording runtime from the first chat message to {@code draft/completed}, using only the user exits of
 * every phase. Also pins the D1/Q13 rules around it: no surface dispatches PAUSE or CANCEL, Stop never
 * changes the state, the bot gateway runs no state command, and the pill never dispatches for the current
 * phase.
 */
public class ProductiveBookJourneyTest {

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

    static final class Fx {
        final InProcessMcpServerRegistry registry = new InProcessMcpServerRegistry();
        final RecordingBackend backend = new RecordingBackend();
        final ProductiveResearchSessionResources resources;
        final ResearchAgentSession session;
        final java.io.File dir;
        final PlainHost host;

        Fx() {
            this(newTempDir());
        }

        Fx(java.io.File dir) {
            this.dir = dir;
            this.host = new PlainHost();
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
                    null, null, null,
                    com.aresstack.askai.research.store.ResearchProjectContext.open("s1", dir), control,
                    null, null, null);
            holder[0] = resources;
            session = new ResearchAgentSession(backend, null, host, "s1", "p1", resources);
            session.activate();
        }
    }

    /** The user's first chat message, answered by the (recording) runtime. */
    static void firstMessage(Fx fx, String text) {
        fx.session.submitPrompt(text);
        fx.session.onEvent(ResearchBackendEvent.builder(
                com.aresstack.askai.research.backend.ResearchBackendEventType.COMPLETED)
                .envelope("evt-complete-" + (++sequence), "s1", "p1", sequence, 0L, sequence, null)
                .build());
    }

    private static long sequence;

    static void settle(Fx fx) {
        try {
            assertTrue(fx.session.awaitTransitionEffects(5000));
        } catch (InterruptedException ex) {
            throw new IllegalStateException(ex);
        }
    }

    static String phase(Fx fx) {
        settle(fx);
        return fx.resources.currentState().getPhaseId() + "/" + fx.resources.currentState().getStateId();
    }

    private static List<String> tagCommands(Fx fx) {
        settle(fx);
        List<String> commands = new ArrayList<String>();
        for (com.aresstack.askai.research.agent.ResearchActionTag tag : fx.session.availableActionTags()) {
            commands.add(tag.getCommand());
        }
        return commands;
    }

    static void assertHandled(String outcome) {
        assertTrue(outcome, outcome.startsWith("handled"));
    }

    private static final String[] FINISH = {"finish-sources", "finish-outline", "finish-document"};

    /** Every finish tag is offered exactly where its command is allowed; elsewhere it is refused. */
    private static void assertOnlyFinishTagFor(Fx fx, String allowed) {
        List<String> tags = tagCommands(fx);
        for (String finish : FINISH) {
            if (finish.equals(allowed)) {
                assertTrue(finish + " offered in " + phase(fx), tags.contains(finish));
            } else {
                assertFalse(finish + " not offered in " + phase(fx), tags.contains(finish));
                String before = phase(fx);
                String outcome = fx.session.executeCommand(finish, "");
                assertTrue(finish + " refused in " + before + ": " + outcome,
                        outcome.startsWith("rejected") && outcome.contains("Valid now"));
                assertEquals("a refused command changes nothing", before, phase(fx));
            }
        }
    }

    private void startOutcome(Fx fx, String stopReason) {
        fx.session.onEvent(ResearchBackendEvent.builder(
                com.aresstack.askai.research.backend.ResearchBackendEventType.RUN_OUTCOME)
                .envelope("evt-outcome-" + (++sequence), "s1", "p1", sequence, 0L, sequence, null)
                .activity("research-run-p1", null, "", "")
                .runOutcome(new com.aresstack.askai.research.backend.ResearchRunOutcomeInfo("p1",
                        stopReason, 10, 7, 1, 3, 2, true, "INSUFFICIENT_HOST_DIVERSITY",
                        "CONTINUE_RESEARCH"))
                .build());
    }

    @Test
    public void theUserReachesTheCompletedBookThroughTheExitsOfEveryPhase() {
        Fx fx = new Fx();
        firstMessage(fx, "A book about plugin isolation in Java");
        assertEquals("scoping/running", phase(fx));
        assertTrue("one concept card", fx.session.conceptBranchService()
                .addNode(Collections.<String>emptyList(), "Class loaders").isApplied());
        assertOnlyFinishTagFor(fx, null);

        ConceptTestSupport.makeSubmittable(fx.session);

        assertHandled(fx.session.executeCommand("submit-scope", ""));
        assertEquals("submit-scope also starts the search", "research/running", phase(fx));
        assertOnlyFinishTagFor(fx, "finish-sources");
        assertNull("the pill offers nothing for the current phase",
                fx.session.currentResearchSnapshot().advanceCommandFor(ResearchStateIds.RESEARCH));

        assertHandled(fx.session.executeCommand("finish-sources", ""));
        assertEquals("research/waiting_approval", phase(fx));
        assertTrue(tagCommands(fx).contains("approve"));
        assertTrue(tagCommands(fx).contains("request-changes"));

        // Request changes with a comment returns to research/running at the Sources gate.
        assertHandled(fx.session.executeCommand("request-changes", "add two primary sources"));
        assertEquals("research/running", phase(fx));
        assertHandled(fx.session.executeCommand("finish-sources", ""));
        assertEquals("research/waiting_approval", phase(fx));

        assertHandled(fx.session.executeCommand("approve", ""));
        assertEquals("outline/running", phase(fx));
        assertOnlyFinishTagFor(fx, "finish-outline");

        String empty = fx.session.executeCommand("finish-outline", "");
        assertTrue("PR 21 (Q24): an outline without a section is refused: " + empty,
                empty.contains(com.aresstack.askai.research.book.OutlineFreezeHook.NO_SECTION));
        assertEquals("outline/running", phase(fx));
        OutlineTestSupport.makeFinishable(fx.session);
        assertHandled(fx.session.executeCommand("finish-outline", ""));
        assertEquals("outline/waiting_approval", phase(fx));
        assertHandled(fx.session.executeCommand("approve", ""));
        assertEquals("approving the outline also starts the writing", "draft/running", phase(fx));
        assertOnlyFinishTagFor(fx, "finish-document");

        assertHandled(fx.session.executeCommand("finish-document", ""));
        assertEquals("draft/waiting_approval", phase(fx));
        assertHandled(fx.session.executeCommand("approve", ""));
        assertEquals("draft/completed", phase(fx));
    }

    @Test
    public void noUserSurfaceDispatchesPauseOrCancelAndStopKeepsThePhase() {
        Fx fx = new Fx();
        firstMessage(fx, "A book about plugin isolation in Java");
        ConceptTestSupport.makeSubmittable(fx.session);
        assertHandled(fx.session.executeCommand("submit-scope", ""));
        assertEquals("research/running", phase(fx));
        for (String name : new String[] {"pause", "cancel"}) {
            assertTrue(fx.session.executeCommand(name, "").startsWith("rejected"));
            assertTrue(fx.session.executeBotCommand(name, "").startsWith("rejected"));
            assertFalse(tagCommands(fx).contains(name));
            for (com.aresstack.askai.plugin.api.agent.command.ChatCommandContribution command
                    : com.aresstack.askai.research.agent.ResearchChatCommands.all()) {
                assertFalse("no slash command " + name,
                        name.equals(command.getDescriptor().getName()));
            }
        }
        assertEquals("research/running", phase(fx));

        // Stop during a turn leaves the phase running; the turn is cancelled on the transport.
        fx.session.getChatTarget().stop();
        long deadline = System.currentTimeMillis() + 2000;
        while (fx.backend.cancels < 1 && System.currentTimeMillis() < deadline) {
            Thread.yield();
        }
        assertEquals("Stop cancels the agent turn", 1, fx.backend.cancels);
        assertEquals("Stop never changes the state", "research/running", phase(fx));

        // No review/limitation offers remain; "end" still finishes the sources.
        startOutcome(fx, "USER_CANCELLED");
        List<String> tags = tagCommands(fx);
        assertFalse(tags.contains("review"));
        assertFalse(tags.contains("accept-limitation"));
        assertFalse(tags.contains("limit"));
        assertTrue(tags.contains("end"));
        assertHandled(fx.session.executeCommand("end", ""));
        assertEquals("research/waiting_approval", phase(fx));
    }

    @Test
    public void aConnectedBotRunsOffersButNeverChangesThePhase() {
        Fx fx = new Fx();
        assertTrue("a plain bot chat message in scoping/new would START",
                fx.session.executeBotCommand("", "hello").startsWith("rejected"));
        assertEquals("scoping/new", phase(fx));
        firstMessage(fx, "A book about plugin isolation in Java");
        for (String name : new String[] {"submit-scope", "approve", "request-changes", "continue",
                "retry", "resume", "reopen-concept", "finish-sources", "finish-outline",
                "finish-document"}) {
            String outcome = fx.session.executeBotCommand(name, "");
            assertTrue(name + ": " + outcome, outcome.contains("Phase changes need the user"));
        }
        assertEquals("scoping/running", phase(fx));
        ConceptTestSupport.makeSubmittable(fx.session);
        assertHandled(fx.session.executeCommand("submit-scope", ""));

        // A live run-plan offer in research/running: the bot runs it; /continue is no offer alias.
        startOutcome(fx, "TOOL_BUDGET_EXHAUSTED");
        assertTrue(tagCommands(fx).contains("run-plan"));
        assertTrue(fx.session.executeBotCommand("end", "").contains("Phase changes need the user"));
        String slash = fx.session.executeCommand("continue", "");
        assertTrue(slash, slash.startsWith("rejected") && slash.contains("research/running")
                && slash.contains("Valid now") && slash.contains("run-plan"));
        assertHandled(fx.session.executeBotCommand("run-plan", ""));
        assertFalse("the offer ran and was consumed", tagCommands(fx).contains("run-plan"));
        assertEquals("research/running", phase(fx));

        startOutcome(fx, "SEARCH_TECHNICAL_PROBLEM");
        assertHandled(fx.session.executeBotCommand("retry-query", ""));
        assertEquals("research/running", phase(fx));
    }

    @Test
    public void choosingTheCurrentPhaseInThePillChangesNothing() {
        Fx fx = new Fx();
        firstMessage(fx, "A book");
        ConceptTestSupport.makeSubmittable(fx.session);
        assertHandled(fx.session.executeCommand("submit-scope", ""));
        com.aresstack.askai.research.agent.ResearchStateSnapshot snapshot =
                fx.session.currentResearchSnapshot();
        assertNull(snapshot.advanceCommandFor(ResearchStateIds.RESEARCH));
        assertEquals("the way back stays", ResearchCommandType.REOPEN_CONCEPT,
                snapshot.advanceCommandFor(ResearchStateIds.SCOPING));
    }

    // ------------------------------------------------------------------ PR 06

    private static List<String> journalCommands(Fx fx, String actor) throws Exception {
        List<String> commands = new ArrayList<String>();
        for (com.google.gson.JsonObject record : fx.resources.transitions().journalRecords()) {
            if ("prepared".equals(record.get("kind").getAsString())) {
                com.google.gson.JsonObject transition = record.getAsJsonObject("transition");
                assertEquals(actor, transition.get("actor").getAsString());
                commands.add(transition.get("command").getAsString());
            }
        }
        return commands;
    }

    @Test
    public void submitScopeJournalsBothTransitionsWithTheUserAsActor() throws Exception {
        Fx fx = new Fx();
        firstMessage(fx, "A book about plugin isolation in Java");
        ConceptTestSupport.makeSubmittable(fx.session);
        assertHandled(fx.session.executeCommand("submit-scope", ""));
        assertEquals("research/running", phase(fx));
        assertEquals(java.util.Arrays.asList("START", "SUBMIT_SCOPE", "START_RESEARCH"),
                journalCommands(fx, "USER"));
    }

    @Test
    public void botAndHostStateCommandsAreRefusedOnEverySurface() {
        Fx fx = new Fx();
        for (com.aresstack.askai.research.state.CommandSurface surface : new com.aresstack.askai.research
                .state.CommandSurface[] {com.aresstack.askai.research.state.CommandSurface.BOT_CONTROL,
                com.aresstack.askai.research.state.CommandSurface.CHATGPT_CONNECTOR}) {
            String chat = fx.session.executeBotCommand(surface, "", "hello");
            assertTrue(chat, chat.startsWith("rejected") && chat.contains("Phase changes need the user"));
            assertEquals("scoping/new", phase(fx));
        }
        firstMessage(fx, "A book");
        ConceptTestSupport.makeSubmittable(fx.session);
        assertHandled(fx.session.executeCommand("submit-scope", ""));
        assertHandled(fx.session.executeCommand("finish-sources", ""));
        assertEquals("research/waiting_approval", phase(fx));
        for (com.aresstack.askai.research.state.CommandSurface surface
                : com.aresstack.askai.research.state.CommandSurface.values()) {
            String bot = fx.session.executeCommandAs(com.aresstack.askai.research.state.CommandActor.BOT,
                    surface, "approve", "");
            assertTrue(surface + ": " + bot, bot.contains("Phase changes need the user"));
            String host = fx.session.executeCommandAs(com.aresstack.askai.research.state.CommandActor.HOST,
                    surface, "approve", "");
            assertTrue(surface + ": " + host, host.startsWith("rejected")
                    && host.contains("The host dispatches no state command"));
        }
        String connector = fx.session.executeBotCommand(
                com.aresstack.askai.research.state.CommandSurface.CHATGPT_CONNECTOR, "approve", "");
        assertTrue(connector, connector.contains("Phase changes need the user"));
        assertEquals("research/waiting_approval", phase(fx));
    }

    @Test
    public void twoApproveClicksOnTheSameRevisionApproveOnce() {
        Fx fx = new Fx();
        firstMessage(fx, "A book");
        ConceptTestSupport.makeSubmittable(fx.session);
        assertHandled(fx.session.executeCommand("submit-scope", ""));
        assertHandled(fx.session.executeCommand("finish-sources", ""));
        long seen = fx.resources.currentState().getRevision();
        assertHandled(fx.session.executeUserCommand("approve", "", seen));
        String second = fx.session.executeUserCommand("approve", "", seen);
        assertTrue(second, second.contains("The workflow changed. Check the current state and try again."));
        assertEquals("outline/running", phase(fx));
    }

    @Test
    public void requestChangesWithACommentIsJournalledAndPostedWithoutAModelTurn() throws Exception {
        Fx fx = new Fx();
        firstMessage(fx, "A book");
        ConceptTestSupport.makeSubmittable(fx.session);
        assertHandled(fx.session.executeCommand("submit-scope", ""));
        assertHandled(fx.session.executeCommand("finish-sources", ""));
        assertHandled(fx.session.executeCommand("approve", ""));
        OutlineTestSupport.makeFinishable(fx.session);
        assertHandled(fx.session.executeCommand("finish-outline", ""));
        assertEquals("outline/waiting_approval", phase(fx));
        int prompts = fx.backend.prompts.size();
        assertHandled(fx.session.executeCommand("request-changes", "too long"));
        assertEquals("outline/running", phase(fx));
        assertEquals("no model turn", prompts, fx.backend.prompts.size());
        assertTrue(fx.host.userMessages.toString(), fx.host.userMessages.contains("Changes requested: too long"));
        boolean journalled = false;
        for (com.google.gson.JsonObject record : fx.resources.transitions().journalRecords()) {
            journalled |= "prepared".equals(record.get("kind").getAsString()) && "too long".equals(
                    record.getAsJsonObject("transition").get("comment").getAsString());
        }
        assertTrue(journalled);
    }

    @Test
    public void busyIsFalseWhenNothingRunsAndDividersSurviveARestart() {
        java.io.File dir = newTempDir();
        Fx fx = new Fx(dir);
        firstMessage(fx, "A book");
        ConceptTestSupport.makeSubmittable(fx.session);
        assertHandled(fx.session.executeCommand("submit-scope", ""));
        assertHandled(fx.session.executeCommand("finish-sources", ""));
        assertHandled(fx.session.executeCommand("approve", ""));
        assertEquals("outline/running", phase(fx));
        assertFalse("busy means an agent turn in flight", fx.session.getState().isBusy());
        assertTrue(fx.host.infos.toString(), fx.host.infos.contains("Phase 2: Sources, started by you (concept revision 1)"));
        assertTrue(fx.host.infos.toString(), fx.host.infos.contains("Phase 3: Outline, started by you"));
        int dividers = fx.host.infos.size();
        fx.session.close();
        fx.resources.close();

        Fx reopened = new Fx(dir);
        assertEquals("outline/running", phase(reopened));
        assertFalse("no divider is written twice", reopened.host.infos.contains("Phase 3: Outline, started by you"));
        assertTrue(new java.io.File(new java.io.File(dir, "state"), "transitions.jsonl").isFile());
        assertTrue(dividers >= 2);
    }

    @Test
    public void reopenConceptDuringARunningTurnLeavesTheComposerIdleAndTheLateOutcomeReArmsNothing() {
        Fx fx = new Fx();
        firstMessage(fx, "A book");
        ConceptTestSupport.makeSubmittable(fx.session);
        assertHandled(fx.session.executeCommand("submit-scope", ""));
        assertEquals("research/running", phase(fx));
        fx.session.submitPrompt("search more");
        assertEquals(com.aresstack.askai.plugin.api.agent.SubmissionAvailability.BUSY,
                fx.session.getChatTarget().getAvailability());
        assertHandled(fx.session.executeCommand("reopen-concept", ""));
        assertEquals("scoping/running", phase(fx));
        assertEquals(com.aresstack.askai.plugin.api.agent.SubmissionAvailability.AVAILABLE,
                fx.session.getChatTarget().getAvailability());
        startOutcome(fx, "TOOL_BUDGET_EXHAUSTED");
        assertFalse("the late outcome re-arms no offer", tagCommands(fx).contains("run-plan"));
        assertEquals(com.aresstack.askai.plugin.api.agent.SubmissionAvailability.AVAILABLE,
                fx.session.getChatTarget().getAvailability());
    }

    @Test
    public void aCorruptPhaseJournalIsKeptAndReportedAndTheSessionOpens() throws Exception {
        java.io.File dir = newTempDir();
        java.io.File journal = new java.io.File(dir, "phase-journal.json");
        java.nio.file.Files.write(journal.toPath(), "{broken".getBytes("UTF-8"));
        Fx fx = new Fx(dir);
        assertFalse(journal.isFile());
        boolean kept = false;
        for (java.io.File file : dir.listFiles()) {
            kept |= file.getName().startsWith("phase-journal.json.corrupt-");
        }
        assertTrue("moved aside, not deleted", kept);
        assertTrue(fx.host.problems.toString(), fx.host.problems.toString().contains("phase journal"));
        firstMessage(fx, "A book");
        assertEquals("the session opens", "scoping/running", phase(fx));
    }

    @Test
    public void aCorruptMementoBlocksTheSessionWithARepairHint() throws Exception {
        java.io.File dir = newTempDir();
        java.io.File state = new java.io.File(dir, "state");
        state.mkdirs();
        java.nio.file.Files.write(new java.io.File(state, "research-session.json").toPath(),
                "garbage".getBytes("UTF-8"));
        Fx fx = new Fx(dir);
        assertTrue(fx.host.problems.toString(), fx.host.problems.toString().contains("Repair or restore"));
        fx.session.submitPrompt("hello");
        assertTrue("nothing started", fx.backend.prompts.isEmpty());
        assertEquals("scoping/new", phase(fx));
        assertEquals("garbage", new String(java.nio.file.Files.readAllBytes(
                new java.io.File(state, "research-session.json").toPath()), "UTF-8"));
    }

    // ------------------------------------------------------------------ minimal host

    private static final class PlainHost implements AgentHostContext {
        final List<String> infos = java.util.Collections.synchronizedList(new ArrayList<String>());
        final List<String> userMessages = java.util.Collections.synchronizedList(new ArrayList<String>());
        final List<String> problems = java.util.Collections.synchronizedList(new ArrayList<String>());

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
            // A recording sink: info lines (phase dividers), user notes and problems.
            return (AgentConversationSink) java.lang.reflect.Proxy.newProxyInstance(
                    AgentConversationSink.class.getClassLoader(),
                    new Class<?>[] {AgentConversationSink.class},
                    new java.lang.reflect.InvocationHandler() {
                        public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) {
                            if ("appendInfoMessage".equals(method.getName())) {
                                infos.add(String.valueOf(args[1]));
                            } else if ("appendUserMessage".equals(method.getName())) {
                                userMessages.add(String.valueOf(args[1]));
                            } else if ("showProblem".equals(method.getName())) {
                                problems.add(String.valueOf(args[1]));
                            }
                            Class<?> type = method.getReturnType();
                            if (type == boolean.class) {
                                return Boolean.FALSE;
                            }
                            if (type == int.class || type == long.class) {
                                return type == int.class ? (Object) 0 : (Object) 0L;
                            }
                            if (type == String.class) {
                                return "";
                            }
                            return null;
                        }
                    });
        }
    }

    /** A fresh temp directory for a file-backed project context (the productive contract). */
    static java.io.File newTempDir() {
        try {
            return java.nio.file.Files.createTempDirectory("askai-research-test").toFile();
        } catch (java.io.IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

}
