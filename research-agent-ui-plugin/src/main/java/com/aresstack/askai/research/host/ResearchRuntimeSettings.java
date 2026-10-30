package com.aresstack.askai.research.host;

import com.aresstack.askai.plugin.api.service.WorkspaceStateStore;
import com.aresstack.askai.research.agentwire.ResearchBackendMode;

import java.util.List;

/**
 * The TYPED, persisted configuration of the research backend — the single mapper between the UI, the
 * {@link WorkspaceStateStore} and {@link ResearchRuntimeConfig}. No free-form maps, no system properties.
 * {@code FAKE} is the deterministic clickdummy/development mode; {@code ACP} is the productive mode. There is
 * no silent fallback in either direction: an unusable productive configuration fails session creation
 * visibly ({@link ResearchAgentSessionFactory}).
 */
public final class ResearchRuntimeSettings {

    static final String KEY_MODE = "research.runtime.mode";
    static final String KEY_BROWSER_CHANNEL = "research.runtime.browserChannel";
    /** Set once the stored channel went through the Camoufox switch (see {@link #load}). */
    static final String KEY_BROWSER_CHANNEL_CAMOUFOX_SWITCH = "research.runtime.browserChannel.camoufoxSwitch";
    /** The managed Camoufox browser (downloaded once by the in-process browser runtime) — the default channel. */
    public static final String CAMOUFOX_CHANNEL = "camoufox";
    static final String KEY_HEADLESS = "research.runtime.headless";
    /** Conversation-policy thresholds (documented defaults; adjustable WITHOUT a rebuild via
     *  the env hand-off — the same mechanism as the concept loop budgets; a settings-UI row
     *  can layer on loadPositiveInt later). */
    public static final int DEFAULT_POLICY_SPARSE_CARDS = 4;
    public static final int DEFAULT_POLICY_NOVELTY_TURNS = 3;

    public static int policySparseCards() {
        return envOverride("ASKAI_POLICY_SPARSE_CARDS", DEFAULT_POLICY_SPARSE_CARDS);
    }

    public static int policyNoveltyTurns() {
        return envOverride("ASKAI_POLICY_NOVELTY_TURNS", DEFAULT_POLICY_NOVELTY_TURNS);
    }

    /**
     * #42: background topic discovery over the prepared corpus (pure CPU over persisted
     * vectors — it never calls the embedding endpoint, so it cannot compete with the
     * foreground GPU/LLM path). Default OFF, deliberately: no hidden background analysis
     * until the user opts in; a settings-UI row can layer on later.
     */
    public static boolean backgroundTopicDiscovery() {
        try {
            return "true".equalsIgnoreCase(System.getenv("ASKAI_BACKGROUND_TOPIC_DISCOVERY"));
        } catch (RuntimeException noEnv) {
            return false;
        }
    }

    /** scope_probe (AP3): the max terms one probe judges — a bound, never hidden magic. */
    public static final int DEFAULT_SCOPE_PROBE_MAX_TERMS = 8;

    public static int scopeProbeMaxTerms() {
        return envOverride("ASKAI_SCOPE_PROBE_MAX_TERMS", DEFAULT_SCOPE_PROBE_MAX_TERMS);
    }

    private static int envOverride(String name, int fallback) {
        try {
            String value = System.getenv(name);
            return value == null || value.trim().isEmpty()
                    ? fallback : Math.max(1, Integer.parseInt(value.trim()));
        } catch (NumberFormatException invalid) {
            return fallback;
        }
    }

    /** DEAD legacy override — kept ONLY so load() can destroy a persisted leftover value. */
    static final String KEY_SEARCH_URL = "research.runtime.searchUrl";
    static final String KEY_ALLOW_PRIVATE = "research.runtime.allowPrivateNetworks";
    static final String KEY_LANGUAGE = "research.runtime.language";
    static final String KEY_RERANKER_MODEL = "research.runtime.selectedRerankerModel";

    static final String KEY_LLM_NARRATION = "research.runtime.llmNarration";

    /** Agent language code ("en" default, "de" German) — read directly, independent of the path model. */
    public static String loadLanguage(WorkspaceStateStore store) {
        return store == null ? "en" : store.get(KEY_LANGUAGE, "en");
    }

    public static void saveLanguage(WorkspaceStateStore store, String code) {
        if (store != null) {
            store.put(KEY_LANGUAGE, "de".equalsIgnoreCase(code) ? "de" : "en");
        }
    }

    /** Clicking a yellow search/activity tag reads it aloud (assertive delivery). Default ON. */
    static final String KEY_READ_TAGS_ON_CLICK = "research.ui.readSearchTagsOnClick";

    public static boolean loadReadSearchTagsOnClick(WorkspaceStateStore store) {
        return store == null || store.getBoolean(KEY_READ_TAGS_ON_CLICK, true);
    }

    public static void saveReadSearchTagsOnClick(WorkspaceStateStore store, boolean enabled) {
        if (store != null) {
            store.putBoolean(KEY_READ_TAGS_ON_CLICK, enabled);
        }
    }

    /**
     * Sub-option of {@link #KEY_READ_TAGS_ON_CLICK}: tags speak ONLY while read-aloud is active
     * (Play pressed, or automatic reading enabled). Default ON — a user who never wanted speech
     * output is not surprised by talking tags.
     */
    static final String KEY_READ_TAGS_ONLY_WHEN_ACTIVE = "research.ui.readSearchTagsOnlyWhenReadAloudActive";

    public static boolean loadReadTagsOnlyWhenReadAloudActive(WorkspaceStateStore store) {
        return store == null || store.getBoolean(KEY_READ_TAGS_ONLY_WHEN_ACTIVE, true);
    }

