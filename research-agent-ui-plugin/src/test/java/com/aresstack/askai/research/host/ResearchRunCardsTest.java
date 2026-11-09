package com.aresstack.askai.research.host;

import com.aresstack.askai.mcp.api.InProcessMcpServerRegistry;
import com.aresstack.askai.plugin.api.agent.AgentConversationSink;
import com.aresstack.askai.plugin.api.agent.AgentHostContext;
import com.aresstack.askai.plugin.api.agent.SubmissionAvailability;
import com.aresstack.askai.plugin.api.service.MarkdownViewFactory;
import com.aresstack.askai.plugin.api.service.NotificationService;
import com.aresstack.askai.plugin.api.service.PluginPathService;
import com.aresstack.askai.plugin.api.service.ThemeService;
import com.aresstack.askai.plugin.api.service.UiExecutor;
import com.aresstack.askai.plugin.api.service.WorkspaceStateStore;
import com.aresstack.askai.research.agent.ResearchAgentSession;
import com.aresstack.askai.research.agent.ResearchArtifactStore;
import com.aresstack.askai.research.agent.ResearchPlaybook;
import com.aresstack.askai.research.backend.ResearchBackendEvent;
import com.aresstack.askai.research.backend.ResearchBackendEventType;
import com.aresstack.askai.research.backend.ResearchProjectRequest;
import com.aresstack.askai.research.backend.ResearchPrompt;
import com.aresstack.askai.research.backend.ResearchRunOutcomeInfo;
import com.aresstack.askai.research.backend.ResearchRunProgressInfo;
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Commit 55: the guided result flow instead of the debug stream. One in-place progress card per run, a
 * localized result card with REAL typed actions after the terminal outcome, an invisible technical terminal,
 * and none of the internal identifiers (stop-reason enums, source ids, raw redirect URLs) in normal chat.
 */
public class ResearchRunCardsTest {

    /** Records every sink interaction; showActionCard keeps options + handler for pressing buttons. */
    private static final class RecordingSink implements AgentConversationSink {
        final List<String> userMessages = new ArrayList<String>();
        final List<String> assistantMessages = new ArrayList<String>();
        final List<String> startedActivities = new ArrayList<String>();
        final List<String> updatedActivities = new ArrayList<String>();
        final List<String> activityBodies = new ArrayList<String>();
        final List<String> completedActivities = new ArrayList<String>();
        final List<String> cardMarkdowns = new ArrayList<String>();
        final List<List<ActionOption>> cardOptions = new ArrayList<List<ActionOption>>();
        final List<ActionHandler> cardHandlers = new ArrayList<ActionHandler>();
        final List<String> technicalLog = new ArrayList<String>();

        @Override
        public void appendTechnicalLog(String line) {
            technicalLog.add(line);
        }

        public void appendUserMessage(String messageId, String markdown) {
            userMessages.add(markdown);
        }

        public void appendAssistantMessage(String messageId, String markdown) {
            assistantMessages.add(markdown);
        }

        public void startThinking(String activityId, String title) {
        }

        public void updateThinking(String activityId, String text) {
        }

        public void finishThinking(String activityId, String summary) {
        }

        public void startToolActivity(String activityId, String title, String explanation) {
            startedActivities.add(activityId);
            activityBodies.add(explanation);
        }

        public void updateToolActivity(String activityId, String title, String explanation) {
            updatedActivities.add(activityId);
            activityBodies.add(explanation);
        }

        public void completeToolActivity(String activityId, String summary) {
            completedActivities.add(activityId);
        }

        public void failToolActivity(String activityId, String summary) {
        }

        public void requestApproval(String approvalId, String prompt) {
        }

        final List<String> problems = new ArrayList<String>();

        public void showProblem(String problemId, String publicMessage) {
            problems.add(publicMessage);
        }

        @Override
        public void showActionCard(String cardId, String markdown, List<ActionOption> actions,
                                   ActionHandler handler) {
            cardMarkdowns.add(markdown);
            cardOptions.add(actions);
            cardHandlers.add(handler);
        }
    }

    private static final class RecordingBackend implements ResearchSessionBackend {
        final List<String> prompts = new ArrayList<String>();

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
        }

