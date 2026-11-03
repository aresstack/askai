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
import com.aresstack.askai.research.agent.ScopingComposerAccessoryContribution;
import com.aresstack.askai.research.agent.ScopingSupportView;
import com.aresstack.askai.research.backend.ResearchBackendEvent;
import com.aresstack.askai.research.backend.ResearchBackendEventType;
import com.aresstack.askai.research.backend.ResearchProjectRequest;
import com.aresstack.askai.research.backend.ResearchPrompt;
import com.aresstack.askai.research.backend.ResearchSessionBackend;
import com.aresstack.askai.research.backend.ResearchSessionHandle;
import com.aresstack.askai.research.backend.ResearchActivityKind;
import com.aresstack.askai.research.backend.ResearchSessionListener;
import com.aresstack.askai.research.backend.ScopingAssistantUpdate;
import com.aresstack.askai.research.mcp.ResearchControlContext;
import com.aresstack.askai.research.mcp.ResearchControlEndpoint;
import com.aresstack.askai.research.search.ManualWebSearchHandle;
import com.aresstack.askai.research.search.ManualWebSearchPort;
import com.aresstack.askai.research.search.ManualWebSearchRequest;
import com.aresstack.askai.research.sources.ResearchSourceRepository;
import com.aresstack.askai.research.state.ResearchCommandType;
import com.aresstack.askai.research.state.oo.OoResearchStateMachine;
import com.aresstack.askai.research.state.oo.ResearchStateIds;

import org.junit.Test;

import javax.swing.AbstractButton;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Slice S1: a yellow scoping suggestion is a USER-SERVICE web search, NOT an agent chat turn. Through the REAL
 * accessory (built by its host contribution) a genuine tag {@code doClick()} must reach the
 * {@link ManualWebSearchPort} exactly once and must NOT submit a chat turn, start an agent turn, dispatch a
 * state-machine command or approve any artifact. The service is phase-independent — it works the same in
 * SCOPING, OUTLINE and RESEARCH.
 */
public class ManualSearchWiringTest {

    @Test
    public void aYellowSuggestionClickRunsAManualSearchNotAChatTurn() {
        Fx fx = new Fx();
        fx.session.dispatch(ResearchCommandType.START, null); // SCOPING/RUNNING
        completeTurn(fx, 1L);
        RecordingManualWebSearchPort port = new RecordingManualWebSearchPort();
        fx.session.setManualWebSearchPort(port);
        feedSuggestion(fx, "wearables audio video");

        int promptsBefore = fx.backend.prompts.size();
        ScopingSupportView view = buildAccessoryView(fx);
        List<AbstractButton> tags = view.getSuggestionButtons();
        assertEquals("the projection renders exactly one suggestion tag", 1, tags.size());

        tags.get(0).doClick(); // REAL yellow tag → accessory callback → live session → manual search port

        assertEquals("the manual search ran exactly once", 1, port.queries.size());
        assertEquals("with exactly the suggestion query", "wearables audio video", port.queries.get(0));
        assertEquals("no chat/agent turn was submitted", promptsBefore, fx.backend.prompts.size());
        assertEquals("the phase is unchanged", ResearchStateIds.SCOPING,
                fx.resources.currentState().getPhaseId());
        assertEquals(ResearchStateIds.RUNNING, fx.resources.currentState().getStateId());
        assertEquals("no artifact approval happened", 0,
                fx.session.researchBriefStore().load().getApprovedRevisions().size());
    }

    @Test
    public void theManualSearchServiceIsPhaseIndependent() {
        Fx fx = new Fx();
        fx.session.dispatch(ResearchCommandType.START, null); // SCOPING/RUNNING
        completeTurn(fx, 1L);
        RecordingManualWebSearchPort port = new RecordingManualWebSearchPort();
        fx.session.setManualWebSearchPort(port);

        // SCOPING: accepted, phase unchanged.
        fx.session.requestManualWebSearch("query in scoping");
        assertEquals(1, port.queries.size());
        assertEquals(ResearchStateIds.SCOPING, fx.resources.currentState().getPhaseId());

        // RESEARCH (C5: SUBMIT_SCOPE lands there directly): still accepted — the service never gates
        // on the phase — and the phase is unchanged by the search.
        fx.session.dispatch(ResearchCommandType.SUBMIT_SCOPE, null);
        assertEquals(ResearchStateIds.RESEARCH, fx.resources.currentState().getPhaseId());
        fx.session.requestManualWebSearch("query in research");
        assertEquals(2, port.queries.size());
        assertEquals(ResearchStateIds.RESEARCH, fx.resources.currentState().getPhaseId());

        // A blank query is ignored (no spurious search).
        fx.session.requestManualWebSearch("   ");
        assertEquals(2, port.queries.size());
    }