    public static void saveReadTagsOnlyWhenReadAloudActive(WorkspaceStateStore store, boolean enabled) {
        if (store != null) {
            store.putBoolean(KEY_READ_TAGS_ONLY_WHEN_ACTIVE, enabled);
        }
    }

    /** A SUCCESSFUL web search reviews its new sources automatically. Deliberately default OFF. */
    static final String KEY_AUTO_REVIEW_AFTER_SEARCH = "research.ui.autoReviewAfterSearch";

    public static boolean loadAutoReviewAfterSearch(WorkspaceStateStore store) {
        return store != null && store.getBoolean(KEY_AUTO_REVIEW_AFTER_SEARCH, false);
    }

    public static void saveAutoReviewAfterSearch(WorkspaceStateStore store, boolean enabled) {
        if (store != null) {
            store.putBoolean(KEY_AUTO_REVIEW_AFTER_SEARCH, enabled);
        }
    }

    /**
     * LLM narration toggle (default OFF until burned in): when on AND the host provides an inference
     * port, milestone texts are phrased by the main model — always validated, always with the static
     * fallback. Read directly like the language, independent of the path model.
     */
    public static boolean loadLlmNarration(WorkspaceStateStore store) {
        return store != null && store.getBoolean(KEY_LLM_NARRATION, false);
    }

    public static void saveLlmNarration(WorkspaceStateStore store, boolean enabled) {
        if (store != null) {
            store.putBoolean(KEY_LLM_NARRATION, enabled);
        }
    }

    static final String KEY_ALWAYS_SUGGEST = "research.runtime.alwaysOfferSearchSuggestions";

    /**
     * "Immer Suchvorschläge anbieten" (DEFAULT OFF = the long-standing behaviour): when on, the scoping
     * assistant treats search suggestions as the user's orientation map — a broad/unclear scope always
     * comes with 3-5 direction-opening suggestions accompanying the clarifying question. Applies to NEW
     * sessions (the flag travels to the agent process at launch).
     */
    public static boolean loadAlwaysOfferSearchSuggestions(WorkspaceStateStore store) {
        return store != null && store.getBoolean(KEY_ALWAYS_SUGGEST, false);
    }

    public static void saveAlwaysOfferSearchSuggestions(WorkspaceStateStore store, boolean enabled) {
        if (store != null) {
            store.putBoolean(KEY_ALWAYS_SUGGEST, enabled);
        }
    }

    // ---- configurable limits: every bound is a setting with a NAMED default — never only a code constant.
    public static final String KEY_SEARCH_TARGET_SOURCES = "research.search.targetSources";
    public static final String KEY_SEARCH_MAX_PAGES = "research.search.maxPages";
    public static final String KEY_SEARCH_MAX_TOOL_CALLS = "research.search.maxToolCalls";
    public static final String KEY_SEARCH_MAX_MINUTES = "research.search.maxMinutes";
    public static final String KEY_SEARCH_MAX_ERRORS = "research.search.maxConsecutiveErrors";
    public static final String KEY_REVIEW_MAX_SOURCES = "research.review.maxSources";
    public static final String KEY_REVIEW_MAX_CHARS = "research.review.maxCharsPerSource";

    /** The search ends normally at this many accepted sources (the completion target). */
    public static final int DEFAULT_SEARCH_TARGET_SOURCES = 8;
    public static final int DEFAULT_SEARCH_MAX_PAGES = 20;
    public static final int DEFAULT_SEARCH_MAX_TOOL_CALLS = 30;
    /** Safety time limit per search run (minutes) — user waits are never counted against it. */
    public static final int DEFAULT_SEARCH_MAX_MINUTES = 10;

    /** Per-call timeout toward the browser (seconds) — must cover a multi-page SERP prepare. */
    public static final String KEY_BROWSER_TOOL_TIMEOUT_SECONDS =
            "research.search.browserToolTimeoutSeconds";
    public static final int DEFAULT_BROWSER_TOOL_TIMEOUT_SECONDS = 300;
    /**
     * How long a browser generation stays warm after a browsing phase ended without a new browser command,
     * before it is stopped (seconds). Keeping it warm saves the browser start (Playwright driver + browser
     * launch, about 10 s) on the next search; 0 = stop right after each search (the historic behaviour).
     */
    public static final String KEY_BROWSER_IDLE_STOP_SECONDS = "research.search.browserIdleStopSeconds";
    public static final int DEFAULT_BROWSER_IDLE_STOP_SECONDS = 300;