        public void close(ResearchSessionHandle handle) {
        }
    }

    private static final class Fx {
        final InProcessMcpServerRegistry registry = new InProcessMcpServerRegistry();
        final RecordingBackend backend = new RecordingBackend();
        final RecordingSink sink = new RecordingSink();
        final com.aresstack.askai.research.sources.InMemoryResearchSourceRepository productiveSources =
                com.aresstack.askai.research.sources.InMemoryResearchSourceRepository.empty();
        final ProductiveResearchSessionResources resources;
        final ResearchAgentSession session;
        long sequence;

        Fx() {
            // Sessions default to English without a persisted store value — nothing global to reset.
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
                    null, productiveSources, null, tempProjectContext(), control, null, null, null);
            holder[0] = resources;
            session = new ResearchAgentSession(backend, null, new SinkHost(sink), "s1", "p1", resources);
            session.activate();
        }

        /** Drive to a running research the user's way: submit the concept; the chained start lands in
         *  RESEARCH/running (plan PR 15: no scope proposal from the agent). */
        void reachRunningResearch() {
            assertTrue(session.dispatch(ResearchCommandType.START, null).isAccepted());
            ConceptTestSupport.makeSubmittable(session);
            assertTrue(session.dispatch(ResearchCommandType.SUBMIT_SCOPE, null).isAccepted());
            if (ResearchStateIds.WAITING.equals(resources.currentState().getStateId())) {
                assertTrue(session.dispatch(ResearchCommandType.START_RESEARCH, null).isAccepted());
            }
            assertEquals(ResearchStateIds.RESEARCH, resources.currentState().getPhaseId());
            assertEquals(ResearchStateIds.RUNNING, resources.currentState().getStateId());
        }

        String lastCardActionId(String id) {
            // Uniform surface: the offer must exist as a RED action tag (command projection).
            String command = id;
            for (com.aresstack.askai.research.agent.ResearchActionTag tag
                    : session.availableActionTags()) {
                if (tag.getCommand().equals(command)) {
                    return id;
                }
            }
            throw new AssertionError("no action tag '" + command + "' offered");
        }

        void press(String actionId) {
            // Uniform surface: pressing = running the command through the ONE processor.
            String command = actionId;
            session.executeCommand(command, "");
        }

        void event(ResearchBackendEvent.Builder builder) {
            session.onEvent(builder
                    .envelope("e-" + sequence, "s1", "p1", 0L, 0L, ++sequence, null).build());
        }
    }

    @Test
    public void aRestoredSessionAtTheApprovalGateReShowsTheApprovalButtons() {
        Fx fx = new Fx();
        // C5: the scope commit has no gate anymore — reach the EVIDENCE approval gate instead.
        fx.reachRunningResearch();
        fx.session.dispatch(com.aresstack.askai.research.state.ResearchCommandType
                .REQUEST_EVIDENCE_REVIEW, null);
        assertEquals(ResearchStateIds.WAITING_APPROVAL, fx.resources.currentState().getStateId());

        // Simulate a restart: a NEW session on the SAME (persisted) resources with a fresh sink. Its
        // conversation text would come back from the persisted transcript; here we only assert that the
        // interactive approval buttons are re-derived from the live state.
        RecordingSink restoredSink = new RecordingSink();
        ResearchAgentSession restored = new ResearchAgentSession(
                fx.backend, null, new SinkHost(restoredSink), "s1", "p1", fx.resources);
        restored.activate();

        // Unified action surface: no chat card — the restored session derives its RED action tags
        // (approve/changes) from the live WAITING_APPROVAL state.
        assertTrue("the approve tag is re-derived from the WAITING_APPROVAL state",
                hasActionTag(restored, "approve"));
        assertTrue("the changes tag is re-derived as well", hasActionTag(restored, "request-changes"));
    }

    private static boolean hasActionTag(ResearchAgentSession session, String command) {
        for (com.aresstack.askai.research.agent.ResearchActionTag tag : session.availableActionTags()) {
            if (tag.getCommand().equals(command) && tag.isEnabled()) {
                return true;
            }
        }
        return false;
    }

    @Test
    public void aProductiveUserTurnIsEchoedAsAUserBubbleButAnEmptyBootstrapIsNot() {
        Fx fx = new Fx();
        int before = fx.sink.userMessages.size();
        fx.session.submitPrompt("Alles über Wearables");
        assertTrue("the user's own turn appears in the shared chat (right-aligned, by role)",
                fx.sink.userMessages.contains("Alles über Wearables"));
        // An empty/whitespace turn (e.g. a greeting bootstrap) must not add a blank user bubble.
        fx.session.submitPrompt("   ");
        assertEquals("no blank user bubble for an empty turn", before + 1, fx.sink.userMessages.size());
    }

    @Test
    public void oneInPlaceProgressCardPerRunAndLogsStayTechnical() {
        Fx fx = new Fx();
        fx.reachRunningResearch();
        int bubblesBefore = fx.sink.assistantMessages.size();

        fx.event(ResearchBackendEvent.builder(ResearchBackendEventType.RUN_PROGRESS)
                .activity("research-run-p1", null, "", "")
                .runProgress(new ResearchRunProgressInfo("p1", 1, 0, 1, 2, "READING_PAGE",
                        "https://example-a.org/x")));
        fx.event(ResearchBackendEvent.builder(ResearchBackendEventType.RUN_LOG)
                .activity("research-run-p1", null, "", "")
                .text("accepted source-3"));
        fx.event(ResearchBackendEvent.builder(ResearchBackendEventType.RUN_PROGRESS)
                .activity("research-run-p1", null, "", "")
                .runProgress(new ResearchRunProgressInfo("p1", 6, 4, 2, 9, "READING_PAGE", "")));

        assertEquals("exactly ONE progress card is started", 1, fx.sink.startedActivities.size());
        assertTrue("later progress updates the SAME card", fx.sink.updatedActivities.size() >= 1);
        assertEquals("no new chat bubbles for pages/sources/logs",
                bubblesBefore, fx.sink.assistantMessages.size());
        String lastBody = fx.sink.activityBodies.get(fx.sink.activityBodies.size() - 1);
        assertTrue("counters are readable", lastBody.contains("6 pages checked"));
        assertFalse("raw log lines never clutter the visible card", lastBody.contains("source-3"));
        assertTrue("technical log goes to the host's Technical details area",
                fx.sink.technicalLog.contains("accepted source-3"));
    }

    @Test
    public void progressCardShowsQueryFinalHostTitleAndABoundedHistory() {
        Fx fx = new Fx();
        fx.reachRunningResearch();

        fx.event(ResearchBackendEvent.builder(ResearchBackendEventType.RUN_PROGRESS)
                .activity("research-run-p1", null, "", "")
                .runProgress(new ResearchRunProgressInfo("p1", 0, 0, 0, 1, "SEARCHING",
                        "pf4j plugin isolation", "", "", "")));
        String body = fx.sink.activityBodies.get(fx.sink.activityBodies.size() - 1);
        assertTrue("the actually used search query is visible", body.contains("Searching the web for"));
        assertTrue(body.contains("pf4j plugin isolation"));

        fx.event(ResearchBackendEvent.builder(ResearchBackendEventType.RUN_PROGRESS)
                .activity("research-run-p1", null, "", "")
                .runProgress(new ResearchRunProgressInfo("p1", 1, 0, 1, 2, "READING_PAGE", "",
                        "https://pf4j.org/doc/getting-started.html", "pf4j.org",
                        "PF4J – Plugin Framework for Java")));
        body = fx.sink.activityBodies.get(fx.sink.activityBodies.size() - 1);
        assertTrue("currentUrl context is no longer ignored: the final host is shown",
                body.contains("Currently open"));
        assertTrue(body.contains("pf4j.org"));
        assertTrue("the page title is shown", body.contains("PF4J – Plugin Framework for Java"));
        assertTrue("the query stays visible while browsing", body.contains("pf4j plugin isolation"));
        assertFalse("raw URLs never appear in the visible card", body.contains("https://"));

        fx.event(ResearchBackendEvent.builder(ResearchBackendEventType.RUN_PROGRESS)
                .activity("research-run-p1", null, "", "")
                .runProgress(new ResearchRunProgressInfo("p1", 1, 1, 1, 3, "SOURCE_ACCEPTED", "",
                        "https://pf4j.org/doc/getting-started.html", "pf4j.org",
                        "PF4J – Plugin Framework for Java")));
        fx.event(ResearchBackendEvent.builder(ResearchBackendEventType.RUN_PROGRESS)
                .activity("research-run-p1", null, "", "")
                .runProgress(new ResearchRunProgressInfo("p1", 2, 1, 1, 5, "PAGE_SKIPPED", "",
                        "https://baeldung.com/x", "baeldung.com", "Some unrelated tutorial")));
        body = fx.sink.activityBodies.get(fx.sink.activityBodies.size() - 1);
        assertTrue("an accepted source is visibly recorded", body.contains("✓ pf4j.org"));
        assertTrue("a skipped page is visibly not relevant", body.contains("– baeldung.com"));
        assertTrue(body.contains("not relevant"));

        // The visible history stays bounded: after many accepted pages only the last 5 entries remain.
        for (int i = 1; i <= 7; i++) {
            fx.event(ResearchBackendEvent.builder(ResearchBackendEventType.RUN_PROGRESS)
                    .activity("research-run-p1", null, "", "")
                    .runProgress(new ResearchRunProgressInfo("p1", 2 + i, 1 + i, 1 + i, 5 + i,
                            "SOURCE_ACCEPTED", "", "https://host" + i + ".example/a",
                            "host" + i + ".example", "Page " + i)));
        }
        body = fx.sink.activityBodies.get(fx.sink.activityBodies.size() - 1);
        int entries = 0;
        for (String line : body.split("\n")) {
            if (line.startsWith("✓ ") || line.startsWith("– ")) {
                entries++;
            }
        }
        assertEquals("at most five history entries stay visible", 5, entries);
        assertTrue("the newest entry is present", body.contains("✓ host7.example — Page 7"));
        assertFalse("the oldest entries dropped out", body.contains("host1.example"));
    }

    @Test
    public void outcomeRendersOneLocalizedResultCardWithTypedActionsAndFreesTheComposer() {
        Fx fx = new Fx();
        fx.reachRunningResearch();
        assertEquals(SubmissionAvailability.BUSY, fx.session.getChatTarget().getAvailability());

        fx.event(ResearchBackendEvent.builder(ResearchBackendEventType.RUN_PROGRESS)
                .activity("research-run-p1", null, "", "")
                .runProgress(new ResearchRunProgressInfo("p1", 10, 7, 1, 30, "OPENING_PAGE", "")));
        int cardsBefore = fx.sink.cardMarkdowns.size();
        int messagesBefore = fx.sink.assistantMessages.size();
        fx.event(ResearchBackendEvent.builder(ResearchBackendEventType.RUN_OUTCOME)
                .activity("research-run-p1", null, "", "")
                .runOutcome(new ResearchRunOutcomeInfo("p1", "TOOL_BUDGET_EXHAUSTED", 10, 7, 1, 3, 2,
                        true, "INSUFFICIENT_HOST_DIVERSITY", "CONTINUE_RESEARCH")));
        fx.event(ResearchBackendEvent.builder(ResearchBackendEventType.COMPLETED).text(""));

        assertEquals("NO result card anymore — the narrative is a plain assistant message",
                cardsBefore, fx.sink.cardMarkdowns.size());
        assertTrue("the narrative arrived as a message",
                fx.sink.assistantMessages.size() > messagesBefore);
        String card = lastOutcomeNarrative(fx);
        assertTrue("plain-language summary", card.contains("7 relevant sources"));
        assertFalse("no stop-reason enum names", card.contains("TOOL_BUDGET_EXHAUSTED"));
        assertFalse("no internal source ids", card.contains("source-"));
        assertFalse("no run-stopped debug lines", card.contains("RESEARCH_RUN_STOPPED"));
        assertEquals("the progress card was closed", 1, fx.sink.completedActivities.size()
                - countOf(fx.sink.completedActivities, "research-turn"));
        assertEquals("the composer is free again",
                SubmissionAvailability.AVAILABLE, fx.session.getChatTarget().getAvailability());
        assertFalse("no 'Agent turn completed.' bubble",
                fx.sink.assistantMessages.toString().contains("Agent turn completed"));

        // The budget-exhausted-with-open-requirements outcome offers the full decision set as tags.
        List<String> ids = lastActionIds(fx);
        assertTrue(ids.contains("run-plan"));
        assertTrue(ids.contains("sources"));
        assertTrue(ids.contains("refine"));
        assertFalse("PR 05: no limitation offer", ids.contains("limit"));
        assertFalse(ids.contains("accept-limitation"));
        assertTrue(ids.contains("end"));
    }

    @Test
    public void runPlanDrivesTheSearchPlanQueueAndSubmitsNoPrompt() {
        Fx fx = new Fx();
        fx.reachRunningResearch();
        int promptsBefore = fx.backend.prompts.size();
        fx.event(ResearchBackendEvent.builder(ResearchBackendEventType.RUN_OUTCOME)
                .activity("research-run-p1", null, "", "")
                .runOutcome(new ResearchRunOutcomeInfo("p1", "TOOL_BUDGET_EXHAUSTED", 10, 7, 1, 3, 2,
                        true, "INSUFFICIENT_HOST_DIVERSITY", "CONTINUE_RESEARCH")));

        fx.press("run-plan");
        // Plan PR 15: the offer drives the plan queue (D5); there is no stored question to resubmit.
        assertEquals("no research-loop prompt", promptsBefore, fx.backend.prompts.size());
        assertEquals("the composer stays available while the plan runs", SubmissionAvailability.AVAILABLE,
                fx.session.getChatTarget().getAvailability());
    }

    @Test
    public void reviewingEvidenceOpensTheEvidenceGateWithButtons() {
        // The reported bug: pressing "Belege prüfen" advanced the state to EVIDENCE/waiting_approval but
        // showed nothing ("geht nicht weiter"). The gate must present its decision buttons.
        Fx fx = new Fx();
        fx.reachRunningResearch();
        fx.event(ResearchBackendEvent.builder(ResearchBackendEventType.RUN_OUTCOME)
                .activity("research-run-p1", null, "", "")
                .runOutcome(new ResearchRunOutcomeInfo("p1", "SUFFICIENT_EVIDENCE", 10, 7, 3, 3, 2,
                        true, "NONE", "REVIEW_EVIDENCE")));

        assertFalse("PR 05: no review offer", lastActionIds(fx).contains("review"));
        fx.press("end"); // "end" finishes the sources (D1), never cancels the phase
        assertEquals(ResearchStateIds.RESEARCH, fx.resources.currentState().getPhaseId());
        assertEquals(ResearchStateIds.WAITING_APPROVAL, fx.resources.currentState().getStateId());

        // Unified action surface: the gate presents its decision as RED action tags, not a chat card.
        assertTrue("the evidence gate derives its approve tag (no dead end)",
                hasActionTag(fx.session, "approve"));
    }

    @Test
    public void viewingSourcesIsNavigationAndKeepsTheContinueDecisionUsable() {
        // The user-reported bug: pressing "View sources" consumed the result card, so "Continue
        // research" was dead afterwards. Navigation actions must not consume the card.
        Fx fx = new Fx();
        fx.reachRunningResearch();
        fx.event(ResearchBackendEvent.builder(ResearchBackendEventType.RUN_OUTCOME)
                .activity("research-run-p1", null, "", "")
                .runOutcome(new ResearchRunOutcomeInfo("p1", "TOOL_BUDGET_EXHAUSTED", 10, 7, 1, 3, 2,
                        true, "INSUFFICIENT_HOST_DIVERSITY", "CONTINUE_RESEARCH")));

        assertTrue("the decision set is offered as tags", lastActionIds(fx).contains("run-plan"));

        // NAVIGATION does not consume the offers: after viewing the sources, continue is still there.
        fx.press("sources");
        assertTrue("navigation keeps the decision usable", lastActionIds(fx).contains("run-plan"));

        // A DECISION consumes the offer set and starts exactly one new run.
        int promptsBefore = fx.backend.prompts.size();
        fx.press("run-plan");
        assertFalse("a decision consumes the offers", lastActionIds(fx).contains("run-plan"));
        assertEquals("the plan queue runs, no research-loop prompt", promptsBefore, fx.backend.prompts.size());

    }

    @Test
    public void technicalRerankerFailuresAreNeverRenderedAsBudgetStops() {
        // A5: RERANKER_UNAVAILABLE / RERANKER_TIMEOUT / RERANKER_INVALID_RESPONSE are technical
        // failures — the card names the problem, never the used-up budget, and never offers
        // "Continue with limitation" (there is no research result whose limitation could be accepted).
        String[][] reasons = {
                {"RERANKER_UNAVAILABLE", "not reachable"},
                {"RERANKER_TIMEOUT", "did not answer in time"},
                {"RERANKER_INVALID_RESPONSE", "incompatible or corrupted"}};
        for (String[] reason : reasons) {
            Fx fx = new Fx();
            fx.reachRunningResearch();
            fx.event(ResearchBackendEvent.builder(ResearchBackendEventType.RUN_OUTCOME)
                    .activity("research-run-p1", null, "", "")
                    .runOutcome(new ResearchRunOutcomeInfo("p1", reason[0], 0, 0, 0, 3, 2,
                            true, "INSUFFICIENT_SOURCES", "RETRY")));
            String card = lastOutcomeNarrative(fx);
            assertTrue(reason[0] + " names the technical problem: " + card,
                    card.contains("technical problem") && card.contains(reason[1]));
            assertFalse(reason[0] + " must never read like a budget stop: " + card,
                    card.toLowerCase().contains("budget"));
            assertFalse("no stop-reason enum names", card.contains(reason[0]));

            List<String> ids = lastActionIds(fx);
            assertTrue("retry offered", ids.contains("retry-query"));
            assertTrue("configuration offered", ids.contains("config"));
            assertFalse(reason[0] + " must not offer 'Continue with limitation'",
                    ids.contains("limit"));
            assertFalse("no plain continue on a technical failure", ids.contains("continue"));

            int promptsBefore = fx.backend.prompts.size();
            fx.press("retry-query");
            assertEquals("retry drives the plan queue, no research-loop prompt", promptsBefore,
                    fx.backend.prompts.size());
        }
    }

    @Test
    public void rerankerConfigurationErrorLeadsToTheRuntimeSettingsFirst() {
        Fx fx = new Fx();
        fx.reachRunningResearch();
        fx.event(ResearchBackendEvent.builder(ResearchBackendEventType.RUN_OUTCOME)
                .activity("research-run-p1", null, "", "")
                .runOutcome(new ResearchRunOutcomeInfo("p1", "RERANKER_CONFIGURATION_ERROR", 0, 0, 0,
                        3, 2, false, "INSUFFICIENT_SOURCES", "OPEN_CONFIGURATION")));
        String card = lastOutcomeNarrative(fx);
        assertTrue("the card points to the runtime settings: " + card,
                card.contains("runtime settings"));
        assertTrue(card.contains("configuration or model selection is invalid"));
        assertFalse(card.toLowerCase().contains("budget"));
        assertFalse(card.contains("RERANKER_CONFIGURATION_ERROR"));

        List<String> ids = lastActionIds(fx);
        assertTrue("fixing the configuration comes FIRST",
                ids.indexOf("config") >= 0 && ids.indexOf("config") < ids.indexOf("retry-query"));
        assertTrue(ids.contains("retry-query"));
        assertFalse(ids.contains("limit"));
    }

    @Test
    public void noSemanticMatchesIsASemanticResultWithRefineActions() {
        Fx fx = new Fx();
        fx.reachRunningResearch();
        fx.event(ResearchBackendEvent.builder(ResearchBackendEventType.RUN_OUTCOME)
                .activity("research-run-p1", null, "", "")
                .runOutcome(new ResearchRunOutcomeInfo("p1", "NO_SEMANTIC_MATCHES", 0, 0, 0, 3, 2,
                        true, "INSUFFICIENT_SOURCES", "REFINE_RESEARCH_SCOPE")));
        String card = lastOutcomeNarrative(fx);
        assertTrue("the semantic outcome is explained: " + card, card.contains("similar enough"));
        assertFalse("not presented as a technical problem", card.contains("technical problem"));
        assertFalse("never presented as a budget stop", card.toLowerCase().contains("budget"));
        assertFalse(card.contains("NO_SEMANTIC_MATCHES"));

        List<String> ids = lastActionIds(fx);
        assertTrue(ids.contains("refine"));
        assertTrue(ids.contains("sources"));
        assertTrue(ids.contains("end"));
        assertFalse("no candidate passed the policy — there is no limitation to accept",
                ids.contains("limit"));
    }

    /** The offered outcome follow-ups, read from the uniform RED tag surface (card-era ids). */
    private static List<String> lastActionIds(Fx fx) {
        List<String> ids = new ArrayList<String>();
        for (com.aresstack.askai.research.agent.ResearchActionTag tag
                : fx.session.availableActionTags()) {
            ids.add(tag.getCommand());
        }
        return ids;
    }

    /** The outcome narrative, now a plain assistant message instead of a card. */
    private static String lastOutcomeNarrative(Fx fx) {
        return fx.sink.assistantMessages.get(fx.sink.assistantMessages.size() - 1);
    }

    @Test
    public void manualChallengeAttentionShowsOneNoticeAndBeepsOncePerEpisode() {
        Fx fx = new Fx();
        fx.reachRunningResearch();
        final int[] beeps = {0};
        fx.session.setAttentionSound(new Runnable() {
            public void run() {
                beeps[0]++;
            }
        });

        ResearchBackendEvent.Builder required = ResearchBackendEvent
                .builder(ResearchBackendEventType.USER_ATTENTION)
                .activity("attention-bing.com", null, "CAPTCHA", "REQUIRED")
                .messages("bing.com", "https://www.bing.com/search?q=pf4j");
        fx.event(required);
        // A repeated REQUIRED for the SAME episode must not beep or notify again.
        fx.event(ResearchBackendEvent.builder(ResearchBackendEventType.USER_ATTENTION)
                .activity("attention-bing.com", null, "CAPTCHA", "REQUIRED")
                .messages("bing.com", "https://www.bing.com/search?q=pf4j"));

        assertEquals("one audible attention per episode", 1, beeps[0]);
        assertEquals("one visible notice per episode", 1, fx.sink.problems.size());
        assertTrue("the notice names the domain and asks for manual input",
                fx.sink.problems.get(0).contains("bing.com"));
        assertTrue(fx.sink.problems.get(0).contains("Manual input required"));

        fx.event(ResearchBackendEvent.builder(ResearchBackendEventType.USER_ATTENTION)
                .activity("attention-bing.com", null, "CAPTCHA", "RESOLVED")
                .messages("bing.com", ""));
        String lastMessage = fx.sink.assistantMessages.get(fx.sink.assistantMessages.size() - 1);
        assertTrue("the all-clear is visible", lastMessage.contains("Security check solved"));

        // A NEW episode on the same domain notifies (and beeps) again.
        fx.event(ResearchBackendEvent.builder(ResearchBackendEventType.USER_ATTENTION)
                .activity("attention-bing.com", null, "CAPTCHA", "REQUIRED")
                .messages("bing.com", "https://www.bing.com/search?q=pf4j"));
        assertEquals(2, beeps[0]);
        assertEquals(2, fx.sink.problems.size());
    }

    @Test
    public void productiveSessionExposesTheProductiveSourcesAndArtifactsNotTheDemoSeeds() {
        // The user-reported bug: the Sources tab kept showing the clickdummy seed records although the
        // productive run had accepted real sources — the session handed out its session-local demo
        // repository/store instead of the resources' ones (where source_accept and the notes land).
        Fx fx = new Fx();
        org.junit.Assert.assertSame("the sources view must read where source_accept writes",
                fx.resources.getRepository(), fx.session.getSourceRepository());
        org.junit.Assert.assertSame("the artifact tabs must read where the agent writes",
                fx.resources.getArtifactStore(), fx.session.getArtifactStore());
        assertTrue("no demo seed records in a fresh productive session",
                fx.session.getSourceRepository()
                        .find(com.aresstack.askai.research.sources.SourceQuery.all()).isEmpty());
    }

    @Test
    public void invisibleTerminalWithoutOutcomeStillFreesTheComposer() {
        Fx fx = new Fx();
        fx.reachRunningResearch();
        int bubblesBefore = fx.sink.assistantMessages.size();
        fx.event(ResearchBackendEvent.builder(ResearchBackendEventType.COMPLETED).text(""));
        assertEquals("no bubble for the technical terminal", bubblesBefore,
                fx.sink.assistantMessages.size());
        assertEquals(SubmissionAvailability.AVAILABLE, fx.session.getChatTarget().getAvailability());
    }

    private static int countOf(List<String> list, String value) {
        int count = 0;
        for (String item : list) {
            if (value.equals(item)) {
                count++;
            }
        }
        return count;
    }

    // ------------------------------------------------------------------ minimal host with a sink

    private static final class SinkHost implements AgentHostContext {
        private final AgentConversationSink sink;

        SinkHost(AgentConversationSink sink) {
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

    private static AgentConversationSink.ActionOption option(String id, String label) {
        return new AgentConversationSink.ActionOption(id, label);
    }

    @Test
    public void aRestoredSessionResumesWithoutRepeatingTheScopingCeremony() throws Exception {
        ResearchPlaybook english = new ResearchPlaybook(
                com.aresstack.askai.research.agent.ResearchLanguage.ENGLISH);
        // Session 1 persisted an assignment + reached the outline approval gate.
        java.io.File projectDir =
                java.nio.file.Files.createTempDirectory("askai-restore-test").toFile();
        com.aresstack.askai.research.store.ResearchProjectContext seeded =
                com.aresstack.askai.research.store.ResearchProjectContext.open("s1", projectDir);
        seeded.getMetadataStore().save(new com.aresstack.askai.research.store
                .ResearchProjectMetadata(1, "s1", "How does PF4J isolation work?",
                java.util.Arrays.asList("classloading"), 1L));
        seeded.getArtifactStore().replace("outline", 0L, "# Outline - PF4J\n");
        ProductiveResearchSessionResources first = new ProductiveResearchSessionResources("s1",
                new OoResearchStateMachine("s1"), null, productiveSources(), null, seeded,
                null, null, null, null);
        first.dispatch(com.aresstack.askai.research.state.ResearchCommandType.START);
        first.dispatch(com.aresstack.askai.research.state.ResearchCommandType.SUBMIT_SCOPE);
        first.dispatch(com.aresstack.askai.research.state.ResearchCommandType.START_RESEARCH);
        first.dispatch(com.aresstack.askai.research.state.ResearchCommandType.REQUEST_EVIDENCE_REVIEW);

        // Session 2 over the same directory: fresh context, fresh resources, fresh session.
        RecordingSink sink = new RecordingSink();
        com.aresstack.askai.research.store.ResearchProjectContext restored =
                com.aresstack.askai.research.store.ResearchProjectContext.open("s1", projectDir);
        ProductiveResearchSessionResources resources = new ProductiveResearchSessionResources("s1",
                new OoResearchStateMachine("s1"), null, productiveSources(), null, restored,
                null, null, null, null);
        ResearchAgentSession session = new ResearchAgentSession(new RecordingBackend(), null,
                new SinkHost(sink), "s1", "p1", resources);
        session.activate();

        assertEquals("restored state is the approval gate",
                ResearchStateIds.WAITING_APPROVAL, resources.currentState().getStateId());
        String firstMessage = sink.assistantMessages.isEmpty() ? "" : sink.assistantMessages.get(0);
        assertFalse("no fresh-start greeting on a restored project",
                firstMessage.equals(english.greeting()));
        assertFalse("no scoping paraphrase either — the assignment is already confirmed",
                firstMessage.contains(english.paraphraseAndFocus("x")
                        .substring(0, Math.min(12, english.paraphraseAndFocus("x").length()))));
        session.close();
    }

    private static com.aresstack.askai.research.sources.ResearchSourceRepository productiveSources() {
        return new com.aresstack.askai.research.sources.InMemoryResearchSourceRepository();
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
