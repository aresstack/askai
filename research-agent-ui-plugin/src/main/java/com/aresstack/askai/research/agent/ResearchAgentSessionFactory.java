package com.aresstack.askai.research.agent;

import com.aresstack.askai.mcp.api.McpServerRegistry;
import com.aresstack.askai.plugin.api.agent.AgentHostContext;
import com.aresstack.askai.plugin.api.agent.AgentSession;
import com.aresstack.askai.plugin.api.agent.AgentSessionCreationRequest;
import com.aresstack.askai.plugin.api.agent.AgentSessionFactory;
import com.aresstack.askai.plugin.api.service.WorkspaceStateStore;
import com.aresstack.askai.research.agentwire.ResearchBackendMode;
import com.aresstack.askai.research.backend.FakeResearchSessionBackend;
import com.aresstack.askai.research.backend.RealResearchScheduler;
import com.aresstack.askai.research.backend.ResearchClock;
import com.aresstack.askai.research.backend.ResearchIdGenerator;
import com.aresstack.askai.research.host.ProductiveResearchBackendFactory;
import com.aresstack.askai.research.host.ProductiveResearchSessionResources;
import com.aresstack.askai.research.host.ResearchRuntimeSettings;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Stateless factory creating a fully isolated {@link ResearchAgentSession} per request. The backend is chosen
 * EXCLUSIVELY by the persisted, validated {@link ResearchRuntimeSettings} mode:
 * <ul>
 * <li>{@code FAKE} → the deterministic clickdummy backend (development/demo mode, clearly labelled in the
 *     runtime settings view);</li>
 * <li>{@code ACP} (the productive mode) → the chain via {@link ProductiveResearchBackendFactory}: the
 *     in-process research agent, research-control + browser-bridge endpoints, the in-process Playwright
 *     browser, file-persisted sources.</li>
 * </ul>
 * A productive start failure (missing host service, agent open failure) throws with the concrete reason —
 * there is NO automatic fallback to the fake backend in any direction.
 */
public final class ResearchAgentSessionFactory implements AgentSessionFactory {

    /** Delay between simulated run steps in the shipped clickdummy. */
    private static final long STEP_DELAY_MILLIS = 350L;

    /** One factory instance per plugin load → one productive generation per plugin generation. */
    private static final AtomicLong GENERATIONS = new AtomicLong();

    private final long generationId = GENERATIONS.incrementAndGet();

    @Override
    public AgentSession create(AgentSessionCreationRequest request, AgentHostContext hostContext) {
        // The persisted language is only the DEFAULT for new sessions — each ResearchAgentSession reads
        // it itself and owns its language from then on (live-switchable per session, nothing global).
        ResearchRuntimeSettings settings = ResearchRuntimeSettings.load(hostContext.getStateStore());
        // There is NO user-facing mode choice: productive is simply THE mode — the agent and the browser
        // run in-process, there is nothing external left to configure. A host without a state store runs
        // the visible clickdummy mode; a persisted FAKE value remains a developer-only override.
        WorkspaceStateStore store = hostContext.getStateStore();
        if (store == null || (settings.getMode() == ResearchBackendMode.FAKE
                && ResearchRuntimeSettings.hasPersistedMode(store))) {
            return createDemo(request, hostContext);
        }
        return createProductive(settings, request, hostContext);
    }

    private AgentSession createDemo(AgentSessionCreationRequest request, AgentHostContext hostContext) {
        RealResearchScheduler scheduler = new RealResearchScheduler();
        FakeResearchSessionBackend backend = new FakeResearchSessionBackend(
                scheduler, ResearchClock.system(), ResearchIdGenerator.random(), STEP_DELAY_MILLIS,
                ResearchLanguage.fromCode(
                        ResearchRuntimeSettings.loadLanguage(hostContext.getStateStore())));
        ResearchAgentSession session = new ResearchAgentSession(backend, scheduler, hostContext,
                request.getSessionId(), request.getProjectId());
        configureNarration(session, hostContext);
        return session;
    }

    /**
     * LLM narration is strictly opt-in (settings toggle) AND host-dependent (inference port present).
     * Both absent/off → the session keeps the static narrator, visibly identical.
     */
    private static void configureNarration(ResearchAgentSession session, AgentHostContext hostContext) {
        if (!com.aresstack.askai.research.host.ResearchRuntimeSettings
                .loadLlmNarration(hostContext.getStateStore())) {
            return;
        }
        com.aresstack.askai.agent.model.inference.AgentInferencePort port = hostContext.getService(
                com.aresstack.askai.agent.model.inference.AgentInferencePort.class);
        if (port != null) {
            session.configureNarration(
                    new com.aresstack.askai.research.agent.narration.LlmNarrator(
                            port, session.getSessionLanguage()));
        }
    }