    // ---- worker browsers: isolated user contexts for parallel research workers. Configured independently of
    // the model capacity ("Model resources"); call timeout and idle stop reuse the two browser values above.
    /** Worker browser processes over all chats; also the number of browser operations that run in parallel. */
    public static final String KEY_WORKER_BROWSER_PROCESSES = "research.browser.workers.maxProcesses";
    public static final int DEFAULT_WORKER_BROWSER_PROCESSES =
            com.aresstack.askai.browser.playwright.WorkerBrowserLimits.DEFAULT_MAX_BROWSER_PROCESSES;
    /** User contexts (one per worker) one worker browser process hosts. */
    public static final String KEY_WORKER_USER_CONTEXTS_PER_PROCESS =
            "research.browser.workers.maxUserContextsPerProcess";
    public static final int DEFAULT_WORKER_USER_CONTEXTS_PER_PROCESS =
            com.aresstack.askai.browser.playwright.WorkerBrowserLimits.DEFAULT_MAX_USER_CONTEXTS_PER_PROCESS;
    /** Tabs (browsing contexts) one worker may hold open in its user context. */
    public static final String KEY_WORKER_TABS_PER_USER_CONTEXT = "research.browser.workers.maxTabsPerUserContext";
    public static final int DEFAULT_WORKER_TABS_PER_USER_CONTEXT = com.aresstack.askai.browser.playwright
            .WorkerBrowserLimits.DEFAULT_MAX_BROWSING_CONTEXTS_PER_USER_CONTEXT;
    /** Worker browser operations against the same site at the same time (search engines count as one). */
    public static final String KEY_WORKER_PER_SITE = "research.browser.workers.maxConcurrentPerSite";
    public static final int DEFAULT_WORKER_PER_SITE =
            com.aresstack.askai.browser.playwright.WorkerBrowserLimits.DEFAULT_MAX_CONCURRENT_PER_SITE;
    /** How long a worker waits for a free user context (seconds). */
    public static final String KEY_WORKER_ACQUIRE_TIMEOUT_SECONDS = "research.browser.workers.acquireTimeoutSeconds";
    public static final int DEFAULT_WORKER_ACQUIRE_TIMEOUT_SECONDS =
            com.aresstack.askai.browser.playwright.WorkerBrowserLimits.DEFAULT_ACQUIRE_TIMEOUT_SECONDS;
    public static final int DEFAULT_SEARCH_MAX_ERRORS = 3;
    /** How many sources one post-search review may read, and how much of each. */
    public static final int DEFAULT_REVIEW_MAX_SOURCES = 12;
    public static final int DEFAULT_REVIEW_MAX_CHARS = 1_200;

    /** A persisted NON-NEGATIVE integer (0 is a legal value); unset, unparsable or negative → the default. */
    public static int loadNonNegativeInt(WorkspaceStateStore store, String key, int fallback) {
        if (store == null) {
            return fallback;
        }
        try {
            int value = Integer.parseInt(store.get(key, String.valueOf(fallback)).trim());
            return value >= 0 ? value : fallback;
        } catch (NumberFormatException invalid) {
            return fallback;
        }
    }

    public static void saveNonNegativeInt(WorkspaceStateStore store, String key, int value) {
        if (store != null && value >= 0) {
            store.put(key, String.valueOf(value));
        }
    }

    /** A persisted positive integer; unset, unparsable or non-positive values fall back to the default. */
    public static int loadPositiveInt(WorkspaceStateStore store, String key, int fallback) {
        if (store == null) {
            return fallback;
        }
        try {
            int value = Integer.parseInt(store.get(key, String.valueOf(fallback)).trim());
            return value > 0 ? value : fallback;
        } catch (NumberFormatException invalid) {
            return fallback;
        }
    }

    public static void savePositiveInt(WorkspaceStateStore store, String key, int value) {
        if (store != null && value > 0) {
            store.put(key, String.valueOf(value));
        }
    }

    /** A persisted double; unset or unparsable values fall back to the default. */
    public static double loadDouble(WorkspaceStateStore store, String key, double fallback) {
        if (store == null) {
            return fallback;
        }
        try {
            return Double.parseDouble(
                    store.get(key, String.valueOf(fallback)).trim().replace(',', '.'));
        } catch (NumberFormatException invalid) {
            return fallback;
        }
    }

    public static void saveDouble(WorkspaceStateStore store, String key, double value) {
        if (store != null) {
            store.put(key, String.valueOf(value));
        }
    }

    // ------------------------------------------------------------------ Z4c: Scope-Pruefung
    // Every behavior-limiting knob of the explicit scope check, settings-backed (session-scoped
    // like the search limits; the values load PER CLICK, so changes apply without a restart).
    // The quantile/margin values remain the documented measurement spike — configurable so the
    // upcoming productive measurements never require a code change, but deliberately presented
    // under "erweitert": they are empirically provisional, not mature user options.
    public static final String KEY_SCOPE_CHECK_TARGET_PROBES =
            "research.scopeCheck.targetBroadProbes";
    public static final String KEY_SCOPE_CHECK_CONTROLS_PER_ANCHOR =
            "research.scopeCheck.controlsPerAnchor";
    public static final String KEY_SCOPE_CHECK_MAX_TOPICS =
            "research.scopeCheck.maxSuggestedTopics";
    public static final String KEY_SCOPE_CHECK_TIMEOUT_SECONDS =
            "research.scopeCheck.generationTimeoutSeconds";
    public static final String KEY_SCOPE_CHECK_GEN_TEMPERATURE =
            "research.scopeCheck.generatorTemperature";
    public static final String KEY_SCOPE_CHECK_GEN_MAX_TOKENS =
            "research.scopeCheck.generatorMaxOutputTokens";
    public static final String KEY_SCOPE_CHECK_NEAR_SIMILARITY =
            "research.scopeCheck.nearSimilarity";
    public static final String KEY_SCOPE_CHECK_HINT_BOUNDARY_MARGIN =
            "research.scopeCheck.hintBoundaryMargin";
    public static final String KEY_SCOPE_CHECK_MISSION_QUANTILE =
            "research.scopeCheck.missionRelevanceQuantile";
    public static final String KEY_SCOPE_CHECK_MISSION_MARGIN =
            "research.scopeCheck.missionRelevanceMargin";
    public static final String KEY_SCOPE_CHECK_NEIGHBOR_QUANTILE =
            "research.scopeCheck.neighborSimilarityQuantile";
    public static final String KEY_SCOPE_CHECK_NEIGHBOR_MARGIN =
            "research.scopeCheck.neighborSimilarityMargin";
    public static final String KEY_SCOPE_CHECK_MIN_NEGOTIATED =
            "research.scopeCheck.minimumNegotiatedAnchors";
    public static final String KEY_SCOPE_CHECK_MIN_NEIGHBORS =
            "research.scopeCheck.minimumNeighborSamples";
    public static final String KEY_SCOPE_CHECK_BOUNDARY_MARGIN =
            "research.scopeCheck.boundaryMargin";
    public static final String KEY_SCOPE_CHECK_NOVELTY_GAP =
            "research.scopeCheck.sweepNoveltyGap";
    public static final String KEY_SCOPE_CHECK_NOVELTY_WEIGHT =
            "research.scopeCheck.noveltyWeight";
    public static final String KEY_SCOPE_CHECK_DUPLICATE_CEILING =
            "research.scopeCheck.duplicateSimilarityCeiling";
    public static final String KEY_SCOPE_CHECK_CHOOSER_TEMPERATURE =
            "research.scopeCheck.chooserTemperature";
    public static final String KEY_SCOPE_CHECK_CHOOSER_MAX_TOKENS =
            "research.scopeCheck.chooserMaxOutputTokens";
    public static final String KEY_SCOPE_CHECK_CHOICE_TIMEOUT_SECONDS =
            "research.scopeCheck.choiceTimeoutSeconds";

