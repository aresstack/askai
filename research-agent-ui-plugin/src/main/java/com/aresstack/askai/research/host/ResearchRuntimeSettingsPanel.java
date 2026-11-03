package com.aresstack.askai.research.host;

import com.aresstack.comiccontrols.control.ComicTextField;
import com.aresstack.comiccontrols.control.ComicPasswordField;
import com.aresstack.comiccontrols.control.ComicControls;
import com.aresstack.comiccontrols.control.ComicButton;
import com.aresstack.comiccontrols.theme.ThemeColor;
import com.aresstack.askai.plugin.api.service.WorkspaceStateStore;
import com.aresstack.askai.research.acp.ResearchBackendMode;

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
 * {@link ResearchRuntimeSettings} (the same mapper the session factory uses). Saving an ACP configuration
 * that fails validation is rejected with the concrete problem list; FAKE is labelled clearly as the
 * clickdummy/development mode. "Check requirements" runs validation + the browser runtime readiness probe on a
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
    /** AI reranker opt-in (default OFF = lexical BM25 scoring, no model load). */
    private final JCheckBox useAiReranker = ComicControls.checkBox(
            "Use AI reranker (semantic model instead of BM25; applies to new sessions)", false);
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
    /**
     * Initial-search source (applies to new sessions): the legacy browser SERP (default) or one of the
     * REST search providers. Provider credentials are NOT configured here — they live in
     * {@code ${user.home}/agents/research/providers/} (brave.json, brightdata.json, dataforseo.json).
     */
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
        form.add(row("Answer budget (tokens):", agentMaxTokens));
        JPanel searchLimits = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0));
        searchLimits.setOpaque(false);
        searchLimits.add(labelled("Source target", searchTargetSources));
        searchLimits.add(javax.swing.Box.createHorizontalStrut(10));
        searchLimits.add(labelled("Pages", searchMaxPages));
        searchLimits.add(javax.swing.Box.createHorizontalStrut(10));
        searchLimits.add(labelled("Tool-Calls", searchMaxToolCalls));
        form.add(row("Search limits:", searchLimits));
        // Second line: the row fits the dialog width instead of running off its right edge.
        JPanel searchLimits2 = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0));
        searchLimits2.setOpaque(false);
        searchLimits2.add(labelled("Minutes", searchMaxMinutes));
        searchLimits2.add(javax.swing.Box.createHorizontalStrut(10));
        searchLimits2.add(labelled("Tool-Timeout (s)", browserToolTimeout));
        searchLimits2.add(javax.swing.Box.createHorizontalStrut(10));
        searchLimits2.add(labelled("Errors", searchMaxErrors));
        form.add(row("", searchLimits2));
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
        scopeCheckRow.add(labelled("Examples per post", scopeCheckControlsPerAnchor));
        form.add(row("Scope check:", scopeCheckRow));
        JPanel scopeCheckRow2 = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0));
        scopeCheckRow2.setOpaque(false);
        scopeCheckRow2.add(labelled("Max. topics", scopeCheckMaxTopics));
        scopeCheckRow2.add(javax.swing.Box.createHorizontalStrut(10));
        scopeCheckRow2.add(labelled("Timeout (s)", scopeCheckTimeout));
        scopeCheckRow2.add(javax.swing.Box.createHorizontalStrut(10));
        final javax.swing.JButton scopeCheckAdvancedToggle =
                new ComicButton("Advanced…");
        scopeCheckRow2.add(scopeCheckAdvancedToggle);
        form.add(row("", scopeCheckRow2));
        JPanel scopeCheckModelRow = new JPanel(
                new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0));
        scopeCheckModelRow.setOpaque(false);
        scopeCheckModelRow.add(labelled("Gen temp", scopeCheckGenTemperature));
        scopeCheckModelRow.add(javax.swing.Box.createHorizontalStrut(10));
        scopeCheckModelRow.add(labelled("Gen tokens", scopeCheckGenMaxTokens));
        scopeCheckModelRow.add(javax.swing.Box.createHorizontalStrut(10));
        scopeCheckModelRow.add(labelled("Choice temp", scopeCheckChooserTemperature));
        JPanel scopeCheckModelRowB = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0));
        scopeCheckModelRowB.setOpaque(false);
        scopeCheckModelRowB.add(labelled("Choice tokens", scopeCheckChooserMaxTokens));
        scopeCheckModelRowB.add(javax.swing.Box.createHorizontalStrut(10));
        scopeCheckModelRowB.add(labelled("Choice timeout (s)", scopeCheckChoiceTimeout));
        final JPanel scopeCheckModelSettings = row("  Advanced (model):", scopeCheckModelRow);
        final JPanel scopeCheckModelSettingsB = row("", scopeCheckModelRowB);
        scopeCheckModelSettings.setVisible(false);
        scopeCheckModelSettingsB.setVisible(false);
        form.add(scopeCheckModelSettings);
        form.add(scopeCheckModelSettingsB);
        JPanel scopeCheckCalibrationRow = new JPanel(
                new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0));
        scopeCheckCalibrationRow.setOpaque(false);
        scopeCheckCalibrationRow.add(labelled("near", scopeCheckNearSimilarity));
        scopeCheckCalibrationRow.add(javax.swing.Box.createHorizontalStrut(8));
        scopeCheckCalibrationRow.add(labelled("Hint margin", scopeCheckHintBoundaryMargin));
        scopeCheckCalibrationRow.add(javax.swing.Box.createHorizontalStrut(8));
        scopeCheckCalibrationRow.add(labelled("Mission Q", scopeCheckMissionQuantile));
        JPanel scopeCheckCalibrationRowB = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0));
        scopeCheckCalibrationRowB.setOpaque(false);
        scopeCheckCalibrationRowB.add(labelled("Mission slack", scopeCheckMissionMargin));
        scopeCheckCalibrationRowB.add(javax.swing.Box.createHorizontalStrut(8));
        scopeCheckCalibrationRowB.add(labelled("Neighbor Q", scopeCheckNeighborQuantile));
        scopeCheckCalibrationRowB.add(javax.swing.Box.createHorizontalStrut(8));
        scopeCheckCalibrationRowB.add(labelled("Neighbor slack", scopeCheckNeighborMargin));
        final JPanel scopeCheckCalibrationSettings =
                row("  Advanced (calibration):", scopeCheckCalibrationRow);
        final JPanel scopeCheckCalibrationSettingsB = row("", scopeCheckCalibrationRowB);
        scopeCheckCalibrationSettings.setVisible(false);
        scopeCheckCalibrationSettingsB.setVisible(false);
        form.add(scopeCheckCalibrationSettings);
        form.add(scopeCheckCalibrationSettingsB);
        JPanel scopeCheckSweepRow = new JPanel(
                new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0));
        scopeCheckSweepRow.setOpaque(false);
        scopeCheckSweepRow.add(labelled("Min. posts", scopeCheckMinNegotiated));
        scopeCheckSweepRow.add(javax.swing.Box.createHorizontalStrut(8));
        scopeCheckSweepRow.add(labelled("Min. examples", scopeCheckMinNeighbors));
        scopeCheckSweepRow.add(javax.swing.Box.createHorizontalStrut(8));
        scopeCheckSweepRow.add(labelled("Boundary margin", scopeCheckBoundaryMargin));
        JPanel scopeCheckSweepRowB = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0));
        scopeCheckSweepRowB.setOpaque(false);
        scopeCheckSweepRowB.add(labelled("Novelty gap", scopeCheckNoveltyGap));
        scopeCheckSweepRowB.add(javax.swing.Box.createHorizontalStrut(8));
        scopeCheckSweepRowB.add(labelled("Novelty weight", scopeCheckNoveltyWeight));
        scopeCheckSweepRowB.add(javax.swing.Box.createHorizontalStrut(8));
        scopeCheckSweepRowB.add(labelled("Duplicate ≥", scopeCheckDuplicateCeiling));
        final JPanel scopeCheckSweepSettings =
                row("  Advanced (sweep):", scopeCheckSweepRow);
        final JPanel scopeCheckSweepSettingsB = row("", scopeCheckSweepRowB);
        scopeCheckSweepSettings.setVisible(false);
        scopeCheckSweepSettingsB.setVisible(false);
        form.add(scopeCheckSweepSettings);
        form.add(scopeCheckSweepSettingsB);
        scopeCheckAdvancedToggle.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent event) {
                boolean show = !scopeCheckModelSettings.isVisible();
                scopeCheckModelSettings.setVisible(show);
                scopeCheckModelSettingsB.setVisible(show);
                scopeCheckCalibrationSettings.setVisible(show);
                scopeCheckCalibrationSettingsB.setVisible(show);
                scopeCheckSweepSettings.setVisible(show);
                scopeCheckSweepSettingsB.setVisible(show);
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
        form.add(row("", useAiReranker));
        // Bot control + the public MCP server (ChatGPT connector) live on their own "MCP" tab (mcpView()).
        // The initial-search PROVIDER selection + its provider-specific settings live on their own
        // "Search" gear tab (SearchProviderCardsPanel), not as shared fields here.
        form.add(row("Browser channel:", browserChannel));
        form.add(row("", headless));
        // Transparency does not depend on a visible browser window: the chat shows the visited sites.
        javax.swing.JTextArea headlessHint = ComicControls.textArea(
                new com.aresstack.askai.research.agent.ResearchPlaybook(
                com.aresstack.askai.research.agent.ResearchLanguage.fromCode(
                        ResearchRuntimeSettings.loadLanguage(store))).headlessHint());
        headlessHint.setLineWrap(true); // wraps inside the field column instead of widening the page
        headlessHint.setWrapStyleWord(true);
        headlessHint.setEditable(false);
        headlessHint.setFocusable(false);
        headlessHint.setOpaque(false);
        headlessHint.setBorder(BorderFactory.createEmptyBorder());
        headlessHint.setForeground(com.aresstack.comiccontrols.theme.ThemeColor.of("research.settingsHintText"));
        form.add(row("", headlessHint));
        form.add(row("", allowPrivate));
        JPanel buttons = new JPanel();
        buttons.setLayout(new BoxLayout(buttons, BoxLayout.X_AXIS));
        buttons.add(save);
        buttons.add(Box.createHorizontalStrut(8));
        buttons.add(check);
        buttons.add(Box.createHorizontalStrut(8));
        buttons.add(busy);
        buttons.add(Box.createHorizontalGlue());
        buttons.setOpaque(false);
        form.add(row("", buttons));
        add(form, BorderLayout.NORTH);

        results.setEditable(false);
        results.setLineWrap(false);
        // The check output is NOT part of the form: it lives on its own tab (see resultsView()).

        // Persisted values first, then the automatic browser-channel decision.
        apply(ResearchRuntimeDefaults.complete(ResearchRuntimeSettings.load(store)));
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
                "Timeout per browser call in seconds. Must cover the whole search engine phase "
                        + "(several engines and result pages, sequential with evaluation) — "
                        + "too small is misread as 'browser unavailable' and aborts every search.");
        bindLimit(reviewMaxSources, ResearchRuntimeSettings.KEY_REVIEW_MAX_SOURCES,
                ResearchRuntimeSettings.DEFAULT_REVIEW_MAX_SOURCES,
                "Maximum number of sources one review reads (newest first).");
        bindLimit(reviewMaxChars, ResearchRuntimeSettings.KEY_REVIEW_MAX_CHARS,
                ResearchRuntimeSettings.DEFAULT_REVIEW_MAX_CHARS,
                "How many characters per source go into the review.");
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
                showResults();
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
        if (settings.isEnabled() && botControlMcp.isSelected()) {
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