    private AgentSession createProductive(ResearchRuntimeSettings settings,
                                          AgentSessionCreationRequest request,
                                          AgentHostContext hostContext) {
        // Empty fields are completed from the detectable environment (installed browsers) — explicit
        // user values always win.
        settings = com.aresstack.askai.research.host.ResearchRuntimeDefaults.complete(settings);
        List<String> problems = settings.validateProductive();
        if (!problems.isEmpty()) {
            throw new IllegalStateException("The productive research configuration is not usable "
                    + "(no fallback to the fake backend): " + problems);
        }
        McpServerRegistry registry = requireService(hostContext, McpServerRegistry.class);
        // Diagnostics: which host services actually reached THIS plugin classloader (a class-identity split
        // across classloaders shows up as present=false even when the host published them).
        System.err.println("[research-runtime] RerankerConfigurationSnapshotProvider present="
                + (hostContext.getService(com.aresstack.askai.agent.model.reranker
                        .RerankerConfigurationSnapshotProvider.class) != null)
                + " EmbeddingConfigurationSnapshotProvider present="
                + (hostContext.getService(com.aresstack.askai.agent.model.embedding
                        .EmbeddingConfigurationSnapshotProvider.class) != null)
                + " NlpConfigurationSnapshotProvider present="
                + (hostContext.getService(com.aresstack.askai.agent.model.nlp
                        .NlpConfigurationSnapshotProvider.class) != null));
        // The reranker snapshot provider is MANDATORY for the productive browser path — a missing host
        // service fails the session start visibly (no fallback to a reranker-less run).
        com.aresstack.askai.agent.model.reranker.RerankerConfigurationSnapshotProvider rerankerSnapshots =
                requireService(hostContext,
                        com.aresstack.askai.agent.model.reranker
                                .RerankerConfigurationSnapshotProvider.class);
        // The reranker model is chosen centrally in AskAI (Configuration → AI models); the host snapshot
        // provider resolves it. Any legacy plugin-side selection still flows through settings.toRuntimeConfig()
        // as a transitional fallback and is migrated into the central store on first use.
        // OPTIONAL structured-inference provider (the central main model for SERP layout repair): absent →
        // the agent keeps the honest unavailable-fallback, so it is looked up leniently (never required).
        com.aresstack.askai.agent.model.inference.InferenceConfigurationSnapshotProvider inferenceSnapshots =
                hostContext.getService(
                        com.aresstack.askai.agent.model.inference
                                .InferenceConfigurationSnapshotProvider.class);
        // OPTIONAL embedding provider for the continuous knowledge pipeline: absent → knowledge processing is a
        // diagnosed UNAVAILABLE capability (never a fake embedder), so it is looked up leniently, never required.
        com.aresstack.askai.agent.model.embedding.EmbeddingConfigurationSnapshotProvider embeddingSnapshots =
                hostContext.getService(
                        com.aresstack.askai.agent.model.embedding
                                .EmbeddingConfigurationSnapshotProvider.class);

        ProductiveResearchBackendFactory factory = new ProductiveResearchBackendFactory(
                registry, settings.toRuntimeConfig(), generationId,
                com.aresstack.askai.research.host.LegacyBrowserSearchSettingsStore
                        .loadValues(hostContext.getStateStore()),
                com.aresstack.askai.research.host.LegacyBrowserSearchSettingsStore
                        .revision(hostContext.getStateStore()),
                rerankerSnapshots, inferenceSnapshots,
                // The persisted initial-search selection (legacy browser default): an API-provider
                // selection is published as a per-session snapshot; the agent's implementation is ready.
                ResearchRuntimeSettings.loadSearchStrategy(hostContext.getStateStore()),
                embeddingSnapshots);
        // The knowledge worker's OpenNLP sentence resolver uses the SAME persisted session language ("en"/"de").
        factory.setResearchLanguageCode(ResearchRuntimeSettings.loadLanguage(hostContext.getStateStore()));
        factory.setBotControlMcpEnabled(
                ResearchRuntimeSettings.loadBotControlMcp(hostContext.getStateStore()));
        factory.setAlwaysOfferSearchSuggestions(
                ResearchRuntimeSettings.loadAlwaysOfferSearchSuggestions(hostContext.getStateStore()));
        factory.setAgentMaxOutputTokens(
                ResearchRuntimeSettings.loadAgentMaxOutputTokens(hostContext.getStateStore()));
        factory.setConceptToolRounds(
                ResearchRuntimeSettings.loadConceptToolRounds(hostContext.getStateStore()));
        factory.setConceptRepairAttempts(
                ResearchRuntimeSettings.loadConceptRepairAttempts(hostContext.getStateStore()));
        factory.setSearchRunLimits(
                ResearchRuntimeSettings.loadPositiveInt(hostContext.getStateStore(),
                        ResearchRuntimeSettings.KEY_SEARCH_TARGET_SOURCES,
                        ResearchRuntimeSettings.DEFAULT_SEARCH_TARGET_SOURCES),
                ResearchRuntimeSettings.loadPositiveInt(hostContext.getStateStore(),
                        ResearchRuntimeSettings.KEY_SEARCH_MAX_PAGES,
                        ResearchRuntimeSettings.DEFAULT_SEARCH_MAX_PAGES),
                ResearchRuntimeSettings.loadPositiveInt(hostContext.getStateStore(),
                        ResearchRuntimeSettings.KEY_SEARCH_MAX_TOOL_CALLS,
                        ResearchRuntimeSettings.DEFAULT_SEARCH_MAX_TOOL_CALLS),
                ResearchRuntimeSettings.loadPositiveInt(hostContext.getStateStore(),
                        ResearchRuntimeSettings.KEY_SEARCH_MAX_MINUTES,
                        ResearchRuntimeSettings.DEFAULT_SEARCH_MAX_MINUTES),
                ResearchRuntimeSettings.loadPositiveInt(hostContext.getStateStore(),
                        ResearchRuntimeSettings.KEY_SEARCH_MAX_ERRORS,
                        ResearchRuntimeSettings.DEFAULT_SEARCH_MAX_ERRORS));
        factory.setUseAiReranker(
                ResearchRuntimeSettings.loadUseAiReranker(hostContext.getStateStore()));
        factory.setOrchestratorEnvironment(
                ResearchRuntimeSettings.loadOrchestratorEnvironment(hostContext.getStateStore()));
        factory.setBrowserToolTimeoutSeconds(
                ResearchRuntimeSettings.loadPositiveInt(hostContext.getStateStore(),
                        ResearchRuntimeSettings.KEY_BROWSER_TOOL_TIMEOUT_SECONDS,
                        ResearchRuntimeSettings.DEFAULT_BROWSER_TOOL_TIMEOUT_SECONDS));
        factory.setBrowserIdleStopSeconds(
                ResearchRuntimeSettings.loadNonNegativeInt(hostContext.getStateStore(),
                        ResearchRuntimeSettings.KEY_BROWSER_IDLE_STOP_SECONDS,
                        ResearchRuntimeSettings.DEFAULT_BROWSER_IDLE_STOP_SECONDS));
        factory.setWorkerBrowserLimits(
                ResearchRuntimeSettings.loadPositiveInt(hostContext.getStateStore(),
                        ResearchRuntimeSettings.KEY_WORKER_BROWSER_PROCESSES,
                        ResearchRuntimeSettings.DEFAULT_WORKER_BROWSER_PROCESSES),
                ResearchRuntimeSettings.loadPositiveInt(hostContext.getStateStore(),
                        ResearchRuntimeSettings.KEY_WORKER_USER_CONTEXTS_PER_PROCESS,
                        ResearchRuntimeSettings.DEFAULT_WORKER_USER_CONTEXTS_PER_PROCESS),
                ResearchRuntimeSettings.loadPositiveInt(hostContext.getStateStore(),
                        ResearchRuntimeSettings.KEY_WORKER_TABS_PER_USER_CONTEXT,
                        ResearchRuntimeSettings.DEFAULT_WORKER_TABS_PER_USER_CONTEXT),
                ResearchRuntimeSettings.loadPositiveInt(hostContext.getStateStore(),
                        ResearchRuntimeSettings.KEY_WORKER_PER_SITE,
                        ResearchRuntimeSettings.DEFAULT_WORKER_PER_SITE),
                ResearchRuntimeSettings.loadPositiveInt(hostContext.getStateStore(),
                        ResearchRuntimeSettings.KEY_WORKER_ACQUIRE_TIMEOUT_SECONDS,
                        ResearchRuntimeSettings.DEFAULT_WORKER_ACQUIRE_TIMEOUT_SECONDS));
        factory.setReviewContextLimits(
                ResearchRuntimeSettings.loadPositiveInt(hostContext.getStateStore(),
                        ResearchRuntimeSettings.KEY_REVIEW_MAX_SOURCES,
                        ResearchRuntimeSettings.DEFAULT_REVIEW_MAX_SOURCES),
                ResearchRuntimeSettings.loadPositiveInt(hostContext.getStateStore(),
                        ResearchRuntimeSettings.KEY_REVIEW_MAX_CHARS,
                        ResearchRuntimeSettings.DEFAULT_REVIEW_MAX_CHARS));
        // The APP-WIDE public connector is configured here, not inside the per-session backend factory:
        // one listener for the whole app, idempotent for an unchanged configuration. The sessions it serves
        // come from the session directory, so this call carries no session state at all. The host's chat
        // catalog gives that directory the titles and the currently SELECTED chat.
        applyChatGptConnectorSettings(hostContext);
        // OPTIONAL host NLP provider: the session resolves its SELECTED sentence model through it (absent →
        // regex fallback). Looked up leniently; the knowledge worker never scans a store or reads global settings.
        factory.setNlpConfigurationSnapshotProvider(hostContext.getService(
                com.aresstack.askai.agent.model.nlp.NlpConfigurationSnapshotProvider.class));
        // The host's model port (ADR 0001, 3.2): every model role of the agent calls it by slot; looked up
        // leniently, an older host without it leaves the roles honestly unavailable.
        factory.setModelPort(hostContext.getService(com.aresstack.askai.agent.model.port.ModelPort.class));
        java.io.File sessionDirectory =
                hostContext.getPluginPathService().getWorkspaceDirectory(request.getSessionId());
        final ProductiveResearchSessionResources resources;
        try {
            resources = factory.createSession(request.getSessionId(), sessionDirectory);
        } catch (IOException ex) {
            throw new IllegalStateException("The productive research backend could not be started "
                    + "(no fallback to the fake backend): " + ex.getMessage(), ex);
        }
        // Register the now-running session so AskAI can re-publish its descriptors on a central model change
        // (unregistered in ResearchAgentSession.close()). Registration happens ONLY after a successful start.
        com.aresstack.askai.agent.model.session.ActiveResearchSessionRegistry activeSessions =
                hostContext.getService(
                        com.aresstack.askai.agent.model.session.ActiveResearchSessionRegistry.class);
        if (activeSessions != null) {
            activeSessions.register(request.getSessionId(), sessionDirectory);
        }
        // The session OWNS the resources: structured commands route to their state machine, close() tears
        // them down last (endpoints → browser runtime).
        ResearchAgentSession session = new ResearchAgentSession(resources.getBackend(), null, hostContext,
                request.getSessionId(), request.getProjectId(), resources,
                publicChatSessionId(request));
        configureNarration(session, hostContext);
        return session;
    }