    /**
     * The full scope-check configuration from the store, every field falling back to the
     * live-gate defaults of {@link
     * com.aresstack.askai.research.scope.ScopeSweepConfiguration#defaults()}.
     */
    public static com.aresstack.askai.research.scope.ScopeSweepConfiguration
            loadScopeSweepConfiguration(WorkspaceStateStore store) {
        com.aresstack.askai.research.scope.ScopeSweepConfiguration d =
                com.aresstack.askai.research.scope.ScopeSweepConfiguration.defaults();
        return new com.aresstack.askai.research.scope.ScopeSweepConfiguration(
                loadPositiveInt(store, KEY_SCOPE_CHECK_TARGET_PROBES, d.targetBroadProbes),
                loadPositiveInt(store, KEY_SCOPE_CHECK_CONTROLS_PER_ANCHOR, d.controlsPerAnchor),
                loadDouble(store, KEY_SCOPE_CHECK_GEN_TEMPERATURE, d.generatorTemperature),
                loadPositiveInt(store, KEY_SCOPE_CHECK_GEN_MAX_TOKENS,
                        d.generatorMaxOutputTokens),
                loadPositiveInt(store, KEY_SCOPE_CHECK_TIMEOUT_SECONDS,
                        d.generationTimeoutSeconds),
                new com.aresstack.askai.research.domain.scope.ScopeFenceEvaluator.Thresholds(
                        loadDouble(store, KEY_SCOPE_CHECK_NEAR_SIMILARITY,
                                d.fenceThresholds.nearSimilarity),
                        loadDouble(store, KEY_SCOPE_CHECK_HINT_BOUNDARY_MARGIN,
                                d.fenceThresholds.boundaryMargin)),
                new com.aresstack.askai.research.domain.scope.ScopeFenceCalibrator
                        .CalibrationParameters(
                        loadDouble(store, KEY_SCOPE_CHECK_MISSION_QUANTILE,
                                d.calibrationParameters.missionRelevanceQuantile),
                        loadDouble(store, KEY_SCOPE_CHECK_MISSION_MARGIN,
                                d.calibrationParameters.missionRelevanceMargin),
                        loadDouble(store, KEY_SCOPE_CHECK_NEIGHBOR_QUANTILE,
                                d.calibrationParameters.neighborSimilarityQuantile),
                        loadDouble(store, KEY_SCOPE_CHECK_NEIGHBOR_MARGIN,
                                d.calibrationParameters.neighborSimilarityMargin),
                        loadPositiveInt(store, KEY_SCOPE_CHECK_MIN_NEGOTIATED,
                                d.calibrationParameters.minimumNegotiatedAnchors),
                        loadPositiveInt(store, KEY_SCOPE_CHECK_MIN_NEIGHBORS,
                                d.calibrationParameters.minimumNeighborSamples)),
                loadDouble(store, KEY_SCOPE_CHECK_BOUNDARY_MARGIN, d.boundaryMargin),
                loadDouble(store, KEY_SCOPE_CHECK_NOVELTY_GAP, d.sweepNoveltyGap),
                new com.aresstack.askai.research.domain.scope.DiverseProbeSelector.Parameters(
                        loadPositiveInt(store, KEY_SCOPE_CHECK_MAX_TOPICS,
                                d.selectorParameters.maximumCandidates),
                        loadDouble(store, KEY_SCOPE_CHECK_NOVELTY_WEIGHT,
                                d.selectorParameters.noveltyWeight),
                        loadDouble(store, KEY_SCOPE_CHECK_DUPLICATE_CEILING,
                                d.selectorParameters.duplicateSimilarityCeiling)),
                loadDouble(store, KEY_SCOPE_CHECK_CHOOSER_TEMPERATURE, d.chooserTemperature),
                loadPositiveInt(store, KEY_SCOPE_CHECK_CHOOSER_MAX_TOKENS,
                        d.chooserMaxOutputTokens),
                loadPositiveInt(store, KEY_SCOPE_CHECK_CHOICE_TIMEOUT_SECONDS,
                        d.choiceTimeoutSeconds));
    }

    static final String KEY_AGENT_MAX_OUTPUT_TOKENS = "research.runtime.agentMaxOutputTokens";
    /** The default answer budget per agent model turn — generous enough for the longest contract (review). */
    public static final int DEFAULT_AGENT_MAX_OUTPUT_TOKENS = 4096;