    @Test
    public void aYellowSuggestionClickSendsATypedServiceCommandNotAChatPrompt() throws Exception {
        Fx fx = new Fx();
        fx.session.dispatch(ResearchCommandType.START, null); // SCOPING/RUNNING
        completeTurn(fx, 1L);
        feedSuggestion(fx, "wearables audio video");
        int promptsBefore = fx.backend.prompts.size();
        // The session publishes its language at start; count only what THIS click causes.
        int envelopesBefore = fx.backend.serviceCommands.size();

        ScopingSupportView view = buildAccessoryView(fx);
        view.getSuggestionButtons().get(0).doClick(); // real tag → session → productive BackendManualWebSearchPort

        // Exactly one typed #RSC1# service command carrying the query — and NOT a chat prompt.
        assertEquals(envelopesBefore + 1, fx.backend.serviceCommands.size());
        String envelope = fx.backend.serviceCommands.get(envelopesBefore);
        assertTrue(envelope.startsWith("#RSC1# manual_search"));
        assertTrue("carries a correlation id", envelope.contains(" request_id="));
        assertTrue("carries the url-encoded query",
                envelope.contains("query=" + java.net.URLEncoder.encode("wearables audio video", "UTF-8")));
        assertTrue("carries the session-language snapshot", envelope.contains(" language=en"));
        assertEquals("no chat prompt was submitted", promptsBefore, fx.backend.prompts.size());
        assertEquals("the phase is unchanged", ResearchStateIds.SCOPING,
                fx.resources.currentState().getPhaseId());
    }

    /**
     * The runtime starts on its own English default and only learns the session language from a
     * set_language command. Without one at session start, a German session got an ENGLISH greeting and
     * English suggestion labels until the user happened to toggle the dropdown.
     */
    @Test
    public void theSessionLanguageIsPublishedToTheRuntimeAtStartNotOnlyOnASwitch() {
        Fx fx = new Fx(); // activate() already ran

        assertFalse("the runtime is told the language before the first turn",
                fx.backend.serviceCommands.isEmpty());
        assertEquals("#RSC1# set_language language=en", fx.backend.serviceCommands.get(0));
    }

    @Test
    public void aLanguageSwitchIsAServiceCommandNeverAChatTurnAndNeverAStateChange() {
        Fx fx = new Fx();
        fx.session.dispatch(ResearchCommandType.START, null); // SCOPING/RUNNING
        completeTurn(fx, 1L);
        int promptsBefore = fx.backend.prompts.size();
        int envelopesBefore = fx.backend.serviceCommands.size();

        fx.session.changeLanguage(com.aresstack.askai.research.agent.ResearchLanguage.GERMAN);

        assertEquals("exactly one set_language envelope for the switch",
                1, countEnvelopes(fx, "#RSC1# set_language language=de"));
        assertTrue("the switch travels as a typed control envelope",
                fx.backend.serviceCommands.contains("#RSC1# set_language language=de"));
        assertEquals("no chat prompt was submitted", promptsBefore, fx.backend.prompts.size());
        assertEquals("the phase is unchanged", ResearchStateIds.SCOPING,
                fx.resources.currentState().getPhaseId());
        assertEquals("the state is unchanged", ResearchStateIds.RUNNING,
                fx.resources.currentState().getStateId());
        assertEquals("the host session mirrors the new language",
                com.aresstack.askai.research.agent.ResearchLanguage.GERMAN,
                fx.session.getSessionLanguage().currentLanguage());
    }

