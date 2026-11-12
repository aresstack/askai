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
    /** The RESEARCH browser (pages visited while researching): visible by default. */
    static final String KEY_HEADLESS = "research.runtime.headless";
    /**
     * The SEARCH browser (Browser (SERP) provider): headless by default. App-wide — one search browser serves all
     * chats — so the key carries the GLOBAL prefix.
     */
    static final String KEY_SEARCH_BROWSER_HEADLESS = WorkspaceStateStore.GLOBAL_KEY_PREFIX
            + "research.runtime.searchBrowserHeadless";

    /** The search engines' first pause after a challenge, in seconds (app-wide: one search browser). */
    static final String KEY_ENGINE_PAUSE_SECONDS = WorkspaceStateStore.GLOBAL_KEY_PREFIX
            + "research.runtime.enginePauseSeconds";
    /** The longest pause the doubling may reach, in minutes. */
    static final String KEY_ENGINE_PAUSE_CAP_MINUTES = WorkspaceStateStore.GLOBAL_KEY_PREFIX
            + "research.runtime.enginePauseCapMinutes";
    public static final int DEFAULT_ENGINE_PAUSE_SECONDS = 60;
    public static final int DEFAULT_ENGINE_PAUSE_CAP_MINUTES = 30;

    public static int loadEnginePauseSeconds(WorkspaceStateStore store) {
        return loadNonNegativeInt(store, KEY_ENGINE_PAUSE_SECONDS, DEFAULT_ENGINE_PAUSE_SECONDS);
    }

    public static void saveEnginePauseSeconds(WorkspaceStateStore store, int seconds) {
        saveNonNegativeInt(store, KEY_ENGINE_PAUSE_SECONDS, seconds);
    }

    public static int loadEnginePauseCapMinutes(WorkspaceStateStore store) {
        return loadNonNegativeInt(store, KEY_ENGINE_PAUSE_CAP_MINUTES, DEFAULT_ENGINE_PAUSE_CAP_MINUTES);
    }

    public static void saveEnginePauseCapMinutes(WorkspaceStateStore store, int minutes) {
        saveNonNegativeInt(store, KEY_ENGINE_PAUSE_CAP_MINUTES, minutes);
    }

    /** Hands the stored engine pause to the one search browser of this AskAI. */
    public static void applyEnginePause(WorkspaceStateStore store) {
        com.aresstack.askai.research.runtime.ResearchRuntimeShared.process().setEnginePause(
                loadEnginePauseSeconds(store) * 1000L, loadEnginePauseCapMinutes(store) * 60_000L);
    }

    /** Whether the search browser runs headless (default {@code true}). */
    public static boolean loadSearchBrowserHeadless(WorkspaceStateStore store) {
        return store == null || store.getBoolean(KEY_SEARCH_BROWSER_HEADLESS, true);
    }

    public static void saveSearchBrowserHeadless(WorkspaceStateStore store, boolean headless) {
        if (store != null) {
            store.putBoolean(KEY_SEARCH_BROWSER_HEADLESS, headless);
        }
    }
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
    /** The folder of the search browser (Camoufox) chosen in the setup; "" = AskAI's automatic choice. */
    static final String KEY_CAMOUFOX_FOLDER = "research.runtime.camoufoxFolder";

    public static String loadCamoufoxFolder(WorkspaceStateStore store) {
        return store == null ? "" : store.get(KEY_CAMOUFOX_FOLDER, "").trim();
    }

    public static void saveCamoufoxFolder(WorkspaceStateStore store, String folder) {
        if (store != null) {
            store.put(KEY_CAMOUFOX_FOLDER, folder == null ? "" : folder.trim());
        }
        applyCamoufoxFolder(store);
    }

    /** The executable chosen for a local browser channel (firefox, chrome, msedge); "" = autodetect. */
    static final String KEY_BROWSER_EXECUTABLE_PREFIX = "research.runtime.browserExecutable.";

    public static String loadBrowserExecutable(WorkspaceStateStore store, String channel) {
        return store == null ? "" : store.get(KEY_BROWSER_EXECUTABLE_PREFIX + channel, "").trim();
    }

    public static void saveBrowserExecutable(WorkspaceStateStore store, String channel, String path) {
        if (store != null) {
            store.put(KEY_BROWSER_EXECUTABLE_PREFIX + channel, path == null ? "" : path.trim());
        }
        applyBrowserExecutable(channel, loadBrowserExecutable(store, channel));
    }

    private static void applyBrowserExecutable(String channel, String path) {
        try {
            com.aresstack.askai.browser.playwright.BrowserExecutables.use(channel, path);
        } catch (RuntimeException | LinkageError unavailable) {
            // no browser runtime here: nothing to configure
        }
    }

    /**
     * Browsers started from now on use the chosen Camoufox folder (when it holds the verified installation) and
     * the chosen Firefox/Chrome/Edge executables.
     */
    public static void applyCamoufoxFolder(WorkspaceStateStore store) {
        for (String channel : new String[]{"firefox", "chrome", "msedge"}) {
            applyBrowserExecutable(channel, loadBrowserExecutable(store, channel));
        }
        String folder = loadCamoufoxFolder(store);
        if (folder.isEmpty()) {
            return; // the automatic choice (or a location configured at launch) stays
        }
        try {
            com.aresstack.askai.browser.playwright.CamoufoxSetup.useCamoufox(folder);
        } catch (RuntimeException | LinkageError unavailable) {
            // no browser runtime here: nothing to configure
        }
    }
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

    // ---- plan PR 14: the caps of the search plan (Search tab, "Search plan") -------------------------

    public static final String KEY_PLAN_SOURCES_PER_NODE = "research.plan.sourcesPerNode";
    public static final String KEY_PLAN_QUERIES_PER_NODE = "research.plan.queriesPerNode";
    public static final String KEY_PLAN_PHASE_SOURCES = "research.plan.phaseSources";
    /** Accepted sources (from at least two domains) that make a concept node covered. */
    public static final int DEFAULT_PLAN_SOURCES_PER_NODE = 3;
    /** Queries per node: the base query and up to three planner variants. */
    public static final int DEFAULT_PLAN_QUERIES_PER_NODE = 4;
    /**
     * Accepted sources the whole Sources phase may collect through the plan; 0 = automatic, what the node budgets
     * allow ({@link com.aresstack.askai.research.plan.SearchPlanCoverage.Caps#phaseSourcesFor}). Live run 4
     * (2026-11-12): a fixed 60 was spent in the first research cycle of a five-node concept (every node runs all its
     * planned angles, each may add "Sources per node" sources), the last node starved and the plan never reached a
     * second cycle for its gaps.
     */
    public static final int DEFAULT_PLAN_PHASE_SOURCES = 0;
    public static final String KEY_PLAN_CYCLES = "research.plan.cycles";
    /**
     * Research cycles: after each cycle the still uncovered nodes are planned again from another angle (M2). Three
     * cycles give a node up to three times "Queries per node" queries before the plan reports it as a gap.
     */
    public static final int DEFAULT_PLAN_CYCLES = 3;

    /**
     * Whether every search skips shop, marketplace and document re-upload sites
     * ({@link com.aresstack.askai.research.domain.scope.NonSourceHosts#DEFAULT}) as a host filter next to the
     * user's blacklist. Default ON: a listing of a book is never a source of what the book says.
     */
    public static final String KEY_SKIP_NON_SOURCE_HOSTS = "research.search.skipNonSourceHosts";

    public static boolean loadSkipNonSourceHosts(WorkspaceStateStore store) {
        return store == null || store.getBoolean(KEY_SKIP_NON_SOURCE_HOSTS, true);
    }

    public static void saveSkipNonSourceHosts(WorkspaceStateStore store, boolean enabled) {
        if (store != null) {
            store.putBoolean(KEY_SKIP_NON_SOURCE_HOSTS, enabled);
        }
    }

    /**
     * The research team of the Sources phase (plan T5): the planned queries run side by side, each with a page
     * worker in a worker browser of its own; the searches still go one after another through the one web search
     * tool. Default ON: one tab and one model call at a time leave the GPU idle most of the time.
     */
    public static final String KEY_TEAM_ENABLED = "research.team.enabled";
    /**
     * How many page workers run at the same time, one tab each in the team's page browser. Default 4: the worker
     * browsers' default tabs per user context; the 2026-10-30 benchmark scales {@code gemma4:e2b} up to about 20.
     */
    public static final String KEY_TEAM_WORKERS = "research.team.workers";
    public static final int DEFAULT_TEAM_WORKERS = 4;

    /** Plan T4: how many of a page's candidate links a page worker may pick to open (depth 1). */
    public static final String KEY_TEAM_FOLLOW_LINKS = "research.team.followLinks";
    public static final int DEFAULT_TEAM_FOLLOW_LINKS = 2;
    /**
     * Plan T4: how much page text one page worker reads (characters). Default 12000: about 3000 tokens, a page
     * turn of {@code gemma4:e2b} stays far inside its 32768-token context also with 20 parallel workers.
     */
    public static final String KEY_TEAM_PAGE_CHARACTERS = "research.team.pageCharacters";
    public static final int DEFAULT_TEAM_PAGE_CHARACTERS = 12_000;

    /**
     * Plan T4: the longest answer of one page worker turn (tokens). Default 512: the labelled lines (a summary of
     * two to five sentences, the verdict, the links and an optional message) fit with room to spare.
     */
    public static final String KEY_TEAM_ANSWER_TOKENS = "research.team.answerTokens";
    public static final int DEFAULT_TEAM_ANSWER_TOKENS = 512;

    public static int loadTeamAnswerTokens(WorkspaceStateStore store) {
        return loadPositiveInt(store, KEY_TEAM_ANSWER_TOKENS, DEFAULT_TEAM_ANSWER_TOKENS);
    }

    /**
     * Plan T4: how many new search queries a page worker may suggest per page it reads. Default 2: enough to follow
     * a named standard or study, few enough that one search does not flood the plan.
     */
    public static final String KEY_TEAM_SEARCHES_PER_PAGE = "research.team.searchesPerPage";
    public static final int DEFAULT_TEAM_SEARCHES_PER_PAGE = 2;

    /**
     * Plan T4: the next search starts when fewer pages than this are still open in the running one, so the team
     * never runs dry. Default: the worker count.
     */
    public static final String KEY_TEAM_NEXT_SEARCH_BELOW = "research.team.nextSearchBelow";

    /**
     * Plan T4: the queries per concept node the seed plan starts with when the research team runs (the base query
     * and the planner's variants). Default 1: the concept's breadth first, one query per node; the deeper queries
     * come from what the team reads.
     */
    public static final String KEY_TEAM_SEED_QUERIES_PER_NODE = "research.team.seedQueriesPerNode";
    public static final int DEFAULT_TEAM_SEED_QUERIES_PER_NODE = 1;

    /**
     * Plan T4 gap review: how long the cycle boundary waits for the shared evidence analysis to hold the material
     * read so far (no source waiting for processing, the corpus analysed); after it the cycle plans from the source
     * counts.
     */
    public static final String KEY_GAP_WAIT_SECONDS = "research.gaps.waitSeconds";
    public static final int DEFAULT_GAP_WAIT_SECONDS = 180;

    /** Plan T4 gap review: how often the waiting review looks again at the analysis (seconds). */
    public static final String KEY_GAP_RECHECK_SECONDS = "research.gaps.recheckSeconds";
    public static final int DEFAULT_GAP_RECHECK_SECONDS = 5;

    public static int loadGapRecheckSeconds(WorkspaceStateStore store) {
        return loadPositiveInt(store, KEY_GAP_RECHECK_SECONDS, DEFAULT_GAP_RECHECK_SECONDS);
    }

    /** Plan T4 gap review: the most new topics of the material (inside the Weidezaun) handed to the planner. */
    public static final String KEY_GAP_NEW_TOPICS = "research.gaps.newTopics";
    public static final int DEFAULT_GAP_NEW_TOPICS = 3;

    /** Plan T4 gap review: the most queries that found nothing named per gap node. */
    public static final String KEY_GAP_FAILED_QUERIES = "research.gaps.failedQueries";
    public static final int DEFAULT_GAP_FAILED_QUERIES = 3;

    public static int loadGapWaitSeconds(WorkspaceStateStore store) {
        return loadNonNegativeInt(store, KEY_GAP_WAIT_SECONDS, DEFAULT_GAP_WAIT_SECONDS);
    }

    public static int loadGapNewTopics(WorkspaceStateStore store) {
        return loadNonNegativeInt(store, KEY_GAP_NEW_TOPICS, DEFAULT_GAP_NEW_TOPICS);
    }

    public static int loadGapFailedQueries(WorkspaceStateStore store) {
        return loadNonNegativeInt(store, KEY_GAP_FAILED_QUERIES, DEFAULT_GAP_FAILED_QUERIES);
    }

    public static int loadTeamSearchesPerPage(WorkspaceStateStore store) {
        return loadNonNegativeInt(store, KEY_TEAM_SEARCHES_PER_PAGE, DEFAULT_TEAM_SEARCHES_PER_PAGE);
    }

    public static int loadTeamNextSearchBelow(WorkspaceStateStore store) {
        return loadPositiveInt(store, KEY_TEAM_NEXT_SEARCH_BELOW, Math.max(1, loadTeamWorkers(store)));
    }

    public static int loadTeamSeedQueriesPerNode(WorkspaceStateStore store) {
        return loadPositiveInt(store, KEY_TEAM_SEED_QUERIES_PER_NODE, DEFAULT_TEAM_SEED_QUERIES_PER_NODE);
    }

    public static int loadTeamFollowLinks(WorkspaceStateStore store) {
        return loadNonNegativeInt(store, KEY_TEAM_FOLLOW_LINKS, DEFAULT_TEAM_FOLLOW_LINKS);
    }

    public static int loadTeamPageCharacters(WorkspaceStateStore store) {
        return loadPositiveInt(store, KEY_TEAM_PAGE_CHARACTERS, DEFAULT_TEAM_PAGE_CHARACTERS);
    }

    public static boolean loadTeamEnabled(WorkspaceStateStore store) {
        return store == null || store.getBoolean(KEY_TEAM_ENABLED, true);
    }

    public static void saveTeamEnabled(WorkspaceStateStore store, boolean enabled) {
        if (store != null) {
            store.putBoolean(KEY_TEAM_ENABLED, enabled);
        }
    }

    /**
     * The page workers that may run at once, 0 when the research team is switched off. Each worker has its own tab
     * in the team's one user context, so the "Tabs/user context" setting of the worker browsers bounds the count.
     */
    public static int loadTeamWorkers(WorkspaceStateStore store) {
        if (!loadTeamEnabled(store)) {
            return 0;
        }
        return Math.min(loadPositiveInt(store, KEY_TEAM_WORKERS, DEFAULT_TEAM_WORKERS),
                loadPositiveInt(store, KEY_WORKER_TABS_PER_USER_CONTEXT, DEFAULT_WORKER_TABS_PER_USER_CONTEXT));
    }

    /** The caps of the search plan, read at each queue start. */
    public static com.aresstack.askai.research.plan.SearchPlanCoverage.Caps loadSearchPlanCaps(
            WorkspaceStateStore store) {
        return new com.aresstack.askai.research.plan.SearchPlanCoverage.Caps(
                loadPositiveInt(store, KEY_PLAN_SOURCES_PER_NODE, DEFAULT_PLAN_SOURCES_PER_NODE),
                loadPositiveInt(store, KEY_PLAN_QUERIES_PER_NODE, DEFAULT_PLAN_QUERIES_PER_NODE),
                loadNonNegativeInt(store, KEY_PLAN_PHASE_SOURCES, DEFAULT_PLAN_PHASE_SOURCES),
                loadPositiveInt(store, KEY_PLAN_CYCLES, DEFAULT_PLAN_CYCLES))
                .withPreferenceQueries(loadSourcePreferenceEnabled(store)
                        ? loadNonNegativeInt(store, KEY_SOURCE_PREFERENCE_QUERIES, DEFAULT_SOURCE_PREFERENCE_QUERIES)
                        : 0);
    }

    // ------------------------------------------------------------------ Sources: the mission's source preference
    /**
     * Whether the mission's source preference ("Bevorzuge … (DGUV, BAuA, EU-OSHA, Gesetze, Studien)") steers the
     * search plan: publisher queries per node and cycle and a ranking that moves preferred hosts up and vendor pages
     * down. Default ON: the 2026-11-10 live run cited no single preferred source although the mission named five.
     */
    public static final String KEY_SOURCE_PREFERENCE_ENABLED = "research.sources.preferMissionSources";
    /** The words that open the preference clause of a mission. */
    public static final String KEY_SOURCE_PREFERENCE_CUES = "research.sources.preferenceCues";
    public static final String DEFAULT_SOURCE_PREFERENCE_CUES =
            "bevorzuge, bevorzugt, vorzugsweise, vorrangig, prefer, preferably, favour, favor";
    /**
     * Publisher name → domain, for the {@code site:} queries and the ranking. The defaults are the German and
     * European authorities, insurers and standards bodies a German non-fiction book cites most; the user edits the
     * list for other fields. A domain written in the mission itself needs no entry.
     */
    public static final String KEY_SOURCE_PREFERENCE_PUBLISHERS = "research.sources.publisherDomains";
    public static final String DEFAULT_SOURCE_PREFERENCE_PUBLISHERS = "DGUV=dguv.de, BAuA=baua.de, "
            + "EU-OSHA=osha.europa.eu, Gesetze=gesetze-im-internet.de, Gesetz=gesetze-im-internet.de, "
            + "EUR-Lex=eur-lex.europa.eu, DIN=din.de, ISO=iso.org, BSI=bsi.bund.de, Destatis=destatis.de, "
            + "Eurostat=ec.europa.eu, RKI=rki.de, BfDI=bfdi.bund.de, EDPB=edpb.europa.eu";
    /** Host suffixes of authorities that move up in the ranking whatever the mission says. */
    public static final String KEY_SOURCE_PREFERENCE_AUTHORITY_HOSTS = "research.sources.authorityHosts";
    public static final String DEFAULT_SOURCE_PREFERENCE_AUTHORITY_HOSTS =
            ".bund.de, .europa.eu, .gv.at, .admin.ch, .gov, .gov.uk, .edu";
    /** URL parts of vendor pages (blog, product, solution, shop) that move down in the ranking, never out. */
    public static final String KEY_SOURCE_PREFERENCE_DEMOTED = "research.sources.demotedUrlParts";
    public static final String DEFAULT_SOURCE_PREFERENCE_DEMOTED = "/blog, /news/, /produkt, /product, /loesung, "
            + "/lösung, /solution, /shop, /kontakt, /contact, /demo";
    /**
     * The publisher queries per node and research cycle, on top of "Queries per node". Default 1: each cycle asks
     * the next preferred publisher for every node, so three cycles reach three publishers without doubling the plan.
     */
    public static final String KEY_SOURCE_PREFERENCE_QUERIES = "research.sources.preferenceQueriesPerNode";
    public static final int DEFAULT_SOURCE_PREFERENCE_QUERIES = 1;
    /**
     * The places a preferred hit moves up and a vendor page moves down among the selected hits of a planned query,
     * and a passage of such a source in the evidence pack of a section. Default 3: a guideline ranked fifth opens
     * before a vendor blog ranked second, the clearly best hit stays first.
     */
    public static final String KEY_SOURCE_PREFERENCE_RANK_SHIFT = "research.sources.preferenceRankShift";
    public static final int DEFAULT_SOURCE_PREFERENCE_RANK_SHIFT = 3;

    public static boolean loadSourcePreferenceEnabled(WorkspaceStateStore store) {
        return store == null || store.getBoolean(KEY_SOURCE_PREFERENCE_ENABLED, true);
    }

    public static void saveSourcePreferenceEnabled(WorkspaceStateStore store, boolean enabled) {
        if (store != null) {
            store.putBoolean(KEY_SOURCE_PREFERENCE_ENABLED, enabled);
        }
    }

    /** A comma-separated list setting; the default when unset. */
    public static String loadText(WorkspaceStateStore store, String key, String fallback) {
        if (store == null) {
            return fallback;
        }
        String value = store.get(key, null);
        return value == null ? fallback : value;
    }

    public static void saveText(WorkspaceStateStore store, String key, String value) {
        if (store != null) {
            store.put(key, value == null ? "" : value.trim());
        }
    }

    /** The items of a comma-separated list. */
    public static java.util.List<String> items(String text) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        if (text != null) {
            for (String part : text.split(",")) {
                if (!part.trim().isEmpty()) {
                    out.add(part.trim());
                }
            }
        }
        return out;
    }

    /** The settings of the mission's source preference. */
    public static com.aresstack.askai.research.plan.SourcePreference.Settings loadSourcePreference(
            WorkspaceStateStore store) {
        java.util.Map<String, String> publishers = new java.util.LinkedHashMap<String, String>();
        for (String pair : items(loadText(store, KEY_SOURCE_PREFERENCE_PUBLISHERS,
                DEFAULT_SOURCE_PREFERENCE_PUBLISHERS))) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                publishers.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
            }
        }
        return new com.aresstack.askai.research.plan.SourcePreference.Settings(
                loadSourcePreferenceEnabled(store),
                items(loadText(store, KEY_SOURCE_PREFERENCE_CUES, DEFAULT_SOURCE_PREFERENCE_CUES)),
                publishers,
                items(loadText(store, KEY_SOURCE_PREFERENCE_AUTHORITY_HOSTS, DEFAULT_SOURCE_PREFERENCE_AUTHORITY_HOSTS)),
                items(loadText(store, KEY_SOURCE_PREFERENCE_DEMOTED, DEFAULT_SOURCE_PREFERENCE_DEMOTED)),
                loadNonNegativeInt(store, KEY_SOURCE_PREFERENCE_QUERIES, DEFAULT_SOURCE_PREFERENCE_QUERIES),
                loadNonNegativeInt(store, KEY_SOURCE_PREFERENCE_RANK_SHIFT, DEFAULT_SOURCE_PREFERENCE_RANK_SHIFT));
    }

    // ------------------------------------------------------------------ Document: writer gaps → research
    /**
     * Whether the gaps the writer left (its {@code GAP:} lines, sections it could not write for lack of evidence)
     * are researched on their own after a writing job, as "Research this gap" entries of the search plan. Default
     * ON: M2 asks the app to research its own gaps without a human; live run 3 ended with four open points nobody
     * searched for.
     */
    public static final String KEY_WRITER_GAP_RESEARCH = "research.document.researchWriterGaps";
    /**
     * The most gaps one writing job sends to research. Default 6: one or two per section of a short book, few enough
     * that the search plan's phase budget is not spent on a single job's open points.
     */
    public static final String KEY_WRITER_GAP_MAX = "research.document.writerGapsPerJob";
    public static final int DEFAULT_WRITER_GAP_MAX = 6;
    /** Words in a writer failure that mean "not enough evidence" (the failed section becomes a gap). */
    public static final String KEY_WRITER_GAP_EVIDENCE_WORDS = "research.document.evidenceFailureWords";
    public static final String DEFAULT_WRITER_GAP_EVIDENCE_WORDS =
            "evidence, passage, source, beleg, quelle, citation";

    public static boolean loadWriterGapResearch(WorkspaceStateStore store) {
        return store == null || store.getBoolean(KEY_WRITER_GAP_RESEARCH, true);
    }

    public static void saveWriterGapResearch(WorkspaceStateStore store, boolean enabled) {
        if (store != null) {
            store.putBoolean(KEY_WRITER_GAP_RESEARCH, enabled);
        }
    }

    // ------------------------------------------------------------------ Document: evidence pack
    /** Fewest words a passage needs to enter a writer's evidence pack. */
    public static final String KEY_WRITER_PROSE_MIN_WORDS = "research.writer.prose.minWords";
    /** Fewest finished sentences a passage needs to enter a writer's evidence pack. */
    public static final String KEY_WRITER_PROSE_MIN_SENTENCES = "research.writer.prose.minSentences";
    /** Longest run of words without punctuation a passage may contain (menus and footers exceed it). */
    public static final String KEY_WRITER_PROSE_MAX_RUN = "research.writer.prose.maxUnpunctuatedRun";

    /**
     * The prose criteria of the evidence pack (live run 3, fix 2), read for every section: navigation, footer and
     * heading passages fail them and never reach the writer.
     */
    public static com.aresstack.askai.research.runtime.writer.ProsePassageFilter.Criteria loadWriterProseCriteria(
            WorkspaceStateStore store) {
        return new com.aresstack.askai.research.runtime.writer.ProsePassageFilter.Criteria(
                loadNonNegativeInt(store, KEY_WRITER_PROSE_MIN_WORDS,
                        com.aresstack.askai.research.runtime.writer.ProsePassageFilter.Criteria.DEFAULT_MIN_WORDS),
                loadNonNegativeInt(store, KEY_WRITER_PROSE_MIN_SENTENCES,
                        com.aresstack.askai.research.runtime.writer.ProsePassageFilter.Criteria
                                .DEFAULT_MIN_SENTENCES),
                loadPositiveInt(store, KEY_WRITER_PROSE_MAX_RUN,
                        com.aresstack.askai.research.runtime.writer.ProsePassageFilter.Criteria
                                .DEFAULT_MAX_UNPUNCTUATED_RUN));
    }

    /** Claim check stage 2: fewest letters of a content word. */
    public static final String KEY_WRITER_CARRY_MIN_WORD_LENGTH = "research.writer.carry.minWordLength";
    /** Claim check stage 2: letters two words must share to count as the same word. */
    public static final String KEY_WRITER_CARRY_STEM_LENGTH = "research.writer.carry.stemLength";
    /** Claim check stage 2: share of a sentence's content words a cited passage must contain. */
    public static final String KEY_WRITER_CARRY_MIN_OVERLAP = "research.writer.carry.minOverlap";

    /**
     * The thresholds of claim check stage 2 (live run 3, fix 3) for the section writer and the book's quality
     * report; the prose criteria are the evidence pack's.
     */
    public static com.aresstack.askai.research.runtime.writer.ClaimCarryCheck.Criteria loadWriterCarryCriteria(
            WorkspaceStateStore store) {
        double overlap = loadDouble(store, KEY_WRITER_CARRY_MIN_OVERLAP,
                com.aresstack.askai.research.runtime.writer.ClaimCarryCheck.Criteria.DEFAULT_MIN_OVERLAP);
        if (overlap < 0 || overlap > 1) {
            overlap = com.aresstack.askai.research.runtime.writer.ClaimCarryCheck.Criteria.DEFAULT_MIN_OVERLAP;
        }
        return new com.aresstack.askai.research.runtime.writer.ClaimCarryCheck.Criteria(
                loadPositiveInt(store, KEY_WRITER_CARRY_MIN_WORD_LENGTH,
                        com.aresstack.askai.research.runtime.writer.ClaimCarryCheck.Criteria
                                .DEFAULT_MIN_WORD_LENGTH),
                loadPositiveInt(store, KEY_WRITER_CARRY_STEM_LENGTH,
                        com.aresstack.askai.research.runtime.writer.ClaimCarryCheck.Criteria.DEFAULT_STEM_LENGTH),
                overlap, loadWriterProseCriteria(store));
    }

    // ------------------------------------------------------------------ Document: writer preset
    /** Words the section writer aims for in one call; a longer section is split by its key points. */
    public static final String KEY_WRITER_WORDS_PER_CALL = "research.writer.wordsPerCall";
    /** Answer token budget of one writer call. */
    public static final String KEY_WRITER_ANSWER_TOKENS = "research.writer.answerTokens";
    /** Passages one writer call gets; a section written in key-point parts gets this many for each part. */
    public static final String KEY_WRITER_PASSAGES = "research.writer.passages";
    /** Tokens each evidence passage may take. */
    public static final String KEY_WRITER_PASSAGE_TOKENS = "research.writer.passageTokens";
    /** Passages one source may contribute to a pack. */
    public static final String KEY_WRITER_PASSAGES_PER_SOURCE = "research.writer.passagesPerSource";
    /**
     * Least similarity of a passage to the section's brief (cosine, 0..1, 0 = off). Embedding-dependent: the default
     * is calibrated for {@code bge-m3} ({@link com.aresstack.askai.research.writer.WriterPackSettings.BriefRelevance}).
     */
    public static final String KEY_WRITER_MIN_BRIEF_SIMILARITY = "research.writer.minBriefSimilarity";
    /**
     * How far a passage may lie below the section's best passage (cosine, 0..1, 0 = off). Embedding-dependent: the
     * default is calibrated for {@code bge-m3}.
     */
    public static final String KEY_WRITER_BRIEF_SIMILARITY_MARGIN = "research.writer.briefSimilarityMargin";
    /**
     * Words per call: the plan's small preset (450). Live run 4 measured {@code llama3.1:8b} at 148 to 425 words per
     * call in all 14 section revisions against the old target of 1,100, so a section of 1,100 words came out at a
     * fifth of its length; at 450, such a section is written in three key-point parts of about 370 words each.
     */
    public static final int DEFAULT_WRITER_WORDS_PER_CALL = 450;
    /** The answer budget of live run 3 (3,072 tokens): room for a part and its GAP lines without a continuation. */
    public static final int DEFAULT_WRITER_ANSWER_TOKENS = 3072;

    /**
     * The section writer's targets (live run 3, fix 4), read for every section: words and answer budget per call,
     * evidence pack size and per-source cap, prose criteria and claim-check thresholds. The defaults are the values
     * that were hard-coded before.
     */
    public static com.aresstack.askai.research.drafting.EvidenceAssignments.Targets loadWriterTargets(
            WorkspaceStateStore store) {
        com.aresstack.askai.research.writer.WriterPackSettings large =
                com.aresstack.askai.research.writer.WriterPackSettings.LARGE;
        int passageTokens = loadPositiveInt(store, KEY_WRITER_PASSAGE_TOKENS, large.getPassageTokens());
        if (passageTokens < com.aresstack.askai.research.writer.WriterPackSettings.MIN_PASSAGE_TOKENS) {
            passageTokens = large.getPassageTokens();
        }
        return new com.aresstack.askai.research.drafting.EvidenceAssignments.Targets(
                new com.aresstack.askai.research.writer.WriterPackSettings(
                        loadPositiveInt(store, KEY_WRITER_PASSAGES, large.getPassageCount()), passageTokens,
                        loadWriterProseCriteria(store),
                        loadPositiveInt(store, KEY_WRITER_PASSAGES_PER_SOURCE,
                                com.aresstack.askai.research.writer.WriterPackSettings.DEFAULT_PASSAGES_PER_SOURCE),
                        loadWriterBriefRelevance(store)),
                loadPositiveInt(store, KEY_WRITER_WORDS_PER_CALL, DEFAULT_WRITER_WORDS_PER_CALL),
                loadPositiveInt(store, KEY_WRITER_ANSWER_TOKENS, DEFAULT_WRITER_ANSWER_TOKENS),
                loadWriterCarryCriteria(store));
    }

    /**
     * How close an evidence passage must be to its section's brief (live run 4, book 6: passages of another subject
     * filled the packs of 1.1, 1.2, 2.1 and 4.1). A value outside 0..1 falls back to its default.
     */
    public static com.aresstack.askai.research.writer.WriterPackSettings.BriefRelevance loadWriterBriefRelevance(
            WorkspaceStateStore store) {
        return new com.aresstack.askai.research.writer.WriterPackSettings.BriefRelevance(
                loadUnitInterval(store, KEY_WRITER_MIN_BRIEF_SIMILARITY,
                        com.aresstack.askai.research.writer.WriterPackSettings.BriefRelevance.DEFAULT_MIN_SIMILARITY),
                loadUnitInterval(store, KEY_WRITER_BRIEF_SIMILARITY_MARGIN,
                        com.aresstack.askai.research.writer.WriterPackSettings.BriefRelevance.DEFAULT_MARGIN));
    }

    private static double loadUnitInterval(WorkspaceStateStore store, String key, double fallback) {
        double value = loadDouble(store, key, fallback);
        return value >= 0 && value <= 1 ? value : fallback;
    }

    // ------------------------------------------------------------------ Document: topic drift in the book report
    public static final String KEY_DRIFT_MIN_TERM_LENGTH = "research.book.drift.minTermLength";
    public static final String KEY_DRIFT_MIN_SENTENCES = "research.book.drift.minSentences";
    public static final String KEY_DRIFT_NOUNS_ONLY = "research.book.drift.nounsOnly";

    /** The topic-drift thresholds of the book's quality report; the stem is the claim check's. */
    public static com.aresstack.askai.research.assembly.TopicDriftCheck.Criteria loadTopicDriftCriteria(
            WorkspaceStateStore store) {
        boolean nounsOnly = store == null
                ? com.aresstack.askai.research.assembly.TopicDriftCheck.Criteria.DEFAULT_NOUNS_ONLY
                : store.getBoolean(KEY_DRIFT_NOUNS_ONLY,
                        com.aresstack.askai.research.assembly.TopicDriftCheck.Criteria.DEFAULT_NOUNS_ONLY);
        return new com.aresstack.askai.research.assembly.TopicDriftCheck.Criteria(
                loadPositiveInt(store, KEY_DRIFT_MIN_TERM_LENGTH,
                        com.aresstack.askai.research.assembly.TopicDriftCheck.Criteria.DEFAULT_MIN_TERM_LENGTH),
                loadPositiveInt(store, KEY_DRIFT_MIN_SENTENCES,
                        com.aresstack.askai.research.assembly.TopicDriftCheck.Criteria.DEFAULT_MIN_SENTENCES),
                loadWriterCarryCriteria(store).getStemLength(), nounsOnly);
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
        return store != null && store.getBoolean(KEY_BOT_CONTROL, false);
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

    // ADR 0001 lane G: the new worker roles (each on by default inside the orchestrated mode) and their limits.
    public static final String KEY_WORKER_SEARCH_PLANNER = "research.orchestrator.worker.searchPlanner";
    public static final String KEY_WORKER_SOURCE_VERIFIER = "research.orchestrator.worker.sourceVerifier";
    public static final String KEY_WORKER_WRITER = "research.orchestrator.worker.writer";
    public static final String KEY_WORKER_PLANNER_MAX_QUERIES = "research.orchestrator.worker.plannerMaxQueries";
    public static final String KEY_WORKER_VERIFIER_MAX_SOURCES = "research.orchestrator.worker.verifierMaxSources";
    public static final String KEY_WORKER_WRITER_MAX_SOURCES = "research.orchestrator.worker.writerMaxSources";
    public static final int DEFAULT_WORKER_PLANNER_MAX_QUERIES =
            com.aresstack.askai.research.runtime.orchestrator.WorkerRoleSettings.DEFAULT_PLANNER_MAX_QUERIES;
    public static final int DEFAULT_WORKER_VERIFIER_MAX_SOURCES =
            com.aresstack.askai.research.runtime.orchestrator.WorkerRoleSettings.DEFAULT_VERIFIER_MAX_SOURCES;
    public static final int DEFAULT_WORKER_WRITER_MAX_SOURCES =
            com.aresstack.askai.research.runtime.orchestrator.WorkerRoleSettings.DEFAULT_WRITER_MAX_SOURCES;

    public static boolean loadWorkerRole(WorkspaceStateStore store, String key) {
        return store == null || store.getBoolean(key, true);
    }

    public static void saveWorkerRole(WorkspaceStateStore store, String key, boolean enabled) {
        if (store != null) {
            store.putBoolean(key, enabled);
        }
    }

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
        env.put(com.aresstack.askai.research.runtime.orchestrator.WorkerRoleSettings.ENV_SEARCH_PLANNER,
                loadWorkerRole(store, KEY_WORKER_SEARCH_PLANNER) ? "on" : "off");
        env.put(com.aresstack.askai.research.runtime.orchestrator.WorkerRoleSettings.ENV_SOURCE_VERIFIER,
                loadWorkerRole(store, KEY_WORKER_SOURCE_VERIFIER) ? "on" : "off");
        env.put(com.aresstack.askai.research.runtime.orchestrator.WorkerRoleSettings.ENV_WRITER,
                loadWorkerRole(store, KEY_WORKER_WRITER) ? "on" : "off");
        env.put(com.aresstack.askai.research.runtime.orchestrator.WorkerRoleSettings.ENV_PLANNER_MAX_QUERIES,
                String.valueOf(loadPositiveInt(store, KEY_WORKER_PLANNER_MAX_QUERIES,
                        DEFAULT_WORKER_PLANNER_MAX_QUERIES)));
        env.put(com.aresstack.askai.research.runtime.orchestrator.WorkerRoleSettings.ENV_VERIFIER_MAX_SOURCES,
                String.valueOf(loadPositiveInt(store, KEY_WORKER_VERIFIER_MAX_SOURCES,
                        DEFAULT_WORKER_VERIFIER_MAX_SOURCES)));
        env.put(com.aresstack.askai.research.runtime.orchestrator.WorkerRoleSettings.ENV_WRITER_MAX_SOURCES,
                String.valueOf(loadPositiveInt(store, KEY_WORKER_WRITER_MAX_SOURCES,
                        DEFAULT_WORKER_WRITER_MAX_SOURCES)));
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
        return new ResearchRuntimeSettings(ResearchBackendMode.FAKE, CAMOUFOX_CHANNEL, false);
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
                // Browsing is visible by default (the search browser has its own, headless default).
                store.getBoolean(KEY_HEADLESS, false),
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