    /**
     * The output-token budget the agent grants the model PER TURN (the longest contracted answer is the
     * post-search source review). A budget, not a target: short turns stay short. Configurable so a too
     * small value never has to be fixed in code again; an unparsable or non-positive persisted value falls
     * back to the default. Applies to NEW sessions.
     */
    public static int loadAgentMaxOutputTokens(WorkspaceStateStore store) {
        if (store == null) {
            return DEFAULT_AGENT_MAX_OUTPUT_TOKENS;
        }
        try {
            int value = Integer.parseInt(store.get(KEY_AGENT_MAX_OUTPUT_TOKENS,
                    String.valueOf(DEFAULT_AGENT_MAX_OUTPUT_TOKENS)).trim());
            return value > 0 ? value : DEFAULT_AGENT_MAX_OUTPUT_TOKENS;
        } catch (NumberFormatException invalid) {
            return DEFAULT_AGENT_MAX_OUTPUT_TOKENS;
        }
    }

    public static void saveAgentMaxOutputTokens(WorkspaceStateStore store, int tokens) {
        if (store != null && tokens > 0) {
            store.put(KEY_AGENT_MAX_OUTPUT_TOKENS, String.valueOf(tokens));
        }
    }

    static final String KEY_CONCEPT_TOOL_ROUNDS = "research.runtime.conceptToolRounds";
    /** How many model↔tool cycles ONE concept turn may make — the WORK budget of the loop. */
    public static final int DEFAULT_CONCEPT_TOOL_ROUNDS = 4;
    static final String KEY_CONCEPT_REPAIR_ATTEMPTS = "research.runtime.conceptRepairAttempts";
    /** How many REJECTED concept steps may be corrected per turn — the ERROR tolerance, kept apart. */
    public static final int DEFAULT_CONCEPT_REPAIR_ATTEMPTS = 2;

    /** Tool-round budget of the Konzeptpapier loop (work budget ≠ error tolerance). New sessions. */
    public static int loadConceptToolRounds(WorkspaceStateStore store) {
        return loadPositiveInt(store, KEY_CONCEPT_TOOL_ROUNDS, DEFAULT_CONCEPT_TOOL_ROUNDS);
    }

    public static void saveConceptToolRounds(WorkspaceStateStore store, int rounds) {
        if (store != null && rounds > 0) {
            store.put(KEY_CONCEPT_TOOL_ROUNDS, String.valueOf(rounds));
        }
    }

    /** Repair budget of the Konzeptpapier loop (rejected steps that may be corrected per turn). */
    public static int loadConceptRepairAttempts(WorkspaceStateStore store) {
        return loadPositiveInt(store, KEY_CONCEPT_REPAIR_ATTEMPTS, DEFAULT_CONCEPT_REPAIR_ATTEMPTS);
    }

    public static void saveConceptRepairAttempts(WorkspaceStateStore store, int attempts) {
        if (store != null && attempts > 0) {
            store.put(KEY_CONCEPT_REPAIR_ATTEMPTS, String.valueOf(attempts));
        }
    }

    static final String KEY_BOT_CONTROL = "research.runtime.botControlMcp";

    /**
     * Bot-control MCP toggle (DEFAULT ON): whether a new session opens the bot-control endpoint
     * (run_command/session_state/chat_history) and writes service-endpoint.json. OFF = the session is
     * GUI-only; no endpoint is registered and no connection file is written. Applies to NEW sessions.
     */
    public static boolean loadBotControlMcp(WorkspaceStateStore store) {
        return store == null || store.getBoolean(KEY_BOT_CONTROL, true);
    }

    /**
     * "KI-Reranker verwenden" (default OFF): OFF scores search candidates lexically (BM25, no model,
     * no GPU); ON uses the configured cross-encoder model. Applies to NEW sessions.
     */
    public static final String KEY_USE_AI_RERANKER = "research.runtime.useAiReranker";

    // ---- ADR 0001 lane F: the orchestrated research run (limits are settings, never constants) ----------

    /** "Orchestrated research" (default OFF = the single autonomous research loop). */
    public static final String KEY_ORCHESTRATOR_ENABLED = "research.orchestrator.enabled";
    public static final String KEY_ORCHESTRATOR_MAX_ROUNDS = "research.orchestrator.maxRounds";
    public static final String KEY_ORCHESTRATOR_MAX_TASKS = "research.orchestrator.maxTasks";
    public static final String KEY_ORCHESTRATOR_TASKS_PER_ROUND = "research.orchestrator.maxTasksPerRound";
    public static final String KEY_ORCHESTRATOR_TOKEN_BUDGET = "research.orchestrator.tokenBudget";
    public static final String KEY_ORCHESTRATOR_TIME_BUDGET_MINUTES = "research.orchestrator.timeBudgetMinutes";
    public static final String KEY_ORCHESTRATOR_TASK_RETRIES = "research.orchestrator.taskRetryLimit";
    public static final String KEY_ORCHESTRATOR_MIN_SOURCES_PER_SECTION =
            "research.orchestrator.minSourcesPerSection";
    // The defaults are the runtime's (OrchestratorSettings), so host and agent never disagree.
    public static final int DEFAULT_ORCHESTRATOR_MAX_ROUNDS =
            com.aresstack.askai.research.runtime.orchestrator.OrchestratorSettings.DEFAULT_MAX_ROUNDS;
    public static final int DEFAULT_ORCHESTRATOR_MAX_TASKS =
            com.aresstack.askai.research.runtime.orchestrator.OrchestratorSettings.DEFAULT_MAX_TASKS;
    public static final int DEFAULT_ORCHESTRATOR_TASKS_PER_ROUND =
            com.aresstack.askai.research.runtime.orchestrator.OrchestratorSettings.DEFAULT_MAX_TASKS_PER_ROUND;
    public static final int DEFAULT_ORCHESTRATOR_TOKEN_BUDGET = (int)
            com.aresstack.askai.research.runtime.orchestrator.OrchestratorSettings.DEFAULT_TOKEN_BUDGET;
    public static final int DEFAULT_ORCHESTRATOR_TIME_BUDGET_MINUTES =
            com.aresstack.askai.research.runtime.orchestrator.OrchestratorSettings.DEFAULT_TIME_BUDGET_MINUTES;
    public static final int DEFAULT_ORCHESTRATOR_TASK_RETRIES =
            com.aresstack.askai.research.runtime.orchestrator.OrchestratorSettings.DEFAULT_TASK_RETRY_LIMIT;
    public static final int DEFAULT_ORCHESTRATOR_MIN_SOURCES_PER_SECTION =
            com.aresstack.askai.research.runtime.orchestrator.OrchestratorSettings.DEFAULT_MIN_SOURCES_PER_SECTION;