    /**
     * The read-aloud trigger goes through the REAL yellow tag: clicking the rendered suggestion
     * button must (a) fire the speaker with EXACTLY the tag's text + the session language and
     * (b) still run the search. With the General-tab setting off, the click stays silent but the
     * search still runs.
     */
    @Test
    public void clickingTheYellowSuggestionTagSpeaksItsTextAndRunsTheSearch() {
        MemoryStore store = new MemoryStore(); // read-aloud setting defaults ON
        Fx fx = new Fx(store);
        final List<String> spoken = new ArrayList<String>();
        fx.session.setTagSpeakerForTest(new java.util.function.BiConsumer<String, String>() {
            public void accept(String text, String language) {
                spoken.add(language + ":" + text);
            }
        });
        fx.session.dispatch(ResearchCommandType.START, null);
        completeTurn(fx, 1L);
        ScopingSupportView view = buildAccessoryView(fx);
        feedSuggestion(fx, "Humor und Satire in der politischen Kultur");
        assertEquals("the projection rendered exactly one yellow tag",
                1, view.getSuggestionButtons().size());

        // The default sub-option gates on the read-aloud state: WITHOUT Play (and without the
        // central auto-read setting) a clicked tag stays silent — the user did not ask for voice.
        view.getSuggestionButtons().get(0).doClick();
        assertTrue("read-aloud not in use → the tag stays silent by default", spoken.isEmpty());
        assertTrue("…but the search ran anyway", fx.backend.serviceCommands.toString()
                .contains("Humor"));

        // Play pressed (the accessory mirrors the sky's wish) → the next tag speaks.
        fx.session.setReadAloudActive(true);
        feedSuggestion(fx, "Psychologische Wirkung von Humor", 3L);
        view.getSuggestionButtons().get(0).doClick();
        assertEquals("the click extracted and spoke the tag text",
                java.util.Arrays.asList("en:Psychologische Wirkung von Humor"), spoken);

        // Sub-option OFF: tags always speak, active or not.
        fx.session.setReadAloudActive(false);
        ResearchRuntimeSettings.saveReadTagsOnlyWhenReadAloudActive(store, false);
        spoken.clear();
        feedSuggestion(fx, "Dritter Vorschlag zum Testen", 4L);
        view.getSuggestionButtons().get(0).doClick();
        assertEquals(java.util.Arrays.asList("en:Dritter Vorschlag zum Testen"), spoken);

        // Parent OFF: silent click, search unaffected.
        ResearchRuntimeSettings.saveReadSearchTagsOnClick(store, false);
        spoken.clear();
        feedSuggestion(fx, "Vierter Vorschlag zum Testen", 5L);
        view.getSuggestionButtons().get(0).doClick();
        assertTrue("read-aloud disabled → nothing spoken", spoken.isEmpty());
    }

    /**
     * The General-tab convenience (default OFF): a SUCCESSFUL search with accepted sources runs
     * the SAME review the "Review new sources" tag triggers — one review_sources envelope goes
     * out by itself. With the setting off, nothing happens automatically.
     */
    @Test
    public void aSuccessfulSearchAutoReviewsOnlyWhenTheGeneralSettingIsOn() {
        MemoryStore store = new MemoryStore();
        store.putBoolean("research.ui.autoReviewAfterSearch", true);
        Fx fx = new Fx(store);
        fx.session.dispatch(ResearchCommandType.START, null);
        completeTurn(fx, 1L);
        fx.session.setManualWebSearchPort(new FixedRequestIdPort("R1"));
        fx.session.requestManualWebSearch("wearables");
        acceptSources(fx, 2);
        manualSearchEvent(fx, 2L, "R1", "completed", "done", "2");
        assertEquals("the completed search launched the review by itself",
                1, countPrefixed(fx, "#RSC1# review_sources"));

        // Default OFF: the same flow leaves the review to the user's explicit click.
        Fx off = new Fx(new MemoryStore());
        off.session.dispatch(ResearchCommandType.START, null);
        completeTurn(off, 1L);
        off.session.setManualWebSearchPort(new FixedRequestIdPort("R1"));
        off.session.requestManualWebSearch("wearables");
        acceptSources(off, 2);
        manualSearchEvent(off, 2L, "R1", "completed", "done", "2");
        assertEquals(0, countPrefixed(off, "#RSC1# review_sources"));
    }

    private static int countPrefixed(Fx fx, String prefix) {
        int count = 0;
        for (String sent : fx.backend.serviceCommands) {
            if (sent.startsWith(prefix)) {
                count++;
            }
        }
        return count;
    }

    /**
     * The switch is ALSO the default for NEW chats: a user who set German yesterday must not be
     * greeted in English today — the seed path ({@code ResearchRuntimeSettings.loadLanguage})
     * reads exactly what the toolbar switch persisted.
     */
    @Test
    public void theLanguageSwitchPersistsAsTheDefaultForNewChats() {
        MemoryStore store = new MemoryStore();
        Fx fx = new Fx(store);
        assertEquals("en", ResearchRuntimeSettings.loadLanguage(store));

        fx.session.changeLanguage(com.aresstack.askai.research.agent.ResearchLanguage.GERMAN);

        assertEquals("de", ResearchRuntimeSettings.loadLanguage(store));
        assertEquals("a NEW session would now seed German",
                com.aresstack.askai.research.agent.ResearchLanguage.GERMAN,
                com.aresstack.askai.research.agent.ResearchLanguage.fromCode(
                        ResearchRuntimeSettings.loadLanguage(store)));
    }

    /** Bare in-memory {@link WorkspaceStateStore} for persistence assertions. */
    private static final class MemoryStore implements WorkspaceStateStore {
        private final java.util.Map<String, String> values =
                new java.util.HashMap<String, String>();

        public String get(String key, String defaultValue) {
            String value = values.get(key);
            return value == null ? defaultValue : value;
        }

