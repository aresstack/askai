package com.aresstack.askai.research.host;

import com.aresstack.comiccontrols.control.ComicTextField;
import com.aresstack.comiccontrols.control.ComicPasswordField;
import com.aresstack.comiccontrols.control.ComicControls;
import com.aresstack.comiccontrols.control.ComicButton;
import com.aresstack.comiccontrols.theme.ThemeColor;
import com.aresstack.askai.plugin.api.service.WorkspaceStateStore;
import com.aresstack.askai.research.agentwire.ResearchBackendMode;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.List;

/**
 * The research runtime configuration surface. Reads/writes exclusively through
 * {@link ResearchRuntimeSettings} (the same mapper the session factory uses). Saving a productive configuration
 * that fails validation is rejected with the concrete problem list; FAKE is labelled clearly as the
 * clickdummy/development mode. "Check requirements" runs validation + the browser readiness probe on a
 * background daemon thread (never the EDT) with a visible busy state; results come back via
 * {@link SwingUtilities#invokeLater} as one structured line per requirement.
 */
public final class ResearchRuntimeSettingsPanel extends JPanel {

    private final WorkspaceStateStore store;
    // There is deliberately NO backend-mode field: productive is simply THE mode whenever its
    // requirements are met; otherwise new sessions run the demo backend with a visible notice.
    // (A persisted FAKE value remains a developer-only override in the store.)
    private final javax.swing.JTextArea backendStatus = com.aresstack.comiccontrols.control.ComicForm.text(" ");
    // Konzeptpapier tool-round loop: work budget and error tolerance, deliberately separate.
    private final JTextField conceptToolRounds = ComicControls.textField(4);
    private final JTextField conceptRepairAttempts = ComicControls.textField(4);
    // Document phase: what a passage needs to enter a writer's evidence pack.
    private final JTextField writerProseMinWords = ComicControls.textField(4);
    private final JTextField writerProseMinSentences = ComicControls.textField(4);
    private final JTextField writerProseMaxRun = ComicControls.textField(4);
    private final JTextField writerCarryMinWordLength = ComicControls.textField(4);
    private final JTextField writerCarryStemLength = ComicControls.textField(4);
    private final JTextField writerCarryMinOverlap = ComicControls.textField(4);
    private final JTextField writerRepeatMinWords = ComicControls.textField(4);
    private final JTextField writerRepeatMinShare = ComicControls.textField(4);
    private final JTextField writerSynthesisMinSources = ComicControls.textField(4);
    private final JTextField writerSynthesisParagraphs = ComicControls.textField(4);
    private final JTextField writerSynthesisMinSentences = ComicControls.textField(4);
    private final JTextField writerSynthesisMaxShare = ComicControls.textField(4);
    private final JTextField writerMinBriefSimilarity = ComicControls.textField(4);
    private final JTextField writerBriefSimilarityMargin = ComicControls.textField(4);
    private final JTextField writerWordsPerCall = ComicControls.textField(5);
    private final JTextField writerAnswerTokens = ComicControls.textField(5);
    private final JTextField writerPassages = ComicControls.textField(4);
    private final JTextField writerPassageTokens = ComicControls.textField(4);
    private final JTextField writerPassagesPerSource = ComicControls.textField(4);
    private final JTextField driftMinTermLength = ComicControls.textField(4);
    private final JTextField driftMinSentences = ComicControls.textField(4);
    private final JCheckBox driftNounsOnly = ComicControls.checkBox("Nouns only (capitalised)", true);
    // Search-run limits: the completion target + the safety limits. Every bound is a setting.
    private final JTextField searchTargetSources = ComicControls.textField(4);
    private final JTextField searchMaxPages = ComicControls.textField(4);
    private final JTextField searchMaxToolCalls = ComicControls.textField(4);
    private final JTextField searchMaxMinutes = ComicControls.textField(4);
    private final JTextField browserToolTimeout = ComicControls.textField(4);
    private final JTextField browserIdleStop = ComicControls.textField(4);
    private final JTextField searchMaxErrors = ComicControls.textField(4);
    // Research team (plan T5): the Sources phase's planned queries side by side, one page worker each.
    private final JCheckBox teamEnabled = ComicControls.checkBox("Parallel page workers in Sources", true);
    private final JTextField teamWorkers = ComicControls.textField(3);
    private final JTextField teamFollowLinks = ComicControls.textField(3);
    private final JTextField teamPageCharacters = ComicControls.textField(6);
    private final JTextField teamAnswerTokens = ComicControls.textField(5);
    private final JTextField teamSearchesPerPage = ComicControls.textField(3);
    private final JTextField teamNextSearchBelow = ComicControls.textField(3);
    private final JTextField teamSeedQueries = ComicControls.textField(3);
    // The mission's source preference: publisher queries per node and cycle, ranking weights (all settings).
    private final JCheckBox preferMissionSources = ComicControls.checkBox("Prefer the sources the mission names", true);
    private final JTextField preferenceQueries = ComicControls.textField(3);
    private final JTextField preferenceRankShift = ComicControls.textField(3);
    private final JTextField preferencePublishers = ComicControls.textField(16);
    private final JTextField preferenceAuthorityHosts = ComicControls.textField(16);
    private final JTextField preferenceDemoted = ComicControls.textField(16);
    private final JTextField preferenceCues = ComicControls.textField(16);
    // Document: the writer's gaps go to research on their own.
    private final JCheckBox writerGapResearch = ComicControls.checkBox("Research writer gaps after writing", true);
    private final JTextField writerGapMax = ComicControls.textField(3);
    private final JTextField writerGapWords = ComicControls.textField(16);
    private final JTextField gapWaitSeconds = ComicControls.textField(3);
    private final JTextField gapRecheckSeconds = ComicControls.textField(3);
    private final JTextField gapNewTopics = ComicControls.textField(3);
    private final JTextField gapFailedQueries = ComicControls.textField(3);
    // Worker browsers (parallel research workers): separate from the model capacity.
    private final JTextField workerProcesses = ComicControls.textField(3);
    private final JTextField workerUserContexts = ComicControls.textField(3);
    private final JTextField workerTabs = ComicControls.textField(3);
    private final JTextField workerPerSite = ComicControls.textField(3);
    private final JTextField workerAcquireTimeout = ComicControls.textField(4);
    // Review-context bounds: how many sources one review reads, and how much of each.
    private final JTextField reviewMaxSources = ComicControls.textField(4);
    private final JTextField reviewMaxChars = ComicControls.textField(6);
    // Z4c Scope-Pruefung: the normal knobs of the explicit "Themenraum pruefen" check…
    private final JTextField scopeCheckTargetProbes = ComicControls.textField(4);
    private final JTextField scopeCheckControlsPerAnchor = ComicControls.textField(3);
    private final JTextField scopeCheckMaxTopics = ComicControls.textField(3);
    private final JTextField scopeCheckTimeout = ComicControls.textField(4);
    // …and the ADVANCED ones (hidden by default): model knobs plus the deliberately provisional
    // calibration spike values — configurable so productive measurements never need a code
    // change, but not presented as mature user options.
    private final JTextField scopeCheckGenTemperature = ComicControls.textField(4);
    private final JTextField scopeCheckGenMaxTokens = ComicControls.textField(5);
    private final JTextField scopeCheckChooserTemperature = ComicControls.textField(4);
    private final JTextField scopeCheckChooserMaxTokens = ComicControls.textField(5);
    private final JTextField scopeCheckChoiceTimeout = ComicControls.textField(4);
    private final JTextField scopeCheckNearSimilarity = ComicControls.textField(4);
    private final JTextField scopeCheckHintBoundaryMargin = ComicControls.textField(4);
    private final JTextField scopeCheckMissionQuantile = ComicControls.textField(4);
    private final JTextField scopeCheckMissionMargin = ComicControls.textField(4);
    private final JTextField scopeCheckNeighborQuantile = ComicControls.textField(4);
    private final JTextField scopeCheckNeighborMargin = ComicControls.textField(4);
    private final JTextField scopeCheckMinNegotiated = ComicControls.textField(3);
    private final JTextField scopeCheckMinNeighbors = ComicControls.textField(3);
    private final JTextField scopeCheckBoundaryMargin = ComicControls.textField(4);
    private final JTextField scopeCheckNoveltyGap = ComicControls.textField(4);
    private final JTextField scopeCheckNoveltyWeight = ComicControls.textField(4);
    private final JTextField scopeCheckDuplicateCeiling = ComicControls.textField(4);
    /** ADR 0001 lane F: orchestrated research opt-in (default OFF = the single autonomous loop). */
    private final JCheckBox orchestratorEnabled = ComicControls.checkBox(
            "Orchestrated research: plan, approve, rounds of work packages (applies to new sessions)", false);
    private final JTextField orchestratorMaxRounds = ComicControls.textField(3);
    private final JTextField orchestratorMaxTasks = ComicControls.textField(3);
    private final JTextField orchestratorTasksPerRound = ComicControls.textField(3);
    private final JTextField orchestratorTokenBudget = ComicControls.textField(7);
    private final JTextField orchestratorTimeBudget = ComicControls.textField(4);
    private final JTextField orchestratorTaskRetries = ComicControls.textField(3);
    private final JTextField orchestratorSectionSources = ComicControls.textField(3);
    /** ADR 0001 lane G: the worker roles the orchestrator may delegate to, and their limits. */
    private final JCheckBox workerSearchPlanner = ComicControls.checkBox("Search planner", true);
    private final JCheckBox workerSourceVerifier = ComicControls.checkBox("Source verifier", true);
    private final JCheckBox workerWriter = ComicControls.checkBox("Writer", true);
    private final JTextField workerPlannerQueries = ComicControls.textField(3);
    private final JTextField workerVerifierSources = ComicControls.textField(3);
    private final JTextField workerWriterSources = ComicControls.textField(3);
    /** AI reranker opt-in (default OFF = lexical BM25 scoring, no model load). */
    private final JCheckBox useAiReranker = ComicControls.checkBox(
            "Use AI reranker for typed searches (semantic model instead of BM25; applies to new sessions)", false);
    /** Bot-control MCP (default ON): run_command/session_state/chat_history + service-endpoint.json. */
    private final JCheckBox botControlMcp = ComicControls.checkBox(
            "Enable MCP server (bot control; applies to new sessions)", false);
    /** Square, margin-less info button (Unicode \u24D8): opens the live connection/tool details. */
    private final javax.swing.JButton botTools = new ComicButton("\u24D8");
    /** The live session behind this settings page (nullable: store-only construction in tests). */
    private final com.aresstack.askai.research.agent.ResearchAgentSession session;
    /** ChatGPT connector (default OFF): AskAI as its own public MCP+OAuth face behind the TLS proxy. */
    private final JCheckBox chatGptConnector = ComicControls.checkBox(
            "ChatGPT connector (public endpoint behind the reverse proxy)", false);
    private final JTextField connectorOrigin = placeholderField("https://askai.example.com", 38);
    private final JTextField connectorPort = ComicControls.textField(6);
    private final JTextField connectorClientId =
            placeholderField(com.aresstack.askai.research.connector.ConnectorConfig.DEFAULT_CLIENT_ID, 12);
    private final javax.swing.JPasswordField connectorClientSecret = placeholderPasswordField(
            com.aresstack.askai.research.connector.ConnectorConfig.DEFAULT_CLIENT_SECRET, 18);
    /** Non-empty = central mode: the AresStack webserver issues the tokens; AskAI only validates. */
    private final JTextField connectorCentralIssuer =
            placeholderField("https://askai.example.com (empty = local OAuth)", 38);
    // The reranker model is chosen centrally in AskAI (Configuration → AI models), NOT here. Any previously
    // persisted plugin-side selection is carried through unchanged so it is never lost on save.
    private String carriedRerankerModel = "";
    private final JButton save = new ComicButton("Save");
    private final JButton check = new ComicButton("Check requirements");
    private final JLabel busy = new JLabel(" ");
    private final JTextArea results = ComicControls.textArea(8, 48);