    public static boolean loadOrchestratorEnabled(WorkspaceStateStore store) {
        return store != null && store.getBoolean(KEY_ORCHESTRATOR_ENABLED, false);
    }

    public static void saveOrchestratorEnabled(WorkspaceStateStore store, boolean enabled) {
        if (store != null) {
            store.putBoolean(KEY_ORCHESTRATOR_ENABLED, enabled);
        }
    }

    /**
     * The orchestrated run's settings as the agent's {@code ASKAI_ORCHESTRATOR_*} hand-off (the same mechanism
     * as the search limits). Values the user never set travel as their defaults.
     */
    public static java.util.Map<String, String> loadOrchestratorEnvironment(WorkspaceStateStore store) {
        java.util.Map<String, String> env = new java.util.LinkedHashMap<String, String>();
        env.put(com.aresstack.askai.research.runtime.orchestrator.OrchestratorSettings.ENV_MODE,
                loadOrchestratorEnabled(store) ? "orchestrated" : "loop");
        env.put(com.aresstack.askai.research.runtime.orchestrator.OrchestratorSettings.ENV_MAX_ROUNDS,
                String.valueOf(loadPositiveInt(store, KEY_ORCHESTRATOR_MAX_ROUNDS, DEFAULT_ORCHESTRATOR_MAX_ROUNDS)));
        env.put(com.aresstack.askai.research.runtime.orchestrator.OrchestratorSettings.ENV_MAX_TASKS,
                String.valueOf(loadPositiveInt(store, KEY_ORCHESTRATOR_MAX_TASKS, DEFAULT_ORCHESTRATOR_MAX_TASKS)));
        env.put(com.aresstack.askai.research.runtime.orchestrator.OrchestratorSettings.ENV_MAX_TASKS_PER_ROUND,
                String.valueOf(loadPositiveInt(store, KEY_ORCHESTRATOR_TASKS_PER_ROUND,
                        DEFAULT_ORCHESTRATOR_TASKS_PER_ROUND)));
        env.put(com.aresstack.askai.research.runtime.orchestrator.OrchestratorSettings.ENV_TOKEN_BUDGET,
                String.valueOf(loadNonNegativeInt(store, KEY_ORCHESTRATOR_TOKEN_BUDGET,
                        DEFAULT_ORCHESTRATOR_TOKEN_BUDGET)));
        env.put(com.aresstack.askai.research.runtime.orchestrator.OrchestratorSettings.ENV_TIME_BUDGET_MINUTES,
                String.valueOf(loadNonNegativeInt(store, KEY_ORCHESTRATOR_TIME_BUDGET_MINUTES,
                        DEFAULT_ORCHESTRATOR_TIME_BUDGET_MINUTES)));
        env.put(com.aresstack.askai.research.runtime.orchestrator.OrchestratorSettings.ENV_TASK_RETRY_LIMIT,
                String.valueOf(loadNonNegativeInt(store, KEY_ORCHESTRATOR_TASK_RETRIES,
                        DEFAULT_ORCHESTRATOR_TASK_RETRIES)));
        env.put(com.aresstack.askai.research.runtime.orchestrator.OrchestratorSettings.ENV_MIN_SOURCES_PER_SECTION,
                String.valueOf(loadPositiveInt(store, KEY_ORCHESTRATOR_MIN_SOURCES_PER_SECTION,
                        DEFAULT_ORCHESTRATOR_MIN_SOURCES_PER_SECTION)));
        return env;
    }

    public static boolean loadUseAiReranker(WorkspaceStateStore store) {
        return store != null && store.getBoolean(KEY_USE_AI_RERANKER, false);
    }

    public static void saveUseAiReranker(WorkspaceStateStore store, boolean enabled) {
        if (store != null) {
            store.putBoolean(KEY_USE_AI_RERANKER, enabled);
        }
    }

    public static void saveBotControlMcp(WorkspaceStateStore store, boolean enabled) {
        if (store != null) {
            store.putBoolean(KEY_BOT_CONTROL, enabled);
        }
    }

    // The connector is APP-WIDE (one listener) — its keys carry the GLOBAL prefix so a session-scoping
    // store never freezes per-chat copies (the panel and the server must always see the SAME values).
    static final String KEY_CONNECTOR = WorkspaceStateStore.GLOBAL_KEY_PREFIX
            + "research.runtime.chatgptConnector";
    static final String KEY_CONNECTOR_PORT = KEY_CONNECTOR + ".port";
    static final String KEY_CONNECTOR_ORIGIN = KEY_CONNECTOR + ".publicOrigin";
    static final String KEY_CONNECTOR_CLIENT_ID = KEY_CONNECTOR + ".clientId";
    static final String KEY_CONNECTOR_CLIENT_SECRET = KEY_CONNECTOR + ".clientSecret";
    static final String KEY_CONNECTOR_CENTRAL_ISSUER = KEY_CONNECTOR + ".centralIssuer";
    /** Pre-global legacy keys (may exist frozen in session scopes) — read-only migration fallback. */
    private static final String LEGACY_CONNECTOR = "research.runtime.chatgptConnector";