        public boolean getBoolean(String key, boolean defaultValue) {
            String value = values.get(key);
            return value == null ? defaultValue : Boolean.parseBoolean(value);
        }

        public int getInt(String key, int defaultValue) {
            String value = values.get(key);
            try {
                return value == null ? defaultValue : Integer.parseInt(value);
            } catch (NumberFormatException invalid) {
                return defaultValue;
            }
        }

        public void put(String key, String value) {
            values.put(key, value);
        }

        public void putBoolean(String key, boolean value) {
            values.put(key, Boolean.toString(value));
        }

        public void putInt(String key, int value) {
            values.put(key, Integer.toString(value));
        }
    }

    @Test
    public void aSearchSnapshotsTheSessionLanguageAtSubmitTime() {
        Fx fx = new Fx();
        fx.session.dispatch(ResearchCommandType.START, null); // SCOPING/RUNNING
        completeTurn(fx, 1L);

        fx.session.changeLanguage(com.aresstack.askai.research.agent.ResearchLanguage.GERMAN);
        fx.session.requestManualWebSearch("wearables"); // search A snapshots de
        fx.session.changeLanguage(com.aresstack.askai.research.agent.ResearchLanguage.ENGLISH);
        fx.session.requestManualWebSearch("medical devices"); // search B snapshots en

        List<String> searches = new ArrayList<String>();
        for (String envelope : fx.backend.serviceCommands) {
            if (envelope.startsWith("#RSC1# manual_search")) {
                searches.add(envelope);
            }
        }
        assertEquals(2, searches.size());
        assertTrue("search A keeps its German snapshot", searches.get(0).contains(" language=de"));
        assertTrue("search B carries the new English snapshot", searches.get(1).contains(" language=en"));
    }

    @Test
    public void theToolbarDropdownSwitchesTheSessionLanguageViaTheServiceCommandPath() {
        final Fx fx = new Fx();
        fx.session.dispatch(ResearchCommandType.START, null);
        completeTurn(fx, 1L);
        com.aresstack.askai.research.agent.ResearchLanguageToolbarContribution contribution =
                new com.aresstack.askai.research.agent.ResearchLanguageToolbarContribution();
        assertTrue("the control applies to research sessions", contribution.supports(fx.session));

        com.aresstack.comiccontrols.control.ResearchPillDropdown pill =
                (com.aresstack.comiccontrols.control.ResearchPillDropdown)
                        contribution.createComponent(
                                new com.aresstack.askai.plugin.api.agent.toolbar.AgentToolbarContext() {
                                    public com.aresstack.askai.plugin.api.agent.AgentSession getSession() {
                                        return fx.session;
                                    }

                                    public UiExecutor getUiExecutor() {
                                        return inlineUi();
                                    }

                                    public ThemeService getThemeService() {
                                        return null;
                                    }
                                });
        assertEquals("the dropdown mirrors the session language (index 0 = English)",
                0, pill.getSelectedIndex());

        int promptsBefore = fx.backend.prompts.size();
        pill.select(1); // GERMAN, like a popup click

        assertEquals("the switch reaches the host session first",
                com.aresstack.askai.research.agent.ResearchLanguage.GERMAN,
                fx.session.getSessionLanguage().currentLanguage());
        assertEquals("exactly one set_language envelope for the switch",
                1, countEnvelopes(fx, "#RSC1# set_language language=de"));
        assertEquals("never a chat turn", promptsBefore, fx.backend.prompts.size());
        assertEquals("never a state change", ResearchStateIds.RUNNING,
                fx.resources.currentState().getStateId());

        pill.select(1);
        assertEquals("re-selecting the current language sends nothing",
                1, countEnvelopes(fx, "#RSC1# set_language language=de"));
    }

    @Test
    public void manualSearchEventsRenderAsActivityAndStaleEventsAreIgnored() {
        Fx fx = new Fx();
        fx.session.dispatch(ResearchCommandType.START, null);
        completeTurn(fx, 1L);
        // A port with a KNOWN requestId so the test controls correlation.
        fx.session.setManualWebSearchPort(new FixedRequestIdPort("R1"));
        fx.session.requestManualWebSearch("wearables"); // active correlation id becomes R1

        manualSearchEvent(fx, 2L, "R1", "started", "Websuche: wearables");
        assertEquals(1, fx.sink.started.size());
        assertEquals("manual-search-R1|Websuche: wearables", fx.sink.started.get(0));

        // A late/stale event from a DIFFERENT request must be ignored (filter out unrelated turn completions).
        manualSearchEvent(fx, 3L, "R2", "completed", "9 Treffer");
        assertTrue("stale completed is ignored", manualEntries(fx.sink.completed).isEmpty());

        // The matching completion renders and clears the correlation.
        manualSearchEvent(fx, 4L, "R1", "completed", "3 Treffer", "3");
        assertEquals(1, manualEntries(fx.sink.completed).size());
        assertEquals("manual-search-R1|3 Treffer", manualEntries(fx.sink.completed).get(0));
    }