    public ResearchRuntimeSettingsPanel(WorkspaceStateStore store) {
        this(store, null);
    }

    public ResearchRuntimeSettingsPanel(WorkspaceStateStore store,
            com.aresstack.askai.research.agent.ResearchAgentSession session) {
        super(new BorderLayout(8, 8));
        this.session = session;
        this.store = store;
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        // Grouped like every other settings page: label left, fields right (wrapping onto further lines
        // instead of running off the dialog's right edge), buttons beside their field.
        com.aresstack.comiccontrols.control.ComicForm agent =
                com.aresstack.comiccontrols.control.ComicForm.titled("Agent");
        agent.row("Backend", backendStatus);
        agent.row("", useAiReranker);

        com.aresstack.comiccontrols.control.ComicForm limits =
                com.aresstack.comiccontrols.control.ComicForm.titled("Limits");
        limits.row("Search run", group(labelled("Source target", searchTargetSources),
                labelled("Pages", searchMaxPages), labelled("Tool calls", searchMaxToolCalls),
                labelled("Minutes", searchMaxMinutes), labelled("Tool timeout (s)", browserToolTimeout),
                labelled("Idle stop (s)", browserIdleStop), labelled("Errors", searchMaxErrors)));
        limits.row("Review context", group(labelled("Sources", reviewMaxSources),
                labelled("Chars/source", reviewMaxChars)));
        limits.row("Concept loop", group(labelled("Tool rounds", conceptToolRounds),
                labelled("Repairs", conceptRepairAttempts)));

        // Document phase: the section writer's evidence and checks.
        com.aresstack.comiccontrols.control.ComicForm writer =
                com.aresstack.comiccontrols.control.ComicForm.titled("Document writer");
        writer.row("Writer preset", group(labelled("Words/call", writerWordsPerCall),
                labelled("Answer tokens", writerAnswerTokens), labelled("Passages", writerPassages),
                labelled("Tokens/passage", writerPassageTokens), labelled("Per source", writerPassagesPerSource)));
        writer.row("Evidence prose", group(labelled("Words ≥", writerProseMinWords),
                labelled("Sentences ≥", writerProseMinSentences),
                labelled("Unpunctuated run ≤", writerProseMaxRun)));
        writer.row("Evidence relevance", group(labelled("Similarity ≥", writerMinBriefSimilarity),
                labelled("Below best ≤", writerBriefSimilarityMargin)));
        writer.row("Claim check", group(labelled("Word letters ≥", writerCarryMinWordLength),
                labelled("Stem letters", writerCarryStemLength), labelled("Overlap ≥", writerCarryMinOverlap)));
        writer.row("Repeat check", group(labelled("Words ≥", writerRepeatMinWords),
                labelled("Share ≥", writerRepeatMinShare)));
        writer.row("Synthesis", group(labelled("Sources ≥", writerSynthesisMinSources),
                labelled("Paragraphs/part", writerSynthesisParagraphs),
                labelled("Report: sentences ≥", writerSynthesisMinSentences),
                labelled("One-source share ≤", writerSynthesisMaxShare)));
        writer.row("Topic drift", group(labelled("Term letters ≥", driftMinTermLength),
                labelled("Sentences ≥", driftMinSentences), driftNounsOnly));

        // Plan T5: the research team of the Sources phase and the worker browsers its page workers read in.
        com.aresstack.comiccontrols.control.ComicForm team =
                com.aresstack.comiccontrols.control.ComicForm.titled("Research team");
        team.row("Sources phase", group(teamEnabled, labelled("Workers", teamWorkers)));
        team.row("Page worker", group(labelled("Links to follow", teamFollowLinks),
                labelled("Page characters", teamPageCharacters), labelled("Answer tokens", teamAnswerTokens)));
        team.row("New searches", group(labelled("Per page", teamSearchesPerPage),
                labelled("Next search below open pages", teamNextSearchBelow),
                labelled("Seed queries per node", teamSeedQueries)));
        team.row("Gap review", group(labelled("Wait for analysis (s)", gapWaitSeconds),
                labelled("Recheck (s)", gapRecheckSeconds),
                labelled("New topics", gapNewTopics), labelled("Failed queries named", gapFailedQueries)));

        com.aresstack.comiccontrols.control.ComicForm preference =
                com.aresstack.comiccontrols.control.ComicForm.titled("Source preference");
        preference.row("Mission sources", group(preferMissionSources,
                labelled("Queries per node", preferenceQueries), labelled("Rank shift", preferenceRankShift)));
        preference.row("Publisher domains", preferencePublishers);
        preference.row("Authority hosts", preferenceAuthorityHosts);
        preference.row("Vendor URL parts", preferenceDemoted);
        preference.row("Preference words", preferenceCues);
        preference.row("Writer gaps", group(writerGapResearch, labelled("Per job", writerGapMax)));
        preference.row("Evidence failure words", writerGapWords);

        // Orchestrated research (ADR 0001 lane F/G): the plan of work packages, its limits and the worker browsers.
        com.aresstack.comiccontrols.control.ComicForm orchestrator =
                com.aresstack.comiccontrols.control.ComicForm.titled("Orchestrator");
        // Plan PR 15 (Q5): the Sources phase runs the deterministic plan queue; the orchestrated-mode opt-in is
        // hidden (its stored value stays) until the owner confirms its removal (PR 37).
        orchestrator.row("Limits", group(labelled("Rounds", orchestratorMaxRounds),
                labelled("Packages", orchestratorMaxTasks), labelled("Per round", orchestratorTasksPerRound),
                labelled("Tokens", orchestratorTokenBudget), labelled("Minutes", orchestratorTimeBudget),
                labelled("Retries", orchestratorTaskRetries),
                labelled("Sources/section", orchestratorSectionSources)));
        orchestrator.row("Worker browsers", group(labelled("Processes", workerProcesses),
                labelled("User contexts/process", workerUserContexts),
                labelled("Tabs/user context", workerTabs), labelled("Per site", workerPerSite),
                labelled("Wait (s)", workerAcquireTimeout)));
        orchestrator.row("Worker roles", group(workerSearchPlanner, labelled("Queries", workerPlannerQueries),
                workerSourceVerifier, labelled("Sources", workerVerifierSources),
                workerWriter, labelled("Sources", workerWriterSources)));

        com.aresstack.comiccontrols.control.ComicForm scope =
                com.aresstack.comiccontrols.control.ComicForm.titled("Scope check");
        final javax.swing.JButton scopeCheckAdvancedToggle = new ComicButton("Advanced…");
        scope.row("Topic space", group(labelled("Breadth (concepts)", scopeCheckTargetProbes),
                labelled("Examples per post", scopeCheckControlsPerAnchor),
                labelled("Max. topics", scopeCheckMaxTopics), labelled("Timeout (s)", scopeCheckTimeout)),
                scopeCheckAdvancedToggle);
        // The ADVANCED knobs (model + provisional calibration/sweep values) stay hidden until asked for.
        final com.aresstack.comiccontrols.control.ComicForm scopeAdvanced =
                com.aresstack.comiccontrols.control.ComicForm.titled("Scope check: advanced");
        scopeAdvanced.row("Model", group(labelled("Gen temp", scopeCheckGenTemperature),
                labelled("Gen tokens", scopeCheckGenMaxTokens),
                labelled("Choice temp", scopeCheckChooserTemperature),
                labelled("Choice tokens", scopeCheckChooserMaxTokens),
                labelled("Choice timeout (s)", scopeCheckChoiceTimeout)));
        scopeAdvanced.row("Calibration", group(labelled("near", scopeCheckNearSimilarity),
                labelled("Hint margin", scopeCheckHintBoundaryMargin),
                labelled("Mission Q", scopeCheckMissionQuantile),
                labelled("Mission slack", scopeCheckMissionMargin),
                labelled("Neighbor Q", scopeCheckNeighborQuantile),
                labelled("Neighbor slack", scopeCheckNeighborMargin)));
        scopeAdvanced.row("Sweep", group(labelled("Min. posts", scopeCheckMinNegotiated),
                labelled("Min. examples", scopeCheckMinNeighbors),
                labelled("Boundary margin", scopeCheckBoundaryMargin),
                labelled("Novelty gap", scopeCheckNoveltyGap),
                labelled("Novelty weight", scopeCheckNoveltyWeight),
                labelled("Duplicate ≥", scopeCheckDuplicateCeiling)));
        scopeAdvanced.setVisible(false);
        scopeCheckAdvancedToggle.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent event) {
                scopeAdvanced.setVisible(!scopeAdvanced.isVisible());
                revalidate();
                repaint();
            }
        });
        botTools.setMargin(new java.awt.Insets(0, 0, 0, 0));
        botTools.setPreferredSize(new java.awt.Dimension(24, 24));
        botTools.setFont(botTools.getFont().deriveFont(java.awt.Font.PLAIN, 15f));
        botTools.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent event) {
                showBotTools();
            }
        });
        com.aresstack.askai.research.mcp.ResearchBotControlEndpoint liveEndpoint =
                session == null ? null : session.botControlEndpoint();
        botTools.setEnabled(liveEndpoint != null);
        botTools.setToolTipText(liveEndpoint != null
                ? "Streamable HTTP (MCP JSON-RPC): " + liveEndpoint.connectionUrl()
                : "This session has no bot endpoint (disabled or not a productive session)");
        // Bot control + the public MCP server (ChatGPT connector) live on their own "MCP" tab (mcpView()); browser
        // channel, headless and private targets on the "Browser" tab (BrowserBasicsRows); language and guidance
        // on "General"; the provider selection and the browser search's own settings on the "Search" tab.
        com.aresstack.comiccontrols.control.ComicForm actions = new com.aresstack.comiccontrols.control.ComicForm();
        actions.actions(save, check, busy);
        // Phase 3: the shared topic analysis, the outline synthesis and the book length (saved field by field).
        com.aresstack.comiccontrols.control.ComicForm[] outline = OutlineSettingsRows.forms(store);
        com.aresstack.comiccontrols.control.ComicForm.alignLabels(agent, limits, writer, team, preference,
                orchestrator, scope, scopeAdvanced, outline[0], outline[1], outline[2]);
        add(com.aresstack.comiccontrols.control.ComicForm.stack(agent, limits, writer, team, preference,
                orchestrator, scope, scopeAdvanced, outline[0], outline[1], outline[2], actions),
                BorderLayout.NORTH);

        results.setEditable(false);
        results.setLineWrap(false);
        // The check output is NOT part of the form: it lives on its own tab (see resultsView()).

        // Persisted values first, then the automatic browser-channel decision.
        apply(ResearchRuntimeDefaults.complete(ResearchRuntimeSettings.load(store)));
        orchestratorEnabled.setSelected(ResearchRuntimeSettings.loadOrchestratorEnabled(store));
        orchestratorEnabled.setToolTipText("Off: a research question runs the single autonomous search loop. "
                + "On: the orchestrator proposes a plan of work packages; nothing runs until you approve it, "
                + "and anything beyond the approved plan or budget comes back to you.");
        orchestratorEnabled.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent event) {
                ResearchRuntimeSettings.saveOrchestratorEnabled(ResearchRuntimeSettingsPanel.this.store,
                        orchestratorEnabled.isSelected());
            }
        });
        bindLimit(orchestratorMaxRounds, ResearchRuntimeSettings.KEY_ORCHESTRATOR_MAX_ROUNDS,
                ResearchRuntimeSettings.DEFAULT_ORCHESTRATOR_MAX_ROUNDS,
                "Evaluation rounds of one approved run before it comes back to you.");
        bindLimit(orchestratorMaxTasks, ResearchRuntimeSettings.KEY_ORCHESTRATOR_MAX_TASKS,
                ResearchRuntimeSettings.DEFAULT_ORCHESTRATOR_MAX_TASKS,
                "Work packages of one approved run (plan plus follow-ups).");
        bindLimit(orchestratorTasksPerRound, ResearchRuntimeSettings.KEY_ORCHESTRATOR_TASKS_PER_ROUND,
                ResearchRuntimeSettings.DEFAULT_ORCHESTRATOR_TASKS_PER_ROUND,
                "How many follow-up packages one round may propose.");
        bindNonNegativeLimit(orchestratorTokenBudget, ResearchRuntimeSettings.KEY_ORCHESTRATOR_TOKEN_BUDGET,
                ResearchRuntimeSettings.DEFAULT_ORCHESTRATOR_TOKEN_BUDGET,
                "Token budget of one approved run (orchestrator and workers); 0 = not limited.");
        bindNonNegativeLimit(orchestratorTimeBudget, ResearchRuntimeSettings.KEY_ORCHESTRATOR_TIME_BUDGET_MINUTES,
                ResearchRuntimeSettings.DEFAULT_ORCHESTRATOR_TIME_BUDGET_MINUTES,
                "Time budget of one approved run in minutes; 0 = not limited.");
        bindNonNegativeLimit(orchestratorTaskRetries, ResearchRuntimeSettings.KEY_ORCHESTRATOR_TASK_RETRIES,
                ResearchRuntimeSettings.DEFAULT_ORCHESTRATOR_TASK_RETRIES,
                "How often a work package is retried after a technical failure or an interruption.");
        bindLimit(orchestratorSectionSources, ResearchRuntimeSettings.KEY_ORCHESTRATOR_MIN_SOURCES_PER_SECTION,
                ResearchRuntimeSettings.DEFAULT_ORCHESTRATOR_MIN_SOURCES_PER_SECTION,
                "Accepted sources every outline section needs before the run may close.");
        bindWorkerRole(workerSearchPlanner, ResearchRuntimeSettings.KEY_WORKER_SEARCH_PLANNER,
                "The orchestrator may hand an aspect to the search planner, which writes distinct search "
                        + "queries that a web research package then searches.");
        bindWorkerRole(workerSourceVerifier, ResearchRuntimeSettings.KEY_WORKER_SOURCE_VERIFIER,
                "The orchestrator may have accepted sources checked: relevance, the claim each supports, "
                        + "contradictions. Rejected sources stop counting as evidence; nothing is removed.");
        bindWorkerRole(workerWriter, ResearchRuntimeSettings.KEY_WORKER_WRITER,
                "The orchestrator may have cited section drafts written from the sources. The drafts stay on "
                        + "the task; the document is only written in the draft phase.");
        bindLimit(workerPlannerQueries, ResearchRuntimeSettings.KEY_WORKER_PLANNER_MAX_QUERIES,
                ResearchRuntimeSettings.DEFAULT_WORKER_PLANNER_MAX_QUERIES,
                "Search queries one planner package may propose.");
        bindLimit(workerVerifierSources, ResearchRuntimeSettings.KEY_WORKER_VERIFIER_MAX_SOURCES,
                ResearchRuntimeSettings.DEFAULT_WORKER_VERIFIER_MAX_SOURCES,
                "Sources one verifier package checks.");
        bindLimit(workerWriterSources, ResearchRuntimeSettings.KEY_WORKER_WRITER_MAX_SOURCES,
                ResearchRuntimeSettings.DEFAULT_WORKER_WRITER_MAX_SOURCES,
                "Sources one writer package writes from.");
        useAiReranker.setSelected(ResearchRuntimeSettings.loadUseAiReranker(store));
        useAiReranker.setToolTipText("Off: search hits are scored lexically with BM25 — fast, "
                + "no model. On: the configured cross-encoder model scores them semantically.");
        useAiReranker.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent event) {
                ResearchRuntimeSettings.saveUseAiReranker(ResearchRuntimeSettingsPanel.this.store,
                        useAiReranker.isSelected());
            }
        });
        botControlMcp.setSelected(ResearchRuntimeSettings.loadBotControlMcp(store));
        botControlMcp.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent event) {
                // Persisted immediately; running sessions keep their endpoint until they close.
                ResearchRuntimeSettings.saveBotControlMcp(ResearchRuntimeSettingsPanel.this.store,
                        botControlMcp.isSelected());
                // The connector key is app-wide (bot control is per chat): switching the server off
                // clears the connector flag itself, so no chat's frozen value can stop another's listener.
                if (!botControlMcp.isSelected()) {
                    chatGptConnector.setSelected(false);
                }
                updateMcpFieldsEnabled();
                persistConnectorSettings();
            }
        });
        ResearchRuntimeSettings.ChatGptConnectorSettings connector =
                ResearchRuntimeSettings.loadChatGptConnectorSettings(store);
        chatGptConnector.setSelected(connector.isEnabled());
        connectorOrigin.setText(connector.getPublicOrigin());
        connectorOrigin.setToolTipText(
                "The public HTTPS origin the Apache proxy serves, e.g. https://askai.example.com");
        connectorPort.setText(String.valueOf(connector.getPort()));
        connectorPort.setToolTipText("Local plain-HTTP listen port; the proxy machine forwards to it");
        connectorClientId.setText(com.aresstack.askai.research.connector.ConnectorConfig
                .DEFAULT_CLIENT_ID.equals(connector.getClientId()) ? "" : connector.getClientId());
        connectorClientSecret.setText(com.aresstack.askai.research.connector.ConnectorConfig
                .DEFAULT_CLIENT_SECRET.equals(connector.getClientSecret()) ? ""
                : connector.getClientSecret());
        connectorCentralIssuer.setText(connector.getCentralIssuer());
        connectorCentralIssuer.setToolTipText(
                "Empty: AskAI runs its own OAuth server (legacy). Set to the central AresStack "
                        + "issuer URL to validate its JWTs instead; /oauth/* is then served centrally.");
        chatGptConnector.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent event) {
                updateMcpFieldsEnabled();
                persistConnectorSettings();
            }
        });
        updateMcpFieldsEnabled();
        bindScopeCheckSettings();
        bindLimit(searchTargetSources, ResearchRuntimeSettings.KEY_SEARCH_TARGET_SOURCES,
                ResearchRuntimeSettings.DEFAULT_SEARCH_TARGET_SOURCES,
                "The search ends normally after this many accepted sources (applies to new sessions).");
        bindLimit(searchMaxPages, ResearchRuntimeSettings.KEY_SEARCH_MAX_PAGES,
                ResearchRuntimeSettings.DEFAULT_SEARCH_MAX_PAGES,
                "Safety limit: maximum pages visited per search run.");
        bindLimit(searchMaxToolCalls, ResearchRuntimeSettings.KEY_SEARCH_MAX_TOOL_CALLS,
                ResearchRuntimeSettings.DEFAULT_SEARCH_MAX_TOOL_CALLS,
                "Safety limit: maximum tool calls per search run.");
        bindLimit(searchMaxMinutes, ResearchRuntimeSettings.KEY_SEARCH_MAX_MINUTES,
                ResearchRuntimeSettings.DEFAULT_SEARCH_MAX_MINUTES,
                "Safety limit (timeout): maximum run time in minutes; time spent waiting for the "
                        + "user does not count.");
        bindLimit(searchMaxErrors, ResearchRuntimeSettings.KEY_SEARCH_MAX_ERRORS,
                ResearchRuntimeSettings.DEFAULT_SEARCH_MAX_ERRORS,
                "Safety limit: maximum CONSECUTIVE technical errors before the run ends.");
        bindLimit(browserToolTimeout, ResearchRuntimeSettings.KEY_BROWSER_TOOL_TIMEOUT_SECONDS,
                ResearchRuntimeSettings.DEFAULT_BROWSER_TOOL_TIMEOUT_SECONDS,
                "Timeout per browser call in seconds. Must cover the whole search-engine phase "
                        + "(several engines and result pages, sequential with evaluation) — too small "
                        + "is misread as 'browser dead' and aborts every search.");
        bindNonNegativeLimit(browserIdleStop, ResearchRuntimeSettings.KEY_BROWSER_IDLE_STOP_SECONDS,
                ResearchRuntimeSettings.DEFAULT_BROWSER_IDLE_STOP_SECONDS,
                "How long the browser stays open after a search without a new browser command before it "
                        + "is stopped. Keeping it warm saves the browser start (about 10 s) on the next "
                        + "search; 0 = stop right after each search.");
        teamEnabled.setSelected(ResearchRuntimeSettings.loadTeamEnabled(store));
        teamEnabled.setToolTipText("The planned queries of the Sources phase run side by side: each page worker "
                + "visits its hits in its own tab of one page browser, never two tabs on the same site at once. The "
                + "searches still go one after another through the search browser. Applies to the next planned "
                + "query.");
        teamEnabled.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent event) {
                ResearchRuntimeSettings.saveTeamEnabled(ResearchRuntimeSettingsPanel.this.store,
                        teamEnabled.isSelected());
            }
        });
        bindLimit(teamWorkers, ResearchRuntimeSettings.KEY_TEAM_WORKERS, ResearchRuntimeSettings.DEFAULT_TEAM_WORKERS,
                "How many page workers run at the same time. Each has its own tab in one page browser (at most "
                        + "Worker browsers > Tabs/user context) and its own call to the Sources model; the model "
                        + "capacity in AI models > Model resources decides how many calls run in parallel.");
        bindNonNegativeLimit(teamFollowLinks, ResearchRuntimeSettings.KEY_TEAM_FOLLOW_LINKS,
                ResearchRuntimeSettings.DEFAULT_TEAM_FOLLOW_LINKS,
                "How many of a page's links a page worker may pick to open next (one level deep); 0 = none.");
        bindLimit(teamPageCharacters, ResearchRuntimeSettings.KEY_TEAM_PAGE_CHARACTERS,
                ResearchRuntimeSettings.DEFAULT_TEAM_PAGE_CHARACTERS,
                "How much of a page's text one page worker reads (characters). Longer pages are cut. Mind the "
                        + "model's context when many workers run at once.");
        bindLimit(teamAnswerTokens, ResearchRuntimeSettings.KEY_TEAM_ANSWER_TOKENS,
                ResearchRuntimeSettings.DEFAULT_TEAM_ANSWER_TOKENS,
                "The longest answer of one page worker turn (tokens): its summary, verdict, links and messages.");
        bindNonNegativeLimit(teamSearchesPerPage, ResearchRuntimeSettings.KEY_TEAM_SEARCHES_PER_PAGE,
                ResearchRuntimeSettings.DEFAULT_TEAM_SEARCHES_PER_PAGE,
                "How many new search queries a page worker may suggest per page it reads. Each passes the Weidezaun "
                        + "and the blacklist and then runs before the planned queries; 0 = none.");
        bindLimit(teamNextSearchBelow, ResearchRuntimeSettings.KEY_TEAM_NEXT_SEARCH_BELOW,
                ResearchRuntimeSettings.DEFAULT_TEAM_WORKERS,
                "The next search starts when fewer pages than this are still open in the running one, so the "
                        + "workers never wait at the end of a search. Usually the worker count.");
        bindSourcePreference();
        bindLimit(teamSeedQueries, ResearchRuntimeSettings.KEY_TEAM_SEED_QUERIES_PER_NODE,
                ResearchRuntimeSettings.DEFAULT_TEAM_SEED_QUERIES_PER_NODE,
                "With the research team, the plan starts with this many queries per concept node (breadth first); "
                        + "the deeper queries come from what the team reads, up to Queries per node.");
        bindNonNegativeLimit(gapWaitSeconds, ResearchRuntimeSettings.KEY_GAP_WAIT_SECONDS,
                ResearchRuntimeSettings.DEFAULT_GAP_WAIT_SECONDS,
                "At the end of a research cycle, wait this long for the evidence analysis to hold every source read "
                        + "so far; without it the next cycle plans from the source counts.");
        bindLimit(gapRecheckSeconds, ResearchRuntimeSettings.KEY_GAP_RECHECK_SECONDS,
                ResearchRuntimeSettings.DEFAULT_GAP_RECHECK_SECONDS,
                "While sources are still being processed, the waiting review looks at the analysis again this "
                        + "often.");
        bindNonNegativeLimit(gapNewTopics, ResearchRuntimeSettings.KEY_GAP_NEW_TOPICS,
                ResearchRuntimeSettings.DEFAULT_GAP_NEW_TOPICS,
                "The most new topics of the material, inside the scope, the planner may search for in the next "
                        + "cycle; 0 = none.");
        bindNonNegativeLimit(gapFailedQueries, ResearchRuntimeSettings.KEY_GAP_FAILED_QUERIES,
                ResearchRuntimeSettings.DEFAULT_GAP_FAILED_QUERIES,
                "The most queries that found nothing the planner sees per thin or missing node, so it changes "
                        + "the strategy.");
        bindLimit(workerProcesses, ResearchRuntimeSettings.KEY_WORKER_BROWSER_PROCESSES,
                ResearchRuntimeSettings.DEFAULT_WORKER_BROWSER_PROCESSES,
                "Worker browser processes for parallel research workers, over all chats. This is also how many "
                        + "browser operations run at the same time. Independent of the model capacity "
                        + "(AI models > Model resources). Each process costs a browser plus a driver JVM.");
        bindLimit(workerUserContexts, ResearchRuntimeSettings.KEY_WORKER_USER_CONTEXTS_PER_PROCESS,
                ResearchRuntimeSettings.DEFAULT_WORKER_USER_CONTEXTS_PER_PROCESS,
                "Isolated user contexts (own cookies and sign-ins, one per worker) one worker browser hosts. "
                        + "Workers in the same process take turns on its browser.");
        bindLimit(workerTabs, ResearchRuntimeSettings.KEY_WORKER_TABS_PER_USER_CONTEXT,
                ResearchRuntimeSettings.DEFAULT_WORKER_TABS_PER_USER_CONTEXT,
                "Tabs (browsing contexts) one worker may hold open in its user context.");
        bindLimit(workerPerSite, ResearchRuntimeSettings.KEY_WORKER_PER_SITE,
                ResearchRuntimeSettings.DEFAULT_WORKER_PER_SITE,
                "Worker browser operations against the same site at the same time, over all workers and "
                        + "chats. Search engines count as one site.");
        bindLimit(workerAcquireTimeout, ResearchRuntimeSettings.KEY_WORKER_ACQUIRE_TIMEOUT_SECONDS,
                ResearchRuntimeSettings.DEFAULT_WORKER_ACQUIRE_TIMEOUT_SECONDS,
                "How long a worker waits for a free user context before its task fails.");
        bindLimit(reviewMaxSources, ResearchRuntimeSettings.KEY_REVIEW_MAX_SOURCES,
                ResearchRuntimeSettings.DEFAULT_REVIEW_MAX_SOURCES,
                "How many sources one review reads at most (newest first).");
        bindLimit(reviewMaxChars, ResearchRuntimeSettings.KEY_REVIEW_MAX_CHARS,
                ResearchRuntimeSettings.DEFAULT_REVIEW_MAX_CHARS,
                "How many characters per source enter the review.");
        bindLimit(conceptToolRounds, ResearchRuntimeSettings.KEY_CONCEPT_TOOL_ROUNDS,
                ResearchRuntimeSettings.DEFAULT_CONCEPT_TOOL_ROUNDS,
                "How many model-tool cycles ONE concept turn may make (work budget).");
        bindLimit(conceptRepairAttempts, ResearchRuntimeSettings.KEY_CONCEPT_REPAIR_ATTEMPTS,
                ResearchRuntimeSettings.DEFAULT_CONCEPT_REPAIR_ATTEMPTS,
                "How many rejected concept steps may be corrected per turn "
                        + "(error tolerance, separate from the work budget).");
        bindNonNegativeLimit(writerProseMinWords, ResearchRuntimeSettings.KEY_WRITER_PROSE_MIN_WORDS,
                com.aresstack.askai.research.runtime.writer.ProsePassageFilter.Criteria.DEFAULT_MIN_WORDS,
                "Evidence pack and topic analysis: a passage with fewer words (a heading, a label) never reaches "
                        + "the section writer and is no topic material.");
        bindNonNegativeLimit(writerProseMinSentences, ResearchRuntimeSettings.KEY_WRITER_PROSE_MIN_SENTENCES,
                com.aresstack.askai.research.runtime.writer.ProsePassageFilter.Criteria.DEFAULT_MIN_SENTENCES,
                "Evidence pack and topic analysis: a passage needs this many finished sentences (. ! ?); "
                        + "breadcrumbs, titles and contact lines have none.");
        bindLimit(writerProseMaxRun, ResearchRuntimeSettings.KEY_WRITER_PROSE_MAX_RUN,
                com.aresstack.askai.research.runtime.writer.ProsePassageFilter.Criteria
                        .DEFAULT_MAX_UNPUNCTUATED_RUN,
                "Evidence pack and topic analysis: navigation menus and footers run on for dozens of words "
                        + "without a comma or full stop; a passage with a longer run is left out.");
        bindLimit(writerWordsPerCall, ResearchRuntimeSettings.KEY_WRITER_WORDS_PER_CALL,
                ResearchRuntimeSettings.DEFAULT_WRITER_WORDS_PER_CALL,
                "Section writer: words one call aims for; a section with a larger word target is split by its key "
                        + "points. A local model writes about 150 to 450 words per call whatever the target, so a "
                        + "higher value gives shorter sections. With too little evidence the writer stays below it and "
                        + "the book report says so.");
        bindLimit(writerAnswerTokens, ResearchRuntimeSettings.KEY_WRITER_ANSWER_TOKENS,
                ResearchRuntimeSettings.DEFAULT_WRITER_ANSWER_TOKENS,
                "Section writer: answer token budget of one call (keep it above the words per call).");
        bindLimit(writerPassages, ResearchRuntimeSettings.KEY_WRITER_PASSAGES,
                com.aresstack.askai.research.writer.WriterPackSettings.LARGE.getPassageCount(),
                "Evidence pack: passages one writer call gets. A section written in parts gets this many for "
                        + "each part, chosen by the part's key points. More passages give the writer more to say, "
                        + "and a longer prompt.");
        bindLimit(writerPassageTokens, ResearchRuntimeSettings.KEY_WRITER_PASSAGE_TOKENS,
                com.aresstack.askai.research.writer.WriterPackSettings.LARGE.getPassageTokens(),
                "Evidence pack: tokens each passage may take (cut at a sentence end; at least "
                        + com.aresstack.askai.research.writer.WriterPackSettings.MIN_PASSAGE_TOKENS + ").");
        bindLimit(writerPassagesPerSource, ResearchRuntimeSettings.KEY_WRITER_PASSAGES_PER_SOURCE,
                com.aresstack.askai.research.writer.WriterPackSettings.DEFAULT_PASSAGES_PER_SOURCE,
                "Evidence pack: passages one source may contribute, so one page cannot fill a section.");
        bindDecimal(writerMinBriefSimilarity, ResearchRuntimeSettings.KEY_WRITER_MIN_BRIEF_SIMILARITY,
                com.aresstack.askai.research.writer.WriterPackSettings.BriefRelevance.DEFAULT_MIN_SIMILARITY,
                "Evidence pack: a passage needs at least this similarity (0..1) to the section's brief, otherwise "
                        + "it treats another subject and stays out (0 = off). Depends on the embedding model: the "
                        + "default is calibrated for bge-m3; nomic-embed-text scores higher and closer together.");
        bindDecimal(writerBriefSimilarityMargin, ResearchRuntimeSettings.KEY_WRITER_BRIEF_SIMILARITY_MARGIN,
                com.aresstack.askai.research.writer.WriterPackSettings.BriefRelevance.DEFAULT_MARGIN,
                "Evidence pack: a passage may lie at most this far (0..1) below the section's best passage; "
                        + "further down the search only finds neighbouring subjects (0 = off). Depends on the "
                        + "embedding model: the default is calibrated for bge-m3; with nomic-embed-text use less.");
        bindLimit(driftMinTermLength, ResearchRuntimeSettings.KEY_DRIFT_MIN_TERM_LENGTH,
                com.aresstack.askai.research.assembly.TopicDriftCheck.Criteria.DEFAULT_MIN_TERM_LENGTH,
                "Book report: a term this long that a section repeats but the concept and outline never mention "
                        + "is reported as a foreign subject (live run 3: Funkalarmanlagen in 1.4).");
        bindLimit(driftMinSentences, ResearchRuntimeSettings.KEY_DRIFT_MIN_SENTENCES,
                com.aresstack.askai.research.assembly.TopicDriftCheck.Criteria.DEFAULT_MIN_SENTENCES,
                "Book report: in how many sentences of one section the foreign term must occur.");
        driftNounsOnly.setSelected(ResearchRuntimeSettings.loadTopicDriftCriteria(store).isNounsOnly());
        driftNounsOnly.setToolTipText("Book report: count only capitalised words inside a sentence (German nouns), "
                + "so verbs and adjectives are never reported as subjects.");
        driftNounsOnly.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent event) {
                if (ResearchRuntimeSettingsPanel.this.store != null) {
                    ResearchRuntimeSettingsPanel.this.store.putBoolean(ResearchRuntimeSettings.KEY_DRIFT_NOUNS_ONLY,
                            driftNounsOnly.isSelected());
                }
            }
        });
        bindLimit(writerCarryMinWordLength, ResearchRuntimeSettings.KEY_WRITER_CARRY_MIN_WORD_LENGTH,
                com.aresstack.askai.research.runtime.writer.ClaimCarryCheck.Criteria.DEFAULT_MIN_WORD_LENGTH,
                "Claim check: words with fewer letters (und, der, ist) are ignored when a sentence is compared "
                        + "with the passage it cites.");
        bindLimit(writerCarryStemLength, ResearchRuntimeSettings.KEY_WRITER_CARRY_STEM_LENGTH,
                com.aresstack.askai.research.runtime.writer.ClaimCarryCheck.Criteria.DEFAULT_STEM_LENGTH,
                "Claim check: two words that share this many leading letters count as one (erhöht ~ Erhöhung).");
        bindDecimal(writerCarryMinOverlap, ResearchRuntimeSettings.KEY_WRITER_CARRY_MIN_OVERLAP,
                com.aresstack.askai.research.runtime.writer.ClaimCarryCheck.Criteria.DEFAULT_MIN_OVERLAP,
                "Claim check: a cited passage must contain at least this share (0..1) of the sentence's words, "
                        + "otherwise the sentence is flagged \"citation does not carry the claim\" and the "
                        + "writer gets one repair. A menu, footer or heading never carries a claim.");
        bindLimit(writerRepeatMinWords, ResearchRuntimeSettings.KEY_WRITER_REPEAT_MIN_WORDS,
                com.aresstack.askai.research.runtime.writer.RepeatCheck.Criteria.DEFAULT_MIN_WORDS,
                "Repeat check: a sentence with at least this many content words leaves the section when another "
                        + "written section (or an earlier part) already holds most of them. Shorter sentences leave "
                        + "only when they are repeated word for word.");
        bindDecimal(writerRepeatMinShare, ResearchRuntimeSettings.KEY_WRITER_REPEAT_MIN_SHARE,
                com.aresstack.askai.research.runtime.writer.RepeatCheck.Criteria.DEFAULT_MIN_SHARE,
                "Repeat check: the share (0..1) of a sentence's content words that one sentence the book already "
                        + "has must contain for the new sentence to count as a repeat. Lower values take out more.");
        bindLimit(writerSynthesisMinSources, ResearchRuntimeSettings.KEY_WRITER_SYNTHESIS_MIN_SOURCES,
                com.aresstack.askai.research.runtime.writer.SynthesisCriteria.DEFAULT_MIN_SOURCES,
                "Synthesis: how many different sources one paragraph combines (at least 2). The writer is asked "
                        + "for such paragraphs, and the book report counts a paragraph with fewer as retelling.");
        bindNonNegativeLimit(writerSynthesisParagraphs, ResearchRuntimeSettings.KEY_WRITER_SYNTHESIS_PARAGRAPHS,
                com.aresstack.askai.research.runtime.writer.SynthesisCriteria.DEFAULT_PARAGRAPHS_PER_PART,
                "Synthesis: paragraphs that combine sources the writer must write in each key-point part of a "
                        + "section (0 = no such instruction).");
        bindLimit(writerSynthesisMinSentences, ResearchRuntimeSettings.KEY_WRITER_SYNTHESIS_MIN_SENTENCES,
                com.aresstack.askai.research.runtime.writer.SynthesisCriteria.DEFAULT_MIN_SENTENCES,
                "Book report: a paragraph with fewer sentences (a transition) is not counted as retelling.");
        bindDecimal(writerSynthesisMaxShare, ResearchRuntimeSettings.KEY_WRITER_SYNTHESIS_MAX_SHARE,
                com.aresstack.askai.research.runtime.writer.SynthesisCriteria.DEFAULT_MAX_SINGLE_SOURCE_SHARE,
                "Book report: when more than this share (0..1) of a section's paragraphs cite one source only, the "
                        + "report notes the section as retelling instead of synthesis (a note, never blocking).");
        refreshBackendStatus();
        // The "Browser" tab changes the channel: the status follows whenever this tab is shown again.
        addHierarchyListener(new java.awt.event.HierarchyListener() {
            public void hierarchyChanged(java.awt.event.HierarchyEvent event) {
                if ((event.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing()) {
                    refreshBackendStatus();
                }
            }
        });

        save.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent event) {
                saveSettings();
            }
        });
        check.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent event) {
                showResults();
                runCheck();
            }
        });
    }

    /**
     * The settings NEW sessions get, as the SAME typed model the factory reads — no second mapping. The browser
     * fields come from the store (the "Browser" tab saves them on every change).
     */
    ResearchRuntimeSettings currentSettings() {
        // Saving from the panel always persists the automatic mode (this also migrates stores where an
        // old default once wrote FAKE); the demo backend is chosen by the FACTORY only when the
        // requirements are not met.
        ResearchRuntimeSettings stored = ResearchRuntimeDefaults.complete(ResearchRuntimeSettings.load(store));
        return new ResearchRuntimeSettings(ResearchBackendMode.ACP, stored.getBrowserChannel(),
                stored.isHeadless(), stored.isAllowPrivateNetworks(), carriedRerankerModel);
    }

    /** The live projection of what NEW sessions will do — computed, never chosen. */
    private void refreshBackendStatus() {
        List<String> problems = ResearchRuntimeDefaults.complete(currentSettings()).validateProductive();
        backendStatus.setText(problems.isEmpty()
                ? "Productive (requirements met — new sessions research with the real browser)"
                : "DEMO (missing: " + problems.get(0)
                        + (problems.size() > 1 ? " +" + (problems.size() - 1) + " more" : "") + ")");
    }

    private void apply(ResearchRuntimeSettings settings) {
        // Carry any previously persisted reranker selection through invisibly (chosen centrally now).
        carriedRerankerModel = settings.getSelectedRerankerModel();
    }

    /** A text field with a gray in-field placeholder, shown while the field is empty. */
    private static JTextField placeholderField(final String placeholder, int columns) {
        final JTextField field = new ComicTextField(columns) {
            protected void paintComponent(java.awt.Graphics g) {
                super.paintComponent(g);
                if (getText().isEmpty()) {
                    java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
                    g2.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING,
                            java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                    g2.setColor(ThemeColor.of("research.fieldPlaceholderText"));
                    g2.setFont(getFont());
                    g2.drawString(placeholder, getInsets().left + 2,
                            getBaseline(getWidth(), getHeight()));
                    g2.dispose();
                }
            }
        };
        return field;
    }

    /** A password field with a gray in-field placeholder (the effective default), shown while empty. */
    private static javax.swing.JPasswordField placeholderPasswordField(final String placeholder,
                                                                       int columns) {
        return new ComicPasswordField(columns) {
            protected void paintComponent(java.awt.Graphics g) {
                super.paintComponent(g);
                if (getPassword().length == 0) {
                    java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
                    g2.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING,
                            java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                    g2.setColor(ThemeColor.of("research.fieldPlaceholderText"));
                    g2.setFont(getFont());
                    g2.drawString(placeholder, getInsets().left + 2,
                            getBaseline(getWidth(), getHeight()));
                    g2.dispose();
                }
            }
        };
    }

    /** Several small fields on one line of the field column; wraps onto further lines when the dialog is narrow. */
    private static JPanel group(javax.swing.JComponent... parts) {
        JPanel line = new WrapGroup();
        for (javax.swing.JComponent part : parts) {
            line.add(part);
        }
        return line;
    }

    /**
     * The wrapping field line: it may shrink to its widest part (the form never clips or scrolls sideways), is as
     * tall as its wrapped rows at the CURRENT width, and lays the form out again when a resize changes that height.
     */
    private static final class WrapGroup extends JPanel {
        private static final long serialVersionUID = 1L;
        private static final int GAP = 12;

        WrapGroup() {
            super(new com.aresstack.comiccontrols.control.WrapRowLayout(java.awt.FlowLayout.LEFT, GAP, 2));
            setOpaque(false);
            addComponentListener(new java.awt.event.ComponentAdapter() {
                @Override
                public void componentResized(java.awt.event.ComponentEvent event) {
                    if (getLayout().preferredLayoutSize(WrapGroup.this).height != getHeight()) {
                        revalidate();
                    }
                }
            });
        }

        @Override
        public java.awt.Dimension getMinimumSize() {
            int widest = 0;
            for (java.awt.Component part : getComponents()) {
                widest = Math.max(widest, part.getPreferredSize().width);
            }
            java.awt.Insets insets = getInsets();
            return new java.awt.Dimension(widest + 2 * GAP + insets.left + insets.right,
                    getLayout().preferredLayoutSize(this).height);
        }
    }

    /** A tiny "Label: [field]" group for compact multi-field limit rows. */
    private static JPanel labelled(String label, JTextField field) {
        JPanel group = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0));
        group.setOpaque(false);
        group.add(new JLabel(label + ": "));
        group.add(field);
        return group;
    }

    /** The mission's source preference: the switch, the two numbers and the four lists. */
    private void bindSourcePreference() {
        preferMissionSources.setSelected(ResearchRuntimeSettings.loadSourcePreferenceEnabled(store));
        preferMissionSources.setToolTipText("The mission's \"prefer …\" clause (publishers, domains, kinds of "
                + "sources) adds publisher queries to every research cycle and moves preferred hosts up and vendor "
                + "pages down in the ranking. Nothing is forbidden; the Weidezaun and the blacklist stay.");
        preferMissionSources.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent event) {
                ResearchRuntimeSettings.saveSourcePreferenceEnabled(ResearchRuntimeSettingsPanel.this.store,
                        preferMissionSources.isSelected());
            }
        });
        bindNonNegativeLimit(preferenceQueries, ResearchRuntimeSettings.KEY_SOURCE_PREFERENCE_QUERIES,
                ResearchRuntimeSettings.DEFAULT_SOURCE_PREFERENCE_QUERIES,
                "Publisher queries (base query + site:domain or a preferred kind) per concept node and research "
                        + "cycle, on top of Queries per node. Each cycle asks the next publisher; 0 = none.");
        bindNonNegativeLimit(preferenceRankShift, ResearchRuntimeSettings.KEY_SOURCE_PREFERENCE_RANK_SHIFT,
                ResearchRuntimeSettings.DEFAULT_SOURCE_PREFERENCE_RANK_SHIFT,
                "Places a hit from a preferred host moves up, and a vendor page moves down, among the ranked hits "
                        + "of a planned query and among the passages of a section's evidence pack; 0 = the ranking "
                        + "stays as it is.");
        bindText(preferencePublishers, ResearchRuntimeSettings.KEY_SOURCE_PREFERENCE_PUBLISHERS,
                ResearchRuntimeSettings.DEFAULT_SOURCE_PREFERENCE_PUBLISHERS,
                "Name=domain pairs, comma-separated: a publisher the mission names becomes site:domain queries and "
                        + "a preferred host. A domain written in the mission needs no entry.");
        bindText(preferenceAuthorityHosts, ResearchRuntimeSettings.KEY_SOURCE_PREFERENCE_AUTHORITY_HOSTS,
                ResearchRuntimeSettings.DEFAULT_SOURCE_PREFERENCE_AUTHORITY_HOSTS,
                "Host suffixes, comma-separated, that are always preferred in the ranking (authorities).");
        bindText(preferenceDemoted, ResearchRuntimeSettings.KEY_SOURCE_PREFERENCE_DEMOTED,
                ResearchRuntimeSettings.DEFAULT_SOURCE_PREFERENCE_DEMOTED,
                "URL parts, comma-separated, of vendor pages (blogs, products, solutions) that move down in the "
                        + "ranking. They are never removed.");
        writerGapResearch.setSelected(ResearchRuntimeSettings.loadWriterGapResearch(store));
        writerGapResearch.setToolTipText("After a writing job, each GAP: line of the writer and each section it could "
                + "not write for lack of evidence becomes a search plan entry (Research this gap), with the "
                + "mission's preferred sources. The same gap is searched once.");
        writerGapResearch.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent event) {
                ResearchRuntimeSettings.saveWriterGapResearch(ResearchRuntimeSettingsPanel.this.store,
                        writerGapResearch.isSelected());
            }
        });
        bindNonNegativeLimit(writerGapMax, ResearchRuntimeSettings.KEY_WRITER_GAP_MAX,
                ResearchRuntimeSettings.DEFAULT_WRITER_GAP_MAX,
                "The most writer gaps one writing job sends to research; 0 = none.");
        bindText(writerGapWords, ResearchRuntimeSettings.KEY_WRITER_GAP_EVIDENCE_WORDS,
                ResearchRuntimeSettings.DEFAULT_WRITER_GAP_EVIDENCE_WORDS,
                "Words, comma-separated: a writer failure naming one means the section lacked evidence and is "
                        + "researched again.");
        bindText(preferenceCues, ResearchRuntimeSettings.KEY_SOURCE_PREFERENCE_CUES,
                ResearchRuntimeSettings.DEFAULT_SOURCE_PREFERENCE_CUES,
                "Words, comma-separated, that open the preference clause of the mission (\"Bevorzuge …\").");
    }

    /** A comma-separated list setting: saved on Enter and on focus loss; an empty field restores the default. */
    private void bindText(final JTextField field, final String key, final String fallback, String tooltip) {
        field.setText(ResearchRuntimeSettings.loadText(store, key, fallback));
        field.setToolTipText(tooltip);
        final Runnable persist = new Runnable() {
            public void run() {
                String value = field.getText().trim();
                if (value.isEmpty()) {
                    value = fallback;
                    field.setText(value);
                }
                ResearchRuntimeSettings.saveText(store, key, value);
            }
        };
        field.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent event) {
                persist.run();
            }
        });
        field.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override
            public void focusLost(java.awt.event.FocusEvent event) {
                persist.run();
            }
        });
    }

    /** Z4c: every scope-check knob binds to its persisted setting (loaded per CLICK, no restart). */
    private void bindScopeCheckSettings() {
        com.aresstack.askai.research.scope.ScopeSweepConfiguration d =
                com.aresstack.askai.research.scope.ScopeSweepConfiguration.defaults();
        bindLimit(scopeCheckTargetProbes, ResearchRuntimeSettings.KEY_SCOPE_CHECK_TARGET_PROBES,
                d.targetBroadProbes, "How many broad concepts a topic-space check generates. "
                        + "Small main models (2B class) often cannot sustain 50 — set 20 "
                        + "then.");
        bindLimit(scopeCheckControlsPerAnchor,
                ResearchRuntimeSettings.KEY_SCOPE_CHECK_CONTROLS_PER_ANCHOR, d.controlsPerAnchor,
                "Local calibration examples per negotiated fence post");
        bindLimit(scopeCheckMaxTopics, ResearchRuntimeSettings.KEY_SCOPE_CHECK_MAX_TOPICS,
                d.selectorParameters.maximumCandidates,
                "Suggest at most this many unexplored topics per check");
        bindLimit(scopeCheckTimeout, ResearchRuntimeSettings.KEY_SCOPE_CHECK_TIMEOUT_SECONDS,
                d.generationTimeoutSeconds, "Topic generation timeout in seconds");
        bindDecimal(scopeCheckGenTemperature,
                ResearchRuntimeSettings.KEY_SCOPE_CHECK_GEN_TEMPERATURE, d.generatorTemperature,
                "Topic generation temperature (breadth needs variety)");
        bindLimit(scopeCheckGenMaxTokens, ResearchRuntimeSettings.KEY_SCOPE_CHECK_GEN_MAX_TOKENS,
                d.generatorMaxOutputTokens, "Topic generation token budget");
        bindDecimal(scopeCheckChooserTemperature,
                ResearchRuntimeSettings.KEY_SCOPE_CHECK_CHOOSER_TEMPERATURE, d.chooserTemperature,
                "Question choice temperature (focused, not creative)");
        bindLimit(scopeCheckChooserMaxTokens,
                ResearchRuntimeSettings.KEY_SCOPE_CHECK_CHOOSER_MAX_TOKENS,
                d.chooserMaxOutputTokens, "Question choice token budget");
        bindLimit(scopeCheckChoiceTimeout,
                ResearchRuntimeSettings.KEY_SCOPE_CHECK_CHOICE_TIMEOUT_SECONDS,
                d.choiceTimeoutSeconds, "Question choice timeout in seconds");
        String spike = " (measurement spike: formula deliberately provisional, frozen after productive "
                + "measurements)";
        bindDecimal(scopeCheckNearSimilarity,
                ResearchRuntimeSettings.KEY_SCOPE_CHECK_NEAR_SIMILARITY,
                d.fenceThresholds.nearSimilarity, "Z2 hint: from this cosine on a topic counts as "
                        + "\u201cnear a post\u201d" + spike);
        bindDecimal(scopeCheckHintBoundaryMargin,
                ResearchRuntimeSettings.KEY_SCOPE_CHECK_HINT_BOUNDARY_MARGIN,
                d.fenceThresholds.boundaryMargin, "Z2 hint boundary margin" + spike);
        bindDecimal(scopeCheckMissionQuantile,
                ResearchRuntimeSettings.KEY_SCOPE_CHECK_MISSION_QUANTILE,
                d.calibrationParameters.missionRelevanceQuantile,
                "Quantile of the anchors' mission relevance (0=minimum)" + spike);
        bindDecimal(scopeCheckMissionMargin,
                ResearchRuntimeSettings.KEY_SCOPE_CHECK_MISSION_MARGIN,
                d.calibrationParameters.missionRelevanceMargin,
                "Slack below the mission quantile" + spike);
        bindDecimal(scopeCheckNeighborQuantile,
                ResearchRuntimeSettings.KEY_SCOPE_CHECK_NEIGHBOR_QUANTILE,
                d.calibrationParameters.neighborSimilarityQuantile,
                "Quantile of the anchor-neighbor cosines (0=minimum)" + spike);
        bindDecimal(scopeCheckNeighborMargin,
                ResearchRuntimeSettings.KEY_SCOPE_CHECK_NEIGHBOR_MARGIN,
                d.calibrationParameters.neighborSimilarityMargin,
                "Slack below the neighbor quantile" + spike);
        bindLimit(scopeCheckMinNegotiated,
                ResearchRuntimeSettings.KEY_SCOPE_CHECK_MIN_NEGOTIATED,
                d.calibrationParameters.minimumNegotiatedAnchors,
                "Minimum negotiated IN/OUT posts for a reliable calibration");
        bindLimit(scopeCheckMinNeighbors,
                ResearchRuntimeSettings.KEY_SCOPE_CHECK_MIN_NEIGHBORS,
                d.calibrationParameters.minimumNeighborSamples,
                "Minimum valid neighbor examples");
        bindDecimal(scopeCheckBoundaryMargin,
                ResearchRuntimeSettings.KEY_SCOPE_CHECK_BOUNDARY_MARGIN, d.boundaryMargin,
                "|margin| below this value = genuine IN/OUT boundary question" + spike);
        bindDecimal(scopeCheckNoveltyGap,
                ResearchRuntimeSettings.KEY_SCOPE_CHECK_NOVELTY_GAP, d.sweepNoveltyGap,
                "Sweep-relative novelty line: median minus this value" + spike);
        bindDecimal(scopeCheckNoveltyWeight,
                ResearchRuntimeSettings.KEY_SCOPE_CHECK_NOVELTY_WEIGHT,
                d.selectorParameters.noveltyWeight,
                "Weight of novelty in topic diversity");
        bindDecimal(scopeCheckDuplicateCeiling,
                ResearchRuntimeSettings.KEY_SCOPE_CHECK_DUPLICATE_CEILING,
                d.selectorParameters.duplicateSimilarityCeiling,
                "From this similarity on two topics count as word variants");
    }

    /** Like {@link #bindLimit} for decimal knobs (temperatures, cosines, margins). */
    private void bindDecimal(final JTextField field, final String key, final double fallback,
                             String tooltip) {
        field.setText(String.valueOf(
                ResearchRuntimeSettings.loadDouble(store, key, fallback)));
        field.setToolTipText(tooltip);
        final Runnable persist = new Runnable() {
            public void run() {
                try {
                    ResearchRuntimeSettings.saveDouble(store, key, Double.parseDouble(
                            field.getText().trim().replace(',', '.')));
                    return;
                } catch (NumberFormatException invalid) {
                    field.setText(String.valueOf(
                            ResearchRuntimeSettings.loadDouble(store, key, fallback)));
                }
            }
        };
        field.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent event) {
                persist.run();
            }
        });
        field.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override
            public void focusLost(java.awt.event.FocusEvent event) {
                persist.run();
            }
        });
    }

    /**
     * Bind one positive-integer limit field to its persisted setting: initial value from the store,
     * persisted on Enter and focus-lost, and a non-numeric/non-positive entry resets to the persisted
     * value instead of silently storing something broken. Applies to NEW sessions like its neighbours.
     */
    /** Like {@link #bindLimit} but 0 is a legal value (e.g. "stop right away"). */
    private void bindNonNegativeLimit(final JTextField field, final String key, final int fallback,
                                      String tooltip) {
        field.setText(String.valueOf(
                ResearchRuntimeSettings.loadNonNegativeInt(store, key, fallback)));
        field.setToolTipText(tooltip);
        final Runnable persist = new Runnable() {
            public void run() {
                try {
                    int value = Integer.parseInt(field.getText().trim());
                    if (value >= 0) {
                        ResearchRuntimeSettings.saveNonNegativeInt(store, key, value);
                        return;
                    }
                } catch (NumberFormatException invalid) {
                    // fall through to the reset below
                }
                field.setText(String.valueOf(
                        ResearchRuntimeSettings.loadNonNegativeInt(store, key, fallback)));
            }
        };
        field.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent event) {
                persist.run();
            }
        });
        field.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override
            public void focusLost(java.awt.event.FocusEvent event) {
                persist.run();
            }
        });
    }

    private void bindWorkerRole(final JCheckBox box, final String key, String tooltip) {
        box.setSelected(ResearchRuntimeSettings.loadWorkerRole(store, key));
        box.setToolTipText(tooltip + " Applies to new sessions.");
        box.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent event) {
                ResearchRuntimeSettings.saveWorkerRole(ResearchRuntimeSettingsPanel.this.store, key, box.isSelected());
            }
        });
    }

    private void bindLimit(final JTextField field, final String key, final int fallback, String tooltip) {
        field.setText(String.valueOf(
                ResearchRuntimeSettings.loadPositiveInt(store, key, fallback)));
        field.setToolTipText(tooltip);
        final Runnable persist = new Runnable() {
            public void run() {
                try {
                    int value = Integer.parseInt(field.getText().trim());
                    if (value > 0) {
                        ResearchRuntimeSettings.savePositiveInt(store, key, value);
                        return;
                    }
                } catch (NumberFormatException invalid) {
                    // fall through to the reset below
                }
                field.setText(String.valueOf(
                        ResearchRuntimeSettings.loadPositiveInt(store, key, fallback)));
            }
        };
        field.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent event) {
                persist.run();
            }
        });
        field.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override
            public void focusLost(java.awt.event.FocusEvent event) {
                persist.run();
            }
        });
    }

    /** All five connector controls as ONE persisted unit (from the toggle and from Save). */
    private void persistConnectorSettings() {
        int port;
        try {
            port = Integer.parseInt(connectorPort.getText().trim());
        } catch (NumberFormatException invalid) {
            port = 8082;
        }
        // The secret is OPTIONAL: empty = public client (PKCE only) — exactly what ChatGPT's own
        // dynamic client registration expects. A non-empty secret is enforced on the token endpoint.
        String secret = new String(connectorClientSecret.getPassword()).trim();
        ResearchRuntimeSettings.ChatGptConnectorSettings settings =
                new ResearchRuntimeSettings.ChatGptConnectorSettings(
                        chatGptConnector.isSelected(), port, connectorOrigin.getText().trim(),
                        connectorClientId.getText().trim(), secret,
                        connectorCentralIssuer.getText().trim());
        ResearchRuntimeSettings.saveChatGptConnectorSettings(store, settings);
        // The listener follows the setting IMMEDIATELY — no new session required. It serves whatever is
        // in the session directory; OFF stops only the listener, the running sessions stay registered.
        com.aresstack.askai.research.connector.ChatGptConnectorRuntime runtime =
                com.aresstack.askai.research.connector.ChatGptConnectorRuntime.get();
        if (settings.isEnabled()) {
            runtime.ensureStarted(new com.aresstack.askai.research.connector.ConnectorConfig(
                    settings.getPort(), settings.getPublicOrigin(), settings.getClientId(),
                    settings.getClientSecret(),
                    com.aresstack.askai.research.connector.ChatGptConnectorRuntime
                            .defaultRefreshStore(),
                    settings.getCentralIssuer()));
            String failure = runtime.lastStartFailure();
            results.setText(runtime.isRunning()
                    ? "ChatGPT connector: listening on port " + runtime.runningPort()
                    : "ChatGPT connector: NOT running - " + failure);
            if (!runtime.isRunning()) {
                javax.swing.JOptionPane.showMessageDialog(this,
                        "The ChatGPT connector could not start:\n" + failure,
                        "ChatGPT connector", javax.swing.JOptionPane.WARNING_MESSAGE);
            }
        } else {
            runtime.stopListener();
        }
    }

    private void saveSettings() {
        ResearchRuntimeSettings settings = currentSettings();
        // Always save — configuration is never rejected; what NEW sessions do is computed from it.
        settings.save(store);
        persistConnectorSettings();
        List<String> problems = ResearchRuntimeDefaults.complete(settings).validateProductive();
        refreshBackendStatus();
        StringBuilder sb = new StringBuilder("Saved.\n");
        if (problems.isEmpty()) {
            sb.append("New Research sessions run PRODUCTIVE (real browser research).\n");
        } else {
            sb.append("New Research sessions run in DEMO mode until these are fixed:\n");
            for (String problem : problems) {
                sb.append("  - ").append(problem).append('\n');
            }
        }
        sb.append("\nThe RUNNING session keeps its current backend — close this Research session and"
                + "\nopen a new one to apply.");
        results.setText(sb.toString());
    }

    private void runCheck() {
        final ResearchRuntimeSettings settings = ResearchRuntimeDefaults.complete(currentSettings());
        check.setEnabled(false);
        save.setEnabled(false);
        busy.setText("Checking requirements (starts the browser briefly)…");
        results.setText("");
        Thread worker = new Thread(new Runnable() {
            public void run() {
                final StringBuilder sb = new StringBuilder();
                try {
                    for (ResearchRuntimeCapabilityCheck.Item item
                            : ResearchRuntimeCapabilityCheck.run(settings)) {
                        sb.append(item.render()).append('\n');
                    }
                } catch (RuntimeException unexpected) {
                    sb.append("[FAIL] Requirement check failed — ").append(unexpected.getMessage());
                }
                SwingUtilities.invokeLater(new Runnable() {
                    public void run() {
                        results.setText(sb.toString());
                        busy.setText(" ");
                        check.setEnabled(true);
                        save.setEnabled(true);
                    }
                });
            }
        }, "research-runtime-check");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * The built-in MCP server as its own "MCP" tab: the switch on top (default OFF), its settings below
     * (editable only while switched on), then the local bot-control endpoint.
     */
    public javax.swing.JComponent mcpView() {
        if (mcpView != null) {
            return mcpView;
        }
        JPanel form = new JPanel();
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        form.setOpaque(false);
        JPanel botRow = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0));
        botRow.setOpaque(false);
        botRow.add(botControlMcp);
        botRow.add(javax.swing.Box.createHorizontalStrut(6));
        botRow.add(botTools);
        form.add(row("", botRow));
        form.add(row("", chatGptConnector));
        form.add(row("Public origin:", connectorOrigin));
        form.add(row("Connector port:", connectorPort));
        form.add(row("Client ID:", connectorClientId));
        form.add(row("Client secret:", connectorClientSecret));
        form.add(row("Central OAuth issuer:", connectorCentralIssuer));
        for (java.awt.Component child : form.getComponents()) {
            if (child instanceof javax.swing.JComponent) {
                ((javax.swing.JComponent) child).setAlignmentX(LEFT_ALIGNMENT);
            }
        }
        // Field edits persist when the field loses focus (the Save button sits on the Runtime tab).
        java.awt.event.FocusAdapter persistOnLeave = new java.awt.event.FocusAdapter() {
            public void focusLost(java.awt.event.FocusEvent event) {
                persistConnectorSettingsQuietly();
            }
        };
        connectorOrigin.addFocusListener(persistOnLeave);
        connectorPort.addFocusListener(persistOnLeave);
        connectorClientId.addFocusListener(persistOnLeave);
        connectorClientSecret.addFocusListener(persistOnLeave);
        connectorCentralIssuer.addFocusListener(persistOnLeave);
        mcpView = form;
        return mcpView;
    }

    private javax.swing.JComponent mcpView;

    /** The server's settings are only editable while the server is switched on. */
    private void updateMcpFieldsEnabled() {
        boolean server = botControlMcp.isSelected();
        chatGptConnector.setEnabled(server);
        boolean on = server && chatGptConnector.isSelected();
        connectorOrigin.setEnabled(on);
        connectorPort.setEnabled(on);
        connectorClientId.setEnabled(on);
        connectorClientSecret.setEnabled(on);
        connectorCentralIssuer.setEnabled(on);
    }

    /** Stores the edited fields without (re)starting the listener; Save or the switch applies them. */
    private void persistConnectorSettingsQuietly() {
        int port;
        try {
            port = Integer.parseInt(connectorPort.getText().trim());
        } catch (NumberFormatException invalid) {
            port = 8082;
        }
        ResearchRuntimeSettings.saveChatGptConnectorSettings(store,
                new ResearchRuntimeSettings.ChatGptConnectorSettings(
                        chatGptConnector.isSelected(), port, connectorOrigin.getText().trim(),
                        connectorClientId.getText().trim(),
                        new String(connectorClientSecret.getPassword()).trim(),
                        connectorCentralIssuer.getText().trim()));
    }

    /** The requirements-check output, shown by the host as its own "Check results" tab. */
    public javax.swing.JComponent resultsView() {
        if (resultsView == null) {
            resultsView = new JScrollPane(results);
        }
        return resultsView;
    }

    private javax.swing.JComponent resultsView;

    /** Brings the check output's tab to the front (if the panel sits in a tabbed pane). */
    private void showResults() {
        javax.swing.JComponent view = resultsView();
        javax.swing.JTabbedPane tabs = (javax.swing.JTabbedPane)
                SwingUtilities.getAncestorOfClass(javax.swing.JTabbedPane.class, this);
        if (tabs != null) {
            for (int i = 0; i < tabs.getTabCount(); i++) {
                if (SwingUtilities.isDescendingFrom(view, (java.awt.Component) tabs.getComponentAt(i))) {
                    tabs.setSelectedIndex(i);
                    return;
                }
            }
        }
    }

    private static JPanel row(String label, javax.swing.JComponent field) {
        JPanel row = new JPanel();
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        JLabel jLabel = new JLabel(label);
        jLabel.setPreferredSize(new java.awt.Dimension(170, jLabel.getPreferredSize().height));
        row.add(jLabel);
        row.add(field);
        row.add(Box.createHorizontalGlue());
        return row;
    }
    /** The connection details + the live tool catalog — ONE compact block, uniformly formatted. */
    private void showBotTools() {
        com.aresstack.askai.research.mcp.ResearchBotControlEndpoint endpoint =
                session == null ? null : session.botControlEndpoint();
        if (endpoint == null) {
            return;
        }
        StringBuilder html = new StringBuilder("<html><body style='width:520px'>")
                .append("<b>Transport:</b> Streamable HTTP (MCP JSON-RPC), 127.0.0.1, "
                        + "token in the URL path<br>")
                .append("<b>Connection file:</b> &lt;project&gt;/service-endpoint.json "
                        + "(URL, token, usage guide)<br><br>")
                .append("<b>Tools (live):</b><br>");
        for (java.util.Map.Entry<String, String> tool : endpoint.toolCatalog().entrySet()) {
            html.append("<b>").append(tool.getKey()).append("</b> \u2014 ")
                .append(escapeHtml(tool.getValue())).append("<br><br>");
        }
        html.append("</body></html>");
        JPanel content = new JPanel(new BorderLayout(0, 8));
        content.add(copyableValue(endpoint.connectionUrl()), java.awt.BorderLayout.NORTH);
        content.add(new JLabel(html.toString()), java.awt.BorderLayout.CENTER);
        javax.swing.JOptionPane.showMessageDialog(this, content, "Bot control (MCP)",
                javax.swing.JOptionPane.PLAIN_MESSAGE);
    }

    private static String escapeHtml(String text) {
        return text == null ? "" : text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** A selectable, borderless field + copy button — the URL is DATA, not decoration. */
    private javax.swing.JComponent copyableValue(final String text) {
        JPanel value = new JPanel(new BorderLayout(6, 0));
        value.setOpaque(false);
        final javax.swing.JTextField field = ComicControls.textField(text == null ? "" : text);
        field.setEditable(false);
        field.setBorder(null);
        field.setOpaque(false);
        field.setCaretPosition(0);
        value.add(field, java.awt.BorderLayout.CENTER);
        javax.swing.JButton copy = new ComicButton("Copy");
        copy.setMargin(new java.awt.Insets(0, 6, 0, 6));
        copy.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent event) {
                java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(
                        new java.awt.datatransfer.StringSelection(field.getText()), null);
            }
        });
        value.add(copy, java.awt.BorderLayout.EAST);
        return value;
    }

}