package com.aresstack.askai.java8.ui;

import com.aresstack.askai.java8.AskAiModel;
import com.aresstack.askai.java8.audio.AudioProfileRepository;
import com.aresstack.askai.java8.audio.FileAudioProfileRepository;
import com.aresstack.askai.java8.state.ApplicationStateService;
import com.aresstack.askai.java8.config.AppConfigurationRepository;
import com.aresstack.askai.java8.service.AskAiService;
import com.aresstack.askai.java8.service.DefaultOllamaService;
import com.aresstack.askai.java8.service.VirtualOllamaContainerService;
import com.aresstack.askai.java8.service.FeatureActionService;
import com.aresstack.askai.java8.service.OllamaFeatureActionService;
import com.aresstack.askai.java8.service.OllamaService;
import com.aresstack.askai.java8.stt.AudioCapability;
import com.aresstack.askai.java8.stt.DefaultSpeechToTextService;
import com.aresstack.askai.java8.stt.SpeechToTextService;
import com.aresstack.askai.java8.catalog.GlobalCatalogRefreshService;
import com.aresstack.askai.java8.catalog.GlobalCatalogSnapshot;
import com.aresstack.askai.java8.client.AskAiOllamaClient;
import com.aresstack.askai.java8.client.OllamaModelInfo;
import com.aresstack.askai.java8.batch.service.BatchAudioPreparationService;
import com.aresstack.askai.java8.batch.service.BatchMarkdownResultWriter;
import com.aresstack.askai.java8.batch.service.BatchProfileCatalogLoadedEvent;
import com.aresstack.askai.java8.batch.service.BatchProfileCatalogService;
import com.aresstack.askai.java8.batch.service.BatchTranscriptionEventPublisher;
import com.aresstack.askai.java8.batch.service.BatchTranscriptionService;
import com.aresstack.askai.java8.batch.ui.BatchProfileRefresher;
import com.aresstack.askai.java8.batch.ui.BatchTranscriptionController;
import com.aresstack.askai.java8.batch.ui.BatchTranscriptionPanel;
import com.aresstack.askai.java8.ui.chat.ChatSessionComponent;
import com.aresstack.askai.java8.ui.chat.ChatSessionId;
import com.aresstack.askai.java8.ui.chat.ChatWorkspacePanel;
import com.aresstack.audio.profile.AudioProcessingProfile;
import com.aresstack.audio.application.DefaultAudioProcessingPreviewService;
import com.aresstack.comiccontrols.theme.AskAiTheme;
import com.aresstack.comiccontrols.theme.ComicTheme;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

/**
 * Main frame for provider-based AI chat and model installation. Java 8 port of the original AskAI
 * frame: same menu structure and cards; the Install and Network views are the Java 8 panels that
 * carry this port's extensions (HuggingFace GGUF flow, WScript proxy discovery, TLS trust, IPv6).
 */
public final class AskAiFrame extends JFrame {

    private static final String CHAT_VIEW = "chat";
    private static final String BATCH_VIEW = "batch";
    private static final String MODELS_VIEW = "models";
    private static final String ACTIONS_VIEW = "actions";
    private static final String INSTALL_VIEW = "install";
    private static final String CONNECTIONS_VIEW = "connections";
    private static final String AI_MODELS_VIEW = "ai-models";
    private static final String NETWORK_VIEW = "network";
    private static final String ABOUT_VIEW = "about";
    private static final String AUDIO_PROCESSING_VIEW = "audio-processing";
    private static final String PLUGINS_VIEW = "plugins";

    private final AskAiModel model;
    private final AppConfigurationRepository configurationRepository;
    private final AskAiService askAiService;
    private final OllamaService ollamaService;
    private final FeatureActionService featureActionService;
    private final SpeechToTextService speechToTextService;
    private final ConnectionWidget connectionStatusView;
    private final CardLayout contentLayout;
    private final JPanel contentPanel;
    private final OllamaModelsPanel modelsPanel;
    private final AudioProfileRepository audioProfileRepository;
    /** Persisted CSV of the currently open chat-tab ids, so "closed stays closed" survives a crash. */
    private static final String STATE_OPEN_CHAT_TABS = "chat.openTabs";
    /** Shutdown runs exactly once (from the window close OR the File > Beenden menu). */
    private final java.util.concurrent.atomic.AtomicBoolean shuttingDown =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /** One concise shutdown log line to STDERR (the runWithDevPlugins console). */
    private static void shutdownLog(String message) {
        System.err.println("[shutdown] " + message);
    }

    private final ApplicationStateService applicationState;
    private OllamaConfigPanel configPanel;
    private ChatWorkspacePanel chatWorkspace;
    private com.aresstack.askai.plugin.host.ChatWorkspaceHostPanel chatWorkspaceHost;
    private com.aresstack.askai.plugin.host.WorkspacePluginService pluginService;
    private com.aresstack.askai.plugin.host.PluginEnablementService pluginEnablement;
    private java.io.File pluginsDirectory;
    private com.aresstack.askai.plugin.host.AgentSessionCoordinator agentCoordinator;
    /**
     * The read-only chat catalog handed to plugins as a host service: it answers "which chat is selected"
     * and "what is this chat called". Created with the chat history store, updated on every tab switch.
     */
    private com.aresstack.askai.java8.plugin.host.LocalChatSessionCatalog chatSessionCatalog;
    /** Reads the PERSISTED messages of a chat for plugins — same store, no second message truth. */
    private com.aresstack.askai.java8.plugin.host.LocalChatSessionHistoryReader chatSessionHistory;
    /** The one WRITING chat port for plugins: create a chat and activate an agent in it. */
    private com.aresstack.askai.java8.plugin.host.LocalChatSessionLauncher chatSessionLauncher;
    /** Builds the active agent's composer accessories above the active tab's composer (kept to avoid GC). */
    private com.aresstack.askai.plugin.host.AgentComposerAccessoryArea composerAccessoryArea;
    /** The tab currently showing a composer accessory, so it can be cleared when the active tab changes. */
    private OllamaChatPanel accessoryTargetTab;
    /** The tab currently showing a BELOW-composer accessory (tracked independently of the upper slot). */
    private OllamaChatPanel belowAccessoryTargetTab;
    /** The tab currently showing a TRANSCRIPT overlay accessory (the out-of-scope sky). */
    private OllamaChatPanel skyAccessoryTargetTab;
    /** Host runtime services (owns the process-global Solon MCP server); stopped synchronously on close. */
    private com.aresstack.askai.java8.plugin.host.AgentRuntimeServices agentRuntimeServices;
    private AudioProcessingPanel audioProcessingPanel;
    private ModelSearchPanel installSearchPanel;
    private BatchTranscriptionPanel batchPanel;
    private final GlobalCatalogRefreshService catalogRefreshService;
    /** The general-purpose video recorder controller; created lazily on first use of Help → Record Video. */
    private com.aresstack.askai.java8.video.VideoRecordingController videoRecordingController;