    @Test
    public void theDerivedReviewIsAnExplicitActionNeverAnAutomaticContinuationOfTheSearch() {
        Fx fx = new Fx();
        fx.session.dispatch(ResearchCommandType.START, null);
        completeTurn(fx, 1L);
        fx.session.setManualWebSearchPort(new FixedRequestIdPort("R1"));
        fx.session.requestManualWebSearch("wearables");

        // The search accepted sources: in the productive path they are on disk BEFORE the completion
        // event, and that persisted material — not a flag — is what the offer is derived from.
        acceptSources(fx, 8);
        // completed IS the search terminal now (issue #29): the correlation id is cleared, so a stray
        // review event for the finished search is dropped and NOTHING model-backed starts implicitly.
        manualSearchEvent(fx, 2L, "R1", "completed", "8 Treffer", "8");
        assertEquals(1, manualEntries(fx.sink.completed).size());
        manualSearchEvent(fx, 3L, "R1", "review_started", "", "");
        assertTrue("no implicit post-search thinking bubble", fx.sink.thinkingStarted.isEmpty());

        // Accepted sources → the session OFFERS the derived step as a RED action tag (uniform surface,
        // no chat card).
        boolean offered = false;
        for (com.aresstack.askai.research.agent.ResearchActionTag tag : fx.session.availableActionTags()) {
            offered |= "review-sources".equals(tag.getCommand());
        }
        assertTrue("the review offer appears as an action tag", offered);
        assertTrue("no chat action card anymore", fx.sink.actionCards.isEmpty());

        // The user presses "Neue Quellen auswerten": the card is an adapter over the derived-action
        // command (issue #33) — exactly one typed review_sources service command follows.
        assertTrue(fx.session.derivedActions().reviewSources().isAccepted());
        String envelope = null;
        for (String sent : fx.backend.serviceCommands) {
            if (sent.startsWith("#RSC1# review_sources")) {
                envelope = sent;
            }
        }
        assertTrue("the explicit action sends the review_sources control envelope", envelope != null);
        assertTrue(envelope.contains(" request_id=review-"));
        assertTrue("the review is pinned to the corpus the ledger will mark reviewed",
                envelope.contains(" captured_through="));
        String reviewId = lastReviewRequestId(fx);

        // The runtime's review bracket now correlates against the NEW review request id.
        manualSearchEvent(fx, 4L, reviewId, "review_started", "", "");
        assertEquals("one post-search thinking bubble after the explicit action",
                Collections.singletonList("post-search-summary-" + reviewId), fx.sink.thinkingStarted);
        assertTrue("the review keeps the composer busy", fx.session.getState().isBusy());

        // The runtime's summary arrives as a plain assistant message BETWEEN started and finished.
        fx.session.onEvent(ResearchBackendEvent.builder(ResearchBackendEventType.ASSISTANT_MESSAGE)
                .envelope("evt-sum", "s1", "p1", 5L, 0L, 5L, null)
                .text("Die neuen Quellen zeigen …").build());
        assertEquals(Collections.singletonList("Die neuen Quellen zeigen …"),
                fx.sink.assistantMessages);
        assertEquals("the bubble collapses into the summary",
                Collections.singletonList("post-search-summary-" + reviewId), fx.sink.thinkingFinished);

        manualSearchEvent(fx, 6L, reviewId, "review_finished", "",
                com.aresstack.askai.research.domain.search.PostSearchReviewOutcome.SUCCEEDED.token());
        assertFalse("a succeeded review covers exactly these sources — nothing left to offer",
                offersReview(fx));
        // review_finished is the terminal: the correlation id is cleared, so...
        manualSearchEvent(fx, 7L, reviewId, "review_started", "", "");
        assertEquals("no second thinking bubble after the terminal",
                1, fx.sink.thinkingStarted.size());
    }

    @Test
    public void aSearchWithoutAcceptedSourcesOffersNoReviewAction() {
        Fx fx = new Fx();
        fx.session.dispatch(ResearchCommandType.START, null);
        completeTurn(fx, 1L);
        fx.session.setManualWebSearchPort(new FixedRequestIdPort("R1"));
        fx.session.requestManualWebSearch("wearables");

        manualSearchEvent(fx, 2L, "R1", "completed", "0 Treffer", "0");
        boolean offered = false;
        for (com.aresstack.askai.research.agent.ResearchActionTag tag : fx.session.availableActionTags()) {
            offered |= "review-sources".equals(tag.getCommand());
        }
        assertFalse("nothing to review → no review tag", offered);
    }