    /**
     * The PUBLIC id of this session for external addressing: the host's chat session id. Older hosts that
     * do not fill {@code scopeId} yet still carry it inside the session key ({@code agentId#chatId}).
     */
    private static String publicChatSessionId(AgentSessionCreationRequest request) {
        String scope = request.getScopeId();
        if (scope != null && !scope.trim().isEmpty()) {
            return scope.trim();
        }
        String sessionId = request.getSessionId();
        int hash = sessionId.indexOf('#');
        return hash < 0 ? "" : sessionId.substring(hash + 1);
    }

    /**
     * Apply the APP-WIDE ChatGPT-connector settings: enabled → the one listener runs (idempotent for an
     * unchanged configuration), disabled → only the LISTENER stops. Running sessions stay registered either
     * way, so a later switch-on reaches them without a session restart.
     */
    private static void applyChatGptConnectorSettings(AgentHostContext hostContext) {
        com.aresstack.askai.research.connector.ChatGptConnectorRuntime runtime =
                com.aresstack.askai.research.connector.ChatGptConnectorRuntime.get();
        // The host catalog is OPTIONAL: without it the directory cannot resolve titles or the selected chat,
        // and callers simply have to pass sessionId explicitly.
        runtime.sessions().setChatSessionCatalog(hostContext.getService(
                com.aresstack.askai.plugin.api.service.ChatSessionCatalog.class));
        // The WRITING port (session_create). Also optional: a host without it simply cannot be asked to
        // open a chat, and the tool says so instead of failing obscurely.
        runtime.sessions().setChatSessionLauncher(hostContext.getService(
                com.aresstack.askai.plugin.api.service.ChatSessionLauncher.class));
        ResearchRuntimeSettings.ChatGptConnectorSettings connector =
                ResearchRuntimeSettings.loadChatGptConnectorSettings(hostContext.getStateStore());
        if (connector.isEnabled()) {
            runtime.ensureStarted(new com.aresstack.askai.research.connector.ConnectorConfig(
                    connector.getPort(), connector.getPublicOrigin(), connector.getClientId(),
                    connector.getClientSecret(),
                    com.aresstack.askai.research.connector.ChatGptConnectorRuntime.defaultRefreshStore(),
                    connector.getCentralIssuer()));
        } else {
            runtime.stopListener();
        }
    }

    private static <T> T requireService(AgentHostContext hostContext, Class<T> type) {
        T service = hostContext.getService(type);
        if (service == null) {
            throw new IllegalStateException("The host does not provide the required service "
                    + type.getSimpleName() + " for the productive research mode "
                    + "(no fallback to the fake backend).");
        }
        return service;
    }
}