    /**
     * ChatGPT-connector toggle (DEFAULT OFF — this listener is reachable from other machines; enabling
     * a public face must be an explicit decision). When on, AskAI serves the OAuth endpoints + the MCP
     * endpoint /askai on the configured port; TLS terminates at the external Apache reverse proxy.
     */
    public static boolean loadChatGptConnector(WorkspaceStateStore store) {
        return store != null && store.getBoolean(KEY_CONNECTOR, false);
    }

    public static void saveChatGptConnector(WorkspaceStateStore store, boolean enabled) {
        if (store != null) {
            store.putBoolean(KEY_CONNECTOR, enabled);
        }
    }

    public static ChatGptConnectorSettings loadChatGptConnectorSettings(WorkspaceStateStore store) {
        if (store == null) {
            return new ChatGptConnectorSettings(false, 8082, "", "", "");
        }
        int port;
        try {
            port = Integer.parseInt(store.get(KEY_CONNECTOR_PORT,
                    store.get(LEGACY_CONNECTOR + ".port", "8082")).trim());
        } catch (NumberFormatException invalid) {
            port = 8082;
        }
        return new ChatGptConnectorSettings(
                store.getBoolean(KEY_CONNECTOR,
                        store.getBoolean(LEGACY_CONNECTOR, false)),
                port,
                store.get(KEY_CONNECTOR_ORIGIN,
                        store.get(LEGACY_CONNECTOR + ".publicOrigin", "")),
                store.get(KEY_CONNECTOR_CLIENT_ID,
                        store.get(LEGACY_CONNECTOR + ".clientId", "")),
                store.get(KEY_CONNECTOR_CLIENT_SECRET,
                        store.get(LEGACY_CONNECTOR + ".clientSecret", "")),
                store.get(KEY_CONNECTOR_CENTRAL_ISSUER, ""));
    }

    public static void saveChatGptConnectorSettings(WorkspaceStateStore store,
                                                    ChatGptConnectorSettings settings) {
        if (store == null || settings == null) {
            return;
        }
        store.putBoolean(KEY_CONNECTOR, settings.isEnabled());
        store.put(KEY_CONNECTOR_PORT, String.valueOf(settings.getPort()));
        store.put(KEY_CONNECTOR_ORIGIN, settings.getPublicOrigin());
        store.put(KEY_CONNECTOR_CLIENT_ID, settings.getClientId());
        store.put(KEY_CONNECTOR_CLIENT_SECRET, settings.getClientSecret());
        store.put(KEY_CONNECTOR_CENTRAL_ISSUER, settings.getCentralIssuer());
    }

    /** The typed ChatGPT-connector configuration (persisted; applies to NEW sessions). */
    public static final class ChatGptConnectorSettings {
        private final boolean enabled;
        private final int port;
        private final String publicOrigin;
        private final String clientId;
        private final String clientSecret;
        /** Non-empty = CENTRAL mode: the AresStack webserver issues the tokens. */
        private final String centralIssuer;

        public ChatGptConnectorSettings(boolean enabled, int port, String publicOrigin,
                                        String clientId, String clientSecret) {
            this(enabled, port, publicOrigin, clientId, clientSecret, "");
        }

        public ChatGptConnectorSettings(boolean enabled, int port, String publicOrigin,
                                        String clientId, String clientSecret, String centralIssuer) {
            this.enabled = enabled;
            this.port = port;
            this.publicOrigin = nullToEmpty(publicOrigin);
            this.clientId = nullToEmpty(clientId);
            this.clientSecret = nullToEmpty(clientSecret);
            this.centralIssuer = nullToEmpty(centralIssuer);
        }

        public boolean isEnabled() { return enabled; }
        public int getPort() { return port; }
        public String getPublicOrigin() { return publicOrigin; }
        public String getClientId() { return clientId; }
        public String getClientSecret() { return clientSecret; }
        public String getCentralIssuer() { return centralIssuer; }
    }

    static final String KEY_SEARCH_STRATEGY = "research.search.strategy";
    static final String KEY_SEARCH_PROVIDER = "research.search.provider";
    static final String KEY_SEARCH_ENGINE = "research.search.engine";
    static final String KEY_SEARCH_LANGUAGE = "research.search.language";
    static final String KEY_SEARCH_COUNTRY = "research.search.country";

    /**
     * The persisted initial-search strategy selection (default: legacy browser SERP — nothing changes for
     * existing installations). Read directly like the language; applies to NEW sessions.
     */
    public static SearchStrategySelection loadSearchStrategy(WorkspaceStateStore store) {
        if (store == null) {
            return SearchStrategySelection.legacyBrowser();
        }
        return new SearchStrategySelection(
                store.get(KEY_SEARCH_STRATEGY, SearchStrategySelection.STRATEGY_LEGACY_BROWSER),
                store.get(KEY_SEARCH_PROVIDER, ""),
                store.get(KEY_SEARCH_ENGINE, ""),
                store.get(KEY_SEARCH_LANGUAGE, ""),
                store.get(KEY_SEARCH_COUNTRY, ""));
    }

    public static void saveSearchStrategy(WorkspaceStateStore store, SearchStrategySelection selection) {
        if (store == null || selection == null) {
            return;
        }
        store.put(KEY_SEARCH_STRATEGY, selection.getStrategy());
        store.put(KEY_SEARCH_PROVIDER, selection.getProvider());
        store.put(KEY_SEARCH_ENGINE, selection.getEngine());
        store.put(KEY_SEARCH_LANGUAGE, selection.getLanguage());
        store.put(KEY_SEARCH_COUNTRY, selection.getCountry());
    }

