package com.aresstack.askai.research.agent;

import com.aresstack.askai.plugin.api.agent.AgentArtifact;
import com.aresstack.askai.plugin.api.agent.AgentConversationSink;
import com.aresstack.askai.plugin.api.agent.AgentHostContext;
import com.aresstack.askai.plugin.api.agent.AgentSession;
import com.aresstack.askai.plugin.api.agent.AgentStateSnapshot;
import com.aresstack.askai.plugin.api.agent.ChatSubmissionTarget;
import com.aresstack.askai.plugin.api.agent.SubmissionAvailability;
import com.aresstack.askai.plugin.api.agent.artifact.AgentArtifactStore;
import com.aresstack.askai.plugin.api.service.UiExecutor;
import com.aresstack.askai.research.backend.ResearchBackendEvent;
import com.aresstack.askai.research.backend.ResearchProjectRequest;
import com.aresstack.askai.research.backend.ResearchPrompt;
import com.aresstack.askai.research.backend.ResearchScheduler;
import com.aresstack.askai.research.backend.ResearchSessionBackend;
import com.aresstack.askai.research.backend.ResearchSessionHandle;
import com.aresstack.askai.research.backend.ResearchSessionListener;
import com.aresstack.askai.research.state.ResearchCommandType;
import com.aresstack.askai.research.state.ResearchPhase;
import com.aresstack.askai.research.state.ResearchRunState;

import java.util.ArrayList;
import java.util.List;

/**
 * A research {@link AgentSession} backed by the existing deterministic {@link ResearchSessionBackend}. It owns
 * no chat surface and no composer: backend events are marshalled onto the UI thread via {@link UiExecutor} and
 * pushed into the shared chat through the {@link AgentConversationSink}, with the same late/duplicate/foreign
 * guards the workspace controller uses. Slash commands call the typed control methods here (never raw strings),
 * which reach {@code DefaultResearchStateMachine} through the backend.
 */