    @Test
    public void aSearchWithoutAcceptedSourcesIsNoSuccess() {
        Fx fx = new Fx();
        fx.session.dispatch(ResearchCommandType.START, null);
        completeTurn(fx, 1L);
        fx.session.setManualWebSearchPort(new FixedRequestIdPort("R1"));
        fx.session.requestManualWebSearch("wearables");
        manualSearchEvent(fx, 2L, "R1", "completed", "0 Treffer", "0");
        assertTrue("no green completion for 0 sources", manualEntries(fx.sink.completed).isEmpty());
        assertFalse("the query stays offered", fx.session.wasManuallySearched("wearables"));
    }

    private static List<String> manualEntries(List<String> entries) {
        List<String> out = new ArrayList<String>();
        for (String entry : entries) {
            if (entry.startsWith("manual-search-")) {
                out.add(entry);
            }
        }
        return out;
    }

    @Test
    public void aSearchedSuggestionDisappearsAndIsNotReOffered() {
        Fx fx = new Fx();
        fx.session.dispatch(ResearchCommandType.START, null);
        completeTurn(fx, 1L);
        fx.session.setManualWebSearchPort(new FixedRequestIdPort("R1"));
        ScopingAssistantUpdate projection = new ScopingAssistantUpdate(ResearchStateIds.SCOPING,
                java.util.Arrays.asList(
                        new ScopingAssistantUpdate.Suggestion("wearables audio video", "explore", 1),
                        new ScopingAssistantUpdate.Suggestion("wearables health", "explore", 1)),
                "NEUTRAL", "");
        fx.session.onEvent(ResearchBackendEvent.builder(ResearchBackendEventType.SCOPING_PROJECTION)
                .envelope("evt-proj", "s1", "p1", 2L, 0L, 2L, null).scopingProjection(projection).build());
        ScopingSupportView view = buildAccessoryView(fx);
        assertEquals("both suggestions render initially", 2, view.getSuggestionButtons().size());

        fx.session.requestManualWebSearch("wearables audio video");
        manualSearchEvent(fx, 3L, "R1", "completed", "3 Treffer", "3"); // the search covered the first query

        assertTrue(fx.session.wasManuallySearched("wearables audio video"));
        List<javax.swing.AbstractButton> remaining = view.getSuggestionButtons();
        assertEquals("the searched suggestion disappears + the list re-arranges", 1, remaining.size());
        assertEquals("wearables health", remaining.get(0).getText());
    }

    // ------------------------------------------------------------------ helpers

    /** Feed a MANUAL_SEARCH backend event (as the mapper would produce) straight into the session. */
    private static void manualSearchEvent(Fx fx, long seq, String requestId, String subKind, String message) {
        manualSearchEvent(fx, seq, requestId, subKind, message, "");
    }

    /** As above with the mapper's publicMessage (the raw accepted-source count on 'completed'). */
    private static void manualSearchEvent(Fx fx, long seq, String requestId, String subKind, String message,
                                          String publicMessage) {
        fx.session.onEvent(ResearchBackendEvent.builder(ResearchBackendEventType.MANUAL_SEARCH)
                .envelope("evt-ms-" + seq, "s1", "p1", seq, 0L, seq, null)
                .activity("manual-search-" + requestId, ResearchActivityKind.TOOL_UPDATE, subKind, message)
                .messages(publicMessage, requestId)
                .build());
    }

    private static final class FixedRequestIdPort implements ManualWebSearchPort {
        private final String requestId;

        FixedRequestIdPort(String requestId) {
            this.requestId = requestId;
        }

        public ManualWebSearchHandle search(ManualWebSearchRequest request) {
            return new ManualWebSearchHandle() {
                public String getRequestId() {
                    return requestId;
                }

                public void cancel() {
                }
            };
        }
    }

    private static ScopingSupportView buildAccessoryView(Fx fx) {
        ComposerAccessory accessory = new ScopingComposerAccessoryContribution()
                .create(new FakeComposerContext(fx.session));
        return (ScopingSupportView) accessory.getComponent();
    }

    /** Deliver a scoping projection so the accessory renders a clickable yellow suggestion tag. */
    private static void feedSuggestion(Fx fx, String query) {
        feedSuggestion(fx, query, 2L);
    }

    /** As above with an explicit sequence — REPEATED projections need increasing sequences. */
    private static void feedSuggestion(Fx fx, String query, long seq) {
        ScopingAssistantUpdate projection = new ScopingAssistantUpdate(
                ResearchStateIds.SCOPING,
                Collections.singletonList(new ScopingAssistantUpdate.Suggestion(query, "explore", 1)),
                "NEUTRAL", "");
        fx.session.onEvent(ResearchBackendEvent.builder(ResearchBackendEventType.SCOPING_PROJECTION)
                .envelope("evt-proj-" + seq, "s1", "p1", seq, 0L, seq, null)
                .scopingProjection(projection).build());
    }