    private final ResearchBackendMode mode;
    private final String browserChannel;
    private final boolean headless;
    private final boolean allowPrivateNetworks;
    /**
     * A LEGACY reranker selection carried transparently ("" = none). The reranker is now chosen centrally
     * in AskAI (Configuration → AI models); this value is no longer edited here — it only carries a
     * previously persisted selection through to the host, which migrates it into the central store on first
     * use. See {@code LocalRerankerConfigurationSnapshotProvider}.
     */
    private final String selectedRerankerModel;

    public ResearchRuntimeSettings(ResearchBackendMode mode, String browserChannel, boolean headless) {
        this(mode, browserChannel, headless, false);
    }

    /** @param allowPrivateNetworks development-only override of the strict URL policy (default false). */
    public ResearchRuntimeSettings(ResearchBackendMode mode, String browserChannel, boolean headless,
                                   boolean allowPrivateNetworks) {
        this(mode, browserChannel, headless, allowPrivateNetworks, "");
    }

    /** @param selectedRerankerModel the explicitly selected virtual reranker model id ("" = none). */
    public ResearchRuntimeSettings(ResearchBackendMode mode, String browserChannel, boolean headless,
                                   boolean allowPrivateNetworks, String selectedRerankerModel) {
        this.mode = mode == null ? ResearchBackendMode.FAKE : mode;
        this.browserChannel = browserChannel == null || browserChannel.isEmpty() ? CAMOUFOX_CHANNEL : browserChannel;
        this.headless = headless;
        this.allowPrivateNetworks = allowPrivateNetworks;
        this.selectedRerankerModel = nullToEmpty(selectedRerankerModel);
    }

    public static ResearchRuntimeSettings defaults() {
        return new ResearchRuntimeSettings(ResearchBackendMode.FAKE, CAMOUFOX_CHANNEL, true);
    }

    /** True when a mode value was ever persisted (the FAKE developer override requires an explicit one). */
    public static boolean hasPersistedMode(WorkspaceStateStore store) {
        return store != null && store.get(KEY_MODE, null) != null;
    }

    public static ResearchRuntimeSettings load(WorkspaceStateStore store) {
        if (store == null) {
            return defaults(); // a host without persisted state runs the visible clickdummy mode
        }
        ResearchBackendMode mode;
        try {
            mode = ResearchBackendMode.valueOf(store.get(KEY_MODE, ResearchBackendMode.FAKE.name()));
        } catch (IllegalArgumentException unknownValue) {
            mode = ResearchBackendMode.FAKE; // unknown persisted value → the safe, visible clickdummy mode
        }
        return new ResearchRuntimeSettings(mode,
                browserChannel(store),
                store.getBoolean(KEY_HEADLESS, true),
                store.getBoolean(KEY_ALLOW_PRIVATE, false),
                store.get(KEY_RERANKER_MODEL, ""));
    }

    /**
     * Camoufox became the default browser. "chrome" was the old default and every settings save wrote it,
     * so a stored "chrome" is switched to Camoufox ONCE; an explicit choice made after that switch (or a
     * stored "msedge") stays.
     */
    private static String browserChannel(WorkspaceStateStore store) {
        String stored = store.get(KEY_BROWSER_CHANNEL, CAMOUFOX_CHANNEL);
        if (store.get(KEY_BROWSER_CHANNEL_CAMOUFOX_SWITCH, null) == null) {
            store.put(KEY_BROWSER_CHANNEL_CAMOUFOX_SWITCH, "done");
            if ("chrome".equals(stored)) {
                store.put(KEY_BROWSER_CHANNEL, CAMOUFOX_CHANNEL);
                return CAMOUFOX_CHANNEL;
            }
        }
        return stored;
    }

    /**
     * The legacy per-store "Search URL" override is DEAD: it silently replaced the whole engine list
     * (order, fallback endpoints, per-engine result pages, delay) with one page-1-only engine. Any
     * leftover value is DESTROYED in the store the moment it is seen — nothing reads it, nothing can
     * resurrect it, and the file stops claiming otherwise.
     */
    private static void destroyLegacySearchUrlLeftover(WorkspaceStateStore store) {
        if (!store.get(KEY_SEARCH_URL, "").isEmpty()) {
            store.put(KEY_SEARCH_URL, "");
        }
    }

    public void save(WorkspaceStateStore store) {
        store.put(KEY_MODE, mode.name());
        store.put(KEY_BROWSER_CHANNEL, browserChannel);
        store.putBoolean(KEY_HEADLESS, headless);
        destroyLegacySearchUrlLeftover(store);
        store.putBoolean(KEY_ALLOW_PRIVATE, allowPrivateNetworks);
        store.put(KEY_RERANKER_MODEL, selectedRerankerModel);
    }

    public ResearchBackendMode getMode() { return mode; }
    public String getBrowserChannel() { return browserChannel; }
    public boolean isHeadless() { return headless; }
    public boolean isAllowPrivateNetworks() { return allowPrivateNetworks; }
    public String getSelectedRerankerModel() { return selectedRerankerModel; }

    /** The productive runtime config. The URL policy is strict unless the explicit, persisted
     * development-only override is set — never an implicit relaxation. */
    public ResearchRuntimeConfig toRuntimeConfig() {
        return new ResearchRuntimeConfig(browserChannel, headless, allowPrivateNetworks, selectedRerankerModel);
    }

    /**
     * The runtime rules ({@link ResearchRuntimeConfig#validate()}). Empty = usable.
     * <p>
     * A search provider URL does not exist anymore: the product ships with search engines and the
     * user orders them in the search settings. Dev/test worlds pass the in-process browser an explicit
     * {@code --search-url} per run (askai.research.browser.args) — never a persisted value.
     */
    public List<String> validateProductive() {
        return new java.util.ArrayList<String>(toRuntimeConfig().validate());
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