    public AskAiFrame(AppConfigurationRepository configurationRepository, final AskAiService askAiService) {
        super("AskAI");
        // Frameless: AskAI draws its own title bar (icon + title left like Windows, the active agent's
        // search in the middle, the comic window control right) — see buildUserInterface.
        setUndecorated(true);
        AppIcon.apply(this); // the AskAI robot everywhere the Java cup used to be (taskbar, Alt-Tab)
        // The theme FIRST: every component built from here on (models panel, connection widget, ...)
        // picks up the comic delegates and colors — not Metal's.
        ComicTheme.install(AskAiTheme.active());
        this.configurationRepository = configurationRepository;
        this.askAiService = askAiService;
        this.model = new AskAiModel(configurationRepository);
        // R0.3: the VIRTUAL container service is the single source of every model list —
        // remote Ollama and AskAI's local model runtime aggregated with per-source fault isolation.
        this.ollamaService = new VirtualOllamaContainerService(model,
                askAiService.localRuntimeManager());
        this.featureActionService = new OllamaFeatureActionService(model);
        this.speechToTextService = new DefaultSpeechToTextService(configurationRepository);
        this.connectionStatusView = new ConnectionWidget(new Runnable() {
            public void run() {
                openConnectionSettings();
            }
        }, new Runnable() {
            public void run() {
                catalogRefreshService.refresh(); // no-op if one is already running
            }
        });
        this.contentLayout = new CardLayout();
        this.contentPanel = new JPanel(contentLayout);
        this.modelsPanel = new OllamaModelsPanel(model, ollamaService, askAiService, configurationRepository);
        this.catalogRefreshService = new GlobalCatalogRefreshService(
                new GlobalCatalogRefreshService.CatalogLoader<String>() {
                    public List<String> load() throws Exception { return loadInstalledModelNames(); }
                },
                new GlobalCatalogRefreshService.CatalogLoader<String>() {
                    public List<String> load() throws Exception { return loadAudioCapableModelNames(); }
                },
                new GlobalCatalogRefreshService.CatalogLoader<AudioProcessingProfile>() {
                    public List<AudioProcessingProfile> load() { return audioProfileRepository.findAll(); }
                },
                new Consumer<Runnable>() {
                    public void accept(Runnable runnable) { onUi(runnable); }
                });
        // R0.4 lifecycle: after an AskAI restart the local model runtime comes back automatically
        // when locally installed models exist, and it always ends with AskAI.
        final com.aresstack.askai.java8.localmodels.LocalModelRuntimeManager localRuntime =
                askAiService.localRuntimeManager();
        if (localRuntime.hasInstalledModels() && localRuntime.isAvailable()) {
            Thread localRuntimeBoot = new Thread(new Runnable() {
                public void run() {
                    try {
                        localRuntime.ensureStarted();
                    } catch (Exception ex) {
                        System.err.println("[local-runtime] start on boot failed: "
                                + ex.getMessage());
                    }
                }
            }, "askai-local-runtime-boot");
            localRuntimeBoot.setDaemon(true);
            localRuntimeBoot.start();
        }
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            public void run() {
                localRuntime.stop();
            }
        }, "askai-local-runtime-shutdown"));
        this.catalogRefreshService.subscribe(new GlobalCatalogRefreshService.Listener() {
            public void onRefreshStarted() { connectionStatusView.setRefreshEnabled(false); }
            public void onCatalogRefreshed(GlobalCatalogSnapshot snapshot) { applyGlobalSnapshot(snapshot); }
        });
        this.audioProfileRepository = new FileAudioProfileRepository();
        this.applicationState = new ApplicationStateService();
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            public void windowClosed(WindowEvent event) {
                performShutdown();
            }
        });
        setSize(1180, 820);
        setMinimumSize(new Dimension(980, 680));
        setLocationRelativeTo(null);
        buildUserInterface();
        showScreen(CHAT_VIEW);
        catalogRefreshService.refresh(); // initial catalog load, distributed to the chat + batch panels
    }

    /**
     * The single shutdown path (window close OR File &gt; Beenden). Tears the plugin/runtime down gracefully and
     * — crucially — stops the process-global Solon MCP server synchronously via {@link AgentRuntimeServices}.
     * Solon's HTTP server runs a NON-daemon {@code HTTP-Dispatcher} thread that is otherwise never released, so
     * once research has booted Solon the JVM cannot exit naturally (the app hung on close). Stopping it here lets
     * every remaining thread (EDT after dispose, the plugin-shutdown worker once it completes) finish so the JVM
     * exits on its own — no {@code System.exit}/{@code halt} needed. Idempotent.
     */
    private void performShutdown() {
        if (!shuttingDown.compareAndSet(false, true)) {
            return; // idempotent
        }
        shutdownLog("performShutdown begin");
        if (chatWorkspaceHost != null) {
            try {
                chatWorkspaceHost.shutdown();
            } catch (RuntimeException ex) {
                shutdownLog("chatWorkspaceHost.shutdown() threw: " + ex);
            }
        }
        if (chatWorkspace != null) {
            for (ChatSessionComponent session : chatWorkspace.sessions()) {
                session.disposeSession();
            }
        }
        if (batchPanel != null) {
            batchPanel.dispose();
        }
        // A running recording must be finalized, not left corrupt, when AskAI exits.
        if (videoRecordingController != null) {
            videoRecordingController.stopIfRecordingForShutdown();
        }
        askAiService.shutdown();
        // Release the non-daemon Solon HTTP-Dispatcher so the JVM can terminate naturally (see method javadoc).
        if (agentRuntimeServices != null) {
            agentRuntimeServices.shutdown();
        }
        shutdownLog("performShutdown done (JVM will exit naturally)");
    }

    /** Kept for the existing launcher: builds the frame and makes it visible. */
    public void showFrame() {
        setVisible(true);
    }

    private void buildUserInterface() {
        // The comic design language's global part: the active theme's tokens for all standard
        // Swing components (surfaces, fields, tables, tabs, …), the comic button/scrollbar
        // delegates, and the menu/popup contours (issue #36).
        this.titleBar = new AskAiTitleBar(getTitle(),
                () -> setState(java.awt.Frame.ICONIFIED),
                () -> dispatchEvent(new java.awt.event.WindowEvent(this, java.awt.event.WindowEvent.WINDOW_CLOSING)));
        // Title bar ABOVE the menu bar, both inside the frameless window's ink contour.
        JPanel header = new JPanel();
        header.setLayout(new javax.swing.BoxLayout(header, javax.swing.BoxLayout.Y_AXIS));
        header.setOpaque(false);
        titleBar.setAlignmentX(LEFT_ALIGNMENT);
        // Right-aligned in the title bar, left of the close chip: the Text | Audio toggle, the connection dot
        // (address, status and version in its hover popup; click refreshes), models, gear and help.
        connectionStatusView.setCompact(true);
        titleBar.addTrailingControl(createModeToggle());
        titleBar.addTrailingControl(connectionStatusView);
        titleBar.addTrailingControl(createModelsButton());
        titleBar.addTrailingControl(createSettingsGear());
        titleBar.addTrailingControl(createHelpButton());
        header.add(titleBar);
        JPanel windowSurface = new JPanel(new BorderLayout());
        windowSurface.add(header, BorderLayout.NORTH);
        windowSurface.add(createContentPanel(), BorderLayout.CENTER);
        setContentPane(windowSurface);
        com.aresstack.comiccontrols.control.ComicWindowShape.install(this, windowSurface,
                com.aresstack.comiccontrols.theme.ComicPalette.defaultPalette(), WINDOW_PADDING,
                com.aresstack.comiccontrols.theme.ResearchUiMetrics.RADIUS_WINDOW);
        com.aresstack.comiccontrols.control.ComicWindowResizer.install(windowSurface, RESIZE_GRIP);
        com.aresstack.comiccontrols.control.ComicWindowDragger.install(titleBar);
    }

    /** The frameless window's ink-contour padding (doubles as the resize grip zone). */
    private static final int WINDOW_PADDING = 4;
    private static final int RESIZE_GRIP = 6;

    /** AskAI's own title bar (the window is frameless). */
    private AskAiTitleBar titleBar;

    @Override
    public void setTitle(String title) {
        super.setTitle(title);
        if (titleBar != null) {
            titleBar.setTitle(title);
        }
    }

    /** The title bar's main switch: Text (the chat) | Audio (batch transcription, audio processing). */
    private com.aresstack.comiccontrols.control.ComicSegmentedToggle modeToggle;
    private static final int SLIM_TOGGLE_HEIGHT = 12;

    private javax.swing.JComponent createModeToggle() {
        modeToggle = new com.aresstack.comiccontrols.control.ComicSegmentedToggle("Text", "Audio");
        // slim, as it looked in the old navigation slot (squeezed to 12 px there)
        modeToggle.setToggleHeight(SLIM_TOGGLE_HEIGHT);
        modeToggle.setToolTipText("Text: the chat · Audio: batch transcription and audio processing");
        modeToggle.setSelectionListener(index -> showScreen(index == 0 ? CHAT_VIEW : AUDIO_SCREEN));
        return modeToggle;
    }

    private void markNavigation(String screenName) {
        if (modeToggle == null) {
            return;
        }
        if (CHAT_VIEW.equals(screenName)) {
            modeToggle.setSelectedIndex(0);
        } else if (AUDIO_SCREEN.equals(screenName)) {
            modeToggle.setSelectedIndex(1);
        }
    }

    // ------------------------------------------------------------------ drawer screens (Audio, Models)

    /** The Audio screen: drawer with Batch transcription and Audio processing. */
    private static final String AUDIO_SCREEN = "audio-screen";
    /** The Models screen: drawer with Setup, Installed and Running Models, "Back" in the footer. */
    private static final String MODELS_SCREEN = "models-screen";
    private static final String PAGE_BATCH = "Batch transcription";
    private static final String PAGE_AUDIO_PROCESSING = "Audio processing";
    private static final String PAGE_SETUP = "Setup";
    private static final String PAGE_INSTALLED = "Installed";
    private static final String PAGE_RUNNING = "Running Models";
    private com.aresstack.askai.java8.ui.chat.DrawerScreen audioScreen;
    private com.aresstack.askai.java8.ui.chat.DrawerScreen modelsScreen;
    /** The last Text/Audio screen — where the Models drawer's "Back" returns to. */
    private String lastMainScreen = CHAT_VIEW;

    private com.aresstack.askai.java8.ui.chat.DrawerScreen audioScreen() {
        if (audioScreen == null) {
            audioScreen = new com.aresstack.askai.java8.ui.chat.DrawerScreen("AUDIO");
            audioScreen.addPage(PAGE_BATCH, new JPanel(), null); // placeholder: fixes the page order
            contentPanel.add(audioScreen, AUDIO_SCREEN);
        }
        return audioScreen;
    }

    private com.aresstack.askai.java8.ui.chat.DrawerScreen modelsScreen() {
        if (modelsScreen == null) {
            modelsScreen = new com.aresstack.askai.java8.ui.chat.DrawerScreen("MODELS");
            modelsScreen.addPage(PAGE_SETUP, new JPanel(), null); // placeholder: fixes the page order
            modelsScreen.setBackAction("Back", () -> showScreen(lastMainScreen));
            contentPanel.add(modelsScreen, MODELS_SCREEN);
        }
        return modelsScreen;
    }

    /** The round blue "?" (MainframeMate's help button): the help entries as a menu. */
    private javax.swing.JComponent createHelpButton() {
        final com.aresstack.comiccontrols.control.ComicHelpButton help =
                new com.aresstack.comiccontrols.control.ComicHelpButton(24, "Help");
        help.addActionListener(event -> {
            javax.swing.JPopupMenu menu = createHelpPopup();
            menu.show(help, help.getWidth() - menu.getPreferredSize().width, help.getHeight());
        });
        return help;
    }

    private javax.swing.JPopupMenu createHelpPopup() {
        javax.swing.JPopupMenu menu = new javax.swing.JPopupMenu();
        menu.add(createScreenItem("Actions", ACTIONS_VIEW));
        JMenuItem recordVideo = new JMenuItem("Record Video...");
        recordVideo.addActionListener(event -> openRecordVideoDialog());
        menu.add(recordVideo);
        menu.addSeparator();
        menu.add(createScreenItem("About", ABOUT_VIEW));
        return menu;
    }

    /** The models button (cube glyph, the gear's size): Setup / Installed / Running Models as a menu. */
    private javax.swing.JComponent createModelsButton() {
        final com.aresstack.comiccontrols.control.ComicButton models = new com.aresstack.comiccontrols.control.ComicButton(
                new ScaledIcon(com.aresstack.askai.java8.ui.ChatComposerPanel.createModelsGlyphIcon(), 20));
        models.setContentAreaFilled(false);
        models.setFocusable(false);
        models.setMargin(new java.awt.Insets(0, 0, 0, 0));
        models.setPreferredSize(new Dimension(24, 24));
        models.setToolTipText("Models: setup, installed, running");
        models.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        models.addActionListener(event -> showScreen(MODELS_SCREEN)); // the Models drawer
        models.setMaximumSize(models.getPreferredSize());
        return models;
    }

    /** The gear's menu: chat settings first, then the configuration screens. */
    private javax.swing.JPopupMenu createSettingsMenu() {
        javax.swing.JPopupMenu menu = new javax.swing.JPopupMenu();
        JMenuItem chatSettings = new JMenuItem("Chat settings…");
        chatSettings.addActionListener(event -> {
            if (chatWorkspace != null) {
                showScreen(CHAT_VIEW); // the settings live in the chat workspace (drawer + main area)
                chatWorkspace.openActiveChatSettings();
            }
        });
        menu.add(chatSettings);
        menu.addSeparator();
        menu.add(createScreenItem("Connections", CONNECTIONS_VIEW));
        menu.add(createScreenItem("AI models", AI_MODELS_VIEW));
        menu.add(createScreenItem("Network", NETWORK_VIEW));
        menu.add(createScreenItem("Plugins", PLUGINS_VIEW));
        return menu;
    }

    /** An icon drawn scaled to {@code size} pixels (the 15px stroke glyphs at title-bar size). */
    private static final class ScaledIcon implements javax.swing.Icon {
        private final javax.swing.Icon icon;
        private final int size;

        ScaledIcon(javax.swing.Icon icon, int size) {
            this.icon = icon;
            this.size = size;
        }

        public int getIconWidth() {
            return size;
        }

        public int getIconHeight() {
            return size;
        }

        public void paintIcon(java.awt.Component c, java.awt.Graphics g, int x, int y) {
            java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
            try {
                g2.translate(x, y);
                g2.scale(size / (double) icon.getIconWidth(), size / (double) icon.getIconHeight());
                icon.paintIcon(c, g2, 0, 0);
            } finally {
                g2.dispose();
            }
        }
    }

    /** The flat title-bar gear (glyph only, ink, the close chip's size); opens the settings menu. */
    private javax.swing.JComponent createSettingsGear() {
        com.aresstack.comiccontrols.control.ComicButton gear = new com.aresstack.comiccontrols.control.ComicButton(
                com.aresstack.askai.java8.ui.ChatComposerPanel.createGearGlyphIcon());
        gear.setContentAreaFilled(false); // flat: just the glyph in the menu bar
        gear.setFocusable(false);
        gear.setIcon(new ScaledIcon(com.aresstack.askai.java8.ui.ChatComposerPanel.createGearGlyphIcon(), 20));
        gear.setMargin(new java.awt.Insets(0, 0, 0, 0));
        gear.setPreferredSize(new Dimension(24, 24)); // the size of the close chip beside it
        gear.setToolTipText("Settings and configuration");
        gear.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        // The gear is THE settings menu: the chat settings dialog plus the configuration screens
        // (formerly the "Configuration" menu). Built per click, so it always wears the current theme.
        gear.addActionListener(event -> {
            javax.swing.JPopupMenu menu = createSettingsMenu();
            menu.show(gear, gear.getWidth() - menu.getPreferredSize().width, gear.getHeight());
        });
        gear.setMaximumSize(gear.getPreferredSize()); // never stretched by the menu-bar layout
        return gear;
    }

    /** Load installed model names for the chat model list (blocking; runs off the EDT in the refresh service). */
    private List<String> loadInstalledModelNames() throws Exception {
        // Feeds the CHAT dropdown catalogs: local models only when they can actually chat.
        return ((VirtualOllamaContainerService) ollamaService).loadChatModelNamesNow();
    }

    /** Load only the audio-capable model names (exact {@code audio} capability), same rule as the batch list. */
    private List<String> loadAudioCapableModelNames() throws Exception {
        VirtualOllamaContainerService virtual = (VirtualOllamaContainerService) ollamaService;
        List<String> audioModels = new ArrayList<String>();
        for (OllamaModelInfo installed : virtual.loadInstalledModelsNow()) {
            String name = installed.getDisplayName();
            try {
                if (AudioCapability.isAudioCapable(
                        virtual.loadModelInfoNow(name).getCapabilities())) {
                    audioModels.add(name);
                }
            } catch (Exception ignored) {
                // Skip a model we cannot query; UNKNOWN capabilities never count as audio.
            }
        }
        return audioModels;
    }

    /** Distribute a global catalog snapshot to every open chat tab and the batch panel (on the EDT). */
    private void applyGlobalSnapshot(GlobalCatalogSnapshot snapshot) {
        connectionStatusView.setRefreshEnabled(true);
        if (chatWorkspace != null) {
            for (ChatSessionComponent session : chatWorkspace.sessions()) {
                if (session instanceof OllamaChatPanel) {
                    ((OllamaChatPanel) session).applyCatalogSnapshot(snapshot);
                }
            }
        }
        if (batchPanel != null) {
            batchPanel.applyCatalogSnapshot(snapshot);
        }
        refreshConnectionStatus();
    }

    /** Shows the Connections view and puts the cursor in its Base URL field, selected for overwrite. */
    private void openConnectionSettings() {
        showScreen(CONNECTIONS_VIEW);
        if (configPanel != null) {
            configPanel.focusBaseUrl();
        }
    }

    /**
     * Refreshes the top-right connection indicator: shows "Connecting…" then pings the server off the
     * EDT, mapping a version response to "Connected" and an error to "Not reachable" (URL and detail
     * kept in the tooltip). Cheap enough to run on view switches.
     */
    private void refreshConnectionStatus() {
        final String url = model.getOllamaBaseUrl();
        connectionStatusView.setStatus(ConnectionStatus.CONNECTING, url, "");
        ollamaService.getServerVersion(new OllamaService.ServerVersionListener() {
            @Override
            public void onServerVersion(final String version) {
                onUi(new Runnable() {
                    public void run() {
                        connectionStatusView.setStatus(ConnectionStatus.forVersion(version), url,
                                version == null || version.isEmpty() ? "" : "version " + version);
                    }
                });
            }

            @Override
            public void onError(final Exception ex) {
                onUi(new Runnable() {
                    public void run() {
                        connectionStatusView.setStatus(ConnectionStatus.NOT_REACHABLE, url,
                                ex.getMessage() == null ? ex.toString() : ex.getMessage());
                    }
                });
            }
        });
    }

    /** Route a plugin's overlay request (toolbar button or /map) to the ACTIVE chat's transcript. */
    private void showOverlayOnActiveChat(final javax.swing.JComponent content, final String title) {
        onUi(new Runnable() {
            public void run() {
                ChatSessionComponent active = chatWorkspace == null ? null
                        : chatWorkspace.activeSession();
                if (active instanceof OllamaChatPanel) {
                    ((OllamaChatPanel) active).showTranscriptOverlay(content, title);
                }
            }
        });
    }

    /** Same routing for a Mermaid SOURCE — the chat panel embeds the full diagram viewer. */
    private void showDiagramOverlayOnActiveChat(final String mermaidSource, final String title) {
        onUi(new Runnable() {
            public void run() {
                ChatSessionComponent active = chatWorkspace == null ? null
                        : chatWorkspace.activeSession();
                if (active instanceof OllamaChatPanel) {
                    ((OllamaChatPanel) active).showDiagramOverlay(mermaidSource, title);
                }
            }
        });
    }

    private static void onUi(Runnable runnable) {
        if (javax.swing.SwingUtilities.isEventDispatchThread()) {
            runnable.run();
        } else {
            javax.swing.SwingUtilities.invokeLater(runnable);
        }
    }

    /** The general-purpose video recorder (first entry point: Help). Works over the abstract recording layer. */
    private void openRecordVideoDialog() {
        if (videoRecordingController == null) {
            videoRecordingController = new com.aresstack.askai.java8.video.VideoRecordingController(
                    com.aresstack.askai.java8.video.MediaRecorderProviders.defaults());
        }
        String fileName = "askai-recording-" + videoTimestamp() + ".mp4";
        // Neutral source: only the frame's screen bounds are handed over, never the AskAiFrame itself.
        com.aresstack.askai.java8.video.ui.RecordVideoDialog dialog =
                new com.aresstack.askai.java8.video.ui.RecordVideoDialog(
                        this, videoRecordingController, getBounds(), fileName);
        dialog.setVisible(true);
    }

    private static String videoTimestamp() {
        return new java.text.SimpleDateFormat("yyyy-MM-dd-HHmmss").format(new java.util.Date());
    }

    private JMenuItem createScreenItem(String title, String screenName) {
        JMenuItem item = new JMenuItem(title);
        item.addActionListener(event -> showScreen(screenName));
        return item;
    }

    /**
     * Builds the generic chat workspace host around the existing chat panel. The host discovers workspace
     * plugins from a controlled root (overridable via {@code -Daskai.pluginsDir=...}), off the EDT, and wraps
     * AskAI's Markdown/bubble UI as host services. The research plugin is never a compile dependency.
     */
    private com.aresstack.askai.plugin.host.ChatWorkspaceHostPanel buildChatWorkspaceHost(
            ChatWorkspacePanel chatTabs) {
        migrateLegacyChatModeState();
        String override = System.getProperty("askai.pluginsDir");
        java.nio.file.Path pluginsRoot = override != null && override.trim().length() > 0
                ? java.nio.file.Paths.get(override.trim())
                : com.aresstack.askai.java8.settings.AskAiPaths.appDirectory().resolve("plugins");
        try {
            java.nio.file.Files.createDirectories(pluginsRoot);
        } catch (java.io.IOException ignored) {
            // A missing plugin dir just yields an empty catalog; Normal Chat still works.
        }

        java.io.File dataDir = com.aresstack.askai.java8.settings.AskAiPaths.appDirectory().toFile();
        com.aresstack.askai.plugin.host.SwingUiExecutor uiExecutor =
                new com.aresstack.askai.plugin.host.SwingUiExecutor();
        com.aresstack.askai.java8.plugin.host.AskAiNotificationService notificationService =
                new com.aresstack.askai.java8.plugin.host.AskAiNotificationService();
        com.aresstack.askai.plugin.host.WorkspaceHostContextFactory hostContextFactory =
                new com.aresstack.askai.plugin.host.WorkspaceHostContextFactory(
                        dataDir, uiExecutor,
                        new com.aresstack.askai.java8.plugin.host.AskAiThemeService(),
                        new com.aresstack.askai.java8.plugin.host.AskAiMarkdownViewFactory(),
                        new com.aresstack.askai.java8.plugin.host.AskAiConversationSurfaceFactory(),
                        notificationService);
        com.aresstack.askai.plugin.api.service.WorkspaceStateStore hostState =
                new com.aresstack.askai.java8.plugin.host.ApplicationStateWorkspaceStateStore(applicationState, "");
        this.pluginEnablement = new com.aresstack.askai.plugin.host.PluginEnablementService(hostState);
        this.pluginsDirectory = pluginsRoot.toFile();
        com.aresstack.askai.plugin.host.WorkspacePluginService pluginService =
                new com.aresstack.askai.plugin.host.WorkspacePluginService(pluginsRoot, HOST_PLUGIN_VERSION, 1,
                        uiExecutor, pluginEnablement);
        this.pluginService = pluginService;
        com.aresstack.askai.plugin.host.ChatWorkspaceHostPanel host =
                new com.aresstack.askai.plugin.host.ChatWorkspaceHostPanel(
                        chatTabs, pluginService, hostContextFactory, uiExecutor, hostState, notificationService);
        // The controls factory needs the mode controller, which the host panel is; wire it now so agent
        // workspaces can embed a reusable Yapping/Questing + agent selector bound to the same controller.
        hostContextFactory.setInteractionModeControlsFactory(
                new com.aresstack.askai.plugin.host.DefaultInteractionModeControlsFactory(host));
        // Controller = single source of truth: every chat tab's composer selector is bound to it in
        // wireChatTabToAgentHost (per tab, because tabs come and go).

        // New agent model (Commit 11): Questing routes to an agent session over the SHARED chat/composer.
        // The agent's activity is pushed into the transcript of the chat tab that is ACTIVE when the agent
        // session is created (the sink is resolved per session, not captured once); the composer routes
        // plain prompts + stop to the active session. Yapping keeps the Ollama path.
        final com.aresstack.askai.plugin.host.SwingUiExecutor agentUiExecutor = uiExecutor;
        final com.aresstack.askai.java8.plugin.host.AskAiNotificationService agentNotify = notificationService;
        final java.io.File agentDataDir = dataDir;
        final com.aresstack.askai.plugin.host.WorkspacePluginService agentPluginService = pluginService;
        com.aresstack.askai.plugin.host.AgentSessionCoordinator.AgentExtensionResolver agentResolver =
                new com.aresstack.askai.plugin.host.AgentSessionCoordinator.AgentExtensionResolver() {
                    public com.aresstack.askai.plugin.pf4j.api.AgentPluginExtension resolve(String agentId) {
                        return agentPluginService.getSelectableAgentExtension(agentId);
                    }
                };
        // Host-owned runtime services for the productive research mode (lazy Solon MCP runtime, model
        // snapshot providers). Plugins look them up via AgentHostContext.getService.
        final com.aresstack.askai.java8.plugin.host.AgentRuntimeServices agentRuntimeServices =
                new com.aresstack.askai.java8.plugin.host.AgentRuntimeServices(
                        askAiService.localRuntimeManager(), configurationRepository);
        this.agentRuntimeServices = agentRuntimeServices; // stopped synchronously in performShutdown()
        // Belt-and-suspenders for non-window exits (Ctrl-C etc.); idempotent with the direct call on close.
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            public void run() {
                agentRuntimeServices.shutdown();
            }
        }, "agent-runtime-services-shutdown"));
        // Central model change → re-publish the running research sessions' descriptors (no direct UI→research
        // coupling: the event flows AskAiModel → this listener → the host session registry).
        final com.aresstack.askai.java8.localmodels.LocalActiveResearchSessionRegistry activeResearchSessions =
                agentRuntimeServices.activeSessionRegistry();
        if (activeResearchSessions != null) {
            model.addAiModelSelectionListener(new com.aresstack.askai.java8.AiModelSelectionListener() {
                public void onMainModelChanged() {
                    activeResearchSessions.refreshInference();
                }

                public void onRerankerOrEmbeddingsChanged() {
                    activeResearchSessions.refreshReranker();
                }
            });
        }
        // The typed "open sources/config view" card actions reach the artifact area through this host
        // service (same hook as /open); the coordinator holder is filled right below.
        final com.aresstack.askai.plugin.host.AgentSessionCoordinator[] coordinatorRef =
                new com.aresstack.askai.plugin.host.AgentSessionCoordinator[1];
        com.aresstack.askai.plugin.host.AgentSessionCoordinator.AgentHostContextProvider agentHostProvider =
                new com.aresstack.askai.plugin.host.AgentSessionCoordinator.AgentHostContextProvider() {
                    public com.aresstack.askai.plugin.api.agent.AgentHostContext create(
                            String agentId, String sessionInstanceId) {
                        java.util.Map<Class<?>, Object> services =
                                new java.util.LinkedHashMap<Class<?>, Object>(
                                        agentRuntimeServices.asServiceMap());
                        // READ-ONLY view of the chat tabs: which one is selected, what each is called.
                        // A plugin needs it to publish its sessions under the host's own chat ids.
                        if (chatSessionCatalog != null) {
                            services.put(com.aresstack.askai.plugin.api.service.ChatSessionCatalog.class,
                                    chatSessionCatalog);
                        }
                        // The persisted messages of a chat: a SEPARATE port (ISP) over the same store, so a
                        // plugin can project the canonical conversation instead of keeping its own copy.
                        if (chatSessionHistory != null) {
                            services.put(
                                    com.aresstack.askai.plugin.api.service.ChatSessionHistoryReader.class,
                                    chatSessionHistory);
                        }
                        // The only WRITING chat port: create a chat + activate an agent in it. Unlike the
                        // read ports this one deliberately changes what the user sees.
                        if (chatSessionLauncher != null) {
                            services.put(com.aresstack.askai.plugin.api.service.ChatSessionLauncher.class,
                                    chatSessionLauncher);
                        }
                        services.put(com.aresstack.askai.plugin.api.service.ArtifactViewOpener.class,
                                new com.aresstack.askai.plugin.api.service.ArtifactViewOpener() {
                                    public void openArtifact(String artifactId) {
                                        if (coordinatorRef[0] != null) {
                                            coordinatorRef[0].openArtifactView(artifactId);
                                        }
                                    }
                                });
                        // Narrow streaming inference for in-process plugins (e.g. the research narrator):
                        // the existing chat stack + the centrally selected main model, read per call.
                        services.put(com.aresstack.askai.agent.model.inference.AgentInferencePort.class,
                                new com.aresstack.askai.java8.plugin.host.OllamaAgentInferencePort(
                                        ollamaService,
                                        new com.aresstack.askai.java8.plugin.host
                                                .OllamaAgentInferencePort.ModelSource() {
                                            public String mainModelName() {
                                                return configurationRepository.load()
                                                        .getAiModelSelections().getMainModel();
                                            }
                                        }));
                        return new com.aresstack.askai.plugin.host.DefaultAgentHostContext(
                                agentUiExecutor,
                                new com.aresstack.askai.java8.plugin.host.AskAiThemeService(),
                                new com.aresstack.askai.java8.plugin.host.AskAiMarkdownViewFactory(),
                                agentNotify,
                                // SESSION-scoped settings, last-setting-wins: a NEW chat starts from
                                // the user's latest choice (write-through template), an EXISTING chat is
                                // frozen at first use and keeps its own values.
                                new com.aresstack.askai.plugin.host.SessionScopedWorkspaceStateStore(
                                        new com.aresstack.askai.java8.plugin.host
                                                .ApplicationStateWorkspaceStateStore(
                                                applicationState, "agent." + agentId + "."),
                                        new com.aresstack.askai.java8.plugin.host
                                                .ApplicationStateWorkspaceStateStore(
                                                applicationState, "agent." + agentId + ".session."
                                                        + sessionInstanceId + ".")),
                                new com.aresstack.askai.plugin.host.ScopedPluginPathService(agentDataDir, agentId),
                                resolveAgentSinkForSessionKey(sessionInstanceId),
                                services);
                    }
                };
        // PER-TAB session scope: the session id (and thus its project directory + sink) is derived from the
        // ACTIVE chat tab's ChatSessionId, so two tabs of the same agent get distinct research sessions and a
        // closed tab's session is never resumed by a fresh tab. Falls back to "session" when no tab is active.
        final com.aresstack.askai.plugin.host.AgentSessionCoordinator.SessionScopeProvider sessionScope =
                new com.aresstack.askai.plugin.host.AgentSessionCoordinator.SessionScopeProvider() {
                    public String currentScope() {
                        OllamaChatPanel chat = activeChat();
                        return chat != null ? chat.getSessionId().toString() : "session";
                    }
                };
        final com.aresstack.askai.plugin.host.AgentSessionCoordinator agentCoordinator =
                new com.aresstack.askai.plugin.host.AgentSessionCoordinator(
                        agentResolver, agentHostProvider, uiExecutor, sessionScope);
        coordinatorRef[0] = agentCoordinator;
        // Transactional refresh: the coordinator detaches the outgoing generation's sessions on the EDT and the
        // service closes them off-EDT before retiring the old classloaders, so no session survives a swap.
        pluginService.setGenerationSwapHook(agentCoordinator);
        host.setAgentSessionCoordinator(agentCoordinator);
        this.agentCoordinator = agentCoordinator;
        // The ACTIVE agent's artifact views live in the LEFT chat sidebar (the old right-hand artifact
        // area is gone): tabs exist exactly while an agent session is active (e.g. Research in Questing)
        // and disappear otherwise. Views are cached per coordinator change, so editing state survives
        // drawer open/close; /open reveals the matching pane latched.
        final com.aresstack.askai.plugin.host.AgentArtifactTabs artifactTabs =
                new com.aresstack.askai.plugin.host.AgentArtifactTabs(agentCoordinator, uiExecutor,
                        new com.aresstack.askai.java8.plugin.host.AskAiThemeService(),
                        new com.aresstack.askai.java8.plugin.host.AskAiMarkdownViewFactory());
        // The artifact views (e.g. Concept/Sources) open the same overlays as the toolbar does.
        artifactTabs.setOverlayHost(new com.aresstack.askai.plugin.host.AgentOverlayHost() {
            public void showTranscriptOverlay(javax.swing.JComponent content, String title) {
                showOverlayOnActiveChat(content, title);
            }

            public void showDiagramOverlay(String mermaidSource, String title) {
                showDiagramOverlayOnActiveChat(mermaidSource, title);
            }

            @Override
            public void setMainContent(String ownerTabTitle, javax.swing.JComponent content) {
                chatTabs.setMainContent(ownerTabTitle, content);
            }
        });
        chatTabs.setSidebarTabContributions(
                new java.util.function.Supplier<java.util.List<com.aresstack.askai.java8.ui.sidebar.ChatSidebarTab>>() {
                    public java.util.List<com.aresstack.askai.java8.ui.sidebar.ChatSidebarTab> get() {
                        java.util.List<com.aresstack.askai.java8.ui.sidebar.ChatSidebarTab> tabs =
                                new java.util.ArrayList<com.aresstack.askai.java8.ui.sidebar.ChatSidebarTab>();
                        for (final com.aresstack.askai.plugin.host.AgentArtifactTabs.Tab tab : artifactTabs.tabs()) {
                            tabs.add(new com.aresstack.askai.java8.ui.sidebar.ChatSidebarTab() {
                                public String getTitle() {
                                    return tab.getTitle();
                                }

                                public javax.swing.JComponent getComponent() {
                                    return tab.getComponent();
                                }
                            });
                        }
                        return tabs;
                    }
                });
        artifactTabs.addChangeListener(new Runnable() {
            public void run() {
                chatTabs.refreshSidebarTabs(); // keeps an open drawer honest on agent switch
            }
        });
        // /open <artifact> reveals the matching sidebar pane (latched, so it stays put).
        agentCoordinator.setArtifactOpener(new com.aresstack.askai.plugin.host.AgentSessionCoordinator.ArtifactOpener() {
            public void open(String artifactId) {
                com.aresstack.askai.plugin.host.AgentArtifactTabs.Tab tab = artifactTabs.tabForArtifact(artifactId);
                if (tab != null) {
                    chatTabs.openSidebarTab(tab.getTitle());
                }
            }
        });
        // Overlays (e.g. /map's mindmap) land over the ACTIVE chat's transcript.
        agentCoordinator.setTranscriptOverlayHost(
                new com.aresstack.askai.plugin.host.AgentSessionCoordinator.TranscriptOverlayHost() {
                    public void show(javax.swing.JComponent content, String title) {
                        showOverlayOnActiveChat(content, title);
                    }

                    public void showDiagram(String mermaidSource, String title) {
                        showDiagramOverlayOnActiveChat(mermaidSource, title);
                    }
                });
        // Generic composer accessories: place the ACTIVE agent's accessory above the active tab's composer.
        // The area rebuilds on every coordinator change (tab/agent switch); the frame routes it to the active
        // tab and clears the previously-targeted one.
        final com.aresstack.askai.plugin.host.ComposerAccessoryHost accessoryHost =
                new com.aresstack.askai.plugin.host.ComposerAccessoryHost() {
                    public void setAccessory(javax.swing.JComponent component) {
                        OllamaChatPanel active = activeChat();
                        if (accessoryTargetTab != null && accessoryTargetTab != active) {
                            accessoryTargetTab.clearComposerAccessory();
                        }
                        if (active != null) {
                            active.setComposerAccessory(component);
                            accessoryTargetTab = active;
                        } else {
                            accessoryTargetTab = null;
                        }
                    }

                    public void clearAccessory() {
                        if (accessoryTargetTab != null) {
                            accessoryTargetTab.clearComposerAccessory();
                            accessoryTargetTab = null;
                        }
                    }

                    public void setBelowAccessory(javax.swing.JComponent component) {
                        OllamaChatPanel active = activeChat();
                        if (belowAccessoryTargetTab != null && belowAccessoryTargetTab != active) {
                            belowAccessoryTargetTab.clearBelowComposerAccessory();
                        }
                        if (active != null) {
                            active.setBelowComposerAccessory(component);
                            belowAccessoryTargetTab = active;
                        } else {
                            belowAccessoryTargetTab = null;
                        }
                    }

                    public void clearBelowAccessory() {
                        if (belowAccessoryTargetTab != null) {
                            belowAccessoryTargetTab.clearBelowComposerAccessory();
                            belowAccessoryTargetTab = null;
                        }
                    }

                    public void setTranscriptAccessory(javax.swing.JComponent component) {
                        OllamaChatPanel active = activeChat();
                        if (skyAccessoryTargetTab != null && skyAccessoryTargetTab != active) {
                            skyAccessoryTargetTab.clearTranscriptSkyAccessory();
                        }
                        if (active != null) {
                            active.setTranscriptSkyAccessory(component);
                            skyAccessoryTargetTab = active;
                        } else {
                            skyAccessoryTargetTab = null;
                        }
                    }

                    public void clearTranscriptAccessory() {
                        if (skyAccessoryTargetTab != null) {
                            skyAccessoryTargetTab.clearTranscriptSkyAccessory();
                            skyAccessoryTargetTab = null;
                        }
                    }

                    public void setComposerPlaceholder(String placeholderOrNull) {
                        if (accessoryTargetTab != null) {
                            accessoryTargetTab.setComposerPlaceholder(placeholderOrNull);
                        }
                    }
                };
        this.composerAccessoryArea = new com.aresstack.askai.plugin.host.AgentComposerAccessoryArea(
                agentCoordinator, uiExecutor,
                new com.aresstack.askai.java8.plugin.host.AskAiThemeService(),
                new com.aresstack.askai.java8.plugin.host.AskAiMarkdownViewFactory(),
                accessoryHost);
        // Generic agent toolbar contributions (left of the workspace gear): same coordinator-driven
        // lifecycle as the composer accessories; the workspace only hosts the slot.
        new com.aresstack.askai.plugin.host.AgentToolbarArea(
                agentCoordinator, uiExecutor,
                new com.aresstack.askai.java8.plugin.host.AskAiThemeService(),
                new com.aresstack.askai.plugin.host.AgentToolbarHost() {
                    public void setToolbar(javax.swing.JComponent component) {
                        chatTabs.setAgentToolbar(component);
                    }

                    public void clearToolbar() {
                        chatTabs.clearAgentToolbar();
                    }

                    public void setCenterToolbar(javax.swing.JComponent component) {
                        chatTabs.setAgentCenterToolbar(component);
                    }

                    public void clearCenterToolbar() {
                        chatTabs.clearAgentCenterToolbar();
                    }

                    public void setFooterToolbar(javax.swing.JComponent component) {
                        chatTabs.setAgentFooterToolbar(component);
                    }

                    public void clearFooterToolbar() {
                        chatTabs.clearAgentFooterToolbar();
                    }

                    public void setLeadingToolbar(javax.swing.JComponent component) {
                        chatTabs.setAgentLeadingToolbar(component);
                    }

                    public void clearLeadingToolbar() {
                        chatTabs.clearAgentLeadingToolbar();
                    }

                    public void showTranscriptOverlay(javax.swing.JComponent content, String title) {
                        showOverlayOnActiveChat(content, title);
                    }

                    public void showDiagramOverlay(String mermaidSource, String title) {
                        showDiagramOverlayOnActiveChat(mermaidSource, title);
                    }
                });
        // The ACTIVE plugin may brand the hamburger with its own glyph (hover restores ☰); the
        // footer's mode pill follows the shared controller through the same change stream.
        final com.aresstack.askai.plugin.host.AgentSessionCoordinator brandingCoordinator =
                agentCoordinator;
        agentCoordinator.addChangeListener(() -> uiExecutor.execute(() ->
                chatTabs.setMenuOverrideIcon(brandingCoordinator.getActiveMenuIcon())));
        chatTabs.setMenuOverrideIcon(agentCoordinator.getActiveMenuIcon());
        // The title-bar search follows the active agent: its contribution defines the search, no agent = hidden.
        final Runnable bindTitleSearch = () -> titleBar.bindSearch(
                brandingCoordinator.getActiveSearchContribution(), brandingCoordinator::getActiveSession);
        agentCoordinator.addChangeListener(() -> uiExecutor.execute(bindTitleSearch));
        bindTitleSearch.run();
        chatTabs.setWorkspaceModeController(host); // the host panel IS the mode controller
        return host;
    }

    /**
     * Binds one chat tab's composer to the plugin host: Yapping/Questing mode selector, agent submission
     * routing and slash commands. Restored tabs are wired right after the host is built; later tabs by
     * the session factory (no-op until the host exists).
     */
    private void wireChatTabToAgentHost(OllamaChatPanel chat) {
        if (chatWorkspaceHost == null || agentCoordinator == null) {
            return;
        }
        chat.setWorkspaceModeController(chatWorkspaceHost);
        chat.setChatSubmissionRouter(agentCoordinator);
        // Gear menu: the ACTIVE agent's settings pages, session-based (the dialog opens from the active
        // tab, whose session is the coordinator's active one).
        chat.setAgentSettingsSource(
                new java.util.function.Supplier<java.util.List<
                        com.aresstack.askai.plugin.api.agent.AgentSettingsContribution>>() {
                    public java.util.List<com.aresstack.askai.plugin.api.agent.AgentSettingsContribution>
                            get() {
                        return agentCoordinator.getActiveSettingsContributions();
                    }
                },
                new java.util.function.Supplier<com.aresstack.askai.plugin.api.agent.AgentSession>() {
                    public com.aresstack.askai.plugin.api.agent.AgentSession get() {
                        return agentCoordinator.getActiveSession();
                    }
                });
        chat.setAgentCommandRegistry(agentCoordinator);
        // The chat list's activity dot/metadata: authoritative per-scope runtime state, no UI heuristics.
        final com.aresstack.askai.plugin.host.AgentSessionCoordinator activityCoordinator = agentCoordinator;
        final String activityScope = chat.getSessionId().toString();
        chat.setAgentActivityProbes(
                () -> activityCoordinator.isScopeBusy(activityScope),
                () -> activityCoordinator.scopePhaseLabel(activityScope));
        // Tab close ENDS this tab's agent session(s) off-EDT (its ChatSessionId is the scope).
        final com.aresstack.askai.plugin.host.AgentSessionCoordinator closerCoordinator = agentCoordinator;
        chat.setTabSessionCloser(new OllamaChatPanel.TabSessionCloser() {
            public void closeSessionsForTab(String scope) {
                closerCoordinator.closeSessionsForScope(scope);
            }
        });
    }

    /**
     * The conversation sink an agent session binds to: the tab the session BELONGS to, identified by the
     * scope embedded in the coordinator's session key ({@code agentId#<ChatSessionId>}). Resolving via the
     * ACTIVE tab instead put another tab's bubbles/action buttons into whichever tab happened to be selected
     * when a session was (re)created with several chat tabs open. Falls back to the active tab only when no
     * tab matches the scope (e.g. the legacy "session" scope with no tab id).
     */
    private com.aresstack.askai.plugin.api.agent.AgentConversationSink resolveAgentSinkForSessionKey(
            String sessionKey) {
        String scope = null;
        if (sessionKey != null) {
            int hash = sessionKey.indexOf('#');
            scope = hash < 0 ? sessionKey : sessionKey.substring(hash + 1);
        }
        if (scope != null && !scope.isEmpty() && chatWorkspace != null) {
            for (ChatSessionComponent session : chatWorkspace.sessions()) {
                if (session instanceof OllamaChatPanel
                        && scope.equals(((OllamaChatPanel) session).getSessionId().toString())) {
                    return ((OllamaChatPanel) session).getAgentConversationSink();
                }
            }
        }
        return resolveActiveAgentSink();
    }

    /** Fallback sink: the active chat tab's (there is always one tab). */
    private com.aresstack.askai.plugin.api.agent.AgentConversationSink resolveActiveAgentSink() {
        OllamaChatPanel chat = activeChat();
        if (chat == null && chatWorkspace != null) {
            for (ChatSessionComponent session : chatWorkspace.sessions()) {
                if (session instanceof OllamaChatPanel) {
                    chat = (OllamaChatPanel) session;
                    break;
                }
            }
        }
        if (chat == null) {
            throw new IllegalStateException("no chat tab available for the agent conversation sink");
        }
        return chat.getAgentConversationSink();
    }

    /**
     * One-time migration from the old chat.mode/chat.agent keys to the controller's chat.interactionMode/
     * chat.questingAgentId. Runs only when the new keys are absent; old keys are then ignored.
     */
    private void migrateLegacyChatModeState() {
        if (applicationState.get("chat.interactionMode", null) != null) {
            return;
        }
        String oldMode = applicationState.get("chat.mode", null);
        if (oldMode == null) {
            return;
        }
        boolean questing = !"Yapping".equalsIgnoreCase(oldMode.trim());
        applicationState.putAndSave("chat.interactionMode",
                questing ? com.aresstack.askai.plugin.host.WorkspaceModeEntry.QUESTING_ID
                        : com.aresstack.askai.plugin.host.WorkspaceModeEntry.YAPPING_ID);
        String oldAgent = applicationState.get("chat.agent", null);
        if (questing && oldAgent != null && oldAgent.trim().length() > 0) {
            applicationState.putAndSave("chat.questingAgentId", oldAgent.trim());
        }
    }

    /** Host version advertised to PF4J for a plugin's {@code Plugin-Requires} check. */
    private static final String HOST_PLUGIN_VERSION = "0.1.0";

    private JPanel createContentPanel() {
        final OllamaChatPanel.InstallAudioModelHandler installHandler =
                new OllamaChatPanel.InstallAudioModelHandler() {
                    public void openInstall() {
                        showScreen(INSTALL_VIEW);
                    }
                };
        // "Edit profiles…" from the chat settings opens the Audio processing editor page.
        final OllamaChatPanel.AudioProcessingSettingsHandler audioHandler =
                new OllamaChatPanel.AudioProcessingSettingsHandler() {
                    public void openAudioProcessing() {
                        showScreen(AUDIO_PROCESSING_VIEW);
                    }
                };
        // Each tab is an independent chat session, created on demand by the workspace sidebar.
        final com.aresstack.askai.java8.history.ChatHistoryStore historyStore =
                new com.aresstack.askai.java8.history.ChatHistoryStore();
        // The SAME store backs the plugin-facing chat catalog — no second persistence, no copied titles.
        this.chatSessionCatalog =
                new com.aresstack.askai.java8.plugin.host.LocalChatSessionCatalog(historyStore);
        this.chatSessionHistory =
                new com.aresstack.askai.java8.plugin.host.LocalChatSessionHistoryReader(historyStore);
        ChatWorkspacePanel.ChatSessionFactory chatFactory = new ChatWorkspacePanel.ChatSessionFactory() {
            public ChatSessionComponent create(ChatSessionId id) {
                OllamaChatPanel chat = new OllamaChatPanel(id, model, ollamaService, speechToTextService,
                        audioProfileRepository, applicationState, historyStore);
                chat.setInstallAudioModelHandler(installHandler);
                chat.setAudioProcessingSettingsHandler(audioHandler);
                // A persisted title/timestamp must reach an OPEN chat list right away.
                chat.setHistoryChangedListener(new Runnable() {
                    public void run() {
                        if (chatWorkspace != null) {
                            chatWorkspace.refreshChatTitles();
                        }
                    }
                });
                wireChatTabToAgentHost(chat);
                return chat;
            }
        };
        // Restore previously persisted chats (most recent first) so tabs survive a restart — but only the
        // tabs that were OPEN at the last change. "Closed stays closed": the open set is persisted
        // immediately on every open/close (below), so a crash never resurrects a tab the user closed. On
        // first run with no persisted set (migration) we fall back to ALL history, the historical behaviour.
        String openCsv = applicationState.get(STATE_OPEN_CHAT_TABS, null);
        java.util.Set<String> openIds = openCsv == null ? null
                : new java.util.HashSet<String>(java.util.Arrays.asList(openCsv.split(",")));
        List<ChatSessionId> restoreIds = new ArrayList<ChatSessionId>();
        for (com.aresstack.askai.java8.history.ChatRecord record : historyStore.list()) {
            if (openIds != null && !openIds.contains(record.getId())) {
                continue; // this chat's tab was closed — kept in history, but not reopened
            }
            try {
                restoreIds.add(new ChatSessionId(java.util.UUID.fromString(record.getId())));
            } catch (IllegalArgumentException ignored) {
                // Skip records whose id is not a valid UUID.
            }
        }
        this.chatWorkspace = new ChatWorkspacePanel(chatFactory, restoreIds, historyStore);
        // Restore + persist the burger drawer's pinned state across restarts.
        this.chatWorkspace.setApplicationState(applicationState);
        // Persist the open-tab set immediately on every open/close (and once now for the restored set), so a
        // later crash/kill never brings a closed tab back.
        this.chatWorkspace.setTabSetListener(new ChatWorkspacePanel.TabSetListener() {
            public void tabSetChanged(List<ChatSessionId> ids) {
                StringBuilder csv = new StringBuilder();
                for (ChatSessionId id : ids) {
                    if (csv.length() > 0) {
                        csv.append(',');
                    }
                    csv.append(id.toString());
                }
                applicationState.putAndSave(STATE_OPEN_CHAT_TABS, csv.toString());
            }
        });
        // The Chat view is a generic workspace host: "Normal Chat" (the tabbed chat workspace) plus any
        // installed, compatible workspace plugins. The host never constructs the chats itself.
        this.chatWorkspaceHost = buildChatWorkspaceHost(chatWorkspace);
        // Tabs restored above were created BEFORE the plugin host existed — wire them now; tabs opened
        // later are wired by the factory itself.
        for (ChatSessionComponent session : chatWorkspace.sessions()) {
            if (session instanceof OllamaChatPanel) {
                wireChatTabToAgentHost((OllamaChatPanel) session);
            }
        }
        // The workspace mode (Yapping/Questing) + agent is held PER TAB: on every tab switch (and on the
        // initial selection) the host loads and activates that tab's own mode. Closing the last research tab
        // therefore opens a fresh tab whose default mode restarts the agent with a new greeting.
        final com.aresstack.askai.plugin.host.ChatWorkspaceHostPanel hostForTabs = this.chatWorkspaceHost;
        final com.aresstack.askai.java8.plugin.host.LocalChatSessionCatalog catalogForTabs =
                this.chatSessionCatalog;
        chatWorkspace.setActiveSessionListener(new ChatWorkspacePanel.ActiveSessionListener() {
            public void activeSessionChanged(ChatSessionId id) {
                if (hostForTabs != null) {
                    hostForTabs.setActiveChatSession(id.toString());
                }
                // Publish the selection for plugins (read off-EDT by MCP workers): an agent session that
                // is addressed WITHOUT an explicit id follows the visible chat immediately — no session
                // restart, no re-attach anywhere.
                catalogForTabs.setActiveSessionId(id.toString());
            }
        });
        // Seed the initial selection: the listener only fires on CHANGES.
        OllamaChatPanel initialChat = activeChat();
        if (initialChat != null) {
            catalogForTabs.setActiveSessionId(initialChat.getSessionId().toString());
        }
        // The WRITING port: a new chat + its agent, exactly like the user doing it by hand (new chat →
        // Questing → agent). Runs on the EDT; the agent session is up when this returns, because
        // selecting the agent activates it synchronously for the now-active tab.
        final com.aresstack.askai.plugin.host.ChatWorkspaceHostPanel hostForLauncher = this.chatWorkspaceHost;
        final ChatWorkspacePanel workspaceForLauncher = this.chatWorkspace;
        this.chatSessionLauncher = new com.aresstack.askai.java8.plugin.host.LocalChatSessionLauncher(
                new com.aresstack.askai.java8.plugin.host.LocalChatSessionLauncher.EdtChatCreator() {
                    public String create(String agentId, String title) {
                        ChatSessionComponent created = workspaceForLauncher.openNewChat();
                        if (created instanceof OllamaChatPanel) {
                            ((OllamaChatPanel) created).setChatTitle(title);
                        }
                        if (hostForLauncher != null && agentId != null && !agentId.trim().isEmpty()) {
                            hostForLauncher.selectAgent(agentId.trim());
                            hostForLauncher.setInteractionMode(
                                    com.aresstack.askai.plugin.host.WorkspaceModeEntry.QUESTING_ID);
                        }
                        return created.getSessionId().toString();
                    }
                });
        contentPanel.add(chatWorkspaceHost, CHAT_VIEW);
        // One-click "Use in chat" from an installed model card: switch to Chat and select the model.
        modelsPanel.setUseInChatHandler(new OllamaModelsPanel.UseInChatHandler() {
            public void useInChat(String modelName) {
                useModelInChat(modelName);
            }
        });
        modelsScreen().addPage(PAGE_INSTALLED, modelsPanel, () -> showModelsPage(true));
        modelsScreen().addPage(PAGE_RUNNING, modelsPanel, () -> showModelsPage(false));
        contentPanel.add(withFooter(new OllamaActionsPanel(featureActionService, ollamaService)), ACTIONS_VIEW);
        // Java 8 port: model search with two sources in tabs — HuggingFace (search/analyze/import)
        // and the Ollama Library (scrape ollama.com, pull a tag on the remote server).
        // The curated OpenNLP sentence-detection models live in their own store; the Model Browser gets an NLP
        // tab to install them, and the AI-models settings gets a per-language selector from the same catalog.
        final com.aresstack.askai.java8.localmodels.LocalNlpModelStore nlpModelStore =
                new com.aresstack.askai.java8.localmodels.LocalNlpModelStore();
        final com.aresstack.askai.java8.localmodels.LocalNlpModelCatalog nlpModelCatalog =
                new com.aresstack.askai.java8.localmodels.LocalNlpModelCatalog(nlpModelStore);
        final NlpModelsPanel nlpModelsPanel = new NlpModelsPanel(
                new com.aresstack.askai.java8.localmodels.ApacheOpenNlpModelCatalogProvider(),
                nlpModelCatalog, nlpModelStore, new Runnable() {
                    public void run() {
                        catalogRefreshService.refresh();
                    }
                });
        final ModelSearchPanel modelSearchPanel =
                new ModelSearchPanel(configurationRepository, askAiService, nlpModelsPanel);
        // 🔊 discovery entries in the HuggingFace suggestions are a SHORTCUT: they open the
        // active chat's settings on Audio & Dictation, where the voices are installed and chosen.
        modelSearchPanel.setSpeechOutputOpener(new java.util.function.Consumer<String>() {
            public void accept(String voiceId) {
                OllamaChatPanel chat = activeChat();
                if (chat != null) {
                    chat.openSpeechOutputSettings(voiceId);
                }
            }
        });
        modelsScreen().addPage(PAGE_SETUP, modelSearchPanel, null);
        // "Add-ons on Hugging Face" from an installed model card: switch to Setup and enter add-on mode so
        // the chosen encoder is attached to this model (from/adapters), not installed as a new model.
        modelsPanel.setFindAddOnsHandler(new OllamaModelsPanel.FindAddOnsHandler() {
            public void findAddOns(String modelName) {
                showScreen(INSTALL_VIEW);
                modelSearchPanel.openHuggingFaceAddOnSearch(modelName, modelName);
            }

            public void selectLocalAddOn(String modelName) {
                showScreen(INSTALL_VIEW);
                modelSearchPanel.openLocalProjectorAddOn(modelName);
            }
        });
        // After a verified encoder attach, re-read Installed Models from /api/show (no local state kept).
        modelSearchPanel.setAddOnAttachedListener(new Runnable() {
            public void run() {
                modelsPanel.showInstalled();
            }
        });
        this.installSearchPanel = modelSearchPanel;
        this.configPanel = new OllamaConfigPanel(model, ollamaService);
        contentPanel.add(withFooter(configPanel), CONNECTIONS_VIEW);
        // Central AI-model selection (reranker + embeddings) owned by AskAI for all plugins. The reranker
        // catalog needs the local runtime; when there is none, the panel simply offers no rerank models.
        contentPanel.add(withFooter(new AiModelsPanel(model,
                askAiService.localRuntimeManager() == null ? null
                        : new com.aresstack.askai.java8.localmodels.LocalRerankerModelCatalog(
                                askAiService.localRuntimeManager()),
                nlpModelCatalog,
                (VirtualOllamaContainerService) ollamaService)), AI_MODELS_VIEW);
        // Java 8 port: the extended proxy panel (WScript discovery, TLS trust, HTTP client, IPv6).
        contentPanel.add(withFooter(new ProxyPanel(configurationRepository)), NETWORK_VIEW);
        contentPanel.add(withFooter(new OllamaAboutPanel())
                .withCenter(OllamaAboutPanel.createCopyDiagnosticsButton())
                .withTrailing(new com.aresstack.askai.java8.ui.about.CopyrightFooter()), ABOUT_VIEW);
        // Java2D pipeline editor for audio-processing profiles (shared repository instance).
        this.audioProcessingPanel = new AudioProcessingPanel(audioProfileRepository, applicationState);
        audioScreen().addPage(PAGE_AUDIO_PROCESSING, audioProcessingPanel, null);
        // Configuration → Plugins: the real PF4J catalog (enable/disable, details), built after the host so
        // pluginService/enablement exist.
        contentPanel.add(withFooter(new com.aresstack.askai.plugin.host.PluginManagementPanel(
                pluginService, pluginEnablement, pluginsDirectory)), PLUGINS_VIEW);
        wireBatchComponent();
        return contentPanel;
    }

    /** The uniform screen container: the content, below it the footer with "Back" to the last Text/Audio screen. */
    private FooterScreen withFooter(javax.swing.JComponent content) {
        return new FooterScreen(content, () -> showScreen(lastMainScreen));
    }

    /**
     * Wires the batch transcription card. Reuses the shared {@link SpeechToTextService} port and the
     * audio-processing DSP services; the model list is filled asynchronously with only the audio-capable
     * models reported by Ollama's {@code /api/show}, so the panel is built with an empty model list first.
     */
    private void wireBatchComponent() {
        BatchTranscriptionEventPublisher batchEvents = new BatchTranscriptionEventPublisher();
        BatchAudioPreparationService audioPreparation = new BatchAudioPreparationService(
                new DefaultAudioProcessingPreviewService());
        BatchTranscriptionService batchService = new BatchTranscriptionService(
                speechToTextService,
                audioPreparation,
                new BatchMarkdownResultWriter(),
                batchEvents);
        BatchTranscriptionController batchController =
                new BatchTranscriptionController(batchService, batchEvents);

        // The batch local refresh only reloads audio profiles (a local source); audio models arrive via the
        // global catalog refresh below, distributed through applyCatalogSnapshot(...).
        final BatchProfileCatalogService profileCatalog =
                new BatchProfileCatalogService(new Supplier<List<AudioProcessingProfile>>() {
                    public List<AudioProcessingProfile> get() {
                        return audioProfileRepository.findAll();
                    }
                });
        BatchProfileRefresher profileRefresher = new BatchProfileRefresher() {
            public void loadProfiles(Consumer<BatchProfileCatalogLoadedEvent> callback) {
                profileCatalog.loadAsync(callback);
            }
        };

        this.batchPanel = new BatchTranscriptionPanel(
                batchController,
                Collections.<String>emptyList(),
                audioProfileRepository.findAll(),
                profileRefresher);
        audioScreen().addPage(PAGE_BATCH, batchPanel, null);
    }

    /** Switches to the Chat view and selects the given model in the active chat, keeping its conversation. */
    private void useModelInChat(String modelName) {
        showScreen(CHAT_VIEW);
        OllamaChatPanel chat = activeChat();
        if (chat != null) {
            chat.useModel(modelName);
        }
    }

    /** @return the OllamaChatPanel of the currently selected chat tab, or null. */
    private OllamaChatPanel activeChat() {
        if (chatWorkspace == null) {
            return null;
        }
        ChatSessionComponent session = chatWorkspace.activeSession();
        return session instanceof OllamaChatPanel ? (OllamaChatPanel) session : null;
    }

    private void showScreen(String screenName) {
        // Leaving the Setup/HF view must drop any transient add-on target so a later normal install can
        // never be silently attached to the old model.
        if (!INSTALL_VIEW.equals(screenName) && installSearchPanel != null) {
            installSearchPanel.leaveAddOnMode();
        }
        // The former screens live as pages in the drawer screens now.
        if (BATCH_VIEW.equals(screenName)) {
            audioScreen().select(PAGE_BATCH);
            screenName = AUDIO_SCREEN;
        } else if (AUDIO_PROCESSING_VIEW.equals(screenName)) {
            audioScreen().select(PAGE_AUDIO_PROCESSING);
            screenName = AUDIO_SCREEN;
        } else if (INSTALL_VIEW.equals(screenName)) {
            modelsScreen().select(PAGE_SETUP);
            screenName = MODELS_SCREEN;
        } else if (MODELS_VIEW.equals(screenName)) {
            screenName = MODELS_SCREEN;
        }
        if (CHAT_VIEW.equals(screenName) || AUDIO_SCREEN.equals(screenName)) {
            lastMainScreen = screenName;
        }
        contentLayout.show(contentPanel, screenName);
        markNavigation(screenName); // the title bar's Text | Audio toggle follows the shown screen
        refreshConnectionStatus();
        // Re-check speech-to-text readiness when returning to the chat (server/model may have changed).
        if (CHAT_VIEW.equals(screenName)) {
            OllamaChatPanel chat = activeChat();
            if (chat != null) {
                chat.invalidateSpeechReadiness();
            }
        }
    }

    /** Show the Models drawer on the Installed or Running Models page. */
    private void showModels(boolean installed) {
        showScreen(MODELS_SCREEN);
        modelsScreen().select(installed ? PAGE_INSTALLED : PAGE_RUNNING);
    }

    /** The Installed / Running Models page of the Models drawer was chosen. */
    private void showModelsPage(boolean installed) {
        if (installSearchPanel != null) {
            installSearchPanel.leaveAddOnMode(); // leaving Setup drops any transient add-on target
        }
        if (installed) {
            modelsPanel.showInstalled();
        } else {
            modelsPanel.showRunning();
        }
        refreshConnectionStatus();
    }
}