    private static void completeTurn(Fx fx, long seq) {
        fx.session.onEvent(ResearchBackendEvent.builder(ResearchBackendEventType.COMPLETED)
                .envelope("evt-complete-" + seq, "s1", "p1", seq, 0L, seq, null).build());
    }

    // ------------------------------------------------------------------ fixture

    private static final class RecordingManualWebSearchPort implements ManualWebSearchPort {
        final List<String> queries = new ArrayList<String>();

        public ManualWebSearchHandle search(ManualWebSearchRequest request) {
            queries.add(request.getQuery());
            return new ManualWebSearchHandle() {
                public String getRequestId() {
                    return "req-test";
                }

                public void cancel() {
                }
            };
        }
    }

    /** Write N accepted sources into the project the session reads from, like the runtime does. */
    private static void acceptSources(Fx fx, int count) {
        com.aresstack.askai.research.store.FileResearchSourceRepository sources =
                fx.resources.getProjectContext().getFileSourceRepository();
        for (int i = 0; i < count; i++) {
            try {
                sources.put(com.aresstack.askai.research.sources.ResearchSourceRecord
                        .builder("src-" + count + "-" + i)
                        .title("source " + i)
                        .url("https://example.test/" + count + "/" + i)
                        .capturedAt(1_000L + count * 100L + i)
                        .status(com.aresstack.askai.research.sources.SourceStatus.ACCEPTED)
                        .build());
            } catch (java.io.IOException notWritable) {
                throw new IllegalStateException(notWritable);
            }
        }
    }

    private static boolean offersReview(Fx fx) {
        for (com.aresstack.askai.research.agent.ResearchActionTag tag : fx.session.availableActionTags()) {
            if ("review-sources".equals(tag.getCommand())) {
                return true;
            }
        }
        return false;
    }

    /**
     * A review that FAILED reviewed nothing: the sources stay unreviewed and the offer comes back — as a
     * retry, not as a fresh "auswerten". Before, {@code finished} meant success either way and the user was
     * left with sources nobody had read and no way to ask again.
     */
    @Test
    public void aFailedReviewLeavesTheSourcesUnreviewedAndOffersARetry() {
        Fx fx = new Fx();
        fx.session.dispatch(ResearchCommandType.START, null);
        completeTurn(fx, 1L);
        acceptSources(fx, 3);
        assertTrue("unreviewed sources are offered", offersReview(fx));

        assertTrue(fx.session.derivedActions().reviewSources().isAccepted());
        String reviewId = lastReviewRequestId(fx);
        manualSearchEvent(fx, 2L, reviewId, "review_started", "", "");
        manualSearchEvent(fx, 3L, reviewId, "review_finished", "",
                com.aresstack.askai.research.domain.search.PostSearchReviewOutcome.FAILED.token());

        assertEquals(com.aresstack.askai.research.review.PostSearchReviewStatus.RETRYABLE,
                fx.session.postSearchReviewStatus());
        assertTrue("the offer comes back after a failure", offersReview(fx));
    }

    /** The watermark is on disk: a session opened on the same project does not re-offer a done review. */
    @Test
    public void aSucceededReviewSurvivesTheSession() {
        Fx fx = new Fx();
        fx.session.dispatch(ResearchCommandType.START, null);
        completeTurn(fx, 1L);
        acceptSources(fx, 2);
        assertTrue(fx.session.derivedActions().reviewSources().isAccepted());
        String reviewId = lastReviewRequestId(fx);
        manualSearchEvent(fx, 2L, reviewId, "review_started", "", "");
        manualSearchEvent(fx, 3L, reviewId, "review_finished", "",
                com.aresstack.askai.research.domain.search.PostSearchReviewOutcome.SUCCEEDED.token());

        com.aresstack.askai.research.review.PostSearchReviewLedger persisted =
                fx.resources.getProjectContext().getPostSearchReviewStore().load();
        assertEquals(2, persisted.getReviewedThrough().getReviewableCount());
        assertEquals(com.aresstack.askai.research.review.PostSearchReviewStatus.UP_TO_DATE,
                persisted.statusFor(com.aresstack.askai.research.review.SourceCorpusRevision.of(
                        fx.resources.getProjectContext().getSourceRepository().find(
                                com.aresstack.askai.research.sources.SourceQuery.all())), null));
    }

