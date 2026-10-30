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
import javax.swing.JComboBox;
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
    private final JLabel backendStatus = new JLabel(" ");
    /** DEFAULT language for NEW research sessions (English default, German translation) — persisted. */
    private final JComboBox<String> agentLanguage =
            ComicControls.<String>comboBox(new String[]{"English", "Deutsch"});
    /** LLM narration (default off): milestone texts phrased by the main model, validated, with fallback. */
    private final JCheckBox llmNarration = ComicControls.checkBox(
            "AI-phrased guidance (uses the main model; applies to new sessions)", false);
    /** Scoping orientation tags (default off = long-standing behaviour): suggestions on every broad turn. */
    private final JCheckBox alwaysSuggest = ComicControls.checkBox(
            "Always offer search suggestions (orientation tags; applies to new sessions)", false);
    /** Answer budget per agent model turn (tokens) — the longest contracted answer is the source review. */
    private final JTextField agentMaxTokens = ComicControls.textField(6);
    // Konzeptpapier tool-round loop: work budget and error tolerance, deliberately separate.
    private final JTextField conceptToolRounds = ComicControls.textField(4);
    private final JTextField conceptRepairAttempts = ComicControls.textField(4);
    // Search-run limits: the completion target + the safety limits. Every bound is a setting.
    private final JTextField searchTargetSources = ComicControls.textField(4);
    private final JTextField searchMaxPages = ComicControls.textField(4);
    private final JTextField searchMaxToolCalls = ComicControls.textField(4);
    private final JTextField searchMaxMinutes = ComicControls.textField(4);
    private final JTextField browserToolTimeout = ComicControls.textField(4);
    private final JTextField browserIdleStop = ComicControls.textField(4);
    private final JTextField searchMaxErrors = ComicControls.textField(4);
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
    /** AI reranker opt-in (default OFF = lexical BM25 scoring, no model load). */
    private final JCheckBox useAiReranker = ComicControls.checkBox(
            "Use the AI reranker (semantic model instead of BM25; applies to new sessions)", false);
    /** Bot-control MCP (default ON): run_command/session_state/chat_history + service-endpoint.json. */
    private final JCheckBox botControlMcp = ComicControls.checkBox(
            "Bot control via MCP (applies to new sessions)", true);
    /** Square, margin-less info button (Unicode \u24D8): opens the live connection/tool details. */
    private final javax.swing.JButton botTools = new ComicButton("\u24D8");
    /** The live session behind this settings page (nullable: store-only construction in tests). */
    private final com.aresstack.askai.research.agent.ResearchAgentSession session;
    /** ChatGPT connector (default OFF): AskAI as its own public MCP+OAuth face behind the TLS proxy. */
    private final JCheckBox chatGptConnector = ComicControls.checkBox(
            "ChatGPT connector (public endpoint behind the reverse proxy; applies to new sessions)", false);
    private final JTextField connectorOrigin = placeholderField("https://askai.example.com", 38);
    private final JTextField connectorPort = ComicControls.textField(6);
    private final JTextField connectorClientId =
            placeholderField(com.aresstack.askai.research.connector.ConnectorConfig.DEFAULT_CLIENT_ID, 12);
    private final javax.swing.JPasswordField connectorClientSecret = placeholderPasswordField(
            com.aresstack.askai.research.connector.ConnectorConfig.DEFAULT_CLIENT_SECRET, 18);
    /** Non-empty = central mode: the AresStack webserver issues the tokens; AskAI only validates. */
    private final JTextField connectorCentralIssuer =
            placeholderField("https://askai.example.com (empty = local OAuth)", 38);
    /**
     * Initial-search source (applies to new sessions): the legacy browser SERP (default) or one of the
     * REST search providers. Provider credentials are NOT configured here — they live in
     * {@code ${user.home}/agents/research/providers/} (brave.json, brightdata.json, dataforseo.json).
     */
    // No runtime path fields: the research agent and the browser run inside AskAI and need no
    // Java/jar configuration at all.
    private final JComboBox<String> browserChannel = ComicControls.<String>comboBox(
            new String[]{ResearchRuntimeSettings.CAMOUFOX_CHANNEL, "chrome", "msedge"});
    // The reranker model is chosen centrally in AskAI (Configuration → AI models), NOT here. Any previously
    // persisted plugin-side selection is carried through unchanged so it is never lost on save.
    private String carriedRerankerModel = "";
    private final JCheckBox headless = ComicControls.checkBox("Run the browser headless", true);
    private final JCheckBox allowPrivate = ComicControls.checkBox(
            "Allow private/loopback targets (development only)", false);
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

        JPanel form = new JPanel();
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        form.add(row("Backend:", backendStatus));
        form.add(row("Language:", agentLanguage));
        form.add(row("", llmNarration));
        form.add(row("", alwaysSuggest));
        form.add(row("Agent answer budget (tokens):", agentMaxTokens));
        JPanel searchLimits = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0));
        searchLimits.setOpaque(false);
        searchLimits.add(labelled("Target sources", searchTargetSources));
        searchLimits.add(javax.swing.Box.createHorizontalStrut(10));
        searchLimits.add(labelled("Pages", searchMaxPages));
        searchLimits.add(javax.swing.Box.createHorizontalStrut(10));
        searchLimits.add(labelled("Tool calls", searchMaxToolCalls));
        searchLimits.add(javax.swing.Box.createHorizontalStrut(10));
        searchLimits.add(labelled("Minutes", searchMaxMinutes));
        searchLimits.add(labelled("Tool timeout (s)", browserToolTimeout));
        searchLimits.add(javax.swing.Box.createHorizontalStrut(10));
        searchLimits.add(labelled("Idle stop (s)", browserIdleStop));
        searchLimits.add(javax.swing.Box.createHorizontalStrut(10));
        searchLimits.add(labelled("Errors", searchMaxErrors));
        form.add(row("Search limits:", searchLimits));
        form.add(row("", orchestratorEnabled));
        JPanel orchestratorLimits = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0));
        orchestratorLimits.setOpaque(false);
        orchestratorLimits.add(labelled("Rounds", orchestratorMaxRounds));
        orchestratorLimits.add(javax.swing.Box.createHorizontalStrut(10));
        orchestratorLimits.add(labelled("Packages", orchestratorMaxTasks));
        orchestratorLimits.add(javax.swing.Box.createHorizontalStrut(10));
        orchestratorLimits.add(labelled("Per round", orchestratorTasksPerRound));
        orchestratorLimits.add(javax.swing.Box.createHorizontalStrut(10));
        orchestratorLimits.add(labelled("Tokens", orchestratorTokenBudget));
        orchestratorLimits.add(javax.swing.Box.createHorizontalStrut(10));
        orchestratorLimits.add(labelled("Minutes", orchestratorTimeBudget));
        orchestratorLimits.add(javax.swing.Box.createHorizontalStrut(10));
        orchestratorLimits.add(labelled("Retries", orchestratorTaskRetries));
        orchestratorLimits.add(javax.swing.Box.createHorizontalStrut(10));
        orchestratorLimits.add(labelled("Sources/section", orchestratorSectionSources));
        form.add(row("Orchestrator:", orchestratorLimits));
        JPanel reviewLimits = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0));
        reviewLimits.setOpaque(false);
        reviewLimits.add(labelled("Sources", reviewMaxSources));
        reviewLimits.add(javax.swing.Box.createHorizontalStrut(10));
        reviewLimits.add(labelled("Chars/source", reviewMaxChars));
        form.add(row("Review context:", reviewLimits));
        JPanel conceptLimits = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0));
        conceptLimits.setOpaque(false);
        conceptLimits.add(labelled("Tool rounds", conceptToolRounds));
        conceptLimits.add(javax.swing.Box.createHorizontalStrut(10));
        conceptLimits.add(labelled("Repairs", conceptRepairAttempts));
        form.add(row("Concept loop:", conceptLimits));
        JPanel scopeCheckRow = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0));
        scopeCheckRow.setOpaque(false);
        scopeCheckRow.add(labelled("Breadth (concepts)", scopeCheckTargetProbes));
        scopeCheckRow.add(javax.swing.Box.createHorizontalStrut(10));
        scopeCheckRow.add(labelled("Samples per post", scopeCheckControlsPerAnchor));
        scopeCheckRow.add(javax.swing.Box.createHorizontalStrut(10));
        scopeCheckRow.add(labelled("Max. topics", scopeCheckMaxTopics));
        scopeCheckRow.add(javax.swing.Box.createHorizontalStrut(10));
        scopeCheckRow.add(labelled("Timeout (s)", scopeCheckTimeout));
        scopeCheckRow.add(javax.swing.Box.createHorizontalStrut(10));
        final javax.swing.JButton scopeCheckAdvancedToggle =
                new ComicButton("Advanced…");
        scopeCheckRow.add(scopeCheckAdvancedToggle);
        form.add(row("Scope check:", scopeCheckRow));
        JPanel scopeCheckModelRow = new JPanel(
                new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0));
        scopeCheckModelRow.setOpaque(false);
        scopeCheckModelRow.add(labelled("Gen temp", scopeCheckGenTemperature));
        scopeCheckModelRow.add(javax.swing.Box.createHorizontalStrut(10));
        scopeCheckModelRow.add(labelled("Gen tokens", scopeCheckGenMaxTokens));
        scopeCheckModelRow.add(javax.swing.Box.createHorizontalStrut(10));
        scopeCheckModelRow.add(labelled("Choice temp", scopeCheckChooserTemperature));
        scopeCheckModelRow.add(javax.swing.Box.createHorizontalStrut(10));
        scopeCheckModelRow.add(labelled("Choice tokens", scopeCheckChooserMaxTokens));
        scopeCheckModelRow.add(javax.swing.Box.createHorizontalStrut(10));
        scopeCheckModelRow.add(labelled("Choice timeout (s)", scopeCheckChoiceTimeout));
        final JPanel scopeCheckModelSettings = row("  advanced (model):", scopeCheckModelRow);
        scopeCheckModelSettings.setVisible(false);
        form.add(scopeCheckModelSettings);
        JPanel scopeCheckCalibrationRow = new JPanel(
                new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0));
        scopeCheckCalibrationRow.setOpaque(false);
        scopeCheckCalibrationRow.add(labelled("near", scopeCheckNearSimilarity));
        scopeCheckCalibrationRow.add(javax.swing.Box.createHorizontalStrut(8));
        scopeCheckCalibrationRow.add(labelled("Hint margin", scopeCheckHintBoundaryMargin));
        scopeCheckCalibrationRow.add(javax.swing.Box.createHorizontalStrut(8));
        scopeCheckCalibrationRow.add(labelled("Mission Q", scopeCheckMissionQuantile));
        scopeCheckCalibrationRow.add(javax.swing.Box.createHorizontalStrut(8));
        scopeCheckCalibrationRow.add(labelled("Mission slack", scopeCheckMissionMargin));
        scopeCheckCalibrationRow.add(javax.swing.Box.createHorizontalStrut(8));
        scopeCheckCalibrationRow.add(labelled("Neighbour Q", scopeCheckNeighborQuantile));
        scopeCheckCalibrationRow.add(javax.swing.Box.createHorizontalStrut(8));
        scopeCheckCalibrationRow.add(labelled("Neighbour slack", scopeCheckNeighborMargin));
        final JPanel scopeCheckCalibrationSettings =
                row("  advanced (calibration):", scopeCheckCalibrationRow);
        scopeCheckCalibrationSettings.setVisible(false);
        form.add(scopeCheckCalibrationSettings);
        JPanel scopeCheckSweepRow = new JPanel(
                new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0));
        scopeCheckSweepRow.setOpaque(false);
        scopeCheckSweepRow.add(labelled("Min. posts", scopeCheckMinNegotiated));
        scopeCheckSweepRow.add(javax.swing.Box.createHorizontalStrut(8));
        scopeCheckSweepRow.add(labelled("Min. samples", scopeCheckMinNeighbors));
        scopeCheckSweepRow.add(javax.swing.Box.createHorizontalStrut(8));
        scopeCheckSweepRow.add(labelled("Boundary margin", scopeCheckBoundaryMargin));
        scopeCheckSweepRow.add(javax.swing.Box.createHorizontalStrut(8));
        scopeCheckSweepRow.add(labelled("Novelty gap", scopeCheckNoveltyGap));
        scopeCheckSweepRow.add(javax.swing.Box.createHorizontalStrut(8));
        scopeCheckSweepRow.add(labelled("Novelty weight", scopeCheckNoveltyWeight));
        scopeCheckSweepRow.add(javax.swing.Box.createHorizontalStrut(8));
        scopeCheckSweepRow.add(labelled("Duplicate ≥", scopeCheckDuplicateCeiling));
        final JPanel scopeCheckSweepSettings =
                row("  advanced (sweep):", scopeCheckSweepRow);
        scopeCheckSweepSettings.setVisible(false);
        form.add(scopeCheckSweepSettings);
        scopeCheckAdvancedToggle.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent event) {
                boolean show = !scopeCheckModelSettings.isVisible();
                scopeCheckModelSettings.setVisible(show);
                scopeCheckCalibrationSettings.setVisible(show);
                scopeCheckSweepSettings.setVisible(show);
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
                : "This session has no bot endpoint (disabled, or not a productive session)");
        form.add(row("", useAiReranker));
        JPanel botRow = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0));
        botRow.setOpaque(false);
        botRow.add(botControlMcp);
        botRow.add(javax.swing.Box.createHorizontalStrut(6));
        botRow.add(botTools);
        form.add(row("", botRow));
        form.add(row("", chatGptConnector));
        form.add(row("Public origin:", connectorOrigin));
        JPanel connectorDetails = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0));
        connectorDetails.setOpaque(false);
        connectorDetails.add(connectorPort);
        connectorDetails.add(javax.swing.Box.createHorizontalStrut(12));
        connectorDetails.add(new JLabel("Client-ID:"));
        connectorDetails.add(javax.swing.Box.createHorizontalStrut(4));
        connectorDetails.add(connectorClientId);
        connectorDetails.add(javax.swing.Box.createHorizontalStrut(12));
        connectorDetails.add(new JLabel("Secret:"));
        connectorDetails.add(javax.swing.Box.createHorizontalStrut(4));
        connectorDetails.add(connectorClientSecret);
        form.add(row("Connector port:", connectorDetails));
        form.add(row("Central OAuth issuer:", connectorCentralIssuer));
        // The initial-search PROVIDER selection + its provider-specific settings live on their own
        // "Search" gear tab (SearchProviderCardsPanel), not as shared fields here.
        form.add(row("Browser channel:", browserChannel));
        form.add(row("", headless));
        // Transparency does not depend on a visible browser window: the chat shows the visited sites.
        JLabel headlessHint = new JLabel(new com.aresstack.askai.research.agent.ResearchPlaybook(
                com.aresstack.askai.research.agent.ResearchLanguage.fromCode(
                        ResearchRuntimeSettings.loadLanguage(store))).headlessHint());
        headlessHint.setEnabled(false);
        form.add(row("", headlessHint));
        form.add(row("", allowPrivate));
        JPanel buttons = new JPanel();
        buttons.setLayout(new BoxLayout(buttons, BoxLayout.X_AXIS));
        buttons.add(save);
        buttons.add(Box.createHorizontalStrut(8));
        buttons.add(check);
        buttons.add(Box.createHorizontalStrut(8));
        buttons.add(busy);
        form.add(buttons);
        add(form, BorderLayout.NORTH);

        results.setEditable(false);
        results.setLineWrap(false);
        add(new JScrollPane(results), BorderLayout.CENTER);

        // Persisted values first, then AUTOMATIC defaults for whatever is still empty (assembled
        // distribution, discovered Java >= 21) — no empty mandatory fields on a normal dev start.
        // The agent-Java override is kept ONLY when the user persisted one explicitly; the automatic
        // value (AskAI's own JVM) is applied by the factory and never written back as a setting.
        ResearchRuntimeSettings persisted = ResearchRuntimeSettings.load(store);
        apply(ResearchRuntimeDefaults.complete(persisted));
        // The combo persists the DEFAULT language for NEW research sessions; running sessions keep
        // their own session-local language (live-switchable at the session, never globally from here).
        String languageCode = ResearchRuntimeSettings.loadLanguage(store);
        agentLanguage.setSelectedIndex("de".equals(languageCode) ? 1 : 0);
        agentLanguage.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent event) {
                String code = agentLanguage.getSelectedIndex() == 1 ? "de" : "en";
                ResearchRuntimeSettings.saveLanguage(ResearchRuntimeSettingsPanel.this.store, code);
            }
        });
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
                persistConnectorSettings();
            }
        });
        llmNarration.setSelected(ResearchRuntimeSettings.loadLlmNarration(store));
        llmNarration.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent event) {
                // Like the language: persisted immediately; running sessions keep their narrator.
                ResearchRuntimeSettings.saveLlmNarration(ResearchRuntimeSettingsPanel.this.store,
                        llmNarration.isSelected());
            }
        });
        alwaysSuggest.setSelected(ResearchRuntimeSettings.loadAlwaysOfferSearchSuggestions(store));
        alwaysSuggest.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent event) {
                // Persisted immediately; the flag reaches the agent at the NEXT session launch.
                ResearchRuntimeSettings.saveAlwaysOfferSearchSuggestions(
                        ResearchRuntimeSettingsPanel.this.store, alwaysSuggest.isSelected());
            }
        });
        agentMaxTokens.setText(String.valueOf(ResearchRuntimeSettings.loadAgentMaxOutputTokens(store)));
        agentMaxTokens.setToolTipText("The agent's answer budget in tokens per model turn (the longest "
                + "answer is the source review). Too small a value truncates answers. "
                + "Applies to new sessions.");
        agentMaxTokens.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent event) {
                persistAgentMaxTokens();
            }
        });
        agentMaxTokens.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override
            public void focusLost(java.awt.event.FocusEvent event) {
                persistAgentMaxTokens();
            }
        });
        bindScopeCheckSettings();
        bindLimit(searchTargetSources, ResearchRuntimeSettings.KEY_SEARCH_TARGET_SOURCES,
                ResearchRuntimeSettings.DEFAULT_SEARCH_TARGET_SOURCES,
                "The search ends regularly once this many sources are accepted (applies to new sessions).");
        bindLimit(searchMaxPages, ResearchRuntimeSettings.KEY_SEARCH_MAX_PAGES,
                ResearchRuntimeSettings.DEFAULT_SEARCH_MAX_PAGES,
                "Safety limit: maximum pages visited per search run.");
        bindLimit(searchMaxToolCalls, ResearchRuntimeSettings.KEY_SEARCH_MAX_TOOL_CALLS,
                ResearchRuntimeSettings.DEFAULT_SEARCH_MAX_TOOL_CALLS,
                "Safety limit: maximum tool calls per search run.");
        bindLimit(searchMaxMinutes, ResearchRuntimeSettings.KEY_SEARCH_MAX_MINUTES,
                ResearchRuntimeSettings.DEFAULT_SEARCH_MAX_MINUTES,
                "Safety limit (timeout): maximum run time in minutes; waiting for the "
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
        refreshBackendStatus();

        save.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent event) {
                saveSettings();
            }
        });
        check.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent event) {
                runCheck();
            }
        });
    }

    /** The panel state as the SAME typed model the factory reads — no second mapping. */
    ResearchRuntimeSettings currentSettings() {
        // Saving from the panel always persists the automatic mode (this also migrates stores where an
        // old default once wrote FAKE); the demo backend is chosen by the FACTORY only when the
        // requirements are not met.
        return new ResearchRuntimeSettings(ResearchBackendMode.ACP,
                String.valueOf(browserChannel.getSelectedItem()), headless.isSelected(),
                allowPrivate.isSelected(), carriedRerankerModel);
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
        browserChannel.setSelectedItem(settings.getBrowserChannel());
        // Carry any previously persisted reranker selection through invisibly (chosen centrally now).
        carriedRerankerModel = settings.getSelectedRerankerModel();
        headless.setSelected(settings.isHeadless());
        allowPrivate.setSelected(settings.isAllowPrivateNetworks());
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

    /** A tiny "Label: [field]" group for compact multi-field limit rows. */
    private static JPanel labelled(String label, JTextField field) {
        JPanel group = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0));
        group.setOpaque(false);
        group.add(new JLabel(label + ": "));
        group.add(field);
        return group;
    }

    /** Z4c: every scope-check knob binds to its persisted setting (loaded per CLICK, no restart). */
    private void bindScopeCheckSettings() {
        com.aresstack.askai.research.scope.ScopeSweepConfiguration d =
                com.aresstack.askai.research.scope.ScopeSweepConfiguration.defaults();
        bindLimit(scopeCheckTargetProbes, ResearchRuntimeSettings.KEY_SCOPE_CHECK_TARGET_PROBES,
                d.targetBroadProbes, "How many broad concepts one scope check generates. "
                        + "Small main models (2B class) often cannot sustain 50 — set 20 "
                        + "then.");
        bindLimit(scopeCheckControlsPerAnchor,
                ResearchRuntimeSettings.KEY_SCOPE_CHECK_CONTROLS_PER_ANCHOR, d.controlsPerAnchor,
                "Local calibration samples per negotiated fence post");
        bindLimit(scopeCheckMaxTopics, ResearchRuntimeSettings.KEY_SCOPE_CHECK_MAX_TOPICS,
                d.selectorParameters.maximumCandidates,
                "Suggest at most this many unexplored topics per check");
        bindLimit(scopeCheckTimeout, ResearchRuntimeSettings.KEY_SCOPE_CHECK_TIMEOUT_SECONDS,
                d.generationTimeoutSeconds, "Timeout of the topic generation in seconds");
        bindDecimal(scopeCheckGenTemperature,
                ResearchRuntimeSettings.KEY_SCOPE_CHECK_GEN_TEMPERATURE, d.generatorTemperature,
                "Temperature of the topic generation (breadth needs variety)");
        bindLimit(scopeCheckGenMaxTokens, ResearchRuntimeSettings.KEY_SCOPE_CHECK_GEN_MAX_TOKENS,
                d.generatorMaxOutputTokens, "Token budget of the topic generation");
        bindDecimal(scopeCheckChooserTemperature,
                ResearchRuntimeSettings.KEY_SCOPE_CHECK_CHOOSER_TEMPERATURE, d.chooserTemperature,
                "Temperature of the question choice (focused, not creative)");
        bindLimit(scopeCheckChooserMaxTokens,
                ResearchRuntimeSettings.KEY_SCOPE_CHECK_CHOOSER_MAX_TOKENS,
                d.chooserMaxOutputTokens, "Token budget of the question choice");
        bindLimit(scopeCheckChoiceTimeout,
                ResearchRuntimeSettings.KEY_SCOPE_CHECK_CHOICE_TIMEOUT_SECONDS,
                d.choiceTimeoutSeconds, "Timeout of the question choice in seconds");
        String spike = " (measurement spike: the formula is deliberately provisional, frozen after "
                + "productive measurements)";
        bindDecimal(scopeCheckNearSimilarity,
                ResearchRuntimeSettings.KEY_SCOPE_CHECK_NEAR_SIMILARITY,
                d.fenceThresholds.nearSimilarity, "Z2 hint: from this cosine on a probe counts as "
                        + "'near a fence post'" + spike);
        bindDecimal(scopeCheckHintBoundaryMargin,
                ResearchRuntimeSettings.KEY_SCOPE_CHECK_HINT_BOUNDARY_MARGIN,
                d.fenceThresholds.boundaryMargin, "Z2 hint boundary margin" + spike);
        bindDecimal(scopeCheckMissionQuantile,
                ResearchRuntimeSettings.KEY_SCOPE_CHECK_MISSION_QUANTILE,
                d.calibrationParameters.missionRelevanceQuantile,
                "Quantile of the anchors' mission relevance (0 = minimum)" + spike);
        bindDecimal(scopeCheckMissionMargin,
                ResearchRuntimeSettings.KEY_SCOPE_CHECK_MISSION_MARGIN,
                d.calibrationParameters.missionRelevanceMargin,
                "Slack below the mission quantile" + spike);
        bindDecimal(scopeCheckNeighborQuantile,
                ResearchRuntimeSettings.KEY_SCOPE_CHECK_NEIGHBOR_QUANTILE,
                d.calibrationParameters.neighborSimilarityQuantile,
                "Quantile of the anchor-neighbour cosines (0 = minimum)" + spike);
        bindDecimal(scopeCheckNeighborMargin,
                ResearchRuntimeSettings.KEY_SCOPE_CHECK_NEIGHBOR_MARGIN,
                d.calibrationParameters.neighborSimilarityMargin,
                "Slack below the neighbour quantile" + spike);
        bindLimit(scopeCheckMinNegotiated,
                ResearchRuntimeSettings.KEY_SCOPE_CHECK_MIN_NEGOTIATED,
                d.calibrationParameters.minimumNegotiatedAnchors,
                "Minimum number of negotiated IN/OUT fence posts for a reliable calibration");
        bindLimit(scopeCheckMinNeighbors,
                ResearchRuntimeSettings.KEY_SCOPE_CHECK_MIN_NEIGHBORS,
                d.calibrationParameters.minimumNeighborSamples,
                "Minimum number of valid neighbour samples");
        bindDecimal(scopeCheckBoundaryMargin,
                ResearchRuntimeSettings.KEY_SCOPE_CHECK_BOUNDARY_MARGIN, d.boundaryMargin,
                "|margin| below this value = a genuine IN/OUT boundary question" + spike);
        bindDecimal(scopeCheckNoveltyGap,
                ResearchRuntimeSettings.KEY_SCOPE_CHECK_NOVELTY_GAP, d.sweepNoveltyGap,
                "Sweep-relative novelty line: median minus this value" + spike);
        bindDecimal(scopeCheckNoveltyWeight,
                ResearchRuntimeSettings.KEY_SCOPE_CHECK_NOVELTY_WEIGHT,
                d.selectorParameters.noveltyWeight,
                "Weight of novelty in the topic diversity");
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

    /** Persist the agent answer budget; a non-numeric/non-positive entry resets to the persisted value. */
    private void persistAgentMaxTokens() {
        try {
            int tokens = Integer.parseInt(agentMaxTokens.getText().trim());
            if (tokens > 0) {
                ResearchRuntimeSettings.saveAgentMaxOutputTokens(store, tokens);
                return;
            }
        } catch (NumberFormatException invalid) {
            // fall through to the reset below
        }
        agentMaxTokens.setText(String.valueOf(ResearchRuntimeSettings.loadAgentMaxOutputTokens(store)));
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
                        "Der ChatGPT-Connector konnte nicht starten:\n" + failure,
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

    /** A path field with a working Browse button (file chooser preset to the current value). */
    private JPanel pathRow(String label, final JTextField field) {
        JPanel row = row(label, field);
        JButton browse = new ComicButton("…");
        browse.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent event) {
                javax.swing.JFileChooser chooser = new javax.swing.JFileChooser();
                String current = field.getText().trim();
                if (!current.isEmpty()) {
                    chooser.setSelectedFile(new java.io.File(current));
                }
                if (chooser.showOpenDialog(ResearchRuntimeSettingsPanel.this)
                        == javax.swing.JFileChooser.APPROVE_OPTION) {
                    field.setText(chooser.getSelectedFile().getAbsolutePath());
                }
            }
        });
        row.add(browse);
        return row;
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
                        + "Token im URL-Pfad<br>")
                .append("<b>Verbindungsdatei:</b> &lt;Projekt&gt;/service-endpoint.json "
                        + "(URL, Token, Usage-Guide)<br><br>")
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