public final class ResearchAgentSession implements AgentSession, ResearchSessionListener,
        com.aresstack.askai.research.backend.ResearchSessionCommandPort {

    private final ResearchSessionBackend backend;
    private final ResearchScheduler ownedScheduler;
    private final AgentConversationSink sink;
    private final UiExecutor uiExecutor;
    private final ResearchProjectRequest request;
    private final List<AgentArtifact> artifacts = ResearchArtifacts.all();
    private final AgentArtifactStore artifactStore = new ResearchArtifactStore();
    private final com.aresstack.askai.research.sources.ResearchSourceRepository sourceRepository =
            new com.aresstack.askai.research.sources.InMemoryResearchSourceRepository();
    private final ChatSubmissionTarget chatTarget = new ResearchChatTarget();

    /** This session's OWN live language + the playbook bound to it (never static, never shared). */
    private final SessionResearchLanguage sessionLanguage;
    private final ResearchPlaybook playbook;

    // View-model (updated only on the UI thread from backend events). The hierarchical OO memento is the single
    // source of truth: phase, exact state, precise continuation and the pending approval id all come from it.
    private final com.aresstack.askai.research.state.oo.ResearchStateFactory stateFactory =
            com.aresstack.askai.research.state.oo.ResearchStateFactory.getInstance();
    private com.aresstack.askai.research.state.oo.ResearchStateMemento state =
            stateFactory.snapshot(stateFactory.initialPhase(), 0L);
    private String problemMessage = "";
    private long revision;
    private long lastSequence = -1L;

    private final java.util.concurrent.CopyOnWriteArrayList<Runnable> stateListeners =
            new java.util.concurrent.CopyOnWriteArrayList<Runnable>();

    private ResearchSessionHandle handle;
    private boolean started;
    // Volatile: set on close() (possibly off-EDT) and read by late async browser/agent callbacks on the EDT —
    // a callback that arrives after the tab closed must apply nothing.
    private volatile boolean disposed;
    private final com.aresstack.askai.plugin.api.service.WorkspaceStateStore hostStateStore;
    private final AgentHostContext hostContext;
    /** This session's id — used to unregister from the host active-session registry on close. */
    private final String sessionId;
    /** The host CHAT id this session belongs to: its PUBLIC id for external (MCP) addressing; may be "". */
    private final String chatSessionId;
    /** This session's entry in the public session directory (productive only), or null. */
    private volatile com.aresstack.askai.research.mcp.ResearchBotSessionRegistration botRegistration;
    /** The structured command/state/history gateway onto THIS session (productive only), or null. */
    private com.aresstack.askai.research.mcp.ResearchBotSessionGateway botGateway;
    /** Productive mode only: the session's OWN generation-scoped resources (state authority + processes). */
    private final com.aresstack.askai.research.host.ProductiveResearchSessionResources productiveResources;

    /**
     * @param ownedScheduler a scheduler this session must shut down on {@link #close()} (the production path),
     *                        or {@code null} when the scheduler is owned elsewhere (tests inject their own).
     */
    public ResearchAgentSession(ResearchSessionBackend backend, ResearchScheduler ownedScheduler,
                                AgentHostContext host, String sessionId, String projectId) {
        this(backend, ownedScheduler, host, sessionId, projectId, null);
    }

    /**
     * Productive constructor: the session OWNS the resources (closed last on {@link #close()}) and routes
     * structured commands to the resources' state machine — the single transition authority.
     */
    public ResearchAgentSession(ResearchSessionBackend backend, ResearchScheduler ownedScheduler,
                                AgentHostContext host, String sessionId, String projectId,
                                com.aresstack.askai.research.host.ProductiveResearchSessionResources resources) {
        this(backend, ownedScheduler, host, sessionId, projectId, resources, "");
    }

    /**
     * @param chatSessionId the host CHAT session id (the stable chat UUID) this session belongs to. It is
     *                      the PUBLIC id under which the session registers in the
     *                      {@link com.aresstack.askai.research.mcp.ResearchBotSessionDirectory}, so an
     *                      external client can address exactly this session. Empty = not published (fake
     *                      sessions, unit tests).
     */
    public ResearchAgentSession(ResearchSessionBackend backend, ResearchScheduler ownedScheduler,
                                AgentHostContext host, String sessionId, String projectId,
                                com.aresstack.askai.research.host.ProductiveResearchSessionResources resources,
                                String chatSessionId) {
        this.chatSessionId = chatSessionId == null ? "" : chatSessionId.trim();
        this.backend = backend;
        this.ownedScheduler = ownedScheduler;
        this.hostContext = host;
        this.sessionId = sessionId;
        this.sink = host.getConversationSink();
        this.uiExecutor = host.getUiExecutor();
        this.hostStateStore = host.getStateStore();
        // Session-LOCAL language: seeded from the persisted default, then owned by THIS session alone —
        // two parallel research tabs switch independently, nothing is process-global.
        this.sessionLanguage = new SessionResearchLanguage(ResearchLanguage.fromCode(
                com.aresstack.askai.research.host.ResearchRuntimeSettings.loadLanguage(
                        host.getStateStore())));
        this.playbook = new ResearchPlaybook(sessionLanguage);
        this.narrator = new StaticNarrator(playbook);
        this.scoping = new ScopingConversation(narrator);
        this.productiveResources = resources;
        this.request = new ResearchProjectRequest(sessionId, projectId, "Research project");
        if (resources != null) {
            this.state = resources.currentState(); // one truth from the start
            restorePhaseJournal(); // the phase attribution of earlier messages, before anything is recorded
            wireBrowserActivity(resources);
            // C5: when the live outline projection was rebuilt (worker thread), let every state listener —
            // including an open Live Outline artifact view — re-read; marshalled like any other refresh.
            // Issue #33: hand the session's derived-action commands to the resources so the internal
            // service-MCP endpoint can invoke the SAME use cases as the UI buttons.
            resources.setDerivedActions(derivedActions);
            // Konzeptpapier: a committed concept edit (agent MCP tool) refreshes the views like any
            // other state change — marshalled to the EDT like every other refresh.
            resources.setConceptChangedListener(new Runnable() {
                public void run() {
                    uiExecutor.execute(new Runnable() {
                        public void run() {
                            fireStateChanged();
                        }
                    });
                }
            });
            // ONE gateway object serves both faces: the session's own bot-control endpoint (through the
            // resources) and the app-wide public connector (through the session directory, see activate()).
            this.botGateway = new com.aresstack.askai.research.mcp.ResearchBotSessionGateway() {
                        public String execute(String command, String arguments) {
                            return executeCommand(command, arguments);
                        }

                        public String describeState() {
                            return describeSessionState();
                        }

                        public String describeHistory(boolean raw) {
                            return describeChatHistory(raw);
                        }

                        @Override
                        public String describeTechnicalLog(int tailLines) {
                            return ResearchAgentSession.this.describeTechnicalLog(tailLines);
                        }

                        @Override
                        public String describeConceptSnapshot() {
                            com.aresstack.askai.research.concept.ConceptBranchService service =
                                    conceptBranchService();
                            if (service == null) {
                                return null;
                            }
                            com.aresstack.askai.research.concept.ConceptBranchService
                                    .DocumentSnapshot snapshot = service.snapshot();
                            return "revision=" + snapshot.getWorkingRevision() + "\n"
                                    + snapshot.getDocumentJson();
                        }

                        @Override
                        public String describeScopeSnapshot() {
                            com.aresstack.askai.research.scope.ResearchScopeCoordinator
                                    coordinator = scopeCoordinator();
                            if (coordinator == null || !coordinator.isUsable()) {
                                return null;
                            }
                            com.aresstack.askai.research.domain.scope.ResearchScopeDraft draft =
                                    coordinator.current();
                            // The SAME fence rendering the model sees — a driver and the agent
                            // must never argue over two different projections of one scope.
                            return "revision=" + draft.getRevision() + "\n"
                                    + com.aresstack.askai.research.domain.scope
                                            .ResearchScopeFenceView.render(draft);
                        }
                    };
            resources.setSessionGateway(botGateway);
            // The one-command exclusion facade: exclude_topic/resolve_concept_conflict reach the
            // session (which owns coordinator, concept service and conflict registry) via this
            // port — never a second scope instance.
            resources.setScopeCommandPort(new com.aresstack.askai.research.mcp.ScopeCommandPort() {
                public String excludeTopic(String topic) {
                    return excludeTopicCommand(topic);
                }

                public String resolveConceptConflict(String conflictId, String decision) {
                    return resolveConceptConflictCommand(conflictId, decision);
                }

                public String offerSearches(String suggestionsJson) {
                    return offerSearchesCommand(suggestionsJson);
                }

                public java.util.List<String> blacklistedTerms() {
                    return currentBlacklistTerms();
                }

                @Override
                public void conceptToolLog(String line) {
                    technicalLog(line);
                }

                @Override
                public String scopeProbe(java.util.List<String> terms) {
                    return scopeProbeCommand(terms);
                }

                @Override
                public String searchScopeBegin() {
                    return ResearchAgentSession.this.searchScopeBegin();
                }

                @Override
                public String searchScopeEvaluate(String handle, String lane,
                                                  String itemsJson) {
                    return ResearchAgentSession.this.searchScopeEvaluate(handle, lane,
                            itemsJson);
                }

                @Override
                public String searchScopeEnd(String handle) {
                    return ResearchAgentSession.this.searchScopeEnd(handle);
                }
            });
            resources.setProjectionUpdateListener(new Runnable() {
                public void run() {
                    uiExecutor.execute(new Runnable() {
                        public void run() {
                            fireStateChanged();
                        }
                    });
                }
            });
        }
    }

    /** The session's OWN live language — the toolbar switch mutates exactly this, nothing global. */
    public SessionResearchLanguage getSessionLanguage() {
        return sessionLanguage;
    }

    /**
     * Live language switch: host texts and narrations of THIS session pick it up on the next
     * utterance; already rendered history stays untouched. The runtime agent is synchronised
     * best-effort via a {@code set_language} service command — no chat turn, no history entry, no
     * state-machine command; a fake backend's submitServiceCommand is a no-op. The choice ALSO
     * becomes the persisted default for NEW chats (same key as the gear settings' combo): a user
     * who switched to German is greeted in German by the next session too — a fresh chat ignoring
     * yesterday's explicit choice was terrible UX.
     */
    public void changeLanguage(ResearchLanguage value) {
        sessionLanguage.change(value);
        com.aresstack.askai.research.host.ResearchRuntimeSettings.saveLanguage(
                hostStateStore, value.getCode());
        publishSessionLanguage();
        publishScopeFence(); // the model must see the scope the HOST holds, from the very first turn
    }

    /**
     * Tell the runtime agent THIS session's working language. Sent at session start too, not only on a
     * switch: the runtime starts on its English default, so a session configured as German produced an
     * English greeting and English suggestion labels until the user happened to toggle the language.
     */
    private void publishSessionLanguage() {
        if (handle == null || disposed) {
            return;
        }
        backend.submitServiceCommand(handle,
                com.aresstack.askai.research.search.ResearchServiceCommandWire.setLanguage(
                        sessionLanguage.currentLanguage().getCode()));
    }

    /**
     * Surface the lazy browser lifecycle as a transient chat activity: a "Starte Browser…" thinking bubble
     * when the browser starts on the first browser command, finished with "Browser bereit." on READY (or a
     * visible problem on failure). The runtime fires these on its owner thread; we marshal onto the EDT.
     */
    private void wireBrowserActivity(
            com.aresstack.askai.research.host.ProductiveResearchSessionResources resources) {
        if (resources.getBrowser() == null) {
            return; // fake/unit resources without a browser runtime
        }
        resources.getBrowser().setListener(
                new com.aresstack.askai.research.host.BrowserRuntimePort.Listener() {
                    public void onStarting(long generation) {
                        final String id = "browser-start-" + generation;
                        onUi(new Runnable() {
                            public void run() {
                                if (disposed) {
                                    return; // tab closed while the browser was starting: post nothing
                                }
                                sink.startThinking(id, playbook.browserStarting());
                            }
                        });
                    }

                    public void onReady(final long generation) {
                        onUi(new Runnable() {
                            public void run() {
                                if (disposed) {
                                    return; // a late READY after close must not post into a closed sink
                                }
                                sink.finishThinking("browser-start-" + generation,
                                        playbook.browserReady());
                            }
                        });
                    }

                    public void onFailed(final long generation, final String detail) {
                        onUi(new Runnable() {
                            public void run() {
                                if (disposed) {
                                    return; // a late failure after close applies nothing
                                }
                                sink.finishThinking("browser-start-" + generation, "");
                                sink.showProblem(publish("browser-start-" + generation),
                                        playbook.browserFailed(detail));
                            }
                        });
                    }

                    public void onStopped(long generation) {
                        // Nothing to show: the run outcome card already tells the user the run ended.
                    }
                });
    }

    private void onUi(Runnable runnable) {
        if (uiExecutor != null) {
            uiExecutor.execute(runnable);
        } else {
            runnable.run();
        }
    }

    /** Plugin-internal: the host's persisted state store (used by the runtime settings view). */
    /** The immutable settings snapshot of THIS session, or null (fake backend / not started). */
    public com.aresstack.askai.browser.search.SearchProcessingProfileSnapshot getActiveSearchProfile() {
        return productiveResources == null ? null : productiveResources.getSearchProfile();
    }

    public com.aresstack.askai.plugin.api.service.WorkspaceStateStore getHostStateStore() {
        return hostStateStore;
    }

    /** Plugin-internal: a host runtime service (e.g. the reranker model catalog), or null. */
    public <T> T getHostService(Class<T> type) {
        return hostContext == null ? null : hostContext.getService(type);
    }

    // ------------------------------------------------------------------ AgentSession lifecycle

    @Override
    public void activate() {
        if (disposed || started) {
            return;
        }
        // Mark started BEFORE createSession: the backend emits the initial START event synchronously, so the
        // listener must already accept it even though the handle field is assigned only when the call returns.
        started = true;
        handle = backend.createSession(request, this);
        // BEFORE any turn: the runtime must know the session language, otherwise its English default
        // decides the greeting and the suggestion labels of a German session.
        publishSessionLanguage();
        // The session is now driveable: publish it under its CHAT id so external clients (the public
        // ChatGPT connector) can address exactly this session. Registration is independent of whether the
        // connector listener is running — switching it on later still finds everything that is live.
        if (botGateway != null && !chatSessionId.isEmpty()) {
            botRegistration = com.aresstack.askai.research.mcp.ResearchBotSessionDirectory.get()
                    .register(chatSessionId, sessionId, botGateway);
        }
        // Wire the user web search onto the productive backend transport (a #RSC1# service command through the backend transport).
        // Transport-agnostic: a fake/clickdummy backend's submitServiceCommand is a no-op. Tests may override
        // the port AFTER activate().
        this.manualWebSearchPort = new com.aresstack.askai.research.search.BackendManualWebSearchPort(
                backend, handle);
        restoreManualSearchedQueries(); // covered queries survive a restart (each source remembers its query)
        wireToolActivityReadAloud();
        if (productiveResources != null && problemMessage.isEmpty()) {
            // Still restore the persisted assignment (question + focus areas) so a resumed project keeps its
            // scope for continuation; the scoping ceremony is no longer host-driven.
            restoreProjectMetadata();
            // Show the last PERSISTED visualization (possibly stale) — never regenerate on open (issue #29).
            restorePersistedVisualization();
            // A fresh chat (SCOPING/NEW) stays EMPTY: no bootstrap turn, no model call. The former greeting
            // asked for a topic the user was about to name anyway, cost a model call on every new chat and sat
            // in the context of the real first turn. The scoping starts with the user's first message (see
            // startScopingOnFirstMessage).
            com.aresstack.askai.research.state.oo.ResearchStateMemento current =
                    productiveResources.currentState();
            boolean freshState =
                    com.aresstack.askai.research.state.oo.ResearchStateIds.SCOPING.equals(current.getPhaseId())
                    && com.aresstack.askai.research.state.oo.ResearchStateIds.NEW.equals(current.getStateId());
            if (!freshState) {
                // Restored session: the conversation text comes back from the persisted transcript, but the
                // interactive buttons do not. Re-derive the decision buttons from the live state
                // (non-persisted, so they never duplicate/accumulate across restarts), and bring back the
                // display-only scoping tags (persisted separately; the runtime does not re-emit them).
                restoreScopingProjection();
                showRestoredActionsIfAny();
            }
        }
    }

    /** Speaks a CLICKED yellow tag — assertive delivery, session language, settings-gated. */
    private final ReadAloudVoice tagVoice = new ReadAloudVoice();
    /** Test seam: (text, languageCode) — replaces the background speaker thread when set. */
    private volatile java.util.function.BiConsumer<String, String> tagSpeakerOverride;
    /** The sky's LIVE Play/Pause wish, mirrored by the accessory. */
    private volatile boolean readAloudActive;

    public void setTagSpeakerForTest(java.util.function.BiConsumer<String, String> override) {
        this.tagSpeakerOverride = override;
    }

    /** The accessory mirrors the sky's Play/Pause wish here — the tag-reading gate reads it. */
    public void setReadAloudActive(boolean active) {
        this.readAloudActive = active;
    }

    /**
     * Read a clicked YELLOW tag aloud (Research Agent settings → General, default ON): the host's
     * speech output with the ASSERTIVE delivery, the plugin's Windows voice as the usual
     * fallback. The setting is read per click — no restart. Called by the yellow SEARCH
     * SUGGESTION tags above the composer and by the amber activity bubbles in the transcript.
     */
    public void readSearchTagAloud(final String text) {
        if (text == null || text.trim().isEmpty()
                || !com.aresstack.askai.research.host.ResearchRuntimeSettings
                        .loadReadSearchTagsOnClick(hostStateStore)) {
            return;
        }
        // Sub-option (default ON): tags speak only while the user actually USES the speech
        // output — Play pressed in the sky, or automatic reading enabled centrally. Off: always.
        if (com.aresstack.askai.research.host.ResearchRuntimeSettings
                .loadReadTagsOnlyWhenReadAloudActive(hostStateStore)
                && !isReadAloudInUse()) {
            return;
        }
        final String language = sessionLanguage.currentLanguage().getCode();
        java.util.function.BiConsumer<String, String> override = tagSpeakerOverride;
        if (override != null) {
            override.accept(text, language); // tests observe the trigger + extracted text HERE
            return;
        }
        Thread speaker = new Thread(new Runnable() {
            public void run() {
                tagVoice.speakEmphatic(text, language);
            }
        }, "askai-tag-voice");
        speaker.setDaemon(true);
        speaker.start();
    }

    /** Play pressed in this session's sky, OR automatic reading enabled in the central settings. */
    private boolean isReadAloudInUse() {
        if (readAloudActive) {
            return true;
        }
        com.aresstack.askai.agent.model.speech.SpeechSynthesisPort port = getHostService(
                com.aresstack.askai.agent.model.speech.SpeechSynthesisPort.class);
        return port != null && port.isReadAloudActiveByDefault();
    }

    private void wireToolActivityReadAloud() {
        tagVoice.setModelVoice(getHostService(
                com.aresstack.askai.agent.model.speech.SpeechSynthesisPort.class));
        if (sink == null) {
            return;
        }
        sink.setToolActivityClickListener(
                new com.aresstack.askai.plugin.api.agent.AgentConversationSink
                        .ToolActivityClickListener() {
                    public void toolActivityClicked(String title, String explanation) {
                        readSearchTagAloud(title);
                    }
                });
    }

    /**
     * On restore, the decision buttons re-derive from the LIVE state through the semantic command resolver
     * (the red action tags). States without a user decision (running, terminal) show nothing.
     */
    private void showRestoredActionsIfAny() {
        // Issue #34-style unification: NO chat card anymore — the red action tags above the composer are
        // the ONE surface for restored/pending decisions. They re-derive from the live state on every
        // state-change notification, so firing one here is all a restore needs.
        fireStateChanged();
    }

    /**
     * The currently available ACTIONS as red tags — ONE derivation for the accessory surface AND the MCP
     * session_state answer. Every tag is a command of the synchronized command surface; a command that is
     * not allowed right now yields no tag. The submit-scope tag is the one deliberate disabled-with-reason
     * case (visible during scoping, greyed with the concrete blocker as tooltip), so scoping never shows an
     * unexplained dead end.
     */
    public java.util.List<ResearchActionTag> availableActionTags() {
        java.util.List<ResearchActionTag> tags = new java.util.ArrayList<ResearchActionTag>();
        if (disposed || (productiveResources != null && productiveResources.isClosed())) {
            return tags;
        }
        // An OPEN concept conflict leads: the pending question gets two visible decision
        // buttons (like a pending approval — a request-bound decision, never a workflow state).
        // Candidates from reconciliation never arm anything (the candidate/open model).
        if (hasOpenConflict()) {
            tags.add(new ResearchActionTag("resolve-conflict-remove",
                    playbook.isGerman() ? "Aus Konzept entfernen" : "Remove from concept",
                    playbook.isGerman()
                            ? "Den ausgeschlossenen Begriff auch aus dem Konzept entfernen"
                            : "Also remove the excluded term from the concept", true));
            tags.add(new ResearchActionTag("resolve-conflict-keep",
                    playbook.isGerman() ? "Unterdrückt stehen lassen" : "Keep (suppressed)",
                    playbook.isGerman()
                            ? "Der Eintrag bleibt im Konzept; die Recherche dazu bleibt "
                                    + "unterdrückt"
                            : "The entry stays in the concept; research on it remains "
                                    + "suppressed", true));
        }
        if (resolveSemanticCommand("submit-scope") != null) {
            String reason = scopingApprovalUnavailableReason();
            tags.add(new ResearchActionTag("submit-scope",
                    playbook.isGerman() ? "Fragestellung freigeben & weiter" : "Approve brief & continue",
                    reason.isEmpty()
                            ? (playbook.isGerman()
                                    ? "Fragestellung freigeben und mit der Recherche beginnen"
                                    : "Approve the brief and start the research")
                            : reason,
                    reason.isEmpty()));
        }
        // The DECISION commands become buttons (interrupt machinery — pause/cancel — stays composer-only).
        addTagIfAvailable(tags, "approve", playbook.actionLabel("approve"));
        addTagIfAvailable(tags, "request-changes", playbook.actionLabel("changes"));
        addTagIfAvailable(tags, "continue", playbook.actionLabel("continue"));
        addTagIfAvailable(tags, "retry", playbook.actionLabel("retry"));
        addTagIfAvailable(tags, "resume", playbook.actionLabel("resume"));
        for (String id : outcomeOffers) {
            tags.add(new ResearchActionTag("limit".equals(id) ? "accept-limitation" : id,
                    playbook.actionLabel(id), "", true));
        }
        com.aresstack.askai.research.review.PostSearchReviewStatus reviewStatus = postSearchReviewStatus();
        if (reviewStatus.isOffered()) {
            boolean retry =
                    reviewStatus == com.aresstack.askai.research.review.PostSearchReviewStatus.RETRYABLE;
            tags.add(new ResearchActionTag("review-sources",
                    reviewLabel(retry), reviewExplanation(retry), true));
        }
        // Z4c: the explicit scope check — SCOPING only, user-initiated, flips to its own cancel
        // while running (its request-scoped lifecycle, never the foreground chat stop).
        if (scopeCheckOffered()) {
            if (scopeCheckInFlight.get()) {
                tags.add(new ResearchActionTag("cancel-scope-check",
                        playbook.isGerman() ? "Prüfung abbrechen" : "Cancel scope check",
                        playbook.isGerman()
                                ? "Die laufende Themenraum-Prüfung abbrechen"
                                : "Cancel the running topic-space check", true));
            } else {
                tags.add(new ResearchActionTag("check-scope",
                        playbook.isGerman() ? "Themenraum prüfen" : "Check topic space",
                        playbook.isGerman()
                                ? "Den ausgehandelten Themenraum breit auf Lücken prüfen "
                                        + "(dauert etwa 1–2 Minuten)"
                                : "Check the negotiated topic space broadly for gaps "
                                        + "(takes about 1-2 minutes)", true));
            }
        }
        // #42: the topics-from-sources action — only when the background analysis is enabled
        // AND a prepared topic snapshot actually exists (the click consumes, never computes).
        if (topicDiscoveryOffered()) {
            tags.add(new ResearchActionTag("discover-topics",
                    playbook.isGerman() ? "Themen aus Quellen" : "Topics from sources",
                    playbook.isGerman()
                            ? "Zeigt die aus den bisherigen Quellen entdeckten Themenfelder "
                                    + "als Vorschläge — du entscheidest, was ins Konzept kommt"
                            : "Shows the topic areas discovered in the sources so far as "
                                    + "suggestions — you decide what enters the concept",
                    true));
        }
        return tags;
    }

    /** #42: enabled setting + an existing prepared snapshot with topics — never a computation. */
    private boolean topicDiscoveryOffered() {
        if (productiveResources == null || productiveResources.isClosed()
                || !com.aresstack.askai.research.state.oo.ResearchStateIds.SCOPING
                        .equals(state.getPhaseId())
                || !com.aresstack.askai.research.host.ResearchRuntimeSettings
                        .backgroundTopicDiscovery()) {
            return false;
        }
        com.aresstack.askai.research.knowledge.processing.live.SharedTopicDiscovery discovery =
                productiveResources.topicDiscovery();
        if (discovery == null) {
            return false;
        }
        com.aresstack.askai.research.knowledge.processing.live.FileTopicSnapshotStore
                .TopicSnapshot snapshot = discovery.current();
        return snapshot != null && !snapshot.topics.isEmpty();
    }

    /**
     * #42: consume the PREPARED topic snapshot as a host-narrated chat answer — suggestions
     * only. Structurally no mutation (the method only speaks); excluded topics are filtered
     * before presentation; the user's normal reply runs the existing concept operations.
     */
    private String discoverTopicsFromSources() {
        if (!topicDiscoveryOffered()) {
            return "rejected: no prepared topic analysis is available (background semantic "
                    + "analysis off, or no sources processed yet)";
        }
        com.aresstack.askai.research.knowledge.processing.live.SharedTopicDiscovery discovery =
                productiveResources.topicDiscovery();
        com.aresstack.askai.research.knowledge.processing.live.FileTopicSnapshotStore
                .TopicSnapshot snapshot = discovery.current();
        java.util.List<String> titles = ResearchTopicsAnswer.presentableTitles(
                snapshot.topics, currentBlacklistTerms());
        technicalLog("discover-topics -> snapshot revision=" + snapshot.revision
                + " topics=" + snapshot.topics.size() + " presented=" + titles.size()
                + (discovery.isStale() ? " (stale, refresh pending)" : ""));
        sayAsAgent(ResearchTopicsAnswer.render(titles, discovery.isStale(),
                playbook.isGerman()));
        return "handled: presented " + titles.size() + " topic suggestions (no concept "
                + "change)";
    }

    /** SCOPING + a usable productive scope draft — the check's visibility condition. */
    private boolean scopeCheckOffered() {
        if (handle == null || disposed || productiveResources == null
                || productiveResources.isClosed()) {
            return false;
        }
        if (!com.aresstack.askai.research.state.oo.ResearchStateIds.SCOPING
                .equals(productiveResources.currentState().getPhaseId())) {
            return false;
        }
        com.aresstack.askai.research.scope.ResearchScopeCoordinator coordinator =
                scopeCoordinator();
        return coordinator != null && coordinator.isUsable();
    }

    private void addTagIfAvailable(java.util.List<ResearchActionTag> tags, String command, String label) {
        if (resolveSemanticCommand(command) != null) {
            tags.add(new ResearchActionTag(command, label, "", true));
        }
    }

    @Override
    public void deactivate() {
        // Keep all state; the run continues in the background.
    }

    @Override
    public void close() {
        if (disposed) {
            return;
        }
        disposed = true;
        // FIRST: leave the public session directory, so no new external call can reach a session that is
        // being torn down. unregister() removes exactly THIS registration — a chat that was reopened in the
        // meantime keeps its own. deactivate() deliberately does NOT do this: a background session stays
        // driveable while the user looks at another chat.
        com.aresstack.askai.research.mcp.ResearchBotSessionDirectory.get().unregister(botRegistration);
        botRegistration = null;
        briefWriteExecutor.shutdown(); // stop accepting brief writes; in-flight writes are atomic
        journalWriteExecutor.shutdown(); // queued journal writes still complete; new ones are rejected
        if (artifactVisualizer != null) {
            artifactVisualizer.shutdown(); // stop the lazy visualizer; a running visualize is discarded
        }
        // No longer a running session: stop AskAI from re-publishing descriptors to a torn-down dir.
        com.aresstack.askai.agent.model.session.ActiveResearchSessionRegistry activeSessions =
                hostContext.getService(
                        com.aresstack.askai.agent.model.session.ActiveResearchSessionRegistry.class);
        if (activeSessions != null) {
            activeSessions.unregister(sessionId);
        }
        if (handle != null) {
            backend.close(handle);
            handle = null;
        }
        invalidateNarration();
        if (narrationScheduler != null) {
            narrationScheduler.shutdown();
        }
        if (ownedScheduler != null) {
            ownedScheduler.shutdown();
        }
        scopeCheckExecutor.shutdownNow();
        com.aresstack.askai.research.scope.BackendScopeProbeGenerator waitingGenerator =
                activeProbeGenerator;
        if (waitingGenerator != null) {
            // A waiting sweep fails typed instead of running into its timeout on a dead session.
            waitingGenerator.abortAll("session closed");
        }
        com.aresstack.askai.research.scope.BackendScopeAdviceChooser waitingChooser =
                activeAdviceChooser;
        if (waitingChooser != null) {
            waitingChooser.abortAll("session closed");
        }
        if (productiveResources != null) {
            productiveResources.close(); // endpoints → browser runtime (idempotent)
        }
    }

    @Override
    public ChatSubmissionTarget getChatTarget() {
        return chatTarget;
    }

    @Override
    public List<AgentArtifact> getArtifacts() {
        return artifacts;
    }

    @Override
    public AgentArtifactStore getArtifactStore() {
        // Productive sessions expose the resources' store — the one the agent's MCP endpoint writes to.
        // The session-local store is only the clickdummy/demo world.
        return productiveResources != null ? productiveResources.getArtifactStore() : artifactStore;
    }

    /**
     * Plugin-internal accessor (same classloader): the structured sources repository for the sources view.
     * In productive mode this MUST be the resources' repository (where {@code source_accept} lands) — the
     * session-local in-memory repository only backs the demo mode with its visibly seeded examples.
     */
    public com.aresstack.askai.research.sources.ResearchSourceRepository getSourceRepository() {
        return productiveResources != null ? productiveResources.getRepository() : sourceRepository;
    }

    @Override
    public AgentStateSnapshot getState() {
        ResearchPhase phase = com.aresstack.askai.research.state.oo.ResearchStateIds.phase(state.getPhaseId());
        ResearchRunState run =
                com.aresstack.askai.research.state.oo.ResearchStateIds.runState(state.getStateId());
        boolean busy = com.aresstack.askai.research.state.oo.ResearchStateIds.RUNNING.equals(state.getStateId())
                || postSearchSummaryInFlight; // the post-search summary keeps the bot "am Zug" (red send)
        return AgentStateSnapshot.builder()
                .phaseLabel(phase.name())
                .runStateLabel(run.name())
                .busy(busy)
                .pendingApproval(state.getPendingApprovalId() != null)
                .pendingApprovalId(state.getPendingApprovalId())
                .revision(revision)
                .statusLine(phase + " / " + run)
                .allowedCommandNames(allowedCommandNames())
                .build();
    }

    // ------------------------------------------------------------------ typed controls (used by slash commands)

    public boolean hasPendingApproval() {
        return state.getPendingApprovalId() != null;
    }

    /** The user's research question (set once scoping is confirmed; auto-continued after approval). */
    private volatile String researchQuestion = "";
    /** The latest scoping assistant projection (search suggestions) for the composer accessory; transient. */
    private volatile com.aresstack.askai.research.backend.ScopingAssistantUpdate latestScopingProjection;
    /** File-backed research brief store, bound to this session's project dir (null in the clickdummy). */
    private com.aresstack.askai.research.store.FileResearchBriefStore researchBriefStore;
    /** USER-triggered web search service; wired to the productive backend transport at activate(). */
    private volatile com.aresstack.askai.research.search.ManualWebSearchPort manualWebSearchPort =
            new com.aresstack.askai.research.search.LoggingManualWebSearchPort();
    /** The in-flight user search's correlation id (events carrying any other id are stale) and its handle. */
    private volatile String activeManualSearchRequestId;
    private volatile com.aresstack.askai.research.search.ManualWebSearchHandle activeManualSearchHandle;
    /** The query of the in-flight user search (remembered so a completed search marks it as covered). */
    private volatile String activeManualSearchQuery;
    /** True from a manual search's browser-close until the bot's summary/new suggestions arrive: shows a
     * thinking bubble AND keeps the composer busy (red send button) so the user sees work is still ongoing. */
    private volatile boolean postSearchSummaryInFlight;
    private volatile String postSearchThinkingId;
    /** Normalized queries a manual search already covered — the agent's suggestions never re-offer these. */
    private final java.util.Set<String> manualSearchedQueries =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());
    /** Lazy, host-side artifact visualizer (null when no inference port); a derived-view consumer. */
    private com.aresstack.askai.research.visualize.LazyArtifactVisualizer artifactVisualizer;
    /** The latest derived visualization projection for the "Visualisierung" view; transient/rebuildable. */
    private volatile com.aresstack.askai.research.visualize.VisualizationProjection latestVisualization;
    /** The lifecycle status of the visualization (so the view distinguishes never-ran / running / NONE / fail). */
    private volatile com.aresstack.askai.research.visualize.VisualizationStatus visualizationStatus =
            com.aresstack.askai.research.visualize.VisualizationStatus.NOT_STARTED;
    /** Serializes research-brief working-copy writes OFF the EDT (applyEvent runs on the EDT). */
    private final java.util.concurrent.ExecutorService briefWriteExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor(
                    new java.util.concurrent.ThreadFactory() {
                        public Thread newThread(Runnable r) {
                            Thread thread = new Thread(r, "research-brief-write");
                            thread.setDaemon(true);
                            return thread;
                        }
                    });
    /** True while an agent TURN is in flight (productive composer busy-state; cleared on terminal events). */
    private volatile boolean agentTurnInFlight;

    /**
     * The ONLY way this flag changes. The composer reads its availability from here, but it only re-reads
     * when something tells it to — so a plain assignment released the composer in the model and left the red
     * Stop button on screen whenever no bubble happened to follow (the failed post-search review did exactly
     * that). Announcing the change is part of making it.
     */
    private void setAgentTurnInFlight(boolean inFlight) {
        if (agentTurnInFlight == inFlight) {
            return;
        }
        agentTurnInFlight = inFlight;
        if (!inFlight) {
            // The turn is over on ANY path (answer, cancel, error) — the tool-call bubble must
            // never be left thinking.
            finishConceptToolBubble();
        }
        if (sink != null) {
            sink.turnActivityChanged();
        }
        fireStateChanged();
    }

    /**
     * A foreground agent turn is starting: mark the composer busy AND preempt the low-priority artifact
     * visualizer so it yields the shared, serial model immediately (its in-flight inference is aborted, its
     * dirty target kept, and it retries the latest artifact once the turn is done). The visualizer is only
     * touched when it already exists — we never create one here just to preempt it.
     */
    private void beginAgentTurn() {
        setAgentTurnInFlight(true);
        com.aresstack.askai.research.visualize.LazyArtifactVisualizer visualizer = artifactVisualizer;
        if (visualizer != null) {
            visualizer.preempt();
        }
    }
    /** The narration seam: all conversational milestone texts; replaceable by an LLM-backed narrator. */
    private final ResearchNarrator narrator;
    /** The consultative scoping dialog (productive mode). */
    private final ScopingConversation scoping;
    /** Optional warm phrasing (LLM): set once by the factory when the toggle is on and the port exists. */
    private com.aresstack.askai.research.agent.narration.NarrationCoordinator narration;
    private ResearchScheduler narrationScheduler;
    private static final long NARRATION_TIMEOUT_MILLIS = 6000L;

    /**
     * Enable LLM narration: milestone texts go through the coordinator (thought bubble → validated warm
     * text, static fallback on timeout/violation). Without this call every text stays static — identical
     * visible behavior, that is the contract.
     */
    public void configureNarration(com.aresstack.askai.research.agent.narration.AsyncNarrator asyncNarrator) {
        if (asyncNarrator == null || narration != null) {
            return;
        }
        narrationScheduler = new com.aresstack.askai.research.backend.RealResearchScheduler();
        narration = new com.aresstack.askai.research.agent.narration.NarrationCoordinator(
                asyncNarrator, new com.aresstack.askai.research.agent.narration.NarrationValidator(),
                sink, uiExecutor, narrationScheduler, NARRATION_TIMEOUT_MILLIS);
    }

    /** Milestone text through the narration lifecycle; without narration exactly {@link #sayAsAgent}. */
    private void narrateAsAgent(String kind, String fallbackText) {
        if (narration == null) {
            sayAsAgent(fallbackText);
            return;
        }
        narration.narrate(new com.aresstack.askai.research.agent.narration.NarrationRequest(
                        "narration-" + kind + "-" + playbookMessageIds.incrementAndGet(),
                        playbook.narratorThinking(), fallbackText),
                new com.aresstack.askai.research.agent.narration.NarrationCoordinator.Presenter() {
                    public void present(String text) {
                        sayAsAgent(text);
                    }
                });
    }

    /** The situation moved on: in-flight narrations are stale (bubbles close silently, model freed). */
    private void invalidateNarration() {
        if (narration != null) {
            narration.invalidate();
        }
    }

    private final java.util.concurrent.atomic.AtomicLong playbookMessageIds =
            new java.util.concurrent.atomic.AtomicLong();

    /**
     * ADR 0001 lane F: the user's decision on the orchestrated run ({@code "approve"} or {@code "stop"}),
     * sent as a typed service command on a foreground turn, so the busy state and Stop cover the run.
     */
    public void submitOrchestratorDecision(String decision) {
        if (handle == null || productiveResources == null || productiveResources.isClosed()) {
            return;
        }
        String type = "stop".equals(decision) ? "orchestrator_stop" : "orchestrator_approve";
        technicalLog("orchestrator decision by the user: " + decision);
        beginAgentTurn();
        backend.submitPrompt(handle, new ResearchPrompt(
                com.aresstack.askai.research.search.ResearchServiceCommandWire.orchestrator(type, ""), ""));
    }

    /** The project's orchestrated run as persisted, or {@code null} (none, or no productive project). */
    public com.aresstack.askai.research.runtime.orchestrator.OrchestratorRun currentOrchestratorRun() {
        if (productiveResources == null || productiveResources.getProjectContext() == null) {
            return null;
        }
        try {
            return new com.aresstack.askai.research.store.ProjectTaskBoard(productiveResources.getProjectContext())
                    .currentRun();
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    public void submitPrompt(String text, String activeSectionId) {
        if (handle == null) {
            return;
        }
        if (productiveResources != null && !productiveResources.isClosed()) {
            // Productive mode: the model-backed TeamAgent LEADS the whole conversation (greeting, scoping,
            // outline proposal, meta questions). Forward the user's text to the runtime agent; its reply
            // returns as an assistant message and any validated proposal returns as a SCOPE_PROPOSAL the host
            // executes. The host no longer runs ScopingConversation or the playbook greeting/outline here —
            // those stay for FAKE mode and the legacy tests only.
            // The productive in-process agent cannot echo the user's OWN message back, so show it in the shared chat
            // here (right-aligned, by role) before the agent replies — otherwise the user's turn is invisible.
            // Gate 6 contract: while a concept conflict is PENDING, the host OWNS the next
            // unambiguous yes/no — "Ja." never reaches the model (which once answered it with
            // prose and a false "vorgenommen" claim while the card stayed put).
            if (hasOpenConflict()) {
                Boolean agrees = ConflictAnswerInterpreter.interpret(text);
                if (agrees != null) {
                    echoUserMessage(text);
                    resolvePendingConflictFromHost(agrees.booleanValue());
                    return;
                }
            } else if (staleDecisionTombstone != null) {
                // The transcript's last decision question is STALE (its conflict died with a
                // raw save/restore). A bare yes/no gets the deterministic host answer; any
                // substantive turn supersedes the tombstone silently.
                String stale = staleDecisionTombstone;
                staleDecisionTombstone = null;
                Boolean agrees = ConflictAnswerInterpreter.interpret(text);
                if (agrees != null) {
                    echoUserMessage(text);
                    technicalLog("stale conflict confirmation -> deterministic host answer "
                            + "(no concept mutation)");
                    boolean plural = stale.contains(", ");
                    sayAsAgent(playbook.isGerman()
                            ? "Die vorherige Rückfrage ist durch die manuelle Änderung "
                                    + "veraltet. Am Konzept wurde nichts verändert. „" + stale
                                    + "“ " + (plural ? "bleiben" : "bleibt")
                                    + " ausgeschlossen und wird in der Konzeptprojektion "
                                    + "unterdrückt."
                            : "The earlier question is outdated after the manual change. "
                                    + "Nothing in the concept was changed. \"" + stale + "\" "
                                    + (plural ? "remain" : "remains") + " excluded and "
                                    + "suppressed in the concept projection.");
                    fireStateChanged();
                    return;
                }
                technicalLog("stale decision tombstone superseded by a substantive turn");
            }
            echoUserMessage(text);
            startScopingOnFirstMessage();
            // KISS (live-gate 4): the mission is bookkeeping, not intelligence — the user's
            // FIRST message IS the mission until they restate it. No model turn ever begs
            // for setMission again.
            recordMissionIfAbsent(text);
            refreshConversationPolicy(); // once per user turn, before the fence carries it
            publishScopeFence(); // authoritative scope FIRST, then the turn that may change it
            if (boundaryQuestionPending) {
                // Fix 1: the fence just carried the question one last time — THIS user turn is
                // its answer. From the next turn on it is no longer open boundary work.
                boundaryQuestionPending = false;
                lastScopeCheckQuestion = "";
                technicalLog("[scope-check] boundary question consumed by this user turn");
            }
            beginAgentTurn(); // busy + preempt visualizer; cleared by the turn's terminal event
            backend.submitPrompt(handle, new ResearchPrompt(text, activeSectionId));
            return;
        }
        // FAKE mode: host-side explainability + the deterministic backend, unchanged. Meta questions are
        // answered from the playbook + live state, in plain language — never with internal identifiers.
        String phaseDescription = narrator.describePhase(state.getPhaseId(), state.getStateId(),
                !scoping.getQuestion().isEmpty());
        String explanation = narrator.explainOrNull(text, phaseDescription);
        if (explanation != null) {
            narrateAsAgent("explain", explanation);
            return;
        }
        backend.submitPrompt(handle, new ResearchPrompt(text, activeSectionId));
    }

    /**
     * Execute a VALIDATED scope proposal from the runtime TeamAgent. The host is the only command authority:
     * a proposal only applies while still SCOPING (else the state moved on and it is dropped), and the commit
     * + {@link #autoAdvanceTowardsResearch()} only issue transitions the state machine actually allows. The
     * concept + outline are built deterministically FROM the model-confirmed scope — no host-side dialogue.
     * The model never confirms its own scope: this is triggered only by the host executing the proposal.
     */
    private void handleScopeProposal(String question, String aspectsBlock) {
        if (productiveResources == null || productiveResources.isClosed() || handle == null) {
            return;
        }
        if (!com.aresstack.askai.research.state.oo.ResearchStateIds.SCOPING
                .equals(productiveResources.currentState().getPhaseId())) {
            return; // a scope proposal only applies during scoping; the host state has moved on
        }
        if (question == null || question.trim().isEmpty()) {
            return;
        }
        java.util.List<String> aspects = new ArrayList<String>();
        if (aspectsBlock != null && !aspectsBlock.isEmpty()) {
            for (String part : aspectsBlock.split("\n")) {
                if (!part.trim().isEmpty()) {
                    aspects.add(part.trim());
                }
            }
        }
        ScopingConversation built = new ScopingConversation();
        built.restoreCompleted(question.trim(), aspects);
        ResearchScopeCommitService.ScopeCommitResult commit =
                new ResearchScopeCommitService(productiveResources.getProjectContext())
                        .commit(new ConfirmedResearchScope(question.trim(), aspects,
                                built.buildConceptMarkdown(), built.buildOutlineMarkdown()));
        if (!commit.isSuccess()) {
            sayAsAgent(playbook.scopeCommitFailed(commit.getStatus() + ": " + commit.getDetail()));
            return;
        }
        researchQuestion = question.trim();
        // C5: no outline approval gate anymore — a committed scope auto-advances STRAIGHT into
        // RESEARCH/running, so the stored question must start the research turn HERE (previously the
        // outline-gate approval triggered it).
        autoAdvanceTowardsResearch();
        maybeStartResearchTurn();
    }

    /**
     * The model-backed greeting was delivered successfully: advance the scope state ONE step (SCOPING/NEW →
     * SCOPING/RUNNING via START). The greeting depends only on the state, so after this a restart sees a
     * non-fresh state and never greets again (the persisted chat shows the prior greeting instead). Idempotent
     * and only meaningful while still at SCOPING/NEW.
     */
    /**
     * SCOPING/NEW → SCOPING/RUNNING when the user sends the first message of a fresh chat — synchronously,
     * BEFORE the turn reaches the runtime, so the turn's concept writes (bound to SCOPING/running) are allowed
     * and the runtime answers the topic directly. Also the handler of a runtime's GREETING_DONE signal.
     */
    private void startScopingOnFirstMessage() {
        if (productiveResources == null || productiveResources.isClosed()) {
            return;
        }
        com.aresstack.askai.research.state.oo.ResearchStateMemento current =
                productiveResources.currentState();
        if (com.aresstack.askai.research.state.oo.ResearchStateIds.SCOPING.equals(current.getPhaseId())
                && com.aresstack.askai.research.state.oo.ResearchStateIds.NEW.equals(current.getStateId())
                && currentAllowedCommands().contains(ResearchCommandType.START)) {
            dispatch(ResearchCommandType.START, null);
        }
    }

    /**
     * Show the user's OWN turn in the shared chat (right-aligned, by role) — the productive in-process agent never
     * echoes it back. Empty bootstrap turns carry no text and are ignored, so no blank user bubble appears.
     */
    private void echoUserMessage(final String text) {
        if (sink == null || text == null || text.trim().isEmpty()) {
            return;
        }
        // The id is minted HERE so the journal attributes exactly the message the host persists.
        final String messageId = messageIds.next("user");
        attributeToCurrentPhase(messageId);
        uiExecutor.execute(new Runnable() {
            public void run() {
                sink.appendUserMessage(messageId, text);
            }
        });
    }


    /** An agent utterance from the playbook/dialog, routed through the shared sink on the UI thread. */
    private void sayAsAgent(final String text) {
        if (sink == null) {
            return;
        }
        final String messageId = messageIds.next("playbook");
        attributeToCurrentPhase(messageId);
        uiExecutor.execute(new Runnable() {
            public void run() {
                sink.appendAssistantMessage(messageId, text);
            }
        });
    }

    /**
     * Restore the persisted research assignment of this project, if any: the question and the
     * confirmed focus areas come back typed, the scoping dialog is marked complete — a restored
     * session never repeats the scoping ceremony.
     */
    private boolean restoreProjectMetadata() {
        if (productiveResources == null) {
            return false;
        }
        com.aresstack.askai.research.store.MetadataLoadResult loadResult =
                productiveResources.getProjectContext().getMetadataStore()
                        .load(productiveResources.getProjectContext().getProjectId());
        if (loadResult.getStatus()
                != com.aresstack.askai.research.store.MetadataLoadResult.Status.LOADED
                || !loadResult.getMetadata().hasResearchQuestion()) {
            // MISSING is a fresh project; damaged metadata never reaches this point because the
            // factory blocks the productive start fail-closed.
            return false;
        }
        com.aresstack.askai.research.store.ResearchProjectMetadata metadata =
                loadResult.getMetadata();
        researchQuestion = metadata.getResearchQuestion();
        scoping.restoreCompleted(metadata.getResearchQuestion(),
                metadata.getConfirmedFocusAreas());
        return true;
    }

    /**
     * Advance the productive state machine through the transitions that need NO human approval
     * (START, SUBMIT_SCOPE, PROPOSE_OUTLINE, START_RESEARCH). At an approval gate the machine stops and
     * the approval is surfaced in the chat — the user decides; phase rules stay in the machine.
     */
    private void autoAdvanceTowardsResearch() {
        for (int guard = 0; guard < 8; guard++) {
            com.aresstack.askai.research.state.oo.ResearchStateMemento memento =
                    productiveResources.currentState();
            String phase = memento.getPhaseId();
            String stateId = memento.getStateId();
            ResearchCommandType next = null;
            if (com.aresstack.askai.research.state.oo.ResearchStateIds.SCOPING.equals(phase)
                    && com.aresstack.askai.research.state.oo.ResearchStateIds.NEW.equals(stateId)) {
                next = ResearchCommandType.START;
            } else if (com.aresstack.askai.research.state.oo.ResearchStateIds.SCOPING.equals(phase)
                    && com.aresstack.askai.research.state.oo.ResearchStateIds.RUNNING.equals(stateId)) {
                next = ResearchCommandType.SUBMIT_SCOPE;
            } else if (com.aresstack.askai.research.state.oo.ResearchStateIds.RESEARCH.equals(phase)
                    && com.aresstack.askai.research.state.oo.ResearchStateIds.WAITING.equals(stateId)) {
                next = ResearchCommandType.START_RESEARCH;
            }
            if (next == null) {
                break;
            }
            if (!dispatch(next, null).isAccepted()) {
                break;
            }
        }
        // Issue #34-style unification: an approval gate shows NO chat card — the red action tags
        // (approve/changes) derive from WAITING_APPROVAL via availableActionTags(); the state-change
        // notifications of the dispatches above already refreshed them.
    }

    // ------------------------------------------------------------------ ResearchSessionCommandPort

    /** Free-form text — the ONLY thing that travels as prompt; structured actions never do. */
    @Override
    public void submitPrompt(String text) {
        submitPrompt(text, "");
    }

    /**
     * Structured user action. Productive mode routes to the session's OWN state machine
     * ({@code ProductiveResearchSessionResources.dispatch} — which also republishes the MCP tool set);
     * FAKE mode routes through the fake backend exactly as before. Never a synthetic chat message,
     * never a silent no-op.
     */
    @Override
    public com.aresstack.askai.research.backend.ResearchCommandDispatchResult dispatch(
            ResearchCommandType command, String argument) {
        if (command == null) {
            return com.aresstack.askai.research.backend.ResearchCommandDispatchResult.of(
                    com.aresstack.askai.research.backend.ResearchCommandDispatchResult
                            .Status.COMMAND_NOT_AVAILABLE, "No command given.");
        }
        if (disposed || (productiveResources != null && productiveResources.isClosed())) {
            return com.aresstack.askai.research.backend.ResearchCommandDispatchResult.of(
                    com.aresstack.askai.research.backend.ResearchCommandDispatchResult
                            .Status.SESSION_CLOSED, "The research session is closed.");
        }
        if (!started || handle == null) {
            return com.aresstack.askai.research.backend.ResearchCommandDispatchResult.of(
                    com.aresstack.askai.research.backend.ResearchCommandDispatchResult
                            .Status.SESSION_NOT_ACTIVE, "The research session is not active yet.");
        }
        if (productiveResources != null) {
            return dispatchProductive(command);
        }
        // FAKE mode: the deterministic backend owns its state machine; availability from the live memento.
        if (!canDispatch(command)) {
            return com.aresstack.askai.research.backend.ResearchCommandDispatchResult.of(
                    com.aresstack.askai.research.backend.ResearchCommandDispatchResult
                            .Status.INVALID_PHASE,
                    "Not allowed in " + state.getPhaseId() + "/" + state.getStateId() + ".");
        }
        backend.executeCommand(handle, command);
        return com.aresstack.askai.research.backend.ResearchCommandDispatchResult.accepted();
    }

    private com.aresstack.askai.research.backend.ResearchCommandDispatchResult dispatchProductive(
            ResearchCommandType command) {
        boolean allowed = stateFactory
                .restore(productiveResources.currentState())
                .getCurrentState().getAllowedCommands().contains(command);
        try {
            com.aresstack.askai.research.state.oo.ResearchStateTransitionResult result =
                    productiveResources.dispatch(command);
            if (!result.isAccepted()) {
                return com.aresstack.askai.research.backend.ResearchCommandDispatchResult.of(
                        allowed ? com.aresstack.askai.research.backend.ResearchCommandDispatchResult
                                        .Status.DISPATCH_FAILED
                                : com.aresstack.askai.research.backend.ResearchCommandDispatchResult
                                        .Status.INVALID_PHASE,
                        result.getRejectionReason());
            }
            // PAUSE/CANCEL additionally stop the agent's running turn (transport concern, not state
            // logic) — off the EDT: writing to a busy agent's transport must never freeze the UI.
            if (command == ResearchCommandType.PAUSE || command == ResearchCommandType.CANCEL) {
                final ResearchSessionHandle cancelHandle = handle;
                Thread canceller = new Thread(new Runnable() {
                    public void run() {
                        try {
                            backend.cancel(cancelHandle);
                        } catch (RuntimeException ignored) {
                        }
                    }
                }, "research-turn-cancel");
                canceller.setDaemon(true);
                canceller.start();
            }
            final com.aresstack.askai.research.state.oo.ResearchStateMemento next =
                    productiveResources.currentState();
            uiExecutor.execute(new Runnable() {
                public void run() {
                    state = next; // mirror the single truth into the view model, then notify observers
                    revision = next.getRevision();
                    invalidateNarration(); // in-flight warm texts belong to the previous situation
                    fireStateChanged();
                }
            });
            return com.aresstack.askai.research.backend.ResearchCommandDispatchResult.accepted();
        } catch (RuntimeException ex) {
            return com.aresstack.askai.research.backend.ResearchCommandDispatchResult.of(
                    com.aresstack.askai.research.backend.ResearchCommandDispatchResult
                            .Status.DISPATCH_FAILED, ex.getMessage() == null ? "dispatch failed"
                            : ex.getMessage());
        }
    }

    public void approveCurrent() {
        if (productiveResources != null) {
            // The machine knows WHICH approval fits the phase; the UI never re-encodes phase rules.
            ResearchCommandType approve = firstAllowedWithPrefix("APPROVE_");
            if (approve != null && dispatch(approve, null).isAccepted()) {
                // Continue AUTOMATICALLY with the stored research question — the user never has to
                // type it a second time. Auto-advance reaches RESEARCH/running, then the question
                // goes to the agent, which starts the autonomous web research.
                autoAdvanceTowardsResearch();
                maybeStartResearchTurn();
                // Present the NEXT decision gate's buttons (e.g. evidence -> draft): a gate reached by an
                // approval must never be a dead end. Working/terminal states show nothing.
                showRestoredActionsIfAny();
            }
            return;
        }
        String pendingApprovalId = state.getPendingApprovalId();
        if (handle != null && pendingApprovalId != null) {
            backend.approve(handle, pendingApprovalId);
        }
    }

    /** Sends the stored research question to the agent once the state actually reached RUNNING. */
    private void maybeStartResearchTurn() {
        if (productiveResources == null) {
            return;
        }
        if (!researchQuestion.isEmpty() && handle != null
                && com.aresstack.askai.research.state.oo.ResearchStateIds.RUNNING
                        .equals(productiveResources.currentState().getStateId())) {
            beginAgentTurn(); // busy + preempt visualizer; cleared by the turn's terminal event
            backend.submitPrompt(handle, new ResearchPrompt(researchQuestion, ""));
        }
    }

    public void requestChanges(String reason) {
        if (productiveResources != null) {
            ResearchCommandType request = firstAllowedWithPrefix("REQUEST_");
            if (request != null && dispatch(request, reason).isAccepted()) {
                // The resulting state may itself be a decision gate — present its buttons.
                showRestoredActionsIfAny();
            }
            return;
        }
        String pendingApprovalId = state.getPendingApprovalId();
        if (handle != null && pendingApprovalId != null) {
            backend.reject(handle, pendingApprovalId, reason);
        }
    }

    /**
     * The user's explicit, SOLE decision to leave SCOPING: approve the current research brief working copy
     * into an immutable revision and — only when that approval succeeds — dispatch exactly ONE
     * {@code SUBMIT_SCOPE}, so the state machine (the only transition authority) advances SCOPING → OUTLINE.
     * The order is fixed: persist/approve the artifact FIRST, transition only afterwards; a failed or empty
     * approval transitions nothing. This deliberately does NOT chain further phases (no
     * {@link #autoAdvanceTowardsResearch()}): one user click decides exactly one legal workflow transition.
     * No model output, advice, natural-language "weiter", search suggestion or visualizer result may reach
     * this — the scoping button is the only caller.
     * <p>
     * The result is a {@link ScopingApprovalOutcome}, never a bare boolean: a rejected click is NEVER a silent
     * no-op. The gate is re-checked HERE (the button's enabled state is only a snapshot from the last state
     * change — e.g. {@code agentTurnInFlight} can flip to true when a turn starts without a UI refresh), and
     * any non-{@link ScopingApprovalOutcome#SUCCESS} reason is both logged and surfaced to the user.
     */
    public ScopingApprovalOutcome approveScopingBriefAndContinue() {
        final com.aresstack.askai.research.state.oo.ResearchStateMemento snapshot =
                productiveResources != null ? productiveResources.currentState() : state;
        scopeApproveDiag("clicked session=" + Integer.toHexString(System.identityHashCode(this)));
        scopeApproveDiag("phase=" + snapshot.getPhaseId() + " state=" + snapshot.getStateId()
                + " busy=" + agentTurnInFlight + " briefPresent=" + hasNonBlankBrief());
        ScopingApprovalOutcome blocker = scopingApprovalBlocker();
        if (blocker != null) {
            scopeApproveDiag("blocked reason=" + blocker);
            surfaceScopingApprovalProblem(blocker);
            return blocker;
        }
        // 1) Persist/approve the brief artifact when there IS one. It is a by-product of the conversation,
        // not a precondition: an empty brief must never stop the user from leaving scoping.
        if (hasNonBlankBrief()) {
            com.aresstack.askai.research.store.FileResearchBriefStore store = researchBriefStore();
            try {
                scopeApproveDiag("approveResult="
                        + store.approveCurrent(System.currentTimeMillis()).getStatus());
            } catch (RuntimeException approvalFailed) {
                scopeApproveDiag("approveResult=FAILED " + approvalFailed.getClass().getSimpleName());
                surfaceScopingApprovalProblem(ScopingApprovalOutcome.APPROVAL_FAILED);
                return ScopingApprovalOutcome.APPROVAL_FAILED;
            }
        } else {
            scopeApproveDiag("approveResult=SKIPPED (no brief yet — not a precondition)");
        }
        scopeApproveDiag("before=" + snapshot.getPhaseId() + "/" + snapshot.getStateId());
        // 2) Only now the single, explicit transition — never autoAdvanceTowardsResearch(): exactly one step.
        boolean accepted = dispatch(ResearchCommandType.SUBMIT_SCOPE, null).isAccepted();
        scopeApproveDiag("dispatch accepted=" + accepted);
        if (!accepted) {
            surfaceScopingApprovalProblem(ScopingApprovalOutcome.TRANSITION_REJECTED);
            return ScopingApprovalOutcome.TRANSITION_REJECTED;
        }
        com.aresstack.askai.research.state.oo.ResearchStateMemento after =
                productiveResources.currentState();
        scopeApproveDiag("after=" + after.getPhaseId() + "/" + after.getStateId());
        // C5: SUBMIT_SCOPE now lands directly in RESEARCH (no outline gate). The user's ONE decision here IS
        // "finish scoping → research begins"; the technical WAITING→RUNNING step belongs to that same
        // decision, so the click also releases the research turn (with the stored question, when present).
        if (com.aresstack.askai.research.state.oo.ResearchStateIds.RESEARCH.equals(after.getPhaseId())
                && com.aresstack.askai.research.state.oo.ResearchStateIds.WAITING.equals(after.getStateId())
                && dispatch(ResearchCommandType.START_RESEARCH, null).isAccepted()) {
            maybeStartResearchTurn();
        }
        return ScopingApprovalOutcome.SUCCESS;
    }

    /**
     * Whether the explicit "Fragestellung freigeben & weiter" action is currently legal — the button's enabled
     * state. It is exactly {@code scopingApprovalBlocker() == null}, so the enable check and the click check can
     * never drift apart.
     */
    public boolean canApproveScopingBriefAndContinue() {
        return scopingApprovalBlocker() == null;
    }

    /**
     * The concrete reason the scoping-approval action is currently unavailable, or an EMPTY string when it is
     * ready. The UI uses this for the disabled button's tooltip, so a greyed button is never an unexplained
     * dead end (the same plain-language text a rejected click would show).
     */
    /** The plain-language text for a non-success approval outcome (same wording the problem uses). */
    private String scopingApprovalUnavailableReasonFor(ScopingApprovalOutcome outcome) {
        return scopingApprovalProblemText(outcome);
    }

    public String scopingApprovalUnavailableReason() {
        ScopingApprovalOutcome blocker = scopingApprovalBlocker();
        return blocker == null ? "" : scopingApprovalProblemText(blocker);
    }

    // ------------------------------------------------------------------ user-triggered web search (service)

    /**
     * Run a USER-triggered web search — the third interaction kind, wired to the yellow scoping suggestions.
     * This is NOT an agent chat turn and NOT a workflow command: it never calls {@code submitText}, never
     * dispatches a state-machine command, never changes the phase and never starts an agent prompt. It is
     * phase-independent by contract; the yellow tags merely happen to exist only in SCOPING because that is
     * where the agent produces the suggestions, not because the service is gated to that phase.
     */
    public void requestManualWebSearch(String query) {
        if (query == null || query.trim().isEmpty()) {
            return;
        }
        // The request SNAPSHOTS the session language: a live switch never changes a running search.
        com.aresstack.askai.research.search.ManualWebSearchHandle handle = manualWebSearchPort.search(
                new com.aresstack.askai.research.search.ManualWebSearchRequest(query,
                        sessionLanguage.currentLanguage()));
        // Remember the correlation id so inbound events of THIS search render and stale ones are ignored.
        this.activeManualSearchHandle = handle;
        this.activeManualSearchRequestId = handle == null ? null : handle.getRequestId();
        this.activeManualSearchQuery = query.trim();
        System.err.println("[manual-search] host submit requestId="
                + (handle == null ? "none" : handle.getRequestId()) + " queryLen=" + query.trim().length());
    }

    /** Cancel the in-flight user web search, if any; late events of the cancelled run are then ignored. */
    public void cancelManualWebSearch() {
        com.aresstack.askai.research.search.ManualWebSearchHandle handle = activeManualSearchHandle;
        if (handle != null) {
            handle.cancel();
        }
    }

    /** Whether a completed user web search already covered this exact query (normalized) — so it is not re-offered. */
    public boolean wasManuallySearched(String query) {
        return query != null
                && manualSearchedQueries.contains(query.trim().toLowerCase(java.util.Locale.ROOT));
    }

    /**
     * Rebuild the "already searched" set from the PERSISTED sources on restore: each source remembers the user
     * query that found it, so a covered query is never re-suggested even after an app restart. Best-effort.
     */
    private void restoreManualSearchedQueries() {
        com.aresstack.askai.research.sources.ResearchSourceRepository repository = getSourceRepository();
        if (repository == null) {
            return;
        }
        try {
            for (com.aresstack.askai.research.sources.ResearchSourceRecord record
                    : repository.find(com.aresstack.askai.research.sources.SourceQuery.all())) {
                String searched = record.getSearchQuery();
                if (searched != null && !searched.trim().isEmpty()) {
                    manualSearchedQueries.add(searched.trim().toLowerCase(java.util.Locale.ROOT));
                }
            }
        } catch (RuntimeException bestEffort) {
            // a read failure just means no pre-population from disk
        }
    }

    /** Wire the productive manual-web-search service (tests / factory); a null port is ignored. */
    public void setManualWebSearchPort(com.aresstack.askai.research.search.ManualWebSearchPort port) {
        if (port != null) {
            this.manualWebSearchPort = port;
        }
    }

    /**
     * Render a user web search lifecycle event as a transient activity, correlated by requestId so late events
     * of a superseded or cancelled run are ignored. It changes NO phase and NO state — a pure service surface.
     */
    private void applyManualSearch(ResearchBackendEvent event) {
        if (sink == null) {
            return;
        }
        String requestId = event.getTechnicalDetail();
        if (requestId == null || !requestId.equals(activeManualSearchRequestId)) {
            if ("review_finished".equals(event.getTitle()) && postSearchSummaryInFlight) {
                // The single correlation slot was overwritten (a new search/review started) while a
                // review was still in flight. Its end must STILL clear the bubble and release the
                // composer — the alternative is a thinking bubble that never disappears.
                finishPostSearchThinking("");
                setAgentTurnInFlight(false);
                settleReview(com.aresstack.askai.research.domain.search.PostSearchReviewOutcome
                        .fromToken(event.getPublicMessage()));
            }
            // Visible in the diagnostics when the review chain breaks: which event was dropped and why.
            System.err.println("[manual-search] host event " + event.getTitle()
                    + " DROPPED (stale) requestId=" + requestId
                    + " active=" + activeManualSearchRequestId);
            return; // stale/late event from a request the user did not (or no longer) launched
        }
        String activityId = event.getActivityId() != null
                ? event.getActivityId() : "manual-search-" + requestId;
        String subKind = event.getTitle();
        String message = event.getText();
        System.err.println("[manual-search] host event " + subKind + " requestId=" + requestId);
        if ("started".equals(subKind)) {
            // ONE unified, persisted breadcrumb for BOTH entry points (typed /search AND a yellow-suggestion
            // click both funnel through here): a muted italic "Websuche: <query>" line that survives a restart.
            // The transient amber progress card runs alongside it and is ephemeral.
            sink.appendInfoMessage(publish("manual-search-line-" + requestId), message);
            sink.startToolActivity(activityId, "Websuche", message);
            // The composer is BUSY for the whole search: the red Stop button lights up and stops THIS
            // search. Without this, the search ran on the control lane with no visible way to stop it.
            setAgentTurnInFlight(true);
        } else if ("progress".equals(subKind)) {
            sink.updateToolActivity(activityId, "Websuche", message);
        } else if ("completed".equals(subKind)) {
            sink.completeToolActivity(activityId, message);
            // This query is now covered: remember it so the agent's suggestions never re-offer it, and the
            // clicked yellow tag disappears (the accessory filters searched queries) and the list re-arranges.
            String searched = activeManualSearchQuery;
            if (searched != null && !searched.trim().isEmpty()) {
                manualSearchedQueries.add(searched.trim().toLowerCase(java.util.Locale.ROOT));
            }
            stopManualSearchBrowser();
            // The persisted "Websuche: <query>" line gets its comic outcome: a green check.
            // DECORATION ONLY: it must never break the terminal path (mixed dev builds once threw
            // a LinkageError here and swallowed the action offer + composer release below).
            try {
                sink.markInfoStatus(publish("manual-search-line-" + requestId), true, null);
            } catch (RuntimeException | LinkageError decorationOnly) {
                System.err.println("[manual-search] info-status decoration failed: " + decorationOnly);
            }
            setAgentTurnInFlight(false); // the search is over — the composer is the user's again
            // Issue #29: the search is over here — the runtime no longer auto-reviews. When sources were
            // accepted, OFFER the derived AI step as an explicit action instead of running it implicitly.
            activeManualSearchRequestId = null;
            int accepted;
            try {
                accepted = Integer.parseInt(event.getPublicMessage().trim());
            } catch (RuntimeException noCount) {
                accepted = 0;
            }
            if (accepted > 0) {
                // Nothing to remember here: new sources ARE the offer. The action surface re-derives it.
                fireStateChanged();
                // Opt-in convenience (Research Agent settings → General, default OFF): a
                // SUCCESSFUL search runs the same review the "Review new sources" tag triggers —
                // the explicit-action semantics stay, only the click is automated.
                if (com.aresstack.askai.research.host.ResearchRuntimeSettings
                        .loadAutoReviewAfterSearch(hostStateStore)) {
                    requestPostSearchReview();
                }
            }
        } else if ("review_started".equals(subKind)) {
            if (reviewInProgressOn == null) {
                // A review the host did not launch itself (service MCP): pin what it is reviewing now.
                reviewInProgressOn = currentSourceCorpusRevision();
            }
            // The bot is now at the wheel (skimming the new sources, refreshing suggestions): show a thinking
            // bubble AND make the composer BUSY (red, cancellable) exactly like a normal agent turn, so the
            // user both sees the work and can abort it. Cleared by review_finished (always emitted).
            postSearchThinkingId = "post-search-summary-" + requestId;
            postSearchSummaryInFlight = true;
            setAgentTurnInFlight(true);
            // Phase-neutral wording: outside scoping there are no suggestions to refresh, but the
            // summary review runs everywhere.
            sink.startThinking(postSearchThinkingId, "Ich sichte die neuen Quellen …");
        } else if ("review_finished".equals(subKind)) {
            finishPostSearchThinking("");
            setAgentTurnInFlight(false); // release the composer — the review is over (success, failure or cancel)
            activeManualSearchRequestId = null;
            settleReview(com.aresstack.askai.research.domain.search.PostSearchReviewOutcome
                    .fromToken(event.getPublicMessage()));
        } else if ("failed".equals(subKind)) {
            // Both surfaces: close the transient activity AND raise a PERSISTENT, readable problem so the
            // reason does not merely flash away.
            sink.failToolActivity(activityId, message);
            sink.showProblem(publish("manual-search-failed-" + requestId), message);
            // The line's red circling arrow IS the retry: one click repeats exactly this search.
            final String retryQuery = activeManualSearchQuery;
            try {
                sink.markInfoStatus(publish("manual-search-line-" + requestId), false,
                        retryQuery == null || retryQuery.trim().isEmpty() ? null : new Runnable() {
                            public void run() {
                                requestManualWebSearch(retryQuery);
                            }
                        });
            } catch (RuntimeException | LinkageError decorationOnly) {
                System.err.println("[manual-search] info-status decoration failed: " + decorationOnly);
            }
            activeManualSearchRequestId = null;
            finishPostSearchThinking(""); // no summary is coming
            setAgentTurnInFlight(false);
            settleReview(com.aresstack.askai.research.domain.search.PostSearchReviewOutcome.FAILED);
            stopManualSearchBrowser();
        }
    }

    /**
     * What a running review is reviewing. The only volatile part of the review state, and rightly so: a
     * review does not survive the process it runs in. After a restart the material is simply unreviewed
     * again, which is the truth.
     */
    private volatile com.aresstack.askai.research.review.SourceCorpusRevision reviewInProgressOn;
    /** The persisted watermark, read once per session and kept in step with the store. */
    private com.aresstack.askai.research.review.PostSearchReviewLedger reviewLedger;

    /** The project's review bookkeeping, or {@code null} in the in-memory clickdummy. */
    private synchronized com.aresstack.askai.research.store.FilePostSearchReviewStore reviewStore() {
        return productiveResources == null || productiveResources.isClosed()
                ? null : productiveResources.getProjectContext().getPostSearchReviewStore();
    }

    private synchronized com.aresstack.askai.research.review.PostSearchReviewLedger reviewLedger() {
        if (reviewLedger == null) {
            com.aresstack.askai.research.store.FilePostSearchReviewStore store = reviewStore();
            reviewLedger = store == null
                    ? com.aresstack.askai.research.review.PostSearchReviewLedger.INITIAL : store.load();
        }
        return reviewLedger;
    }

    private synchronized void updateReviewLedger(
            com.aresstack.askai.research.review.PostSearchReviewLedger updated) {
        reviewLedger = updated;
        com.aresstack.askai.research.store.FilePostSearchReviewStore store = reviewStore();
        if (store == null) {
            return;
        }
        try {
            store.save(updated);
        } catch (java.io.IOException writeFailed) {
            // A watermark that could not be written means the review will be offered again — visible and
            // harmless. Losing the session over it would not be.
            System.err.println("[manual-search] review watermark not persisted: " + writeFailed.getMessage());
        }
    }

    /** The material of this project as it stands on disk right now. */
    private com.aresstack.askai.research.review.SourceCorpusRevision currentSourceCorpusRevision() {
        if (productiveResources == null || productiveResources.isClosed()) {
            return com.aresstack.askai.research.review.SourceCorpusRevision.EMPTY;
        }
        try {
            return com.aresstack.askai.research.review.SourceCorpusRevision.of(
                    productiveResources.getProjectContext().getSourceRepository()
                            .find(com.aresstack.askai.research.sources.SourceQuery.all()));
        } catch (RuntimeException unreadable) {
            return com.aresstack.askai.research.review.SourceCorpusRevision.EMPTY;
        }
    }

    /**
     * Whether — and how — the post-search review is offered. Derived from the persisted sources and the
     * persisted watermark, so it is the same answer after a restart, after a failure and after a cancel.
     */
    public com.aresstack.askai.research.review.PostSearchReviewStatus postSearchReviewStatus() {
        return reviewLedger().statusFor(currentSourceCorpusRevision(), reviewInProgressOn);
    }

    /** A retry says so: offering "Neue Quellen auswerten" again would hide that the last attempt failed. */
    private String reviewLabel(boolean retry) {
        if (playbook.isGerman()) {
            return retry ? "Auswertung erneut versuchen" : "Neue Quellen auswerten";
        }
        return retry ? "Try the evaluation again" : "Review new sources";
    }

    private String reviewExplanation(boolean retry) {
        if (playbook.isGerman()) {
            return retry
                    ? "Die letzte Auswertung dieser Quellen ist fehlgeschlagen — noch einmal versuchen"
                    : "Die neuen Quellen kurz sichten und zusammenfassen lassen";
        }
        return retry
                ? "The last evaluation of these sources failed — try it again"
                : "Have the agent skim and summarize the new sources";
    }

    /**
     * EXPLICIT user action (issue #29): let the TeamAgent review the accepted sources (summary; suggestion
     * refresh stays scoping-only in the runtime). Runs as a service command — asynchronous AFTER the explicit
     * request, bracketed by the same review_started/review_finished lifecycle as before.
     */
    /**
     * A review ended: record WHAT it means for the project. Only a successful review moves the watermark;
     * a failed or cancelled one records the material it failed on, so the action comes back as a retry
     * instead of disappearing with the sources still unread.
     */
    private void settleReview(com.aresstack.askai.research.domain.search.PostSearchReviewOutcome outcome) {
        com.aresstack.askai.research.review.SourceCorpusRevision target = reviewInProgressOn;
        reviewInProgressOn = null;
        if (target == null) {
            return; // no review of ours was running
        }
        System.err.println("[manual-search] review settled outcome=" + outcome + " target=" + target);
        updateReviewLedger(outcome.isSuccess()
                ? reviewLedger().reviewed(target)
                : reviewLedger().failed(target));
        fireStateChanged();
    }

    public void requestPostSearchReview() {
        if (handle == null || disposed
                || (productiveResources != null && productiveResources.isClosed())) {
            return;
        }
        // Pin the material NOW: whatever the review reports later is a statement about exactly these
        // sources, not about whatever has arrived by the time it answers. The pin travels WITH the
        // request, so the agent reads the same set the ledger will mark reviewed.
        com.aresstack.askai.research.review.SourceCorpusRevision target = currentSourceCorpusRevision();
        reviewInProgressOn = target;
        String reviewRequestId = "review-" + java.util.UUID.randomUUID();
        activeManualSearchRequestId = reviewRequestId; // the review_* events correlate against this id
        backend.submitServiceCommand(handle,
                com.aresstack.askai.research.search.ResearchServiceCommandWire
                        .reviewSources(reviewRequestId, target.getLatestCapturedAt()));
    }

    /** End the post-search thinking bubble + release the composer (red send button), if one is in flight. */
    private void finishPostSearchThinking(String summary) {
        if (!postSearchSummaryInFlight) {
            return;
        }
        postSearchSummaryInFlight = false;
        if (postSearchThinkingId != null && sink != null) {
            try {
                sink.finishThinking(postSearchThinkingId, summary == null ? "" : summary);
            } catch (RuntimeException bubbleAlreadyGone) {
                // The transcript row may already be gone (cleared transcript, superseded id). OUR
                // state must clear regardless — a throw here once wedged the bubble and the red
                // Stop button forever, because the callers' release lines never ran.
            }
        }
        postSearchThinkingId = null;
    }

    /**
     * A user web search has TERMINATED (completed/failed): stop the host-owned Playwright browser runtime so
     * it never lingers open — the same lifecycle the autonomous run gets on RUN_OUTCOME. It is only called on
     * terminal events (a CAPTCHA/challenge wait emits {@code progress}, never {@code completed}/{@code failed}),
     * so an in-flight manual challenge is never cut off.
     */
    private void stopManualSearchBrowser() {
        if (productiveResources != null) {
            // Breadcrumb: a browser that stays open after 'completed' means this line never printed
            // (event lost) or stopBrowserPhase failed right after it (stack trace follows).
            System.err.println("[manual-search] host stopping browser phase (terminal event)");
            productiveResources.stopBrowserPhase();
        }
    }

    /**
     * The single source of truth for the scoping-approval gate: returns the concrete blocking reason, or
     * {@code null} when the action is legal. STRUCTURAL reasons only — a productive session that is still
     * open, no foreground agent turn in flight, and a state machine that allows SUBMIT_SCOPE right now.
     * Nothing about the CONTENT of the scope may appear here.
     */
    private ScopingApprovalOutcome scopingApprovalBlocker() {
        if (disposed || productiveResources == null || productiveResources.isClosed() || handle == null) {
            return ScopingApprovalOutcome.SESSION_INACTIVE;
        }
        if (agentTurnInFlight) {
            return ScopingApprovalOutcome.BUSY; // a foreground agent turn is in flight
        }
        com.aresstack.askai.research.state.oo.ResearchStateMemento memento =
                productiveResources.currentState();
        if (!com.aresstack.askai.research.state.oo.ResearchStateIds.SCOPING.equals(memento.getPhaseId())
                || !currentAllowedCommands().contains(ResearchCommandType.SUBMIT_SCOPE)) {
            return ScopingApprovalOutcome.WRONG_PHASE;
        }
        // NO content gate. The user owns the state machine: when the state machine allows SUBMIT_SCOPE, the
        // user may submit — on the very first turn and with a vague or half-finished scope. The old check
        // required a non-blank research BRIEF ("Es liegt noch keine Fragestellung vor"), which is doubly
        // wrong now: the scope is a ResearchScopeDraft, not a single question, and an assistant's opinion
        // about completeness may advise but never block. Readiness, if it ever exists, stays advisory.
        return null; // ready
    }

    private boolean hasNonBlankBrief() {
        com.aresstack.askai.research.store.FileResearchBriefStore store = researchBriefStore();
        return store != null && !store.effectiveContent().trim().isEmpty();
    }

    /** Make a rejected scoping approval VISIBLE (never a silent no-op), with a concrete plain-language reason. */
    private void surfaceScopingApprovalProblem(final ScopingApprovalOutcome outcome) {
        if (sink == null) {
            return;
        }
        final String message = scopingApprovalProblemText(outcome);
        uiExecutor.execute(new Runnable() {
            public void run() {
                sink.showProblem(publish("scope-approve-" + outcome), message);
            }
        });
    }

    private static String scopingApprovalProblemText(ScopingApprovalOutcome outcome) {
        String reason;
        switch (outcome) {
            case BUSY:
                reason = "Es läuft gerade eine Agent-Antwort. Bitte einen Moment warten.";
                break;
            case MISSING_BRIEF:
                reason = "Es liegt noch keine Fragestellung vor.";
                break;
            case WRONG_PHASE:
                reason = "In dieser Phase ist der Wechsel nicht möglich.";
                break;
            case APPROVAL_FAILED:
                reason = "Der Brief konnte nicht gespeichert werden.";
                break;
            case TRANSITION_REJECTED:
                reason = "Phasenwechsel wurde abgelehnt.";
                break;
            case SESSION_INACTIVE:
                reason = "Die Sitzung ist nicht aktiv.";
                break;
            default:
                reason = "Unbekannter Grund.";
                break;
        }
        return "Fragestellung konnte nicht freigegeben werden: " + reason;
    }

    /** Compact, non-sensitive trace of the scoping-approval click path (runWithDevPlugins console). */
    private static void scopeApproveDiag(String message) {
        System.err.println("[scope-approve] " + message);
    }

    public void pause() {
        if (productiveResources != null) {
            setAgentTurnInFlight(false);
            if (dispatch(ResearchCommandType.PAUSE, null).isAccepted()) {
                // A visible confirmation — and the sink event makes the composer re-read availability.
                narrateAsAgent("paused", narrator.pausedNotice());
            }
            return;
        } else if (handle != null) {
            backend.pause(handle);
        }
    }

    public void resume() {
        if (productiveResources != null) {
            dispatch(ResearchCommandType.RESUME, null);
        } else if (handle != null) {
            backend.resume(handle);
        }
    }

    public void cancel() {
        if (postSearchSummaryInFlight) {
            // The bot's post-search review is the turn in flight. It runs inside the manual-search operation,
            // so cancelling THAT aborts the review's model call (via the runtime @Cancel → cancelInFlight).
            // Release the composer immediately; the runtime still emits review_finished to settle tracking.
            cancelManualWebSearch();
            finishPostSearchThinking("");
            setAgentTurnInFlight(false);
            return;
        }
        if (productiveResources != null) {
            setAgentTurnInFlight(false);
            dispatch(ResearchCommandType.CANCEL, null);
        } else if (handle != null) {
            backend.cancel(handle);
        }
    }

    public boolean canDispatch(ResearchCommandType type) {
        return handle != null && currentAllowedCommands().contains(type);
    }

    /** The live allowed set — productive mode reads the authoritative resources state directly. */
    public java.util.Set<ResearchCommandType> currentAllowedCommands() {
        com.aresstack.askai.research.state.oo.ResearchStateMemento memento =
                productiveResources != null ? productiveResources.currentState() : state;
        return stateFactory.restore(memento).getCurrentState().getAllowedCommands();
    }

    private ResearchCommandType firstAllowedWithPrefix(String prefix) {
        for (ResearchCommandType type : currentAllowedCommands()) {
            if (type.name().startsWith(prefix)) {
                return type;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ event intake (backend thread → UI)

    @Override
    public void onEvent(final ResearchBackendEvent event) {
        if (disposed || !started || !request.getSessionId().equals(event.getSessionId())) {
            return;
        }
        if (event.getType() == com.aresstack.askai.research.backend.ResearchBackendEventType
                .PROBE_GENERATION) {
            // Z3b-3: an INTERNAL transport response — deliver it RIGHT HERE on the backend
            // callback thread. The waiting generator blocks synchronously on its queue; routing
            // this through the uiExecutor would deadlock a sweep that (wrongly) runs on the EDT
            // into its full timeout. Nothing about this payload is UI.
            com.aresstack.askai.research.scope.BackendScopeProbeGenerator current =
                    activeProbeGenerator;
            if (current != null) {
                current.deliver(event.getTitle(), event.getText());
            }
            return; // no UI bookkeeping — the payload never renders anywhere
        }
        if (event.getType() == com.aresstack.askai.research.backend.ResearchBackendEventType
                .ADVICE_DECISION) {
            // Z4b: same internal transport rule as PROBE_GENERATION — backend thread, never EDT.
            com.aresstack.askai.research.scope.BackendScopeAdviceChooser chooser =
                    activeAdviceChooser;
            if (chooser != null) {
                chooser.deliver(event.getTitle(), event.getText());
            }
            return;
        }
        uiExecutor.execute(new Runnable() {
            public void run() {
                applyEvent(event);
            }
        });
    }

    private void applyEvent(ResearchBackendEvent event) {
        if (disposed || !started || !request.getSessionId().equals(event.getSessionId())) {
            return;
        }
        if (event.getSequenceNumber() <= lastSequence) {
            return; // stale or duplicate delivery
        }
        lastSequence = event.getSequenceNumber();
        revision = event.getRevision();
        switch (event.getType()) {
            case SESSION_STATE_CHANGED:
                if (event.getStateMemento() != null) {
                    state = event.getStateMemento(); // the exact live truth: phase/state/continuation/approvalId
                }
                String stateId = state.getStateId();
                if (!com.aresstack.askai.research.state.oo.ResearchStateIds.BLOCKED.equals(stateId)
                        && !com.aresstack.askai.research.state.oo.ResearchStateIds.FAILED.equals(stateId)) {
                    problemMessage = "";
                }
                break;
            case APPROVAL_REQUESTED:
                // The pending approval id already lives in the memento; this only drives the chat approval bubble.
                sink.requestApproval(publish(event.getApprovalId()), event.getText());
                break;
            case ACTIVITY:
                applyActivity(event);
                break;
            case USER_MESSAGE:
                sink.appendUserMessage(publish(event.getEventId()), event.getText());
                break;
            case COMPLETED:
                // The technical turn terminal is INVISIBLE (point 9): it only frees the composer and
                // closes a still-open progress card. The user-facing message is the RUN_OUTCOME card.
                setAgentTurnInFlight(false);
                // Always route through the sink so the composer re-reads its availability, even when no
                // progress card exists (the sink refresh runs also for unknown activity ids).
                sink.completeToolActivity(currentRunActivityId != null
                        ? currentRunActivityId : "research-turn", "");
                currentRunActivityId = null;
                break;
            case ASSISTANT_MESSAGE:
                // If the bot's post-search summary is what is arriving, collapse the thinking bubble first so
                // the summary renders as the assistant turn and the composer is released.
                if (postSearchSummaryInFlight) {
                    System.err.println("[manual-search] host assistant summary received requestId="
                            + activeManualSearchRequestId);
                }
                finishPostSearchThinking("");
                sink.appendAssistantMessage(publish(event.getEventId()), event.getText());
                break;
            case RUN_LOG:
                applyRunLog(event);
                break;
            case RUN_PROGRESS:
                applyRunProgress(event);
                break;
            case RUN_OUTCOME:
                applyRunOutcome(event);
                // The research/browsing run ended: stop the browser phase (async, off-EDT) but keep the
                // TeamAgent alive; a later research run lazily starts a fresh browser generation.
                if (productiveResources != null) {
                    productiveResources.stopBrowserPhase();
                }
                break;
            case USER_ATTENTION:
                applyUserAttention(event);
                break;
            case SCOPE_PROPOSAL:
                handleScopeProposal(event.getText(), event.getTechnicalDetail());
                break;
            case GREETING_DONE:
                startScopingOnFirstMessage();
                break;
            case SCOPE_UPDATE:
                applyScopeUpdate(event.getText());
                break;
            case SCOPE_UPDATE_REJECTED:
                // The answer is fine, the scope proposal was not: say so instead of leaving the user to
                // believe a change was recorded.
                technicalLog("scope update -> REJECTED (runtime validation): " + event.getText());
                reportScopeProblem("Der Rechercheumfang wurde NICHT aktualisiert (fehlerhafter Vorschlag: "
                        + event.getText() + "). Die Antwort selbst bleibt gültig.");
                break;
            case SCOPING_PROJECTION:
                // Display-only support content for the scoping workspace: keep only the LATEST projection
                // (a later turn replaces it — the chat keeps every turn, this panel shows the current state).
                // It moves nothing and writes no artifact; fireStateChanged() lets the workspace re-read it.
                latestScopingProjection = mergeOfferedTags(event.getScopingProjection());
                persistScopingProjection(latestScopingProjection); // survive a restart (display-only working state)
                finishPostSearchThinking(""); // refreshed suggestions are the last step of the summary
                break;
            case RESEARCH_BRIEF:
                // The phase artifact: persist the brief to its working copy (one path, off the EDT). No
                // approval revision, no phase transition — the "Fragestellung" view re-reads the store.
                persistResearchBrief(event.getTitle(), event.getText());
                break;
            case MANUAL_SEARCH:
                // A USER-triggered web search lifecycle: a transient activity only — no phase, no state change.
                applyManualSearch(event);
                break;
            case PROBE_GENERATION:
                // Handled directly on the backend callback thread in onEvent (a blocked sweep
                // waits on it) — nothing to do on the UI side.
                break;
            case BLOCKED:
            case ERROR:
                setAgentTurnInFlight(false); // a failed turn must not wedge the composer
                finishPostSearchThinking(""); // never leave the post-search bubble/red send button stuck
                problemMessage = event.getPublicMessage();
                // Show the WHY, not just the what: the technical detail (exception phase + reason,
                // never secrets) is the only way anyone can act on a start failure.
                String detail = event.getTechnicalDetail();
                sink.showProblem(publish(event.getEventId()), detail == null || detail.isEmpty()
                        ? event.getPublicMessage()
                        : event.getPublicMessage() + "\n" + detail);
                break;
            default:
                break; // SOURCE_ADDED/FINDING_ADDED/OUTLINE_CHANGED/PROBLEM_REPORTED handled by artifact views
        }
        fireStateChanged(); // the State visualization re-reads the domain snapshot
    }

    // ------------------------------------------------------------------ state visualization support

    /** The latest scoping assistant projection (search suggestions), or {@code null} if none yet. */
    public com.aresstack.askai.research.backend.ScopingAssistantUpdate latestScopingProjection() {
        return latestScopingProjection;
    }

    /** File-backed store of the latest scoping projection so the yellow tags survive a restart. Lazy; null in the clickdummy. */
    private com.aresstack.askai.research.store.FileScopingProjectionStore scopingProjectionStore;

    /** The per-project store for the latest scoping projection (yellow suggestion tags); {@code null} in the clickdummy. */
    private synchronized com.aresstack.askai.research.store.FileScopingProjectionStore scopingProjectionStore() {
        if (scopingProjectionStore == null && productiveResources != null && !productiveResources.isClosed()) {
            java.io.File projectDir = productiveResources.getProjectContext().getProjectDirectory();
            scopingProjectionStore = new com.aresstack.askai.research.store.FileScopingProjectionStore(
                    new java.io.File(projectDir, "scoping"));
        }
        return scopingProjectionStore;
    }

    /**
     * Restore the persisted scoping projection (yellow tags) after a restart — the transcript comes back from
     * history but this display-only state was previously in-memory only. Only fills a still-empty projection so
     * a live one is never clobbered; already-searched suggestions are filtered by {@link #wasManuallySearched}.
     */
    private void persistScopingProjection(
            com.aresstack.askai.research.backend.ScopingAssistantUpdate projection) {
        com.aresstack.askai.research.store.FileScopingProjectionStore store = scopingProjectionStore();
        if (store != null && projection != null) {
            store.save(projection);
        }
    }

    private void restoreScopingProjection() {
        if (latestScopingProjection != null) {
            return;
        }
        com.aresstack.askai.research.store.FileScopingProjectionStore store = scopingProjectionStore();
        if (store == null) {
            return;
        }
        com.aresstack.askai.research.backend.ScopingAssistantUpdate restored = store.load();
        if (restored != null) {
            latestScopingProjection = restored;
            fireStateChanged(); // let a mounted scoping accessory re-read + render the tags
        }
    }

    /**
     * The file-backed research brief store bound to this session's project directory — the SINGLE source of
     * truth for the brief. {@code null} in the in-memory clickdummy. The "Fragestellung" view reads it.
     */
    public synchronized com.aresstack.askai.research.store.FileResearchBriefStore researchBriefStore() {
        if (researchBriefStore == null && productiveResources != null && !productiveResources.isClosed()) {
            java.io.File projectDir = productiveResources.getProjectContext().getProjectDirectory();
            researchBriefStore = new com.aresstack.askai.research.store.FileResearchBriefStore(
                    new java.io.File(projectDir, "brief"));
        }
        return researchBriefStore;
    }

    /** The blacklist truth for DISPLAY (tree editor dims suppressed cards subtree-deep). */
    public java.util.List<String> blacklistTermsForDisplay() {
        return currentBlacklistTerms();
    }

    /**
     * One technical-log line for a TREE-EDITOR gesture (ratified D4): the USER_UI origin
     * stays distinguishable — a mouse gesture must never read like an agent tool call.
     */
    public void logConceptUiAction(String line) {
        technicalLog("concept USER_UI -> " + line);
    }

    /**
     * The session's ONE Konzeptpapier branch-edit service (shared with the agent MCP tools — the
     * handle registry lives in it), or {@code null} in the in-memory clickdummy. The Konzept tab
     * and the mindmap overlay render from its atomic snapshots.
     */
    public com.aresstack.askai.research.concept.ConceptBranchService conceptBranchService() {
        com.aresstack.askai.research.concept.ConceptBranchService service =
                productiveResources == null || productiveResources.isClosed()
                        ? null : productiveResources.conceptBranchService();
        wireConceptIdentity(service);
        return service;
    }

    /** The service instance the identity observers are already wired to (idempotent wiring). */
    private volatile com.aresstack.askai.research.concept.ConceptBranchService wiredConceptService;

    /**
     * Wire the identity observers once per service instance: recovery/migration diagnostics go
     * to the technical log, and every transient boundary (raw save, restore, degraded restore)
     * discards conflicts/plans and runs the eager blacklist reconciliation (V3 §8a steps 3-5).
     */
    private void wireConceptIdentity(
            final com.aresstack.askai.research.concept.ConceptBranchService service) {
        if (service == null || service == wiredConceptService) {
            return;
        }
        wiredConceptService = service;
        service.setDiagnosticSink(
                new com.aresstack.askai.research.concept.ConceptBranchService.DiagnosticSink() {
                    public void line(String line) {
                        technicalLog(line);
                    }
                });
        service.setLifecycleListener(
                new com.aresstack.askai.research.concept.ConceptBranchService
                        .LifecycleListener() {
                    public void onTransientBoundary(String reason, String newEpoch) {
                        discardTransientsAndReconcile(reason, newEpoch);
                    }
                });
    }

    /**
     * V3 §8a steps 3-5, fired INSIDE the service's commit lock: every conflict (open AND
     * candidate) dies with the boundary — never revived from history; then fence/UI republish;
     * then the deterministic reconciliation derives fresh CANDIDATES from the new head (silent
     * bookkeeping — a candidate becomes answerable only through a future visible question).
     */
    private void discardTransientsAndReconcile(String reason, String newEpoch) {
        int discarded;
        java.util.List<String> discardedOpenLabels = new java.util.ArrayList<String>();
        synchronized (conceptConflicts) {
            discarded = conceptConflicts.size();
            for (ConceptConflictRef ref : conceptConflicts.values()) {
                if (ref.open) {
                    discardedOpenLabels.add(ref.label);
                }
            }
            conceptConflicts.clear();
        }
        if (discarded > 0) {
            technicalLog("concept identity -> " + discarded
                    + " transient conflict(s) discarded (" + reason + ")");
        }
        if (!discardedOpenLabels.isEmpty()) {
            // The transcript still shows the old removal question — a bare "Ja." afterwards
            // must never reach the model (which once claimed a deletion over an unchanged
            // concept). The tombstone arms exactly ONE deterministic host answer.
            staleDecisionTombstone = joined(discardedOpenLabels);
            technicalLog("concept identity -> stale decision tombstone armed for \""
                    + staleDecisionTombstone + "\"");
        }
        publishScopeFence();
        fireStateChanged();
        reconcileBlacklistAgainstConcept(reason);
    }

    /**
     * Eager reconciliation after a boundary: every EXCLUSION IDENTITY lands in exactly one
     * category (the live gate saw label AND facet id counted separately — "2 gone, 2 leaf
     * candidates" for one gone term and one present leaf, with a DUPLICATE candidate
     * registered). Deterministic host bookkeeping, never a question, never an armed intercept.
     */
    private void reconcileBlacklistAgainstConcept(String reason) {
        final com.aresstack.askai.research.concept.ConceptBranchService service =
                wiredConceptService;
        if (service == null || service.isFailClosed()) {
            return;
        }
        String summary = com.aresstack.askai.research.concept.ConceptBlacklistReconciler
                .reconcile(service, exclusionIdentities(),
                        new com.aresstack.askai.research.concept.ConceptBlacklistReconciler
                                .CandidateRegistrar() {
                            public void candidate(String exclusionId, String label,
                                                  String nodeId) {
                                conceptConflicts.put(
                                        "conflict-" + conflictIds.incrementAndGet(),
                                        new ConceptConflictRef(service.currentEpoch(), nodeId,
                                                service.snapshot().getWorkingRevision(),
                                                label, false));
                            }
                        });
        technicalLog("concept identity -> reconciliation after " + reason + ": " + summary);
    }

    /**
     * One entry per exclusion IDENTITY: every excluded facet contributes id→label (both match
     * the same entry during reconciliation), plain exclusion strings only when no facet
     * already covers them case-insensitively.
     */
    private java.util.LinkedHashMap<String, String> exclusionIdentities() {
        java.util.LinkedHashMap<String, String> exclusions =
                new java.util.LinkedHashMap<String, String>();
        com.aresstack.askai.research.scope.ResearchScopeCoordinator coordinator =
                scopeCoordinator();
        if (coordinator == null || !coordinator.isUsable()) {
            return exclusions;
        }
        com.aresstack.askai.research.domain.scope.ResearchScopeDraft draft = coordinator.current();
        for (com.aresstack.askai.research.domain.scope.ScopeFacet facet
                : draft.excludedFacets()) {
            exclusions.put(facet.getFacetId(), facet.getLabel());
        }
        for (String term : draft.getExclusions()) {
            String candidate = term == null ? "" : term.trim();
            if (candidate.isEmpty()) {
                continue;
            }
            boolean covered = false;
            for (java.util.Map.Entry<String, String> known : exclusions.entrySet()) {
                if (candidate.equalsIgnoreCase(known.getKey())
                        || candidate.equalsIgnoreCase(known.getValue())) {
                    covered = true;
                    break;
                }
            }
            if (!covered) {
                exclusions.put(candidate, candidate);
            }
        }
        return exclusions;
    }

    private static String joined(java.util.List<String> labels) {
        StringBuilder sb = new StringBuilder();
        for (String label : labels) {
            sb.append(sb.length() > 0 ? ", " : "").append(label);
        }
        return sb.toString();
    }

    /**
     * Persist the research brief to its working copy, OFF the EDT, and refresh the view only when it actually
     * changed (no duplicate write/revision for an identical brief). This is the ONLY brief persistence path;
     * the brief is never mirrored into a session field as an alternative source of truth. No approval, no
     * transition.
     */
    private void persistResearchBrief(final String phaseId, final String markdown) {
        final com.aresstack.askai.research.store.FileResearchBriefStore store = researchBriefStore();
        if (store == null || markdown == null || markdown.trim().isEmpty()) {
            return;
        }
        briefWriteExecutor.execute(new Runnable() {
            public void run() {
                boolean changed;
                try {
                    changed = store.updateWorkingCopy(markdown, System.currentTimeMillis());
                } catch (RuntimeException persistFailed) {
                    return; // a brief write failure must never crash the session or the run
                }
                com.aresstack.askai.research.visualize.VisualizerDiagnostics.log(
                        "briefChanged changed=" + changed + " briefChars=" + markdown.length());
                if (changed) {
                    // Refresh the Fragestellung view. Issue #29: a brief write is CORE persistence and has
                    // NO visualization side effect anymore — the existing visualization merely becomes
                    // stale (visible in the tab); regeneration is the user's explicit button action.
                    uiExecutor.execute(new Runnable() {
                        public void run() {
                            fireStateChanged();
                        }
                    });
                }
            }
        });
    }

    /** The latest derived visualization of an artifact, or {@code null} until one has been produced. */
    public com.aresstack.askai.research.visualize.VisualizationProjection latestVisualization() {
        return latestVisualization;
    }

    /** The visualization lifecycle status (never-ran / preparing / running / diagram / none / failed). */
    public com.aresstack.askai.research.visualize.VisualizationStatus visualizationStatus() {
        return visualizationStatus;
    }

    /** File-backed store of the persisted visualization (survives a restart). Lazy; null in the clickdummy. */
    private com.aresstack.askai.research.store.FileVisualizationStore visualizationStore;

    private synchronized com.aresstack.askai.research.store.FileVisualizationStore visualizationStore() {
        if (visualizationStore == null && productiveResources != null && !productiveResources.isClosed()) {
            java.io.File projectDir = productiveResources.getProjectContext().getProjectDirectory();
            visualizationStore = new com.aresstack.askai.research.store.FileVisualizationStore(
                    new java.io.File(projectDir, "visualization"));
        }
        return visualizationStore;
    }

    /**
     * Restore the PERSISTED visualization on session start (issue #29): opening the tab shows the last
     * generated diagram (possibly marked stale) — it never regenerates. Only fills a still-empty projection.
     */
    private void restorePersistedVisualization() {
        if (latestVisualization != null) {
            return;
        }
        com.aresstack.askai.research.store.FileVisualizationStore store = visualizationStore();
        if (store == null) {
            return;
        }
        com.aresstack.askai.research.visualize.VisualizationProjection restored = store.load();
        if (restored != null) {
            latestVisualization = restored;
            visualizationStatus = restored.getResult().isPresent()
                    ? com.aresstack.askai.research.visualize.VisualizationStatus.HAS_DIAGRAM
                    : com.aresstack.askai.research.visualize.VisualizationStatus.NONE_DECIDED;
        }
    }

    /**
     * EXPLICIT user action (issue #29): generate/regenerate the DERIVED visualization from the CURRENT brief.
     * The only path that invokes the visualization model — neither a brief write nor opening the tab does.
     * Reads the brief and schedules off the EDT; the tab re-renders through the normal state listeners.
     */
    public void requestVisualization() {
        final com.aresstack.askai.research.store.FileResearchBriefStore store = researchBriefStore();
        if (store == null) {
            return; // clickdummy: no brief, nothing to visualize
        }
        final String phaseId = productiveResources == null
                ? state.getPhaseId() : productiveResources.currentState().getPhaseId();
        briefWriteExecutor.execute(new Runnable() {
            public void run() {
                String markdown;
                try {
                    markdown = store.effectiveContent();
                } catch (RuntimeException unreadable) {
                    markdown = "";
                }
                if (markdown == null || markdown.trim().isEmpty()) {
                    // Nothing to visualize yet: an honest NONE, never a model call on an empty brief.
                    visualizationStatus =
                            com.aresstack.askai.research.visualize.VisualizationStatus.NONE_DECIDED;
                    uiExecutor.execute(new Runnable() {
                        public void run() {
                            fireStateChanged();
                        }
                    });
                    return;
                }
                scheduleVisualization(phaseId, markdown);
            }
        });
    }

    /**
     * Whether the current visualization is STALE: the brief changed since it was generated. Cheap hash
     * comparison against the persisted brief — never a recomputation (issue #29).
     */
    public boolean visualizationStale() {
        com.aresstack.askai.research.visualize.VisualizationProjection current = latestVisualization;
        if (current == null) {
            return false; // nothing generated yet — the tab shows the explicit not-generated state instead
        }
        com.aresstack.askai.research.store.FileResearchBriefStore store = researchBriefStore();
        if (store == null) {
            return false;
        }
        try {
            String effective = store.effectiveContent();
            if (effective == null || effective.trim().isEmpty()) {
                return false; // no brief content — nothing the visualization could be stale against
            }
            // The SAME hash the visualizer stamps on its projection (ArtifactSnapshot.getContentHash()).
            String briefHash = new com.aresstack.askai.research.visualize.ArtifactSnapshot(
                    "research-brief", effective, "").getContentHash();
            return !briefHash.equals(current.getSourceContentHash());
        } catch (RuntimeException unreadable) {
            return false;
        }
    }

    // ------------------------------------------------------------------ derived actions (issue #33)

    /**
     * THE one implementation of the explicit derived-action commands (issue #33): the UI buttons and the
     * internal service-MCP endpoint both delegate here — same use case, two adapters. Never offered to the
     * TeamAgent (its research-control endpoint has no such tools).
     */
    private final ResearchDerivedActions derivedActions = new ResearchDerivedActions() {
        public ActionOutcome reviewSources() {
            if (handle == null || disposed
                    || (productiveResources != null && productiveResources.isClosed())) {
                return ActionOutcome.rejected("the research session is not active");
            }
            requestPostSearchReview();
            return ActionOutcome.accepted("review requested (review_started/review_finished bracket follows)");
        }

        public ActionOutcome checkScope() {
            String result = requestScopeCheck();
            return result.startsWith("handled: ")
                    ? ActionOutcome.accepted(result.substring("handled: ".length()))
                    : ActionOutcome.rejected(result.startsWith("rejected: ")
                            ? result.substring("rejected: ".length()) : result);
        }

        public ActionOutcome generateVisualization() {
            if (researchBriefStore() == null) {
                return ActionOutcome.rejected("no productive research project (no brief store)");
            }
            requestVisualization();
            return ActionOutcome.accepted("visualization generation scheduled from the current brief");
        }

        public ActionOutcome generateOutline() {
            return requestOutlineRebuild()
                    ? ActionOutcome.accepted("topic discovery + outline rebuild triggered (debounced)")
                    : ActionOutcome.rejected(
                            "knowledge capability unavailable (no embedding world for this session)");
        }
    };

    /**
     * The research view ON the conversation: which phase a message belongs to, plus the phase outcomes. It
     * holds NO message text — the host's chat record is the single truth for that (see
     * {@link #describeChatHistory(boolean)}). Loaded from the project directory, so the attribution survives
     * a restart.
     */
    private ResearchPhaseJournal journal = new ResearchPhaseJournal();
    /** Serializes the small journal writes OFF the caller thread (attribution happens during UI work). */
    private final java.util.concurrent.ExecutorService journalWriteExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor(
                    new java.util.concurrent.ThreadFactory() {
                        public Thread newThread(Runnable r) {
                            Thread thread = new Thread(r, "research-phase-journal");
                            thread.setDaemon(true);
                            return thread;
                        }
                    });

    /** Run-unique ids for everything this session persists (see {@link ResearchMessageIds}). */
    private final ResearchMessageIds messageIds = new ResearchMessageIds();

    /**
     * Qualify a foreign id for THIS run, attribute it to the live phase and return the id to hand to the
     * sink — so the journal key and the persisted message id are the same value by construction.
     */
    private String publish(String rawId) {
        String messageId = messageIds.qualify(rawId);
        attributeToCurrentPhase(messageId);
        return messageId;
    }

    /** Attribute a message the host is about to persist under {@code messageId} to the live phase. */
    private void attributeToCurrentPhase(String messageId) {
        if (journal.attribute(messageId, transcriptPhase())) {
            persistJournal();
        }
    }

    /**
     * Z3b-3: the wire client of the sweep CURRENTLY in flight (created per run with exactly that
     * run's settings, cleared in finally) — the PROBE_GENERATION event route delivers to it on
     * the backend callback thread. The WHOLE sweep is session-locally single-flight: a lazy
     * cached generator would freeze the first run's settings forever, and two racing first calls
     * would create two instances while events only ever reach the last one written — the answer
     * for run A would then feed run B's generator and A would die in its timeout.
     */
    private volatile com.aresstack.askai.research.scope.BackendScopeProbeGenerator
            activeProbeGenerator;
    /** Z4b: the advice chooser of the scope check currently in flight — same lifecycle rules. */
    private volatile com.aresstack.askai.research.scope.BackendScopeAdviceChooser
            activeAdviceChooser;
    /** Z4c: whether a FULL scope check (sweep + chooser) is running — drives the tag flip. */
    private final java.util.concurrent.atomic.AtomicBoolean scopeCheckInFlight =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    /** Set by the user's cancel; presentation then says "abgebrochen" instead of a failure. */
    private volatile boolean scopeCheckCancelRequested;
    /** Runs the 1-2 minute scope check OFF the EDT (the transport delivery depends on it). */
    private final java.util.concurrent.ExecutorService scopeCheckExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor(
                    new java.util.concurrent.ThreadFactory() {
                        public Thread newThread(Runnable runnable) {
                            Thread thread = new Thread(runnable, "research-scope-check");
                            thread.setDaemon(true);
                            return thread;
                        }
                    });
    private final java.util.concurrent.atomic.AtomicBoolean scopeSweepInFlight =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * Z3b-3: run ONE scope sweep — callable, NEVER triggered automatically (the generation costs
     * 45-115s of real model time; WHEN to sweep is a Z4/policy decision, and until that trigger
     * exists nothing invokes this productively). The whole chain derives from ONE immutable draft
     * snapshot and ONE frozen embedding snapshot:
     * draft R + embedding E → reconcile anchor index(R,E) → runtime generation wire → one
     * transient embedding batch on E → calibration → gates → sweep → diversity → outcome bound
     * to (R,E). Every failure is a typed {@link
     * com.aresstack.askai.research.domain.scope.ScopeSweepOutcome}, never an escaping exception —
     * except calling it on a non-productive session, which is a caller bug and fails loudly.
     */
    public com.aresstack.askai.research.domain.scope.ScopeSweepOutcome runScopeSweep(
            com.aresstack.askai.research.scope.ScopeSweepConfiguration configuration) {
        final com.aresstack.askai.research.scope.ResearchScopeCoordinator coordinator =
                scopeCoordinator();
        if (coordinator == null || !coordinator.isUsable() || handle == null) {
            throw new IllegalStateException("a scope sweep needs a productive session with a "
                    + "usable scope draft" + (coordinator == null ? "" : " ("
                    + coordinator.unusableReason() + ")"));
        }
        // The WHOLE sweep is single-flight — not just the model call: two concurrent sweeps would
        // race the generator routing AND reconcile the anchor vector cache file in parallel.
        if (!scopeSweepInFlight.compareAndSet(false, true)) {
            return com.aresstack.askai.research.domain.scope.ScopeSweepOutcome.generationFailed(
                    com.aresstack.askai.research.domain.scope.ScopeProbeGenerator
                            .ProbeGenerationResult.Status.PROVIDER_FAILURE,
                    "a scope sweep is already in flight — no overlapping sweeps");
        }
        try {
            com.aresstack.askai.agent.model.embedding.EmbeddingEndpointDescriptor descriptor =
                    productiveResources.getEmbeddingDescriptor();
            if (descriptor == null) {
                return com.aresstack.askai.research.domain.scope.ScopeSweepOutcome.embeddingFailed(
                        "no embedding model is configured — the sweep capability is unavailable");
            }
            // ONE immutable draft snapshot + ONE frozen embedding snapshot, pinned here only.
            com.aresstack.askai.research.domain.scope.ResearchScopeDraft draft =
                    coordinator.current();
            // Zielbild slice 1 — the fence is a PROJECTION of the two artifacts: the draft's own
            // anchors carry the negotiated OUT side, the EFFECTIVE mindmap (stored concept minus
            // exact blacklist matches) contributes the IN posts. Snapshotted HERE, together with
            // the draft, so vectors/request/plan describe one fence.
            com.aresstack.askai.research.concept.ConceptBranchService conceptService =
                    conceptBranchService();
            java.util.List<com.aresstack.askai.research.domain.scope.ScopeAnchor> conceptAnchors =
                    com.aresstack.askai.research.scope.ConceptAnchorProjection.anchorsOf(
                            conceptService == null ? null
                                    : conceptService.snapshot().getDocumentJson(), draft);
            java.util.List<com.aresstack.askai.research.domain.scope.ScopeAnchor> fenceAnchors =
                    com.aresstack.askai.research.scope.ScopeSweepPlanAssembler.combinedAnchors(
                            draft, conceptAnchors);
            com.aresstack.askai.research.scope.EmbeddingSnapshotSweepEmbedder embedder =
                    new com.aresstack.askai.research.scope.EmbeddingSnapshotSweepEmbedder(
                            descriptor);
            java.util.List<com.aresstack.askai.research.domain.scope
                    .ScopeFenceEvaluator.AnchorVector> anchorVectors;
            try {
                anchorVectors = new com.aresstack.askai.research.store.ScopeAnchorVectorIndex(
                        new java.io.File(productiveResources.getProjectContext()
                                .getProjectDirectory(), "scope-anchor-vectors.json"))
                        .vectorsFor(fenceAnchors, embedder.modelFingerprint(), embedder);
            } catch (java.io.IOException indexFailed) {
                return com.aresstack.askai.research.domain.scope.ScopeSweepOutcome
                        .embeddingFailed("anchor vector index failed: "
                                + indexFailed.getMessage());
            } catch (RuntimeException embeddingBroke) {
                // The index does not wrap its embedder's HTTP failures — this path must land in
                // the typed contract exactly like the transient batch does.
                return com.aresstack.askai.research.domain.scope.ScopeSweepOutcome
                        .embeddingFailed("anchor vector index failed: " + embeddingBroke);
            }
            // Per-run generator with exactly THIS run's settings — never a cached one.
            com.aresstack.askai.research.scope.BackendScopeProbeGenerator generator =
                    new com.aresstack.askai.research.scope.BackendScopeProbeGenerator(
                            backend, handle,
                            new com.aresstack.askai.research.scope.BackendScopeProbeGenerator
                                    .WireSettings(configuration.generatorTemperature,
                                    configuration.generatorMaxOutputTokens,
                                    configuration.controlsPerAnchor,
                                    configuration.generationTimeoutSeconds));
            activeProbeGenerator = generator;
            com.aresstack.askai.research.scope.ScopeSweepService service =
                    new com.aresstack.askai.research.scope.ScopeSweepService(generator, embedder,
                            new com.aresstack.askai.research.scope.ScopeSweepService
                                    .ScopeRevisionProbe() {
                                public long currentRevision() {
                                    return coordinator.current().getRevision();
                                }
                            });
            return service.run(com.aresstack.askai.research.scope.ScopeSweepPlanAssembler.planOf(
                    draft, conceptAnchors, embedder.modelFingerprint(), anchorVectors,
                    configuration));
        } finally {
            activeProbeGenerator = null;
            scopeSweepInFlight.set(false);
        }
    }

    /**
     * Z4: ONE full scope check — sweep, reason-aware advice, chooser — synchronous and long
     * (live: 45-115s generation + a short chooser call); callers run it on a worker, never the
     * EDT. Zero candidates short-circuit to a deterministic NONE (no model call); advice whose
     * revision moved is DISCARDED (stale), and a chooser failure stays a chooser failure — none
     * of these ever reads as "nothing found".
     */
    com.aresstack.askai.research.scope.ScopeCheckReport runScopeCheck(
            com.aresstack.askai.research.scope.ScopeSweepConfiguration configuration) {
        com.aresstack.askai.research.domain.scope.ScopeSweepOutcome outcome =
                runScopeSweep(configuration);
        if (!outcome.isReady()) {
            return com.aresstack.askai.research.scope.ScopeCheckReport.sweepNotReady(outcome);
        }
        com.aresstack.askai.research.domain.scope.ScopeAdviceSet advice = outcome.getAdviceSet();
        com.aresstack.askai.research.scope.ResearchScopeCoordinator coordinator =
                scopeCoordinator();
        com.aresstack.askai.research.domain.scope.ResearchScopeDraft draft = coordinator.current();
        if (!advice.appliesTo(draft.getRevision())) {
            return com.aresstack.askai.research.scope.ScopeCheckReport.staleBeforeAdvice(outcome);
        }
        if (advice.getQuestionCandidates().isEmpty()) {
            // Nothing to offer: a model call over zero candidates would be theater. Deterministic
            // honest NONE — and NONE never means "the scope is complete".
            return com.aresstack.askai.research.scope.ScopeCheckReport.withChoice(outcome,
                    com.aresstack.askai.research.domain.scope.ScopeAdviceChooser.ChoiceResult.ok(
                            com.aresstack.askai.research.domain.scope.ScopeAdviceChooser
                                    .AdviceDecision.none(playbook.isGerman()
                                            ? "In diesem Prüfdurchlauf ist kein zusätzlicher "
                                                    + "Klärungspunkt aufgefallen."
                                            : "This check surfaced no additional point to "
                                                    + "clarify.")));
        }
        com.aresstack.askai.research.scope.BackendScopeAdviceChooser chooser =
                new com.aresstack.askai.research.scope.BackendScopeAdviceChooser(backend, handle,
                        new com.aresstack.askai.research.scope.BackendScopeAdviceChooser
                                .WireSettings(configuration.chooserTemperature,
                                configuration.chooserMaxOutputTokens,
                                configuration.choiceTimeoutSeconds));
        activeAdviceChooser = chooser;
        try {
            return com.aresstack.askai.research.scope.ScopeCheckReport.withChoice(outcome,
                    chooser.choose(com.aresstack.askai.research.scope.ScopeAdviceOfferRenderer
                            .render(advice, draft)));
        } finally {
            activeAdviceChooser = null;
        }
    }

    /**
     * Z4c: the USER-INITIATED trigger — the only way a sweep starts (no automatic background
     * sweeps, no per-turn sweeps; the check costs 1-2 minutes of real model time). Guards, then
     * hands the whole check to the dedicated worker and returns immediately; the outcome arrives
     * as at most ONE assistant question (or an honest info line). It never changes scope, phase
     * or approval — submit-scope stays independently available while the check runs.
     */
    String requestScopeCheck() {
        if (handle == null || disposed
                || (productiveResources != null && productiveResources.isClosed())
                || productiveResources == null) {
            return rejectScopeCheck("the research session is not active");
        }
        com.aresstack.askai.research.scope.ResearchScopeCoordinator coordinator =
                scopeCoordinator();
        if (coordinator == null || !coordinator.isUsable()) {
            return rejectScopeCheck("no usable scope draft"
                    + (coordinator == null ? "" : " (" + coordinator.unusableReason() + ")"));
        }
        if (!com.aresstack.askai.research.state.oo.ResearchStateIds.SCOPING
                .equals(productiveResources.currentState().getPhaseId())) {
            return rejectScopeCheck("the scope check is a SCOPING-phase action");
        }
        if (!scopeCheckInFlight.compareAndSet(false, true)) {
            return rejectScopeCheck("a scope check is already running");
        }
        scopeCheckCancelRequested = false;
        final String checkId = publish("scope-check-" + messageIds.next("scope-check"));
        uiExecutor.execute(new Runnable() {
            public void run() {
                sink.startThinking(checkId, playbook.isGerman()
                        ? "Ich prüfe den Themenraum breit auf mögliche Lücken …"
                        : "Checking the topic space broadly for possible gaps …");
            }
        });
        fireStateChanged(); // the tag flips to "Prüfung abbrechen"
        // Fence fingerprint (connector-gate fix 2): the check judges the EFFECTIVE fence
        // including the concept — stamp what the sweep is about to see, not what exists when
        // the report happens to arrive.
        final String conceptStampAtStart = conceptStamp();
        scopeCheckExecutor.execute(new Runnable() {
            public void run() {
                com.aresstack.askai.research.scope.ScopeCheckReport report = null;
                String unexpected = null;
                try {
                    com.aresstack.askai.research.scope.ScopeSweepConfiguration configuration =
                            com.aresstack.askai.research.host.ResearchRuntimeSettings
                                    .loadScopeSweepConfiguration(getHostStateStore());
                    // Gate 8 observability: a running check is visible in the log WITH its knobs —
                    // "no result line yet" and "which breadth was that" stop being guesswork.
                    technicalLog("[scope-check] started (breadth="
                            + configuration.targetBroadProbes + ", controls/anchor="
                            + configuration.controlsPerAnchor + ", generation timeout="
                            + configuration.generationTimeoutSeconds + "s)");
                    report = runScopeCheck(configuration);
                } catch (RuntimeException broke) {
                    unexpected = String.valueOf(broke);
                } finally {
                    scopeCheckInFlight.set(false);
                }
                final com.aresstack.askai.research.scope.ScopeCheckReport finishedReport = report;
                final String finishedUnexpected = unexpected;
                uiExecutor.execute(new Runnable() {
                    public void run() {
                        presentScopeCheck(checkId, finishedReport, finishedUnexpected,
                                conceptStampAtStart);
                        fireStateChanged();
                    }
                });
            }
        });
        return "handled: scope check started (runs in the background, ~1-2 minutes)";
    }

    private String rejectScopeCheck(String reason) {
        return "rejected: " + reason;
    }

    /**
     * Z4c: the user's request-scoped cancel — aborts the WAITING host side typed (the runtime's
     * model call finishes unobserved; its late answer finds no waiting request and is dropped).
     * Deliberately NOT the foreground chat stop: the check has its own lifecycle.
     */
    String cancelScopeCheck() {
        if (!scopeCheckInFlight.get()) {
            return "rejected: no scope check is running";
        }
        scopeCheckCancelRequested = true;
        String reason = playbook.isGerman()
                ? "Prüfung vom Benutzer abgebrochen" : "check cancelled by the user";
        com.aresstack.askai.research.scope.BackendScopeProbeGenerator generator =
                activeProbeGenerator;
        if (generator != null) {
            generator.abortAll(reason);
        }
        com.aresstack.askai.research.scope.BackendScopeAdviceChooser chooser =
                activeAdviceChooser;
        if (chooser != null) {
            chooser.abortAll(reason);
        }
        return "handled: scope check cancel requested";
    }

    /** Presentation of one finished check — every gate keeps its own honest face (UI thread). */
    private void presentScopeCheck(String checkId,
                                   com.aresstack.askai.research.scope.ScopeCheckReport report,
                                   String unexpected, String conceptStampAtStart) {
        try {
            sink.finishThinking(checkId, "");
        } catch (RuntimeException ignored) {
            // presentation must continue even if the bubble is already gone
        }
        boolean german = playbook.isGerman();
        final String lineId = publish(checkId + "-result");
        if (unexpected != null) {
            sink.appendInfoMessage(lineId, german
                    ? "Themenraum-Prüfung unerwartet fehlgeschlagen." : "Scope check failed.");
            sink.markInfoStatus(lineId, false, scopeCheckRetry());
            technicalLog("[scope-check] unexpected: " + unexpected);
            return;
        }
        if (scopeCheckCancelRequested) {
            sink.appendInfoMessage(lineId, german
                    ? "Themenraum-Prüfung abgebrochen." : "Scope check cancelled.");
            technicalLog("[scope-check] cancelled by the user");
            return;
        }
        appendScopeCheckTechnicalLog(report);
        latestScopeCheckReport = report; // the policy consumes it while its revision is current
        // Fix 2 (connector gate): a concept mutation AFTER the check makes the report stale —
        // the fence is Mindmap + Blacklist, so currency is the PAIR (scope revision, concept
        // stamp), never the scope-draft revision alone.
        latestScopeCheckConceptStamp = conceptStampAtStart;
        // Fix 1 (connector gate): ASKED is pending-until-ANSWERED — the next user turn
        // consumes it; it must never linger as open boundary work until a report replaces it.
        boundaryQuestionPending = report.getKind()
                == com.aresstack.askai.research.scope.ScopeCheckReport.Kind.ASKED;
        if (!boundaryQuestionPending) {
            lastScopeCheckQuestion = "";
        }
        switch (report.getKind()) {
            case ASKED:
                // The deliverable: the agent asks its ONE question. The user's answer flows
                // through the normal scoping turn — this message changes nothing by itself.
                // The RUNTIME model never saw this host-said question; the fence carries it,
                // otherwise the user's answer arrives at a model that never asked.
                lastScopeCheckQuestion = report.getChoice().getDecision().getAssistantMessage();
                publishScopeFence();
                sayAsAgent(report.getChoice().getDecision().getAssistantMessage());
                break;
            case NOTHING_TO_ASK:
                sink.appendInfoMessage(lineId, (german
                        ? "Themenraum geprüft — " : "Topic space checked — ")
                        + report.getChoice().getDecision().getAssistantMessage());
                sink.markInfoStatus(lineId, true, null);
                break;
            case STALE_BEFORE_ADVICE:
                sink.appendInfoMessage(lineId, german
                        ? "Der Themenzuschnitt hat sich während der Prüfung geändert — das "
                                + "Ergebnis wurde verworfen."
                        : "The scope changed while the check ran — the result was discarded.");
                sink.markInfoStatus(lineId, false, scopeCheckRetry());
                break;
            case CHOICE_FAILED:
                sink.appendInfoMessage(lineId, german
                        ? "Die Auswahl der Klärungsfrage ist fehlgeschlagen."
                        : "Choosing the clarification question failed.");
                sink.markInfoStatus(lineId, false, scopeCheckRetry());
                break;
            default:
                sink.appendInfoMessage(lineId, sweepGateText(report.getOutcome(), german));
                sink.markInfoStatus(lineId, false, scopeCheckRetry());
        }
    }

    private Runnable scopeCheckRetry() {
        return new Runnable() {
            public void run() {
                requestScopeCheck();
            }
        };
    }

    /** Honest German/English one-liners for the sweep's typed gates — never "keine Lücken". */
    private static String sweepGateText(
            com.aresstack.askai.research.domain.scope.ScopeSweepOutcome outcome, boolean german) {
        switch (outcome.getStatus()) {
            case GENERATION_FAILED:
                // Live finding: a small central model (gemma4:e2b) loses count at width 50 and
                // truncates — the honest failure must point at the knob that fixes it.
                return german
                        ? "Die Themen-Generierung ist fehlgeschlagen — der Themenraum konnte "
                                + "nicht geprüft werden. Tipp: Bei kleinen Modellen die Breite "
                                + "in den Einstellungen (Scope-Prüfung) reduzieren, z. B. auf 20."
                        : "Probe generation failed — the topic space could not be checked. Tip: "
                                + "for small models reduce the width in the settings "
                                + "(Scope-Prüfung), e.g. to 20.";
            case BROAD_SAMPLE_INCOMPLETE:
                return german
                        ? "Das Modell lieferte nur " + outcome.getAcceptedBroadCount() + " von "
                                + outcome.getRequestedBroadCount() + " Konzepten — zu wenig "
                                + "Breite für eine verlässliche Prüfung. Tipp: die Breite in den "
                                + "Einstellungen (Scope-Prüfung) an das Modell anpassen."
                        : "The model delivered only " + outcome.getAcceptedBroadCount() + " of "
                                + outcome.getRequestedBroadCount() + " concepts — not broad "
                                + "enough for a reliable check. Tip: match the width in the "
                                + "settings (Scope-Prüfung) to the model.";
            case CALIBRATION_WEAK:
                return german
                        ? "Der Zaun hat noch zu wenige ausgehandelte Pfosten für eine "
                                + "verlässliche Prüfung — einfach weiter eingrenzen."
                        : "The fence has too few negotiated posts for a reliable check yet.";
            case EMBEDDING_FAILED:
                return german
                        ? "Die semantische Vermessung ist fehlgeschlagen — der Themenraum "
                                + "konnte nicht geprüft werden."
                        : "Embedding failed — the topic space could not be checked.";
            case STALE_SCOPE:
                return german
                        ? "Der Themenzuschnitt hat sich während der Prüfung geändert — das "
                                + "Ergebnis wurde verworfen."
                        : "The scope changed while the check ran — the result was discarded.";
            default:
                return german ? "Themenraum-Prüfung beendet." : "Scope check finished.";
        }
    }

    /** The measured numbers into the collapsible technical details — never into the chat. */
    private void appendScopeCheckTechnicalLog(
            com.aresstack.askai.research.scope.ScopeCheckReport report) {
        com.aresstack.askai.research.domain.scope.ScopeSweepOutcome outcome = report.getOutcome();
        StringBuilder log = new StringBuilder("[scope-check] outcome=").append(
                outcome.getStatus());
        if (outcome.isReady()) {
            log.append(" revision=").append(outcome.getScopeRevision())
                    .append(" probes=").append(outcome.getSweep().getReadings().size())
                    .append(" candidates=")
                    .append(outcome.getAdviceSet().getQuestionCandidates().size())
                    .append(" driftGuards=")
                    .append(outcome.getAdviceSet().getDriftGuards().size());
        }
        if (!outcome.getDiagnostics().isEmpty()) {
            log.append(" diagnostics=").append(outcome.getDiagnostics());
        }
        technicalLog(log.toString());
        if (outcome.isReady()) {
            for (com.aresstack.askai.research.domain.scope.ScopeDriftGuard guard
                    : outcome.getAdviceSet().getDriftGuards()) {
                technicalLog("[scope-check] drift guard: '" + guard.getProbeText()
                        + "' bleibt ausgeschlossen (" + guard.getNearestOutAnchorId() + ")");
            }
        }
        if (report.getChoice() != null && !report.getChoice().isOk()) {
            technicalLog("[scope-check] choice failed: "
                    + report.getChoice().getStatus() + " " + report.getChoice().getMessage());
        }
    }

    /** Owns the persisted scope draft of this session; null in fake mode (no project context). */
    private com.aresstack.askai.research.scope.ResearchScopeCoordinator scopeCoordinator;

    private com.aresstack.askai.research.scope.ResearchScopeCoordinator scopeCoordinator() {
        if (scopeCoordinator == null && productiveResources != null) {
            scopeCoordinator = new com.aresstack.askai.research.scope.ResearchScopeCoordinator(
                    productiveResources.getProjectContext().getScopeDraftStore());
        }
        return scopeCoordinator;
    }

    /**
     * Apply what a scoping turn proposed. A failure here must NEVER look like success: if the assistant
     * says "ich habe AR als Nebenaspekt notiert" while the update was refused, the conversation and the
     * stored scope would silently diverge — so a rejection becomes a visible problem.
     */
    private void applyScopeUpdate(String documentJson) {
        com.aresstack.askai.research.scope.ResearchScopeCoordinator coordinator = scopeCoordinator();
        if (coordinator == null) {
            return; // fake mode: no persisted scope to update
        }
        com.aresstack.askai.research.scope.ScopeUpdateWireCodec.Result decoded =
                com.aresstack.askai.research.scope.ScopeUpdateWireCodec.decode(documentJson);
        if (!decoded.isOk()) {
            technicalLog("scope update -> REJECTED: " + decoded.getError());
            reportScopeProblem("Der Rechercheumfang konnte nicht aktualisiert werden: "
                    + decoded.getError());
            return;
        }
        com.aresstack.askai.research.scope.ScopeUpdateResult applied =
                coordinator.apply(decoded.getTurn());
        if (applied.getStatus()
                == com.aresstack.askai.research.scope.ScopeUpdateResult.Status.REJECTED) {
            technicalLog("scope update -> REJECTED: " + applied.getReason());
            reportScopeProblem("Der Rechercheumfang konnte nicht gespeichert werden: "
                    + applied.getReason());
            return;
        }
        for (String change : applied.getChanges()) {
            System.err.println("[research-scope] " + change);
        }
        // The live gate showed the concept richly traced while the scope stayed mute — a bot
        // driving via technical_log could not tell whether the Weidezaun was ever fed.
        technicalLog(applied.isApplied()
                ? "scope update -> APPLIED: " + applied.getChanges()
                : "scope update -> NO-OP (nothing changed)");
        if (applied.isApplied()) {
            // The fence must never lag the draft within a multi-round turn — republish NOW,
            // exactly like the manual exclusion chips do.
            publishScopeFence();
            // Scope surfaces (e.g. the blacklist chips under the composer) refresh through the
            // ordinary state listener — a model-recorded exclusion must appear without a restart.
            fireStateChanged();
        }
    }

    /** The current persisted scope draft (an empty one in fake mode) — the blacklist UI reads this. */
    public com.aresstack.askai.research.domain.scope.ResearchScopeDraft currentScopeDraft() {
        com.aresstack.askai.research.scope.ResearchScopeCoordinator coordinator = scopeCoordinator();
        return coordinator == null
                ? com.aresstack.askai.research.domain.scope.ResearchScopeDraft.empty()
                : coordinator.current();
    }

    /**
     * Record a user-worded exclusion ("+ Hinzufügen" on the blacklist) through the ONE scope path —
     * the same coordinator/patch/persist route a scoping turn takes, never a parallel store.
     *
     * @return {@code null} when recorded (or already present), else the honest rejection reason
     */
    public String addScopeExclusion(String text) {
        return applyExclusionPatch(text, true);
    }

    /**
     * Take a plain exclusion back out (the chip's ✕). Only the user-worded string list — EXCLUDED
     * facets stay on record, exactly like {@code ScopePatchOperations.removeExclusion} documents.
     *
     * @return {@code null} when removed (or already absent), else the honest rejection reason
     */
    public String removeScopeExclusion(String text) {
        return applyExclusionPatch(text, false);
    }

    private String applyExclusionPatch(String text, boolean add) {
        String value = text == null ? "" : text.trim();
        if (value.isEmpty()) {
            return "der Ausschluss ist leer";
        }
        com.aresstack.askai.research.scope.ResearchScopeCoordinator coordinator = scopeCoordinator();
        if (coordinator == null) {
            return "kein Projektkontext (Fake-Modus) — der Rechercheumfang wird nicht persistiert";
        }
        if (!add) {
            // The chip may project an EXCLUDED facet (not a string exclusion). Removing it means
            // the user LIFTS the exclusion. Two-artifact alignment: the facet returns as
            // PROVISIONAL bookkeeping (kept on record, no longer an OUT post) — NEVER as a
            // CONFIRMED positive post; the mindmap alone owns the positive space, and a lifted
            // exclusion must not spawn a negotiated IN anchor beside it.
            for (com.aresstack.askai.research.domain.scope.ScopeFacet facet
                    : coordinator.current().excludedFacets()) {
                if (facet.getLabel().trim().equalsIgnoreCase(value)) {
                    com.aresstack.askai.research.scope.ScopeUpdateResult lifted = coordinator.apply(
                            new com.aresstack.askai.research.domain.scope.ScopePatch(
                                    java.util.Collections.singletonList(
                                            com.aresstack.askai.research.domain.scope
                                                    .ScopePatchOperations.addFacet(
                                                            facet.getFacetId(), facet.getLabel(),
                                                            "user lifted the exclusion"))),
                            java.util.Collections.<com.aresstack.askai.research.domain.scope
                                    .UnresolvedScopeIssue>emptyList());
                    if (lifted.getStatus() == com.aresstack.askai.research.scope
                            .ScopeUpdateResult.Status.REJECTED) {
                        return lifted.getReason();
                    }
                    if (lifted.isApplied()) {
                        publishScopeFence();
                        fireStateChanged();
                    }
                    return null;
                }
            }
        }
        // A no-op must not burn a revision: adding an existing / removing an absent value returns quietly.
        boolean present = coordinator.current().getExclusions().contains(value);
        if (add == present) {
            return null;
        }
        com.aresstack.askai.research.domain.scope.ScopePatch patch =
                new com.aresstack.askai.research.domain.scope.ScopePatch(
                        java.util.Collections.singletonList(add
                                ? com.aresstack.askai.research.domain.scope.ScopePatchOperations
                                        .addExclusion(value)
                                : com.aresstack.askai.research.domain.scope.ScopePatchOperations
                                        .removeExclusion(value)));
        com.aresstack.askai.research.scope.ScopeUpdateResult result = coordinator.apply(patch,
                java.util.Collections.<com.aresstack.askai.research.domain.scope
                        .UnresolvedScopeIssue>emptyList());
        if (result.getStatus()
                == com.aresstack.askai.research.scope.ScopeUpdateResult.Status.REJECTED) {
            return result.getReason();
        }
        if (result.isApplied()) {
            publishScopeFence(); // the next model turn must see the authoritative scope immediately
            fireStateChanged();  // the chips re-render through the ordinary state listener
        }
        return null;
    }

    // ------------------------------------------------------ one-command exclusion facade (gate 4)

    /**
     * One registered concept conflict (ID-sidecar slice): references are
     * {@code epoch + nodeId + expectedRevision} — never a stored name path (a rename must not
     * break them). {@code open} implements the ratified candidate/open model: only a conflict
     * whose question was VISIBLY asked (the terminal exclude receipt) is answerable — buttons,
     * fence block and the yes/no intercept all key on it; reconciliation after an epoch
     * boundary produces silent candidates only.
     */
    static final class ConceptConflictRef {
        final String epoch;
        final String nodeId;
        final long expectedRevision;
        final String label;
        final boolean open;

        ConceptConflictRef(String epoch, String nodeId, long expectedRevision, String label,
                           boolean open) {
            this.epoch = epoch;
            this.nodeId = nodeId;
            this.expectedRevision = expectedRevision;
            this.label = label;
            this.open = open;
        }
    }

    /**
     * The DiscardedDecisionTombstone (ID-sidecar gate finding): when a raw save/restore
     * discards an OPEN conflict, the transcript still shows the old removal question — a bare
     * "Ja." afterwards must get a deterministic HOST answer ("the question is stale, nothing
     * changed, the topic stays suppressed"), never a model inference. Consumed on the first
     * answer, superseded silently by any substantive turn, replaced by any new OPEN conflict.
     * Value = the comma-joined labels of the discarded open question(s).
     */
    private volatile String staleDecisionTombstone;

    /** Opaque conflictId → reference, INSERTION-ordered (the host resolves oldest first). */
    private final java.util.Map<String, ConceptConflictRef> conceptConflicts =
            java.util.Collections.synchronizedMap(
                    new java.util.LinkedHashMap<String, ConceptConflictRef>());
    private final java.util.concurrent.atomic.AtomicLong conflictIds =
            new java.util.concurrent.atomic.AtomicLong();

    // ------------------------------------------------ conversation policy (ratified slice)
    /** The latest finished scope check; the policy IGNORES it once its revision is stale. */
    private volatile com.aresstack.askai.research.scope.ScopeCheckReport latestScopeCheckReport;
    /** The concept stamp (epoch#revision) the latest check judged — half the fence fingerprint. */
    private volatile String latestScopeCheckConceptStamp = "";
    /** An ASKED question is pending until the user's next turn answers it (fix 1). */
    private volatile boolean boundaryQuestionPending;
    /** The node-ID novelty window: renames/moves mint no ids and are correctly no novelty. */
    private java.util.Set<String> noveltyPreviousIds = new java.util.HashSet<String>();
    private String noveltyEpoch;
    private int turnsWithoutNewCards;
    /** The ephemeral fence block, recomputed once per user turn — NEVER persisted state. */
    private volatile String conversationPolicyBlock = "";

    /** The concept side of the fence fingerprint: epoch + working revision (or a marker). */
    private String conceptStamp() {
        com.aresstack.askai.research.concept.ConceptBranchService service =
                conceptBranchService();
        if (service == null) {
            return "none";
        }
        try {
            return service.currentEpoch() + "#" + service.snapshot().getWorkingRevision();
        } catch (RuntimeException unreadable) {
            return "unreadable";
        }
    }

    /**
     * Recompute the ratified conversation policy for the upcoming turn: pure projection over
     * the CURRENT snapshot (node-ID novelty, OPEN boundary material, the scope check only
     * while its revision is current) — no second state machine, only this observation window.
     */
    private void refreshConversationPolicy() {
        com.aresstack.askai.research.concept.ConceptBranchService service =
                conceptBranchService();
        com.aresstack.askai.research.scope.ResearchScopeCoordinator coordinator =
                scopeCoordinator();
        if (service == null || coordinator == null || !coordinator.isUsable()) {
            conversationPolicyBlock = "";
            return;
        }
        java.util.Set<String> ids =
                new java.util.HashSet<String>(service.allNodeIds());
        String epoch = service.currentEpoch();
        if (noveltyEpoch != null && noveltyEpoch.equals(epoch)) {
            boolean newCards = false;
            for (String id : ids) {
                if (!noveltyPreviousIds.contains(id)) {
                    newCards = true;
                    break;
                }
            }
            turnsWithoutNewCards = newCards ? 0 : turnsWithoutNewCards + 1;
        } else {
            turnsWithoutNewCards = 0; // a fresh epoch restarts the observation window
        }
        noveltyPreviousIds = ids;
        noveltyEpoch = epoch;

        com.aresstack.askai.research.domain.scope.ResearchScopeDraft draft = coordinator.current();
        boolean missionPresent = draft.getMission() != null
                && !draft.getMission().trim().isEmpty();
        boolean openConflict = false;
        int candidates = 0;
        synchronized (conceptConflicts) {
            for (ConceptConflictRef ref : conceptConflicts.values()) {
                if (ref.open) {
                    openConflict = true;
                } else {
                    candidates++;
                }
            }
        }
        com.aresstack.askai.research.scope.ScopeCheckReport report = latestScopeCheckReport;
        boolean reportCurrent = report != null
                && report.getOutcome().getScopeRevision() == draft.getRevision()
                && conceptStamp().equals(latestScopeCheckConceptStamp);
        com.aresstack.askai.research.scope.ScopeCheckReport.Kind kind =
                reportCurrent ? report.getKind() : null;
        boolean calibrationWeak = reportCurrent && report.getOutcome().getStatus()
                == com.aresstack.askai.research.domain.scope.ScopeSweepOutcome.Status
                        .CALIBRATION_WEAK;
        int driftGuards = reportCurrent && report.getOutcome().getAdviceSet() != null
                ? report.getOutcome().getAdviceSet().getDriftGuards().size() : 0;
        boolean openBoundaryWork = openConflict || candidates > 0 || driftGuards > 0
                || (boundaryQuestionPending
                        && kind == com.aresstack.askai.research.scope.ScopeCheckReport.Kind
                                .ASKED);
        ConversationPolicyProjection.Inputs inputs = new ConversationPolicyProjection.Inputs(
                missionPresent, ids.size(), turnsWithoutNewCards, openBoundaryWork,
                openConflict, reportCurrent, kind, calibrationWeak, driftGuards,
                com.aresstack.askai.research.host.ResearchRuntimeSettings.policySparseCards(),
                com.aresstack.askai.research.host.ResearchRuntimeSettings.policyNoveltyTurns());
        ConversationPolicyProjection.Stage stage =
                ConversationPolicyProjection.stageOf(inputs);
        conversationPolicyBlock =
                ConversationPolicyProjection.hintBlock(stage, calibrationWeak);
        technicalLog(ConversationPolicyProjection.logLine(stage, inputs));
    }

    /** Whether any ANSWERABLE (open) conflict exists — candidates never arm anything. */
    private boolean hasOpenConflict() {
        synchronized (conceptConflicts) {
            for (ConceptConflictRef ref : conceptConflicts.values()) {
                if (ref.open) {
                    return true;
                }
            }
        }
        return false;
    }

    private String firstOpenConflictId() {
        synchronized (conceptConflicts) {
            for (java.util.Map.Entry<String, ConceptConflictRef> entry
                    : conceptConflicts.entrySet()) {
                if (entry.getValue().open) {
                    return entry.getKey();
                }
            }
        }
        return null;
    }

    /** The OPEN conflicts' CURRENT name paths (resolved via the ID sidecar at render time). */
    private java.util.Map<String, java.util.List<String>> openConflictPaths() {
        java.util.Map<String, java.util.List<String>> paths =
                new java.util.LinkedHashMap<String, java.util.List<String>>();
        com.aresstack.askai.research.concept.ConceptBranchService service = conceptBranchService();
        if (service == null) {
            return paths;
        }
        synchronized (conceptConflicts) {
            for (java.util.Map.Entry<String, ConceptConflictRef> entry
                    : conceptConflicts.entrySet()) {
                if (!entry.getValue().open
                        || !entry.getValue().epoch.equals(service.currentEpoch())) {
                    continue;
                }
                java.util.List<String> path = service.pathOfNodeId(entry.getValue().nodeId);
                if (path != null) {
                    paths.put(entry.getKey(), path);
                }
            }
        }
        return paths;
    }

    /**
     * The whole exclusion in ONE host-owned step: derive the id from the USER'S term, persist the
     * EXCLUDED facet (immediately effective — the blacklist wins as the final suppression step),
     * republish the fence, then scan the concept for an EXACT name conflict. The reply is the
     * structured JSON the tool returns; a conflict obliges the agent to INFORM_AND_ASK_REMOVE —
     * this method never touches the concept itself.
     */
    String excludeTopicCommand(String topic) {
        com.aresstack.askai.research.scope.ResearchScopeCoordinator coordinator = scopeCoordinator();
        if (coordinator == null) {
            return null; // no scope system (fake mode) — the tool reports it honestly
        }
        String label = topic == null ? "" : topic.trim();
        String facetId = slugOf(label);
        com.google.gson.JsonObject reply = new com.google.gson.JsonObject();
        if (facetId.isEmpty()) {
            reply.addProperty("result", "REJECTED");
            reply.addProperty("reason", "the topic yields no technical id — pass the user's term");
            return reply.toString();
        }
        // addFacet carries the user's wording as the label; excludeFacet flips the membership —
        // ONE atomic patch, and putFacet's refine semantics keep an existing facet's label.
        com.aresstack.askai.research.scope.ScopeUpdateResult applied = coordinator.apply(
                new com.aresstack.askai.research.domain.scope.ScopePatch(java.util.Arrays.asList(
                        com.aresstack.askai.research.domain.scope.ScopePatchOperations
                                .addFacet(facetId, label, "explicit user exclusion"),
                        com.aresstack.askai.research.domain.scope.ScopePatchOperations
                                .excludeFacet(facetId, "explicit user exclusion"))),
                java.util.Collections.<com.aresstack.askai.research.domain.scope
                        .UnresolvedScopeIssue>emptyList());
        if (applied.getStatus()
                == com.aresstack.askai.research.scope.ScopeUpdateResult.Status.REJECTED) {
            technicalLog("exclude_topic -> REJECTED: " + applied.getReason());
            reply.addProperty("result", "REJECTED");
            reply.addProperty("reason", applied.getReason());
            return reply.toString();
        }
        technicalLog("exclude_topic -> EXCLUDED id=" + facetId + " label=\"" + label + "\"");
        reply.addProperty("result", "EXCLUDED");
        reply.addProperty("facetId", facetId);
        reply.addProperty("label", label);
        // The DETERMINISTIC user-facing sentence (gate 5): the exclusion is a terminal turn
        // action — the runtime answers from THIS text, no further model inference invents a
        // contradiction ("keine dauerhaften Änderungen" over a committed blacklist entry).
        boolean german = playbook.isGerman();
        StringBuilder userMessage = new StringBuilder(german
                ? "„" + label + "“ wurde ausgeschlossen und wird bei der Recherche unterdrückt."
                : "\"" + label + "\" has been excluded and will be suppressed during research.");
        java.util.List<String> conflictPath = conceptConflictPathOf(label);
        if (conflictPath == null) {
            // Fence republish AFTER the (absent) conflict registration — order matters, see below.
            publishScopeFence();
            fireStateChanged();
            reply.addProperty("requiredResponse", "NONE");
            reply.addProperty("userMessage", userMessage.toString());
            return reply.toString();
        }
        com.aresstack.askai.research.concept.ConceptBranchService conceptService =
                conceptBranchService();
        if (conceptService == null || !conceptService.isLeafAt(conflictPath)) {
            // Safety-slice double guard, part 1: a BRANCH conflict is never offered for
            // removal — the gate saw the host vaporise a non-terminal root after a "Ja.".
            // Until the three-way tree-editor UI the only honest offers are "keep suppressed"
            // and the manual editor; the suppression itself is already in effect.
            publishScopeFence();
            fireStateChanged();
            reply.addProperty("requiredResponse", "NONE");
            reply.addProperty("conceptBranchNote",
                    "the term matches a concept BRANCH with sub-cards — removal is manual "
                            + "editor work, no conflict question is asked");
            userMessage.append(german
                    ? " Der Begriff steht außerdem als Zweig mit Unterkarten im Konzept —"
                            + " dort entfernen ist bis zum Tree-Editor Handarbeit im"
                            + " Konzept-Editor; die Recherche dazu bleibt unabhängig davon"
                            + " unterdrückt."
                    : " The term also matches a concept BRANCH with sub-cards — removing it"
                            + " there is manual concept-editor work until the tree editor"
                            + " ships; research on it stays suppressed either way.");
            reply.addProperty("userMessage", userMessage.toString());
            technicalLog("exclude_topic -> concept BRANCH at " + conflictPath
                    + " — no removable conflict registered (manual editor work)");
            return reply.toString();
        }
        String nodeId = conceptService.nodeIdAtPath(conflictPath);
        if (nodeId == null) {
            // No stable identity for the card (recovery edge) — no removable conflict either.
            publishScopeFence();
            fireStateChanged();
            reply.addProperty("requiredResponse", "NONE");
            reply.addProperty("userMessage", userMessage.toString());
            technicalLog("exclude_topic -> concept match at " + conflictPath
                    + " has no identity — no removable conflict registered");
            return reply.toString();
        }
        String conflictId = "conflict-" + conflictIds.incrementAndGet();
        // OPEN directly: the question is part of THIS turn's visible terminal receipt — and a
        // NEW visible question always replaces a stale tombstone.
        staleDecisionTombstone = null;
        conceptConflicts.put(conflictId, new ConceptConflictRef(conceptService.currentEpoch(),
                nodeId, conceptService.snapshot().getWorkingRevision(), label, true));
        // Register the conflict BEFORE republishing the fence (gate-6 rerun finding): the old
        // order published a conflict-free fence in the exclude turn itself, so the OPEN CONCEPT
        // CONFLICT block only appeared one publish later.
        publishScopeFence();
        fireStateChanged();
        com.google.gson.JsonObject conflict = new com.google.gson.JsonObject();
        conflict.addProperty("conflictId", conflictId);
        com.google.gson.JsonArray pathArray = new com.google.gson.JsonArray();
        for (String segment : conflictPath) {
            pathArray.add(segment);
        }
        conflict.add("path", pathArray);
        reply.add("conceptConflict", conflict);
        reply.addProperty("requiredResponse", "INFORM_AND_ASK_REMOVE");
        String parent = conflictPath.size() >= 2 ? conflictPath.get(conflictPath.size() - 2) : null;
        userMessage.append(german
                ? (parent == null
                        ? " Der Begriff steht außerdem noch auf oberster Ebene im Konzept."
                        : " Der Begriff steht außerdem noch im Konzept unter „" + parent + "“.")
                        + " Soll er dort ebenfalls entfernt werden?"
                : (parent == null
                        ? " The term also still sits at the top level of the concept."
                        : " The term also still appears in the concept under \"" + parent + "\".")
                        + " Should it be removed there as well?");
        reply.addProperty("userMessage", userMessage.toString());
        technicalLog("exclude_topic -> concept conflict " + conflictId + " at " + conflictPath);
        return reply.toString();
    }

    /** The user's answer arrives as its OWN command — the reference stays host-side by id. */
    String resolveConceptConflictCommand(String conflictId, String decision) {
        ConceptConflictRef ref = conceptConflicts.get(conflictId);
        if (ref == null || !ref.open) {
            return "Unknown conflict_id \"" + conflictId + "\" — use the id exclude_topic "
                    + "reported (it is single-use).";
        }
        com.google.gson.JsonObject reply = new com.google.gson.JsonObject();
        boolean german = playbook.isGerman();
        if ("KEEP_SUPPRESSED".equalsIgnoreCase(decision)) {
            conceptConflicts.remove(conflictId);
            technicalLog("resolve_concept_conflict " + conflictId + " -> KEPT_SUPPRESSED");
            reply.addProperty("result", "KEPT_SUPPRESSED");
            reply.addProperty("userMessage", german
                    ? "Der Eintrag bleibt im Konzept stehen; die Recherche dazu bleibt "
                            + "unterdrückt."
                    : "The entry stays in the concept; research on it remains suppressed.");
            return reply.toString();
        }
        if (!"REMOVE".equalsIgnoreCase(decision)) {
            return "Unknown decision \"" + decision + "\" — allowed: REMOVE, KEEP_SUPPRESSED.";
        }
        com.aresstack.askai.research.concept.ConceptBranchService service = conceptBranchService();
        if (service == null) {
            return "This session has no concept service.";
        }
        // Full reason re-check IMMEDIATELY before the destructive commit (V3 invariant 6):
        // the reference's epoch must still be current, the node must still exist, and the
        // underlying exclusion must still stand — a stale reference is closed, never executed.
        if (!ref.epoch.equals(service.currentEpoch())) {
            conceptConflicts.remove(conflictId);
            technicalLog("resolve_concept_conflict " + conflictId
                    + " -> REFUSED: identity epoch changed (reference discarded)");
            return "This conflict belongs to an earlier document state and cannot be executed "
                    + "anymore — nothing was removed.";
        }
        java.util.List<String> path = service.pathOfNodeId(ref.nodeId);
        if (path == null) {
            conceptConflicts.remove(conflictId);
            technicalLog("resolve_concept_conflict " + conflictId
                    + " -> node gone — nothing to remove");
            reply.addProperty("result", "KEPT_SUPPRESSED");
            reply.addProperty("reason", "the entry no longer exists in the concept");
            reply.addProperty("userMessage", german
                    ? "Der Eintrag steht nicht mehr im Konzept — es gibt nichts zu entfernen."
                    : "The entry is no longer in the concept — there is nothing to remove.");
            return reply.toString();
        }
        boolean exclusionStillStands = false;
        for (String term : currentBlacklistTerms()) {
            if (term != null && term.trim().equalsIgnoreCase(ref.label.trim())) {
                exclusionStillStands = true;
                break;
            }
        }
        if (!exclusionStillStands) {
            conceptConflicts.remove(conflictId);
            technicalLog("resolve_concept_conflict " + conflictId
                    + " -> REFUSED: the exclusion was lifted (conflict reason gone)");
            reply.addProperty("result", "KEPT_SUPPRESSED");
            reply.addProperty("reason", "the underlying exclusion no longer exists");
            reply.addProperty("userMessage", german
                    ? "Der Ausschluss besteht nicht mehr — der Eintrag bleibt unverändert im "
                            + "Konzept."
                    : "The exclusion no longer stands — the entry stays in the concept "
                            + "unchanged.");
            return reply.toString();
        }
        if (!service.isLeafAt(path)) {
            // Safety-slice double guard, part 2: re-check IMMEDIATELY before the commit — the
            // card may have grown children between the question and the user's answer. The
            // entry stays, the suppression stands, the conflict is closed honestly.
            conceptConflicts.remove(conflictId);
            technicalLog("resolve_concept_conflict " + conflictId
                    + " -> REFUSED: no longer a leaf at " + path
                    + " (kept suppressed, removal is manual editor work)");
            reply.addProperty("result", "KEPT_SUPPRESSED");
            reply.addProperty("reason", "the entry grew into a branch — a non-leaf is never "
                    + "removed by the host; use the concept editor");
            reply.addProperty("userMessage", german
                    ? "Der Eintrag „" + path.get(path.size() - 1) + "“ hat inzwischen "
                            + "Unterkarten und wird deshalb nicht automatisch entfernt — das "
                            + "geht bis zum Tree-Editor nur im Konzept-Editor. Die Recherche "
                            + "dazu bleibt unterdrückt."
                    : "The entry \"" + path.get(path.size() - 1) + "\" now has sub-cards and "
                            + "is therefore not removed automatically — until the tree editor "
                            + "that is concept-editor work. Research on it stays suppressed.");
            return reply.toString();
        }
        com.aresstack.askai.research.concept.ConceptBranchService.EditResult removed =
                service.removeNodeAt(path);
        if (!removed.isApplied()) {
            return removed.getDiagnostic().describeForModel();
        }
        conceptConflicts.remove(conflictId);
        technicalLog("resolve_concept_conflict " + conflictId + " -> REMOVED " + path
                + " revision=" + removed.getNewRevision());
        reply.addProperty("result", "REMOVED");
        com.google.gson.JsonArray pathArray = new com.google.gson.JsonArray();
        for (String segment : path) {
            pathArray.add(segment);
        }
        reply.add("path", pathArray);
        reply.addProperty("userMessage", german
                ? "Der Eintrag „" + path.get(path.size() - 1) + "“ wurde aus dem Konzept "
                        + "entfernt."
                : "The entry \"" + path.get(path.size() - 1) + "\" has been removed from the "
                        + "concept.");
        return reply.toString();
    }

    /**
     * The HOST resolves the pending conflict deterministically (yes/no answer or decision
     * button): resolve command, deterministic receipt answer, fence republished so the OPEN
     * CONCEPT CONFLICT block (and the buttons) disappear at once. Oldest conflict first — in
     * practice there is exactly one, question and answer alternate.
     */
    private String resolvePendingConflictFromHost(boolean remove) {
        String conflictId = firstOpenConflictId();
        if (conflictId == null) {
            return "rejected: no open concept conflict";
        }
        String reply = resolveConceptConflictCommand(conflictId,
                remove ? "REMOVE" : "KEEP_SUPPRESSED");
        String userMessage = userMessageOf(reply);
        sayAsAgent(userMessage != null ? userMessage : reply);
        publishScopeFence();
        fireStateChanged();
        return "handled: conflict " + conflictId
                + (remove ? " -> removed from the concept" : " -> kept (suppressed)");
    }

    /**
     * The fence's OPEN CONCEPT CONFLICT block (empty string without conflicts) — extracted so a
     * test can PROVE the follow-up turn's prompt carries the conflictId and the exact resolve
     * operation (GPT's gate-6 pin: the model must find both in its context, never invent them).
     */
    static String openConflictBlock(java.util.Map<String, java.util.List<String>> conflicts) {
        if (conflicts.isEmpty()) {
            return "";
        }
        StringBuilder open = new StringBuilder();
        open.append("\nOPEN CONCEPT CONFLICT — you already asked the user whether the "
                + "excluded topic should ALSO be removed from the concept; interpret their "
                + "next answer against that question:\n");
        synchronized (conflicts) {
            for (java.util.Map.Entry<String, java.util.List<String>> conflict
                    : conflicts.entrySet()) {
                open.append("- conflictId \"").append(conflict.getKey()).append("\": card \"")
                        .append(conflict.getValue().get(conflict.getValue().size() - 1))
                        .append("\" is still in the concept\n");
            }
        }
        open.append("If the user AGREES to the removal, answer with EXACTLY "
                + "{\"type\": \"resolve\", \"conflictId\": \"<id from above>\", "
                + "\"decision\": \"REMOVE\"}; if they DECLINE, use decision "
                + "\"KEEP_SUPPRESSED\". No other action in that turn.\n");
        return open.toString();
    }

    /** The receipt's user-facing sentence, or {@code null} when the reply is no such JSON. */
    private static String userMessageOf(String replyJson) {
        try {
            com.google.gson.JsonElement parsed = com.google.gson.JsonParser.parseString(replyJson);
            if (parsed.isJsonObject() && parsed.getAsJsonObject().has("userMessage")) {
                return parsed.getAsJsonObject().get("userMessage").getAsString();
            }
        } catch (RuntimeException notJson) {
            // fall through — the caller shows the raw reply honestly
        }
        return null;
    }

    /** How many exploration tags one offer may carry — the accessory row stays readable. */
    private static final int MAX_OFFERED_SEARCHES = 5;

    /** True while the CURRENT projection's tags came from offer_searches, not the in-band field. */
    private volatile boolean offerAuthoredProjection;

    /**
     * Gate 9 lifetime rule: with the action channel active the in-band suggestions field is
     * DELIBERATELY empty — so the turn's closing (suggestion-free) projection must not wipe the
     * tags offer_searches just set. An EMPTY incoming list keeps the offered tags (the rest of
     * the projection updates normally); a NON-empty one is a real replacement and wins.
     */
    private com.aresstack.askai.research.backend.ScopingAssistantUpdate mergeOfferedTags(
            com.aresstack.askai.research.backend.ScopingAssistantUpdate incoming) {
        com.aresstack.askai.research.backend.ScopingAssistantUpdate merged = mergedProjection(
                incoming, latestScopingProjection, offerAuthoredProjection);
        if (incoming != null && !incoming.getSearchSuggestions().isEmpty()) {
            offerAuthoredProjection = false; // a real in-band replacement ends the offer's reign
        }
        return merged;
    }

    /** The pure merge rule (see above) — static so the lifetime semantics stay test-pinned. */
    static com.aresstack.askai.research.backend.ScopingAssistantUpdate mergedProjection(
            com.aresstack.askai.research.backend.ScopingAssistantUpdate incoming,
            com.aresstack.askai.research.backend.ScopingAssistantUpdate current,
            boolean offerAuthored) {
        if (incoming == null) {
            return current;
        }
        if (!incoming.getSearchSuggestions().isEmpty()) {
            return incoming;
        }
        if (offerAuthored && current != null && !current.getSearchSuggestions().isEmpty()) {
            return new com.aresstack.askai.research.backend.ScopingAssistantUpdate(
                    incoming.getPhaseId(), current.getSearchSuggestions(),
                    incoming.getAdviceRecommendation(), incoming.getAdviceReason());
        }
        return incoming;
    }

    /**
     * The search-offer command (gate 8b): the model authored the suggestions, the HOST renders
     * them — the same yellow tags the in-band field used to feed, now set as the current scoping
     * projection (display state; a later projection replaces it, exactly like a model turn's).
     */
    String offerSearchesCommand(String suggestionsJson) {
        java.util.List<com.aresstack.askai.research.backend.ScopingAssistantUpdate.Suggestion>
                suggestions = parseOfferedSearches(suggestionsJson);
        if (suggestions.isEmpty()) {
            return "No usable suggestion — every entry needs a non-empty \"query\". Example: "
                    + "[{\"query\":\"<topic> basics overview\","
                    + "\"purpose\":\"<core aspect>\"}]";
        }
        latestScopingProjection = new com.aresstack.askai.research.backend.ScopingAssistantUpdate(
                state.getPhaseId(), suggestions, "", "");
        offerAuthoredProjection = true; // the closing empty projection must not wipe these tags
        persistScopingProjection(latestScopingProjection);
        technicalLog("offer_searches -> OFFERED " + suggestions.size() + " tags");
        fireStateChanged();
        com.google.gson.JsonObject reply = new com.google.gson.JsonObject();
        reply.addProperty("result", "OFFERED");
        reply.addProperty("count", suggestions.size());
        return reply.toString();
    }

    /**
     * scope_probe (AP3): the READ-ONLY closure sensor. Pins the effective-fence fingerprint
     * (scope revision + concept epoch#revision — the SAME pair the conversation policy trusts),
     * snapshots the anchors, embeds every term with the current embedding snapshot and lets
     * {@link com.aresstack.askai.research.scope.ScopeProbeService} judge them. It mutates
     * NOTHING: no concept card, no blacklist, no mission, no ScopeCheckReport, no workflow
     * state. Shares the sweep's single-flight flag so probe and sweep never race the anchor
     * vector cache.
     */
    String scopeProbeCommand(java.util.List<String> terms) {
        final com.aresstack.askai.research.scope.ResearchScopeCoordinator coordinator =
                scopeCoordinator();
        if (coordinator == null || !coordinator.isUsable() || handle == null
                || productiveResources == null || productiveResources.isClosed()) {
            return null; // no scope system — the tool answers honestly
        }
        com.aresstack.askai.agent.model.embedding.EmbeddingEndpointDescriptor descriptor =
                productiveResources.getEmbeddingDescriptor();
        if (descriptor == null) {
            technicalLog("scope_probe -> PROBE_UNAVAILABLE (no embedding model)");
            return "PROBE_UNAVAILABLE: no embedding model is configured — tell the user the "
                    + "measurement is unavailable; do not guess.";
        }
        if (!scopeSweepInFlight.compareAndSet(false, true)) {
            technicalLog("scope_probe -> PROBE_UNAVAILABLE (a scope check is running)");
            return "PROBE_UNAVAILABLE: a scope check is running — probe again after it "
                    + "finished.";
        }
        try {
            // Pin the fingerprint FIRST, then snapshot — a mutation after the pin fails the
            // final recheck, so a mixed snapshot can never read as current.
            final String pinned = fenceFingerprintNow();
            com.aresstack.askai.research.domain.scope.ResearchScopeDraft draft =
                    coordinator.current();
            com.aresstack.askai.research.concept.ConceptBranchService conceptService =
                    conceptBranchService();
            java.util.List<com.aresstack.askai.research.domain.scope.ScopeAnchor> conceptAnchors =
                    com.aresstack.askai.research.scope.ConceptAnchorProjection.anchorsOf(
                            conceptService == null ? null
                                    : conceptService.snapshot().getDocumentJson(), draft);
            java.util.List<com.aresstack.askai.research.domain.scope.ScopeAnchor> fenceAnchors =
                    com.aresstack.askai.research.scope.ScopeSweepPlanAssembler.combinedAnchors(
                            draft, conceptAnchors);
            java.util.Map<String, String> labels = new java.util.LinkedHashMap<String, String>();
            for (com.aresstack.askai.research.domain.scope.ScopeAnchor anchor : fenceAnchors) {
                labels.put(anchor.getAnchorId(), anchor.getSemanticText());
            }
            com.aresstack.askai.research.scope.EmbeddingSnapshotSweepEmbedder embedder =
                    new com.aresstack.askai.research.scope.EmbeddingSnapshotSweepEmbedder(
                            descriptor);
            java.util.List<com.aresstack.askai.research.domain.scope
                    .ScopeFenceEvaluator.AnchorVector> anchorVectors;
            try {
                anchorVectors = new com.aresstack.askai.research.store.ScopeAnchorVectorIndex(
                        new java.io.File(productiveResources.getProjectContext()
                                .getProjectDirectory(), "scope-anchor-vectors.json"))
                        .vectorsFor(fenceAnchors, embedder.modelFingerprint(), embedder);
            } catch (java.io.IOException indexFailed) {
                technicalLog("scope_probe -> PROBE_FAILED anchor index: "
                        + indexFailed.getMessage());
                return "PROBE_FAILED: anchor vector index failed — the measurement is "
                        + "unavailable right now.";
            } catch (RuntimeException embeddingBroke) {
                technicalLog("scope_probe -> PROBE_FAILED anchor index: " + embeddingBroke);
                return "PROBE_FAILED: anchor embedding failed — the measurement is "
                        + "unavailable right now.";
            }
            com.aresstack.askai.research.scope.ScopeProbeService.Result result =
                    com.aresstack.askai.research.scope.ScopeProbeService.run(terms,
                            com.aresstack.askai.research.host.ResearchRuntimeSettings
                                    .scopeProbeMaxTerms(),
                            anchorVectors, labels, embedder,
                            com.aresstack.askai.research.host.ResearchRuntimeSettings
                                    .loadScopeSweepConfiguration(getHostStateStore())
                                    .fenceThresholds,
                            pinned,
                            new com.aresstack.askai.research.scope.ScopeProbeService
                                    .FenceFingerprint() {
                                public String current() {
                                    return fenceFingerprintNow();
                                }
                            });
            for (String line : result.logLines()) {
                technicalLog(line);
            }
            return result.receipt();
        } finally {
            scopeSweepInFlight.set(false);
        }
    }

    // ------------------------------------------------ SC1 search-scope control (sensor lane)
    /** One pinned, immutable search-run snapshot — SC1's "a running search never goes stale". */
    private static final class SearchScopeSnapshot {
        final java.util.List<com.aresstack.askai.research.domain.scope
                .ScopeFenceEvaluator.AnchorVector> anchorVectors;
        final java.util.Map<String, String> labelsById;
        final com.aresstack.askai.research.scope.EmbeddingSnapshotSweepEmbedder embedder;
        final com.aresstack.askai.research.domain.scope.ScopeFenceEvaluator.Thresholds
                thresholds;

        SearchScopeSnapshot(java.util.List<com.aresstack.askai.research.domain.scope
                .ScopeFenceEvaluator.AnchorVector> anchorVectors,
                java.util.Map<String, String> labelsById,
                com.aresstack.askai.research.scope.EmbeddingSnapshotSweepEmbedder embedder,
                com.aresstack.askai.research.domain.scope.ScopeFenceEvaluator.Thresholds
                        thresholds) {
            this.anchorVectors = anchorVectors;
            this.labelsById = labelsById;
            this.embedder = embedder;
            this.thresholds = thresholds;
        }
    }

    /** Live run snapshots by opaque handle — transient, cleared with the session. */
    private final java.util.Map<String, SearchScopeSnapshot> searchScopeSnapshots =
            java.util.Collections.synchronizedMap(
                    new java.util.LinkedHashMap<String, SearchScopeSnapshot>());

    /**
     * SC1 begin: pin ONE immutable snapshot of the effective fence (Concept → CANONICAL_IN,
     * Blacklist → CANONICAL_OUT via ConceptAnchorProjection — never legacy PROVISIONAL truth).
     * Zero OUT anchors = INACTIVE fast path (no embeddings, baseline search behaviour); a
     * broken infrastructure WITH an OUT anchor answers UNAVAILABLE — the fence never silently
     * disappears.
     */
    String searchScopeBegin() {
        com.aresstack.askai.research.scope.ResearchScopeCoordinator coordinator =
                scopeCoordinator();
        if (coordinator == null || !coordinator.isUsable() || productiveResources == null
                || productiveResources.isClosed()) {
            return "INACTIVE outAnchors=0"; // no scope system = nothing negotiated OUT
        }
        com.aresstack.askai.research.domain.scope.ResearchScopeDraft draft =
                coordinator.current();
        com.aresstack.askai.research.concept.ConceptBranchService conceptService =
                conceptBranchService();
        java.util.List<com.aresstack.askai.research.domain.scope.ScopeAnchor> conceptAnchors =
                com.aresstack.askai.research.scope.ConceptAnchorProjection.anchorsOf(
                        conceptService == null ? null
                                : conceptService.snapshot().getDocumentJson(), draft);
        java.util.List<com.aresstack.askai.research.domain.scope.ScopeAnchor> fenceAnchors =
                com.aresstack.askai.research.scope.ScopeSweepPlanAssembler.combinedAnchors(
                        draft, conceptAnchors);
        int outAnchors = 0;
        int inAnchors = 0;
        java.util.Map<String, String> labels = new java.util.LinkedHashMap<String, String>();
        for (com.aresstack.askai.research.domain.scope.ScopeAnchor anchor : fenceAnchors) {
            labels.put(anchor.getAnchorId(), anchor.getSemanticText());
            if (anchor.getMembership()
                    == com.aresstack.askai.research.domain.scope.ScopeAnchor.Membership.OUT) {
                outAnchors++;
            } else if (anchor.getMembership()
                    == com.aresstack.askai.research.domain.scope.ScopeAnchor.Membership.IN) {
                inAnchors++;
            }
        }
        // SC2a capability split: the OUT filter and the IN affinity are SEPARATE truths —
        // positive concept steering must work with an EMPTY blacklist, and SC1's fail-closed
        // guarantee must never soften because SC2 is a comfort heuristic.
        boolean outFilter = outAnchors > 0;
        boolean inAffinity = inAnchors > 0;
        if (!outFilter && !inAffinity) {
            technicalLog("search-scope INACTIVE (no anchors)");
            return "INACTIVE outAnchors=0";
        }
        com.aresstack.askai.agent.model.embedding.EmbeddingEndpointDescriptor descriptor =
                productiveResources.getEmbeddingDescriptor();
        if (descriptor == null) {
            if (outFilter) {
                technicalLog("search-scope UNAVAILABLE (no embedding model, outAnchors="
                        + outAnchors + ")");
                return "UNAVAILABLE no embedding model";
            }
            // Pure IN affinity is a measurement, never a boundary: degrade to baseline.
            technicalLog("search-scope INACTIVE (inAffinity unavailable: no embedding model)");
            return "INACTIVE inAffinity unavailable";
        }
        com.aresstack.askai.research.scope.EmbeddingSnapshotSweepEmbedder embedder =
                new com.aresstack.askai.research.scope.EmbeddingSnapshotSweepEmbedder(
                        descriptor);
        java.util.List<com.aresstack.askai.research.domain.scope
                .ScopeFenceEvaluator.AnchorVector> anchorVectors;
        try {
            anchorVectors = new com.aresstack.askai.research.store.ScopeAnchorVectorIndex(
                    new java.io.File(productiveResources.getProjectContext()
                            .getProjectDirectory(), "scope-anchor-vectors.json"))
                    .vectorsFor(fenceAnchors, embedder.modelFingerprint(), embedder);
        } catch (java.io.IOException indexFailed) {
            if (outFilter) {
                technicalLog("search-scope UNAVAILABLE (anchor index: "
                        + indexFailed.getMessage() + ")");
                return "UNAVAILABLE anchor index failed";
            }
            technicalLog("search-scope INACTIVE (inAffinity unavailable: anchor index "
                    + indexFailed.getMessage() + ")");
            return "INACTIVE inAffinity unavailable";
        } catch (RuntimeException embeddingBroke) {
            if (outFilter) {
                technicalLog("search-scope UNAVAILABLE (anchor embedding: "
                        + embeddingBroke + ")");
                return "UNAVAILABLE anchor embedding failed";
            }
            technicalLog("search-scope INACTIVE (inAffinity unavailable: anchor embedding "
                    + embeddingBroke + ")");
            return "INACTIVE inAffinity unavailable";
        }
        String handle = "ss-" + java.util.UUID.randomUUID();
        searchScopeSnapshots.put(handle, new SearchScopeSnapshot(anchorVectors, labels,
                embedder,
                com.aresstack.askai.research.host.ResearchRuntimeSettings
                        .loadScopeSweepConfiguration(getHostStateStore()).fenceThresholds));
        String description = "scopeRev=" + draft.getRevision() + " concept=" + conceptStamp()
                + " embedding=" + embedder.modelFingerprint()
                + " inAnchors=" + inAnchors + " outAnchors=" + outAnchors
                + " outFilter=" + outFilter + " inAffinity=" + inAffinity;
        technicalLog("search-scope snapshot " + description);
        return "ACTIVE handle=" + handle + " " + description;
    }

    /** SC1 evaluate: ONE batch against the pinned snapshot; per-item verdict lines. */
    String searchScopeEvaluate(String handle, String lane, String itemsJson) {
        SearchScopeSnapshot snapshot = searchScopeSnapshots.get(handle);
        if (snapshot == null) {
            return "UNKNOWN_HANDLE";
        }
        java.util.List<com.aresstack.askai.research.scope.SearchScopeGate.Item> items =
                new java.util.ArrayList<com.aresstack.askai.research.scope
                        .SearchScopeGate.Item>();
        try {
            com.google.gson.JsonElement parsed =
                    com.google.gson.JsonParser.parseString(itemsJson == null ? "" : itemsJson);
            for (com.google.gson.JsonElement element : parsed.getAsJsonArray()) {
                com.google.gson.JsonObject object = element.getAsJsonObject();
                items.add(new com.aresstack.askai.research.scope.SearchScopeGate.Item(
                        object.has("id") ? object.get("id").getAsString() : "",
                        object.has("text") ? object.get("text").getAsString() : ""));
            }
        } catch (RuntimeException malformed) {
            return "UNAVAILABLE malformed items_json";
        }
        java.util.List<com.aresstack.askai.research.scope.SearchScopeGate.Decision> decisions;
        try {
            decisions = com.aresstack.askai.research.scope.SearchScopeGate.evaluate(items,
                    snapshot.anchorVectors, snapshot.labelsById, snapshot.embedder,
                    snapshot.thresholds);
        } catch (RuntimeException infrastructure) {
            // Fail-closed: with OUT anchors present a broken filter ends the run typed —
            // never a silent return to unfiltered search.
            technicalLog("search-scope " + lane + " -> UNAVAILABLE (" + infrastructure + ")");
            return "UNAVAILABLE " + infrastructure.getMessage();
        }
        int out = 0;
        int unclassified = 0;
        int nearIn = 0;
        StringBuilder reply = new StringBuilder();
        for (com.aresstack.askai.research.scope.SearchScopeGate.Decision decision : decisions) {
            reply.append('\n').append(decision.verdict.name());
            if (decision.verdict
                    == com.aresstack.askai.research.scope.SearchScopeGate.Verdict.OUT) {
                out++;
                reply.append(" nearest=\"").append(decision.nearestOutLabel).append('"');
                technicalLog("search-scope skip " + lane + " nearest=\""
                        + decision.nearestOutLabel + "\" id=" + decision.id);
            } else if (decision.verdict
                    == com.aresstack.askai.research.scope.SearchScopeGate.Verdict
                            .UNCLASSIFIED) {
                unclassified++;
            } else if (decision.nearIn) {
                // SC2a shadow: the IN side of the same reading, observation only.
                nearIn++;
                reply.append(" in=NEAR");
                if (decision.nearestInLabel != null) {
                    reply.append(" nearest_in=\"").append(decision.nearestInLabel)
                            .append('"');
                }
            }
            reply.append(" id=").append(decision.id);
        }
        technicalLog("search-scope " + lane + " items=" + decisions.size()
                + " kept=" + (decisions.size() - out - unclassified) + " out=" + out
                + " nearIn=" + nearIn
                + (unclassified > 0 ? " unclassified=" + unclassified : ""));
        return "EVALUATED items=" + decisions.size() + reply;
    }

    /** SC1 end: release the run snapshot (best effort; session teardown clears leftovers). */
    String searchScopeEnd(String handle) {
        searchScopeSnapshots.remove(handle);
        return "ENDED";
    }

    /** The effective-fence fingerprint: scope revision + concept epoch#revision. */
    private String fenceFingerprintNow() {
        com.aresstack.askai.research.scope.ResearchScopeCoordinator coordinator =
                scopeCoordinator();
        String scopePart = coordinator == null || !coordinator.isUsable()
                ? "gone" : String.valueOf(coordinator.current().getRevision());
        return scopePart + "|" + conceptStamp();
    }

    /** Lenient parse of {@code [{"query":..,"purpose":..},..]}; broken entries are dropped. */
    static java.util.List<com.aresstack.askai.research.backend.ScopingAssistantUpdate.Suggestion>
            parseOfferedSearches(String suggestionsJson) {
        java.util.List<com.aresstack.askai.research.backend.ScopingAssistantUpdate.Suggestion>
                suggestions = new java.util.ArrayList<com.aresstack.askai.research.backend
                        .ScopingAssistantUpdate.Suggestion>();
        if (suggestionsJson == null) {
            return suggestions;
        }
        try {
            com.google.gson.JsonElement parsed =
                    com.google.gson.JsonParser.parseString(suggestionsJson);
            if (!parsed.isJsonArray()) {
                return suggestions;
            }
            for (com.google.gson.JsonElement element : parsed.getAsJsonArray()) {
                if (!element.isJsonObject() || suggestions.size() >= MAX_OFFERED_SEARCHES) {
                    continue;
                }
                com.google.gson.JsonObject suggestion = element.getAsJsonObject();
                String query = suggestion.has("query") && suggestion.get("query").isJsonPrimitive()
                        ? suggestion.get("query").getAsString().trim() : "";
                if (query.isEmpty()) {
                    continue;
                }
                String purpose = suggestion.has("purpose")
                        && suggestion.get("purpose").isJsonPrimitive()
                        ? suggestion.get("purpose").getAsString().trim() : "";
                suggestions.add(new com.aresstack.askai.research.backend.ScopingAssistantUpdate
                        .Suggestion(query, purpose, suggestions.size() + 1));
            }
        } catch (RuntimeException notJson) {
            suggestions.clear();
        }
        return suggestions;
    }

    /** The blacklist truth for the concept tools: excluded labels/ids + plain exclusions. */
    java.util.List<String> currentBlacklistTerms() {
        com.aresstack.askai.research.scope.ResearchScopeCoordinator coordinator = scopeCoordinator();
        if (coordinator == null || !coordinator.isUsable()) {
            return java.util.Collections.emptyList();
        }
        com.aresstack.askai.research.domain.scope.ResearchScopeDraft draft = coordinator.current();
        java.util.LinkedHashSet<String> terms = new java.util.LinkedHashSet<String>();
        for (com.aresstack.askai.research.domain.scope.ScopeFacet facet : draft.excludedFacets()) {
            terms.add(facet.getLabel());
            terms.add(facet.getFacetId());
        }
        terms.addAll(draft.getExclusions());
        terms.remove("");
        return new java.util.ArrayList<String>(terms);
    }

    /** Exact-name concept conflict of an excluded topic, or {@code null} (concept-less/fake ok). */
    private java.util.List<String> conceptConflictPathOf(String topic) {
        com.aresstack.askai.research.concept.ConceptBranchService service = conceptBranchService();
        if (service == null) {
            return null;
        }
        return com.aresstack.askai.research.concept.ConceptTopicScanner.findExactPath(
                service.snapshot().getDocumentJson(), topic);
    }

    /**
     * KISS mission bookkeeping (live-gate 4): the FIRST user message becomes the mission — the
     * fence calibration needs one, and four gates proved the model will not reliably send
     * setMission. The user (or the model, on a restated goal) can refine it later.
     */
    private void recordMissionIfAbsent(String text) {
        String mission = text == null ? "" : text.trim();
        if (mission.isEmpty()) {
            return;
        }
        com.aresstack.askai.research.scope.ResearchScopeCoordinator coordinator = scopeCoordinator();
        if (coordinator == null || !coordinator.isUsable()
                || !coordinator.current().getMission().isEmpty()) {
            return;
        }
        if (mission.length() > 300) {
            mission = mission.substring(0, 300);
        }
        com.aresstack.askai.research.scope.ScopeUpdateResult applied = coordinator.apply(
                new com.aresstack.askai.research.domain.scope.ScopePatch(
                        java.util.Collections.singletonList(
                                com.aresstack.askai.research.domain.scope.ScopePatchOperations
                                        .setMission(mission))),
                java.util.Collections.<com.aresstack.askai.research.domain.scope
                        .UnresolvedScopeIssue>emptyList());
        if (applied.isApplied()) {
            technicalLog("scope update -> APPLIED: mission recorded from the user's first message");
        }
    }

    /** A stable, short, lowercase-ascii id from a human term (runtime keeps its own copy). */
    private static String slugOf(String label) {
        String lower = label.toLowerCase(java.util.Locale.ROOT)
                .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss");
        StringBuilder sb = new StringBuilder();
        boolean gap = false;
        for (int index = 0; index < lower.length() && sb.length() < 40; index++) {
            char character = lower.charAt(index);
            if ((character >= 'a' && character <= 'z') || (character >= '0' && character <= '9')) {
                if (gap && sb.length() > 0) {
                    sb.append('-');
                }
                sb.append(character);
                gap = false;
            } else {
                gap = true;
            }
        }
        return sb.toString();
    }

    private void reportScopeProblem(final String message) {
        System.err.println("[research-scope] " + message);
        if (sink == null) {
            return;
        }
        final String problemId = publish("scope-update-" + playbookMessageIds.incrementAndGet());
        uiExecutor.execute(new Runnable() {
            public void run() {
                if (!disposed) {
                    sink.showProblem(problemId, message);
                }
            }
        });
    }

    /**
     * Send the AUTHORITATIVE scope to the runtime before a turn. The host owns the draft; without this the
     * model would rebuild the scope from the chat history and lose earlier decisions in the process.
     */
    /** The host-said scope-check question, carried on the fence so the model knows it asked. */
    private volatile String lastScopeCheckQuestion = "";

    private void publishScopeFence() {
        com.aresstack.askai.research.scope.ResearchScopeCoordinator coordinator = scopeCoordinator();
        if (coordinator == null || handle == null || disposed || !coordinator.isUsable()) {
            return;
        }
        String fence = com.aresstack.askai.research.domain.scope.ResearchScopeFenceView.render(
                coordinator.current());
        if (!lastScopeCheckQuestion.isEmpty()) {
            fence = fence + "\nLAST SCOPE CHECK — the assistant already asked the user this "
                    + "question; interpret their next answer against it:\n"
                    + lastScopeCheckQuestion + "\n";
        }
        // Gate-6 fix: the exclude turn is TERMINAL, so the model never saw the tool receipt —
        // without this block it cannot know the conflictId when the user answers the removal
        // question. The fence (the model's per-turn scope truth) carries every open conflict.
        // (The host intercepts unambiguous yes/no answers itself; this block guides the model
        // through everything ELSE said while a conflict is open.)
        fence = fence + openConflictBlock(openConflictPaths());
        // The ratified conversation policy travels in the same host envelope, but its header
        // states explicitly that it is guidance — never part of the Weidezaun itself.
        fence = fence + conversationPolicyBlock;
        backend.submitServiceCommand(handle,
                com.aresstack.askai.research.search.ResearchServiceCommandWire.setScope(fence));
    }

    /** The journal file of this research project, or null (fake mode / no project context). */
    private java.io.File journalFile() {
        return productiveResources == null ? null
                : new java.io.File(productiveResources.getProjectContext().getProjectDirectory(),
                        "phase-journal.json");
    }

    /** Write the journal off the caller thread; a failure costs attribution only, never conversation. */
    private void persistJournal() {
        final java.io.File target = journalFile();
        if (target == null || journalWriteExecutor.isShutdown()) {
            return;
        }
        final String json = journal.toJson();
        try {
            journalWriteExecutor.execute(new Runnable() {
                public void run() {
                    try {
                        java.io.OutputStream out = new java.io.FileOutputStream(target);
                        try {
                            out.write(json.getBytes("UTF-8"));
                        } finally {
                            out.close();
                        }
                    } catch (java.io.IOException writeFailed) {
                        System.err.println("[research-journal] could not persist the phase attribution ("
                                + target + "): " + writeFailed.getMessage());
                    }
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException closing) {
            // the session is shutting down — the attribution of this last message is simply not persisted
        }
    }

    /** Load the persisted phase attribution of this project (absent/corrupt → start empty). */
    private void restorePhaseJournal() {
        java.io.File source = journalFile();
        if (source == null || !source.isFile()) {
            return;
        }
        try {
            journal = ResearchPhaseJournal.fromJson(
                    new String(java.nio.file.Files.readAllBytes(source.toPath()), "UTF-8"));
        } catch (java.io.IOException unreadable) {
            System.err.println("[research-journal] could not read " + source + ": "
                    + unreadable.getMessage() + " — phases of older messages stay unknown");
        }
    }

    /**
     * {@code chat_history}: the CANONICAL persisted conversation of this chat, annotated with the research
     * phase where known. The messages come from the host (one truth, the same the user sees — including
     * everything from before this process started); this session contributes only the attribution.
     */
    private String describeChatHistory(boolean raw) {
        com.aresstack.askai.plugin.api.service.ChatSessionHistoryReader reader =
                getHostService(com.aresstack.askai.plugin.api.service.ChatSessionHistoryReader.class);
        if (reader == null || chatSessionId.isEmpty()) {
            return "(this host does not expose the persisted chat history to plugins)";
        }
        return ResearchChatHistoryProjection.render(reader.readMessages(chatSessionId), journal,
                transcriptPhase(), raw);
    }

    private String transcriptPhase() {
        return productiveResources != null ? productiveResources.currentState().getPhaseId()
                : state.getPhaseId();
    }

    /**
     * The LAST persisted assistant answer of this chat, or {@code null} — the read-aloud source.
     * Reads the host's one truth ({@link com.aresstack.askai.plugin.api.service.ChatSessionHistoryReader}),
     * never a second copy of the text; callable from any thread.
     */
    public com.aresstack.askai.plugin.api.service.ChatMessageSnapshot lastAssistantMessage() {
        com.aresstack.askai.plugin.api.service.ChatSessionHistoryReader reader =
                getHostService(com.aresstack.askai.plugin.api.service.ChatSessionHistoryReader.class);
        if (reader == null || chatSessionId.isEmpty()) {
            return null;
        }
        java.util.List<com.aresstack.askai.plugin.api.service.ChatMessageSnapshot> messages =
                reader.readMessages(chatSessionId);
        for (int index = messages.size() - 1; index >= 0; index--) {
            if (com.aresstack.askai.plugin.api.service.ChatMessageSnapshot.ROLE_ASSISTANT
                    .equals(messages.get(index).getRole())) {
                return messages.get(index);
            }
        }
        return null;
    }

    /** The session's derived-action commands — the single entry point for buttons AND the service MCP. */
    public ResearchDerivedActions derivedActions() {
        return derivedActions;
    }

    /** The session's bot-control endpoint, or {@code null} (fake mode / disabled by configuration). */
    public com.aresstack.askai.research.mcp.ResearchBotControlEndpoint botControlEndpoint() {
        return productiveResources == null || productiveResources.isClosed() ? null
                : productiveResources.getBotControlEndpoint();
    }

    /**
     * STRUCTURED headless command execution (issue #33): a bot sends a COMMAND plus ARGUMENTS - never a
     * chat line with a slash prefix. No command = the arguments are a plain chat message. Unknown commands
     * and commands not allowed in the current phase are rejected with the honest reason and the currently
     * valid command list. Executed on the EDT like real user input.
     */
    public String executeCommand(final String command, final String arguments) {
        final String cmd = command == null ? "" : command.trim().toLowerCase(java.util.Locale.ROOT);
        final String args = arguments == null ? "" : arguments.trim();
        if (cmd.isEmpty() && args.isEmpty()) {
            return "rejected: empty input (send a command, or arguments only for a chat message)";
        }
        if (handle == null || disposed
                || (productiveResources != null && productiveResources.isClosed())) {
            return "rejected: the research session is not active";
        }
        final String[] result = {null};
        final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        uiExecutor.execute(new Runnable() {
            public void run() {
                try {
                    result[0] = executeCommandOnUiThread(cmd, args);
                } catch (RuntimeException failed) {
                    result[0] = "rejected: " + failed.getMessage();
                } finally {
                    done.countDown();
                }
            }
        });
        try {
            if (!done.await(15, java.util.concurrent.TimeUnit.SECONDS)) {
                return "rejected: timed out waiting for the UI thread";
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return "rejected: interrupted";
        }
        return result[0] == null ? "rejected: no result" : result[0];
    }

    private String executeCommandOnUiThread(String cmd, String args) {
        if (cmd.isEmpty()) {
            submitPrompt(args, "");
            return "handled: message sent (TeamAgent turn started)";
        }
        if ("search".equals(cmd)) {
            if (args.isEmpty()) {
                return "rejected: search needs arguments (the query)";
            }
            requestManualWebSearch(args);
            return "handled: web search started (manual_search lifecycle events follow)";
        }
        if ("review-sources".equals(cmd)) {
            return renderOutcome(derivedActions.reviewSources());
        }
        if ("generate-visualization".equals(cmd)) {
            return renderOutcome(derivedActions.generateVisualization());
        }
        if ("generate-outline".equals(cmd)) {
            return renderOutcome(derivedActions.generateOutline());
        }
        if ("check-scope".equals(cmd)) {
            return renderOutcome(derivedActions.checkScope());
        }
        if ("discover-topics".equals(cmd)) {
            return discoverTopicsFromSources();
        }
        if ("cancel-scope-check".equals(cmd)) {
            return cancelScopeCheck();
        }
        if ("resolve-conflict-remove".equals(cmd) || "resolve-conflict-keep".equals(cmd)) {
            return resolvePendingConflictFromHost("resolve-conflict-remove".equals(cmd));
        }
        // Transient OUTCOME offers (the follow-up choices of a finished run) — same names as the tags.
        String offerId = "accept-limitation".equals(cmd) ? "limit" : cmd;
        if (outcomeOffers.contains(offerId)) {
            return runOutcomeOffer(offerId);
        }
        // SEMANTIC state commands — internal ResearchCommandType names are NEVER user/bot API. The
        // processor resolves the semantic name against the CURRENT state (the same projection the buttons
        // use), so "approve" hits whichever approval gate is pending.
        if (!SEMANTIC_COMMANDS.contains(cmd)) {
            return "rejected: unknown command '" + cmd + "'. Valid now: " + validCommandNames();
        }
        ResearchCommandType type = resolveSemanticCommand(cmd);
        if (type == null) {
            com.aresstack.askai.research.state.oo.ResearchStateMemento memento = state;
            return "rejected: '" + cmd + "' is not allowed in " + memento.getPhaseId() + "/"
                    + memento.getStateId() + ". Valid now: " + validCommandNames();
        }
        // The buttons' use cases ARE the commands (one processor, no side paths): submit-scope runs the
        // full brief-approval commit; approvals resolve the pending gate; change requests narrate.
        if (type == ResearchCommandType.SUBMIT_SCOPE && productiveResources != null) {
            ScopingApprovalOutcome outcome = approveScopingBriefAndContinue();
            return outcome == ScopingApprovalOutcome.SUCCESS
                    ? "handled: scope approved, research started"
                    : "rejected: " + scopingApprovalUnavailableReasonFor(outcome);
        }
        if ("approve".equals(cmd) && hasPendingApproval()) {
            approveCurrent();
            return "handled: approved (" + type.name().toLowerCase(java.util.Locale.ROOT) + " gate)";
        }
        if ("request-changes".equals(cmd)) {
            requestChanges("");
            narrateAsAgent("refine", narrator.refinePrompt());
            return "handled: changes requested";
        }
        com.aresstack.askai.research.backend.ResearchCommandDispatchResult dispatched =
                dispatch(type, null);
        return dispatched.isAccepted() ? "handled: dispatched " + cmd
                : "rejected: " + dispatched.getStatus() + " " + dispatched.getDetail();
    }

    private static String renderOutcome(ResearchDerivedActions.ActionOutcome outcome) {
        return (outcome.isAccepted() ? "handled: " : "rejected: ") + outcome.getDetail();
    }

    /** The SEMANTIC state-command names — the whole user/bot vocabulary beside the service commands. */
    private static final java.util.List<String> SEMANTIC_COMMANDS = ResearchSemanticCommands.names();

    /**
     * Resolve a semantic command against the CURRENT state, or {@code null} when it is not available now.
     * The name→candidates table lives in {@link ResearchSemanticCommands} — the ONE forward mapping the
     * red tags, slash commands and the State tab's phase clicks all project from; no second action matrix.
     */
    private ResearchCommandType resolveSemanticCommand(String cmd) {
        java.util.Set<ResearchCommandType> allowed = currentAllowedCommands();
        for (ResearchCommandType candidate : ResearchSemanticCommands.candidates(cmd)) {
            if (allowed.contains(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /** Every command valid RIGHT NOW: the always-on service commands + the resolvable semantic commands. */
    private String validCommandNames() {
        StringBuilder sb = new StringBuilder(
                "search <query>, generate-visualization, generate-outline, review-sources, "
                        + "check-scope");
        if (topicDiscoveryOffered()) {
            sb.append(", discover-topics");
        }
        for (String name : SEMANTIC_COMMANDS) {
            if (resolveSemanticCommand(name) != null) {
                sb.append(", ").append(name);
            }
        }
        for (String id : outcomeOffers) {
            String name = "limit".equals(id) ? "accept-limitation" : id;
            if (sb.indexOf(", " + name) < 0) {
                sb.append(", ").append(name);
            }
        }
        return sb.toString();
    }

    /**
     * STRUCTURED session state for a bot (issue #33): PRIMARILY the current phase/run state, plus everything
     * currently actionable - the valid commands (the same set the buttons show), the clickable decision
     * buttons and the current search suggestions. Pure read; triggers nothing.
     */
    public String describeSessionState() {
        com.aresstack.askai.research.state.oo.ResearchStateMemento memento =
                productiveResources != null ? productiveResources.currentState() : state;
        StringBuilder sb = new StringBuilder();
        sb.append("phase=").append(memento.getPhaseId())
          .append(" state=").append(memento.getStateId())
          .append(" revision=").append(memento.getRevision())
          .append(" pendingApproval=").append(memento.getPendingApprovalId() == null ? "-"
                  : memento.getPendingApprovalId())
          .append(" busy=").append(agentTurnInFlight).append('\n');
        sb.append("commands: ").append(validCommandNames()).append('\n');
        sb.append("buttons:");
        java.util.List<ResearchActionTag> buttons = availableActionTags();
        if (buttons.isEmpty()) {
            sb.append(" -");
        } else {
            for (ResearchActionTag tag : buttons) {
                sb.append(' ').append(tag.getCommand())
                  .append(tag.isEnabled() ? "" : "(disabled)");
            }
        }
        sb.append('\n');
        sb.append("suggestions:");
        com.aresstack.askai.research.backend.ScopingAssistantUpdate projection = latestScopingProjection;
        boolean any = false;
        if (projection != null) {
            for (com.aresstack.askai.research.backend.ScopingAssistantUpdate.Suggestion suggestion
                    : projection.getSearchSuggestions()) {
                if (!wasManuallySearched(suggestion.getQuery())) {
                    // Directly executable: run_command(command=search, arguments=<query>).
                    sb.append(any ? " | " : " ").append("search ")
                      .append('"').append(suggestion.getQuery()).append('"');
                    any = true;
                }
            }
        }
        if (!any) {
            sb.append(" -");
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ outline (explicit action, issue #29)

    /** The persisted outline artifact markdown, or "" when none exists yet. Pure read for the Outline tab. */
    public String outlineMarkdown() {
        if (productiveResources == null || productiveResources.isClosed()) {
            return "";
        }
        try {
            return productiveResources.getArtifactStore().read("outline").getMarkdown();
        } catch (RuntimeException unreadable) {
            return "";
        }
    }

    /**
     * Whether the persisted outline is stale relative to its inputs (new passages, Save/Exclude/⭐), or
     * {@code null} when the knowledge capability is unavailable. Pure metadata — never a rebuild.
     */
    public Boolean outlineStale() {
        return productiveResources == null || productiveResources.isClosed()
                ? null : productiveResources.isOutlineStale();
    }

    /**
     * EXPLICIT user action (issue #29): rebuild topics + outline from the persisted corpus. The ONLY rebuild
     * trigger — session open, tab open, passage completion and source changes never invoke it.
     * @return false when the knowledge capability is unavailable.
     */
    public boolean requestOutlineRebuild() {
        return productiveResources != null && !productiveResources.isClosed()
                && productiveResources.triggerOutlineRebuild();
    }

    private void scheduleVisualization(String phaseId, String markdown) {
        com.aresstack.askai.research.visualize.LazyArtifactVisualizer visualizer = artifactVisualizer();
        if (visualizer == null) {
            com.aresstack.askai.research.visualize.VisualizerDiagnostics.log(
                    "unavailable reason=no AgentInferencePort");
            visualizationStatus = com.aresstack.askai.research.visualize.VisualizationStatus.FAILED;
            uiExecutor.execute(new Runnable() {
                public void run() {
                    fireStateChanged();
                }
            });
            return;
        }
        visualizer.onArtifactChanged(new com.aresstack.askai.research.visualize.ArtifactSnapshot(
                "research-brief", markdown, phaseId));
    }

    private synchronized com.aresstack.askai.research.visualize.LazyArtifactVisualizer artifactVisualizer() {
        if (artifactVisualizer == null && !disposed) {
            com.aresstack.askai.agent.model.inference.AgentInferencePort port = hostContext == null ? null
                    : hostContext.getService(com.aresstack.askai.agent.model.inference.AgentInferencePort.class);
            if (port == null) {
                return null; // no host inference (e.g. clickdummy): no visualization, brief still works
            }
            artifactVisualizer = new com.aresstack.askai.research.visualize.LazyArtifactVisualizer(
                    new com.aresstack.askai.research.visualize.ModelArtifactVisualizer(port),
                    new java.util.function.BooleanSupplier() {
                        public boolean getAsBoolean() {
                            return agentTurnInFlight; // defer while the main agent works
                        }
                    },
                    new java.util.function.Consumer<
                            com.aresstack.askai.research.visualize.VisualizationProjection>() {
                        public void accept(
                                com.aresstack.askai.research.visualize.VisualizationProjection projection) {
                            latestVisualization = projection;
                            // Persist the derived result so a restart shows it instead of regenerating
                            // (issue #29). Best-effort: a write failure only costs the restore.
                            try {
                                com.aresstack.askai.research.store.FileVisualizationStore store =
                                        visualizationStore();
                                if (store != null) {
                                    store.save(projection);
                                }
                            } catch (RuntimeException persistFailed) {
                                com.aresstack.askai.research.visualize.VisualizerDiagnostics.log(
                                        "persist failed: " + persistFailed.getMessage());
                            }
                            uiExecutor.execute(new Runnable() {
                                public void run() {
                                    fireStateChanged();
                                }
                            });
                        }
                    },
                    new java.util.function.Consumer<
                            com.aresstack.askai.research.visualize.VisualizationStatus>() {
                        public void accept(
                                com.aresstack.askai.research.visualize.VisualizationStatus status) {
                            visualizationStatus = status;
                            uiExecutor.execute(new Runnable() {
                                public void run() {
                                    fireStateChanged();
                                }
                            });
                        }
                    });
        }
        return artifactVisualizer;
    }

    public void addStateListener(Runnable listener) {
        if (listener != null) {
            stateListeners.addIfAbsent(listener);
        }
    }

    public void removeStateListener(Runnable listener) {
        stateListeners.remove(listener);
    }

    private void fireStateChanged() {
        for (Runnable listener : stateListeners) {
            try {
                listener.run();
            } catch (RuntimeException ex) {
                // A broken observer must never take the session (or other observers) down.
            }
        }
    }

    /**
     * A read-only snapshot of the hierarchical state, rebuilt from the live {@link ResearchStateMemento} — the
     * exact phase/state/continuation/approval id, never a defaulted continuation.
     */
    public ResearchStateSnapshot currentResearchSnapshot() {
        return ResearchStateSnapshot.of(stateFactory.restore(state), revision, problemMessage);
    }

    /**
     * The persisted work packages of this project (ADR 0001, section 4.4), oldest first — read-only, for the
     * task list in the State tab. Empty without a productive project context.
     */
    public java.util.List<com.aresstack.askai.research.domain.task.ResearchTask> currentResearchTasks() {
        if (productiveResources == null || productiveResources.getProjectContext() == null) {
            return java.util.Collections.emptyList();
        }
        return productiveResources.getProjectContext().getTaskStore().loadAll().getTasks();
    }

    /**
     * The sources MINDMAP, built completely mechanically (no model): root = the research question,
     * branches = the user's search queries, leaves = each branch's best-rated sources. @return the
     * Mermaid source, or {@code null} when no source qualifies yet.
     */
    public String buildSourceMindmapMermaid() {
        java.util.List<com.aresstack.askai.research.sources.ResearchSourceRecord> sources =
                getSourceRepository().find(com.aresstack.askai.research.sources.SourceQuery.all());
        return com.aresstack.askai.research.visualize.SourceMindmap.mermaid(
                mindmapRootTitle(), sources);
    }

    /** The brief's first content line (headings unwrapped) as the mindmap root, else "Recherche". */
    private String mindmapRootTitle() {
        try {
            com.aresstack.askai.research.store.FileResearchBriefStore store = researchBriefStore();
            String content = store == null ? null : store.effectiveContent();
            if (content != null) {
                for (String line : content.split("\n")) {
                    String trimmed = line.replaceFirst("^#+\\s*", "").trim();
                    if (!trimmed.isEmpty()) {
                        return trimmed;
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // A broken brief store never blocks the mindmap — the generic root is fine.
        }
        return "Recherche";
    }

    // ------------------------------------------------------------------ run progress / outcome cards

    /** The one in-place progress card of the active run (null when no run is being rendered). */
    private String currentRunActivityId;
    private boolean runCardStarted;
    private com.aresstack.askai.research.backend.ResearchRunProgressInfo lastRunProgress;
    /** The visible activity context of the active run: what is searched, which page is open right now. */
    private String runSearchQuery = "";
    private String runCurrentHost = "";
    private String runCurrentPageTitle = "";
    /** Non-empty while the run searches over a REST provider — the search shows NO browser. */
    private String runApiSearchProvider = "";
    /** The open thought bubble of the REST search, or null (closed when the results are in). */
    private String apiSearchBubbleId;
    /** Bounded, user-readable history of the last processed websites (accepted/skipped) in the card. */
    private final java.util.ArrayDeque<String> runActivityHistory = new java.util.ArrayDeque<String>();
    private static final int RUN_HISTORY_LINES = 5;

    /**
     * Bounded ring buffer of the technical detail lines — what the collapsed diagnostics area
     * shows, kept re-readable so a DRIVING client (bot MCP technical_log) can reconstruct the
     * agent strategy without the GUI. The cap is memory safety only, not a workflow budget.
     */
    private final java.util.ArrayDeque<String> technicalLogLines =
            new java.util.ArrayDeque<String>();
    private static final int TECHNICAL_LOG_MAX_LINES = 2000;

    /** The ONE recorder: every technical line goes to the sink AND into the readable tail. */
    private void technicalLog(String line) {
        synchronized (technicalLogLines) {
            technicalLogLines.addLast(line == null ? "" : line);
            while (technicalLogLines.size() > TECHNICAL_LOG_MAX_LINES) {
                technicalLogLines.removeFirst();
            }
        }
        if (sink != null) { // headless/tests: the readable tail above still records everything
            sink.appendTechnicalLog(line);
        }
    }

    /** The last {@code tailLines} technical lines, oldest first ({@code <= 0} = a sane default). */
    String describeTechnicalLog(int tailLines) {
        int wanted = tailLines <= 0 ? 200 : tailLines;
        StringBuilder sb = new StringBuilder();
        synchronized (technicalLogLines) {
            int skip = Math.max(0, technicalLogLines.size() - wanted);
            int index = 0;
            for (String line : technicalLogLines) {
                if (index++ < skip) {
                    continue;
                }
                sb.append(line).append('\n');
            }
        }
        return sb.length() == 0 ? "(no technical details recorded yet)" : sb.toString();
    }

    private void applyRunLog(ResearchBackendEvent event) {
        // Full diagnostics belong EXCLUSIVELY to the host's collapsed "Technical details" area — the
        // visible progress card never carries raw log lines, source ids or redirect URLs.
        technicalLog(event.getText());
        // EXCEPTION by user decision (gate 6 review): the concept/scope TOOL CALLS of a turn are
        // deliberately visible — a yellow thought bubble mirrors each round, like the research
        // phase's activity bubbles. The raw diagnostics still live only in the technical log.
        updateConceptToolBubble(event.getText());
    }

    // ------------------------------------------------------ visible concept tool-call bubble

    /** The open yellow bubble mirroring this turn's concept/scope tool rounds, or {@code null}. */
    private String conceptToolBubbleId;
    private final java.util.List<String> conceptToolBubbleLines = new java.util.ArrayList<String>();
    private final java.util.concurrent.atomic.AtomicLong conceptToolBubbleSequence =
            new java.util.concurrent.atomic.AtomicLong();

    private void updateConceptToolBubble(String logLine) {
        if (sink == null || logLine == null || !logLine.startsWith("concept ")) {
            return;
        }
        if (conceptToolBubbleId == null) {
            conceptToolBubbleId = "concept-tools-" + conceptToolBubbleSequence.incrementAndGet();
            conceptToolBubbleLines.clear();
            sink.startThinking(conceptToolBubbleId, playbook.isGerman()
                    ? "Werkzeug-Schritte an Konzept & Themenraum …"
                    : "Tool steps on concept & scope …");
        }
        conceptToolBubbleLines.add(logLine.substring("concept ".length()));
        StringBuilder body = new StringBuilder();
        for (String line : conceptToolBubbleLines) {
            body.append("• ").append(line).append('\n');
        }
        sink.updateThinking(conceptToolBubbleId, body.toString());
    }

    /** Close the bubble with an honest step count — never leave it thinking past the turn. */
    private void finishConceptToolBubble() {
        if (conceptToolBubbleId != null && sink != null) {
            sink.finishThinking(conceptToolBubbleId, playbook.isGerman()
                    ? conceptToolBubbleLines.size() + " Werkzeug-Schritte"
                    : conceptToolBubbleLines.size() + " tool steps");
        }
        conceptToolBubbleId = null;
        conceptToolBubbleLines.clear();
    }

    private void applyRunProgress(ResearchBackendEvent event) {
        com.aresstack.askai.research.backend.ResearchRunProgressInfo info = event.getRunProgress();
        String id = event.getActivityId();
        boolean newCard = !runCardStarted || !id.equals(currentRunActivityId);
        if (newCard) {
            resetRunActivityContext();
        }
        lastRunProgress = info;
        rememberRunActivity(info);
        updateApiSearchBubble(info, id);
        if (newCard) {
            currentRunActivityId = id;
            runCardStarted = true;
            sink.startToolActivity(id, playbook.progressTitle(), progressCardBody());
        } else {
            sink.updateToolActivity(id, playbook.progressTitle(), progressCardBody());
        }
    }

    /**
     * The REST search is a THOUGHT BUBBLE, not browser activity: it opens with the first SEARCHING_API
     * progress and closes as soon as the run moves on (results in → the browser then only opens the
     * individual result pages, exactly as before).
     */
    private void updateApiSearchBubble(
            com.aresstack.askai.research.backend.ResearchRunProgressInfo info, String runId) {
        boolean searchingViaApi = "SEARCHING_API".equals(info.getActivityToken());
        if (searchingViaApi && apiSearchBubbleId == null) {
            apiSearchBubbleId = "api-search-" + runId;
            sink.startThinking(apiSearchBubbleId, playbook.apiSearchThinking(
                    runApiSearchProvider, runSearchQuery));
        } else if (!searchingViaApi && apiSearchBubbleId != null) {
            sink.finishThinking(apiSearchBubbleId,
                    playbook.apiSearchDone(runApiSearchProvider));
            apiSearchBubbleId = null;
        }
    }

    private void resetRunActivityContext() {
        if (apiSearchBubbleId != null && sink != null) {
            // A run that ends while the bubble is open (cancel, provider error) must not leave it thinking.
            sink.finishThinking(apiSearchBubbleId, playbook.apiSearchDone(runApiSearchProvider));
            apiSearchBubbleId = null;
        }
        runSearchQuery = "";
        runCurrentHost = "";
        runCurrentPageTitle = "";
        runApiSearchProvider = "";
        runActivityHistory.clear();
    }

    /** Fold one progress snapshot into the card's visible activity context + bounded history. */
    private void rememberRunActivity(com.aresstack.askai.research.backend.ResearchRunProgressInfo info) {
        if (!info.getSearchQuery().isEmpty()) {
            runSearchQuery = info.getSearchQuery();
        }
        if ("SEARCHING_API".equals(info.getActivityToken())) {
            // The provider LABEL travels in the host field — it is not a website and never becomes
            // "currently open"; the search step involves no browser at all.
            runApiSearchProvider = info.getCurrentHost();
            return;
        }
        if (!info.getCurrentHost().isEmpty()) {
            runCurrentHost = info.getCurrentHost();
            runCurrentPageTitle = info.getCurrentPageTitle();
        }
        String token = info.getActivityToken();
        if ("SOURCE_ACCEPTED".equals(token) && !info.getCurrentHost().isEmpty()) {
            pushRunHistory(playbook.historyAccepted(info.getCurrentHost(),
                    info.getCurrentPageTitle()));
        } else if ("PAGE_SKIPPED".equals(token) && !info.getCurrentHost().isEmpty()) {
            pushRunHistory(playbook.historySkipped(info.getCurrentHost()));
        }
    }

    private void pushRunHistory(String entry) {
        runActivityHistory.addLast(entry);
        while (runActivityHistory.size() > RUN_HISTORY_LINES) {
            runActivityHistory.removeFirst();
        }
    }

    private void applyRunOutcome(ResearchBackendEvent event) {
        setAgentTurnInFlight(false); // the run is over; the user decides the next step
        final com.aresstack.askai.research.backend.ResearchRunOutcomeInfo outcome = event.getRunOutcome();
        // The structured outcome narrative IS the phase summary for chat_history's default rendering.
        if (journal.recordOutcome(transcriptPhase(), narrator.outcomeNarrative(outcome))) {
            persistJournal();
        }
        if (runCardStarted && currentRunActivityId != null) {
            sink.completeToolActivity(currentRunActivityId, playbook.runFinishedSummary(
                    outcome.getPagesVisited(), outcome.getAcceptedSources(), outcome.getDistinctHosts()));
        }
        currentRunActivityId = null;
        runCardStarted = false;
        resetRunActivityContext();
        // Uniform action surface: the narrative is a normal assistant message; the follow-up choices
        // become RED tags (offered commands) — the LAST chat card is gone.
        sayAsAgent(narrator.outcomeNarrative(outcome));
        lastOutcome = outcome;
        outcomeOffers = outcomeOfferIds(outcome);
    }

    // ------------------------------------------------------------------ user attention (manual challenge)

    /** Domain families with a visible attention notice; guards the once-per-episode sound. */
    private final java.util.Set<String> attentionEpisodes = new java.util.HashSet<String>();
    /** Injectable for tests; default: one audible attention beep. */
    private volatile Runnable attentionSound = new Runnable() {
        public void run() {
            try {
                java.awt.Toolkit.getDefaultToolkit().beep();
            } catch (RuntimeException ignored) {
                // headless/CI: no sound device is never an error
            }
        }
    };

    /** Test seam: replace the attention sound. */
    public void setAttentionSound(Runnable sound) {
        this.attentionSound = sound == null ? new Runnable() {
            public void run() {
            }
        } : sound;
    }

    /** REQUIRED → persistent visible notice + ONE sound per episode; RESOLVED → visible all-clear. */
    private void applyUserAttention(ResearchBackendEvent event) {
        String domain = event.getPublicMessage() == null ? "" : event.getPublicMessage();
        boolean resolved = "RESOLVED".equals(event.getText());
        if (!resolved) {
            if (attentionEpisodes.add(domain)) {
                attentionSound.run();
                sink.showProblem(publish("attention-" + domain), playbook.attentionRequired(domain));
            }
            return;
        }
        if (attentionEpisodes.remove(domain)) {
            sink.appendAssistantMessage(publish("attention-resolved-" + domain),
                    playbook.attentionResolved(domain));
        }
    }

    /**
     * The card's visible body: what is searched, which real website is open right now (final host +
     * page title), the counters and a bounded history of the last processed websites. No raw URLs,
     * no source ids, no log lines — those live in the host's collapsed "Technical details" only.
     */
    private String progressCardBody() {
        StringBuilder sb = new StringBuilder();
        if (lastRunProgress != null) {
            if (!runSearchQuery.isEmpty()) {
                sb.append(runApiSearchProvider.isEmpty()
                        ? playbook.progressSearchLine(runSearchQuery)
                        : playbook.progressApiSearchLine(runApiSearchProvider, runSearchQuery))
                        .append("\n\n");
            }
            if (!runCurrentHost.isEmpty()) {
                sb.append(playbook.progressPageLine(runCurrentHost, runCurrentPageTitle))
                        .append("\n\n");
            }
            sb.append(playbook.progressLine(lastRunProgress.getPagesVisited(),
                    lastRunProgress.getAcceptedSources(), lastRunProgress.getDistinctHosts(),
                    lastRunProgress.getActivityToken()));
            if (!runActivityHistory.isEmpty()) {
                sb.append("\n\n").append(playbook.recentPagesTitle());
                for (String entry : runActivityHistory) {
                    sb.append('\n').append(entry);
                }
            }
        }
        return sb.toString();
    }

    /** The typed actions offered on the result card — chosen by stop situation (never enum names). */
    /** The transient follow-up choices of a finished run — offered as red tags until a DECISION runs. */
    private volatile java.util.List<String> outcomeOffers = java.util.Collections.emptyList();
    private volatile com.aresstack.askai.research.backend.ResearchRunOutcomeInfo lastOutcome;

    private java.util.List<String> outcomeOfferIds(
            com.aresstack.askai.research.backend.ResearchRunOutcomeInfo o) {
        java.util.List<String> ids = new ArrayList<String>();
        String stop = o.getStopReason();
        if ("USER_CANCELLED".equals(stop)) {
            ids.add("resume");
            ids.add("sources");
            ids.add("end");
        } else if ("MCP_UNAVAILABLE".equals(stop)) {
            ids.add("retry");
            ids.add("config");
        } else if ("ERROR_BUDGET_EXHAUSTED".equals(stop)) {
            ids.add("retry");
            ids.add("sources");
            ids.add("end");
        } else if ("SUFFICIENT_EVIDENCE".equals(stop)
                || ("SOURCE_BUDGET_EXHAUSTED".equals(stop) && o.isEvidenceSufficient())) {
            ids.add("review");
            ids.add("sources");
            ids.add("end");
        } else if ("RERANKER_UNAVAILABLE".equals(stop) || "RERANKER_TIMEOUT".equals(stop)
                || "RERANKER_INVALID_RESPONSE".equals(stop) || "SEARCH_TECHNICAL_PROBLEM".equals(stop)
                || "SCOPE_CONTROL_UNAVAILABLE".equals(stop)) {
            // Technical search/reranker failures: retry or fix the configuration — NEVER "accept the
            // limitation" (there is no research result to accept, only a failed component).
            ids.add("retry");
            ids.add("config");
            ids.add("end");
        } else if ("RERANKER_CONFIGURATION_ERROR".equals(stop)) {
            // The snapshot/selection is invalid: fixing the configuration comes FIRST.
            ids.add("config");
            ids.add("retry");
            ids.add("end");
        } else if ("NO_SEMANTIC_MATCHES".equals(stop)) {
            // Semantic outcome, not a failure: no candidate passed the selection policy.
            ids.add("refine");
            ids.add("sources");
            ids.add("end");
        } else if ("NO_RELEVANT_PATHS".equals(stop) && !o.isEvidenceSufficient()) {
            ids.add("refine");
            ids.add("sources");
            ids.add("end");
        } else {
            // Budget exhausted with open evidence requirements — the screenshotted case.
            ids.add("continue");
            ids.add("sources");
            ids.add("refine");
            ids.add("limit");
            ids.add("end");
        }
        return ids;
    }

    /**
     * Run ONE offered outcome follow-up (red tag / run_command). Navigation offers (sources, config) never
     * consume the offer set; a DECISION consumes ALL offers — the same semantics the old result card had.
     */
    private String runOutcomeOffer(String id) {
        boolean navigation = "sources".equals(id) || "config".equals(id);
        if ("continue".equals(id) || "retry".equals(id) || "resume".equals(id)) {
            continueResearchTurn();
        } else if ("sources".equals(id)) {
            openArtifactView("sources");
        } else if ("config".equals(id)) {
            sayAsAgent(playbook.isGerman()
                    ? "Die Einstellungen findest du im Zahnrad-Menü unten am Eingabefeld "
                            + "(Kategorie „Research Agent“)."
                    : "You find the settings in the gear menu at the composer "
                            + "(category „Research Agent“).");
        } else if ("refine".equals(id)) {
            narrateAsAgent("refine", narrator.refinePrompt()); // the composer is free; the user just types
        } else if ("limit".equals(id)) {
            com.aresstack.askai.research.backend.ResearchRunOutcomeInfo outcome = lastOutcome;
            if (outcome == null) {
                return "rejected: no run outcome to accept a limitation for";
            }
            recordLimitation(outcome);
        } else if ("end".equals(id)) {
            cancel(); // the controlled end of the research phase (state machine stays the authority)
        } else if ("review".equals(id)) {
            requestEvidenceReview();
        } else {
            return "rejected: unknown outcome action " + id;
        }
        if (!navigation) {
            outcomeOffers = java.util.Collections.emptyList();
            uiExecutor.execute(new Runnable() {
                public void run() {
                    fireStateChanged();
                }
            });
        }
        return "handled: " + id;
    }

    /** Continue with the STORED question, a fresh budget and no re-visits (the agent keeps its history). */
    private void continueResearchTurn() {
        if (productiveResources == null || handle == null) {
            return;
        }
        if (com.aresstack.askai.research.state.oo.ResearchStateIds.PAUSED
                .equals(productiveResources.currentState().getStateId())) {
            dispatch(ResearchCommandType.RESUME, null);
        }
        if (!researchQuestion.isEmpty()
                && com.aresstack.askai.research.state.oo.ResearchStateIds.RUNNING
                        .equals(productiveResources.currentState().getStateId())) {
            setAgentTurnInFlight(true); // cleared by the next RUN_OUTCOME / terminal
            backend.submitPrompt(handle, new ResearchPrompt(researchQuestion, ""));
        } else {
            narrateAsAgent("refine", narrator.refinePrompt());
        }
    }

    /** #28/#39.7: the ACTIVE passages derived from one source — read-only inspection. */
    public java.util.List<com.aresstack.askai.research.domain.Passage> passagesForSource(
            String sourceId) {
        return productiveResources == null || productiveResources.isClosed()
                ? java.util.Collections.<com.aresstack.askai.research.domain.Passage>emptyList()
                : productiveResources.passagesForSource(sourceId);
    }

    /** #39: import ONE user source through the canonical acceptance boundary. */
    public String importUserSource(
            com.aresstack.askai.research.capture.SourceImportService.SourceInput input) {
        if (productiveResources == null || productiveResources.isClosed()
                || productiveResources.sourceImportService() == null) {
            return "rejected: this session has no import capability";
        }
        com.aresstack.askai.research.capture.SourceImportService.ImportOutcome outcome =
                productiveResources.sourceImportService().importSource(input,
                        sessionLanguage.currentLanguage().getCode());
        technicalLog("source-import -> " + outcome.status
                + (outcome.sourceId != null ? " id=" + outcome.sourceId : "")
                + " warnings=" + outcome.warnings.size());
        // The import runs on a background worker (off-EDT slice); the state notification
        // marshals to the UI executor like every other background-triggered refresh —
        // listeners touch Swing and must never run on the import worker.
        onUi(new Runnable() {
            public void run() {
                fireStateChanged(); // the sources table re-reads the repository
            }
        });
        switch (outcome.status) {
            case IMPORTED:
                return "handled: imported \"" + outcome.title + "\" (" + outcome.sourceId
                        + (outcome.warnings.isEmpty() ? ")"
                                : ", " + outcome.warnings.size() + " warning(s))");
            case DUPLICATE:
                return "rejected: this content is already present (" + outcome.sourceId + ")";
            case EMPTY:
                return "rejected: no readable text in the input";
            default:
                return "rejected: import failed";
        }
    }

    /** #43 slice 9: the Sources view registers how a source gets focused (spike contract). */
    public interface SourcesFocusHandler {
        void focusSource(String sourceId);
    }

    private volatile SourcesFocusHandler sourcesFocusHandler;

    public void setSourcesFocusHandler(SourcesFocusHandler handler) {
        this.sourcesFocusHandler = handler;
    }

    /** Document [n] badge → reveal the Sources tab and focus that source (best effort). */
    public void revealSourceInSourcesTab(final String sourceId) {
        openArtifactView("sources");
        final SourcesFocusHandler handler = sourcesFocusHandler;
        if (handler != null) {
            uiExecutor.execute(new Runnable() {
                public void run() {
                    handler.focusSource(sourceId);
                }
            });
        }
    }

    /** Reveal an artifact tab via the host service; degrade VISIBLY when the host offers none. */
    private void openArtifactView(String artifactId) {
        com.aresstack.askai.plugin.api.service.ArtifactViewOpener opener = hostContext == null
                ? null : hostContext.getService(com.aresstack.askai.plugin.api.service.ArtifactViewOpener.class);
        if (opener != null) {
            opener.openArtifact(artifactId);
        } else {
            sayAsAgent(playbook.isGerman()
                    ? "Die Ansicht kann hier nicht geöffnet werden — bitte öffne den Tab \"" + artifactId
                            + "\" im Arbeitsbereich."
                    : "This view cannot be opened here — please open the \"" + artifactId
                            + "\" tab in the workspace.");
        }
    }

    /** Record the unmet evidence requirement VISIBLY and move on towards review — never silently. */
    private void recordLimitation(com.aresstack.askai.research.backend.ResearchRunOutcomeInfo outcome) {
        // Issue #32: no research-notes artifact anymore — the visible chat note (persisted transcript) plus
        // the structured run-outcome card ARE the record; diagnostics are never copied into a notes document.
        sayAsAgent(playbook.limitationRecorded(outcome));
        requestEvidenceReview();
    }

    /** Move on to the evidence review when the state machine allows it (the machine stays authority). */
    private void requestEvidenceReview() {
        if (currentAllowedCommands().contains(ResearchCommandType.REQUEST_EVIDENCE_REVIEW)
                && dispatch(ResearchCommandType.REQUEST_EVIDENCE_REVIEW, null).isAccepted()) {
            // The evidence gate (EVIDENCE/waiting_approval) now presents its approve/request-changes buttons —
            // without this the review click advanced the state but showed nothing ("geht nicht weiter").
            showRestoredActionsIfAny();
        }
    }

    private void applyActivity(ResearchBackendEvent event) {
        String id = event.getActivityId();
        switch (event.getActivityKind()) {
            case THINKING_STARTED:
                sink.startThinking(id, event.getTitle());
                break;
            case THINKING_UPDATE:
                sink.updateThinking(id, event.getText());
                break;
            case THINKING_FINISHED:
                sink.finishThinking(id, event.getText());
                break;
            case TOOL_STARTED:
                sink.startToolActivity(id, event.getTitle(), event.getText());
                break;
            case TOOL_UPDATE:
                sink.updateToolActivity(id, event.getTitle(), event.getText());
                break;
            case TOOL_COMPLETED:
                sink.completeToolActivity(id, event.getText());
                break;
            case TOOL_FAILED:
                sink.failToolActivity(id, event.getText());
                break;
            case APPROVAL_REQUIRED:
                sink.requestApproval(publish(id), event.getText());
                break;
            default:
                break;
        }
    }

    /**
     * Single source of command availability: the allowed set of the live state (productive mode reads the
     * authoritative resources state). This is the "available research actions" projection the UI renders —
     * it never re-implements phase rules.
     */
    private List<String> allowedCommandNames() {
        // ONE vocabulary everywhere: /search + /open (composer text adapters) plus the semantic state
        // commands the resolver accepts right now — the same names run_command and the red tags use.
        List<String> names = new ArrayList<String>();
        names.add("search");
        names.add("open");
        for (String name : SEMANTIC_COMMANDS) {
            if (resolveSemanticCommand(name) != null) {
                names.add(name);
            }
        }
        return names;
    }

    /** The composer route: plain prompts go to the backend; stop pauses the run. */
    private final class ResearchChatTarget implements ChatSubmissionTarget {
        public SubmissionAvailability getAvailability() {
            String stateId = state.getStateId();
            boolean terminal = com.aresstack.askai.research.state.oo.ResearchStateIds.isTerminal(stateId);
            if (disposed || handle == null || terminal) {
                return SubmissionAvailability.UNAVAILABLE;
            }
            if (productiveResources != null) {
                // Productive mode: "running" is the PHASE (research stays active between turns) — the
                // composer is busy only while an agent TURN is actually in flight. Otherwise the user
                // could never type again after "Agent turn completed".
                return agentTurnInFlight ? SubmissionAvailability.BUSY
                        : SubmissionAvailability.AVAILABLE;
            }
            return com.aresstack.askai.research.state.oo.ResearchStateIds.RUNNING.equals(stateId)
                    ? SubmissionAvailability.BUSY : SubmissionAvailability.AVAILABLE;
        }

        public void submitText(String text) {
            if (text != null && !text.trim().isEmpty()) {
                submitPrompt(text, "");
            }
        }

        public void stop() {
            if (postSearchSummaryInFlight) {
                cancel(); // the review-abort path: cancels the model call, clears bubble + busy
                return;
            }
            if (activeManualSearchRequestId != null) {
                // Stop pressed DURING the user's web search: it stops THAT search — never the phase.
                // The runtime answers with a terminal event; the composer is released immediately.
                cancelManualWebSearch();
                setAgentTurnInFlight(false);
                return;
            }
            pause();
        }
    }
}