    private static String lastReviewRequestId(Fx fx) {
        String envelope = null;
        for (String sent : fx.backend.serviceCommands) {
            if (sent.startsWith("#RSC1# review_sources")) {
                envelope = sent;
            }
        }
        assertTrue("a review_sources envelope was sent", envelope != null);
        String rest = envelope.substring(envelope.indexOf("request_id=") + "request_id=".length());
        int space = rest.indexOf(' ');
        return space < 0 ? rest : rest.substring(0, space); // the envelope also carries the corpus pin
    }

    private static final class Fx {
        final InProcessMcpServerRegistry registry = new InProcessMcpServerRegistry();
        final RecordingBackend backend = new RecordingBackend();
        final RecordingSink sink = new RecordingSink();
        final ProductiveResearchSessionResources resources;
        final ResearchAgentSession session;
        final WorkspaceStateStore hostStore;

        Fx() {
            this(null);
        }

        Fx(WorkspaceStateStore hostStore) {
            this.hostStore = hostStore;
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
            session = new ResearchAgentSession(backend, null, new PlainHost(sink, hostStore),
                    "s1", "p1", resources);
            session.activate();
        }
    }

    /** How often exactly this envelope was sent — the session also publishes language and scope context. */
    private static int countEnvelopes(Fx fx, String envelope) {
        int count = 0;
        for (String sent : fx.backend.serviceCommands) {
            if (envelope.equals(sent)) {
                count++;
            }
        }
        return count;
    }

    private static final class RecordingBackend implements ResearchSessionBackend {
        final List<String> prompts = new ArrayList<String>();
        final List<String> serviceCommands = new ArrayList<String>();
        int cancels;

        @Override
        public void submitServiceCommand(ResearchSessionHandle handle, String controlEnvelope) {
            serviceCommands.add(controlEnvelope);
        }

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

    private static com.aresstack.askai.research.store.ResearchProjectContext tempProjectContext() {
        try {
            File dir = java.nio.file.Files.createTempDirectory("askai-research-test").toFile();
            return com.aresstack.askai.research.store.ResearchProjectContext.open("s1", dir);
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static final class FakeComposerContext implements ComposerAccessoryContext {
        private final AgentSession session;

        FakeComposerContext(AgentSession session) {
            this.session = session;
        }

        public AgentSession getSession() {
            return session;
        }

        public UiExecutor getUiExecutor() {
            return inlineUi();
        }

        public ThemeService getThemeService() {
            return null;
        }

        public MarkdownViewFactory getMarkdownViewFactory() {
            return null;
        }
    }

    private static final class PlainHost implements AgentHostContext {
        private final AgentConversationSink sink;
        private final WorkspaceStateStore store;

        PlainHost(AgentConversationSink sink) {
            this(sink, null);
        }

        PlainHost(AgentConversationSink sink, WorkspaceStateStore store) {
            this.sink = sink;
            this.store = store;
        }

        public UiExecutor getUiExecutor() {
            return inlineUi();
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
            return store;
        }

        public PluginPathService getPluginPathService() {
            return null;
        }

        public AgentConversationSink getConversationSink() {
            return sink;
        }
    }

    private static UiExecutor inlineUi() {
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

    /** Records the tool-activity lifecycle so manual-search rendering can be asserted. */
    private static final class RecordingSink implements AgentConversationSink {
        final List<String> started = new ArrayList<String>();
        final List<String> updated = new ArrayList<String>();
        final List<String> completed = new ArrayList<String>();
        final List<String> failed = new ArrayList<String>();
        final List<String> assistantMessages = new ArrayList<String>();
        final List<String> thinkingStarted = new ArrayList<String>();
        final List<String> thinkingFinished = new ArrayList<String>();
        final List<String> actionCards = new ArrayList<String>();

        @Override
        public void showActionCard(String cardId, String markdown, List<ActionOption> actions,
                                   ActionHandler handler) {
            actionCards.add(cardId);
        }

        public void appendUserMessage(String messageId, String markdown) {
        }

        public void appendAssistantMessage(String messageId, String markdown) {
            assistantMessages.add(markdown);
        }

        public void startThinking(String activityId, String title) {
            thinkingStarted.add(activityId);
        }

        public void updateThinking(String activityId, String text) {
        }

        public void finishThinking(String activityId, String summary) {
            thinkingFinished.add(activityId);
        }

        public void startToolActivity(String activityId, String title, String explanation) {
            started.add(activityId + "|" + explanation);
        }

        public void updateToolActivity(String activityId, String title, String explanation) {
            updated.add(activityId + "|" + explanation);
        }

        public void completeToolActivity(String activityId, String summary) {
            completed.add(activityId + "|" + summary);
        }

        public void failToolActivity(String activityId, String summary) {
            failed.add(activityId + "|" + summary);
        }

        public void requestApproval(String approvalId, String prompt) {
        }

        public void showProblem(String problemId, String publicMessage) {
        }
    }
}
