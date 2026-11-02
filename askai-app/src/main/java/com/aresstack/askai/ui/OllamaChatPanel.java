package com.aresstack.askai.ui;

import com.aresstack.comiccontrols.control.ComicControls;
import com.aresstack.comiccontrols.control.ComicButton;
import com.aresstack.comiccontrols.control.ComicForm;
import com.aresstack.comiccontrols.theme.ThemeColor;
import com.aresstack.askai.AskAiModel;
import com.aresstack.askai.audio.AudioProfileRepository;
import com.aresstack.askai.audio.format.SupportedAudioFormats;
import com.aresstack.askai.catalog.GlobalCatalogSnapshot;
import com.aresstack.askai.ui.chat.ChatSessionComponent;
import com.aresstack.askai.ui.chat.ChatSessionId;
import com.aresstack.askai.audio.FileAudioProfileRepository;
import com.aresstack.askai.groupchat.GroupChatConnectionState;
import com.aresstack.askai.groupchat.GroupChatMode;
import com.aresstack.askai.groupchat.GroupChatRoom;
import com.aresstack.askai.groupchat.GroupChatTransport;
import com.aresstack.askai.groupchat.MentionParser;
import com.aresstack.askai.groupchat.Participant;
import com.aresstack.askai.groupchat.ParticipantColorPalette;
import com.aresstack.askai.groupchat.jgroups.JGroupsGroupChatTransport;
import com.aresstack.askai.groupchat.jgroups.JGroupsTransportConfig;
import com.aresstack.askai.party.OllamaBotResponder;
import com.aresstack.askai.party.PartySession;
import com.aresstack.askai.party.PartySettings;
import com.aresstack.askai.state.ApplicationStateService;
import com.aresstack.askai.client.OllamaChatTurn;
import com.aresstack.askai.client.OllamaModelInfoView;
import com.aresstack.askai.vision.ChatDraft;
import com.aresstack.askai.vision.ImageAttachment;
import com.aresstack.askai.vision.ImageAttachmentContentLoader;
import com.aresstack.askai.vision.ImageAttachmentException;
import com.aresstack.askai.vision.VisionCapability;
import com.aresstack.askai.service.OllamaService;
import com.aresstack.askai.service.ThinkingOption;
import com.aresstack.askai.speech.AudioModelResolver;
import com.aresstack.askai.speech.ComposerInserter;
import com.aresstack.askai.speech.DefaultRecordingNormalizer;
import com.aresstack.askai.speech.DictationDiagnostics;
import com.aresstack.askai.speech.DictationErrorKind;
import com.aresstack.askai.speech.DictationFailure;
import com.aresstack.askai.speech.DictationListener;
import com.aresstack.askai.speech.DictationResult;
import com.aresstack.askai.speech.DictationState;
import com.aresstack.askai.speech.JavaSoundMicrophoneRecorder;
import com.aresstack.askai.speech.MicrophoneRecorder;
import com.aresstack.askai.speech.OllamaAudioModelResolver;
import com.aresstack.askai.speech.OllamaServerProbe;
import com.aresstack.askai.speech.ReadinessStatus;
import com.aresstack.askai.speech.RecordingNormalizer;
import com.aresstack.askai.speech.ServerProbe;
import com.aresstack.askai.speech.SpeechDictationService;
import com.aresstack.askai.speech.SpeechToTextReadinessService;
import com.aresstack.askai.speech.SpeechTranscriber;
import com.aresstack.askai.stt.AudioCapability;
import com.aresstack.askai.stt.DefaultSpeechToTextService;
import com.aresstack.askai.stt.OllamaSpeechTranscriber;
import com.aresstack.askai.stt.SpeechToTextConfiguration;
import com.aresstack.askai.stt.SpeechToTextService;
import com.aresstack.audio.application.RecordingQualityAnalyzer;
import com.aresstack.audio.dsp.AudioLevelMeter;
import com.aresstack.audio.infrastructure.AvailableAudioDevices;
import com.aresstack.audio.profile.AudioProcessingProfile;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JOptionPane;
import javax.swing.SwingConstants;
import javax.swing.JColorChooser;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Window;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Clean, shippable chat window for the selected Ollama model, with reliable microphone dictation.
 *
 * <p>Chat streaming is unchanged. Dictation (record → normalize → transcribe → insert at the caret) is
 * delegated entirely to the Swing-free {@link SpeechDictationService}: this panel only reads the
 * {@link DictationState}, renders the live level, and applies the recognized text with
 * {@link ComposerInserter}. There is no second microphone path; capture format is negotiated by the
 * service, not forced to 16 kHz here.</p>
 */
public final class OllamaChatPanel extends JPanel implements ChatSessionComponent {

    private static final String MIC_SYSTEM_DEFAULT = "System default";
    private static final String AUDIO_MODEL_AUTOMATIC = "Automatic";
    /** Delete leftover dictation temp files older than this many milliseconds on startup/close. */
    private static final long TEMP_TTL_MILLIS = 24L * 60L * 60L * 1000L;

    /** Application-state keys under which the chat remembers its last selection. */
    private static final String STATE_LAST_MODEL = "chat.lastModel";
    private static final String STATE_REASONING = "chat.reasoningEffort";
    private static final String STATE_MODE = "chat.mode";
    private static final String STATE_AGENT = "chat.agent";
    private static final String YAPPING_MODE = "Yapping";

    private final ChatSessionId sessionId;
    private final AskAiModel model;
    private final OllamaService ollamaService;
    private final SpeechToTextService speechToTextService;
    private final AudioProfileRepository audioProfileRepository;
    private final ApplicationStateService applicationState;
    // The persisted model to restore once the model list first loads (consumed once, then cleared).
    private String pendingRestoreModel;

    private final JComboBox<String> modelCombo;
    private final JTextArea systemPromptArea;
    private final ChatTranscript transcript;
    private final ChatComposerPanel composer;
    /** Generic per-tab slot above the composer for a host-provided agent accessory; hidden when empty. */
    private final JPanel composerAccessorySlot = new JPanel(new BorderLayout());
    /** Its twin BELOW the composer (e.g. the research blacklist strip); hidden when empty. */
    private final JPanel belowComposerAccessorySlot = new JPanel(new BorderLayout());
    // The single source of truth for the interaction mode (Yapping/Questing) and the selected agent is the
    // host WorkspaceModeController; this panel's composer selector only drives and reflects it.
    private com.aresstack.askai.plugin.host.WorkspaceModeController modeController;
    private final Runnable modeChangeListener = new Runnable() {
        public void run() {
            reflectMode();
        }
    };
    // Commit 11: when Questing routes to an agent session, plain prompts and stop go through this router
    // instead of the Ollama path. The chat component and composer stay physically the same.
    private com.aresstack.askai.plugin.host.ChatSubmissionRouter chatSubmissionRouter;
    private com.aresstack.askai.plugin.host.ActiveAgentCommandRegistry agentCommandRegistry;
    private com.aresstack.askai.ui.AskAiAgentConversationSink agentConversationSink;
    private SlashCommandPopup slashPopup;
    private final Runnable routerChangeListener = new Runnable() {
        public void run() {
            onUi(new Runnable() {
                public void run() {
                    // An agent switch / deactivation must never leave stale commands offered.
                    if (slashPopup != null) {
                        slashPopup.hide();
                    }
                    refreshAgentComposerState();
                }
            });
        }
    };
    // Local composer mode besides the controller-owned Yapping/Questing: GroupChatMode.PARTYING marks the
    // LAN group chat; otherwise this stays YAPPING. selectedAgent is only used by the party paths.
    private String chatMode = GroupChatMode.YAPPING;
    private String selectedAgent;
    // Partying (LAN group-chat) state: the active session controller and the persisted settings.
    private PartySession partySession;
    private PartySettings partySettings;
    private com.aresstack.askai.notify.DesktopNotifier notifier;
    private MentionCompletionSupport mentionCompletion;
    private boolean partyJoinInFlight;
    // Set when a send raced the join; the composer content is submitted once the join succeeds.
    private boolean partySendPending;
    // Installed model names, cached on the model refresh so party threads can read them safely.
    private volatile List<String> installedModelNames = Collections.emptyList();
    // Thinking effort ("off"/"low"/"medium"/"high"), only sent when the selected model supports thinking.
    private String reasoningEffort = "off";
    private boolean modelSupportsThinking;
    /** True when the SELECTED model reports rerank-only capabilities — chat send is refused. */
    private volatile boolean selectedModelRerankOnly;
    /** The model the rerank-only transcript hint was last shown for (avoid repeating it). */
    private String rerankOnlyHintShownFor;
    // The model whose thinking capability is currently being probed, to ignore stale /api/show callbacks.
    private String reasoningProbeModel;
    // Per-turn streaming state for the thinking → answer flow.
    private final ThinkingSummaryProvider thinkingSummaryProvider = new DefaultThinkingSummaryProvider();
    private final StringBuilder streamingThinking = new StringBuilder();
    private com.aresstack.askai.ui.bubble.BubbleTranscriptPanel.ThinkingHandle activeThinking;
    private boolean assistantBubbleStarted;
    private String streamingModelName = "";

    // Dictation controls.
    private final JComboBox<String> audioModelCombo = ComicControls.<String>comboBox();
    private final JComboBox<AudioProcessingProfile> audioProfileCombo = ComicControls.<AudioProcessingProfile>comboBox();
    private final JComboBox<String> micCombo = ComicControls.<String>comboBox();
    private final JButton micRefreshButton = new ComicButton("Refresh");
    private final JButton testMicButton = new ComicButton("Test microphone");
    // Speech output (read-aloud), PER LANGUAGE: the Windows voice of that language by default,
    // or an installed Piper model voice — an English answer is never read with a German voice.
    private static final String SPEECH_OUTPUT_WINDOWS = "Windows voice (default)";
    private final java.util.Map<String, JComboBox<Object>> speechOutputCombos =
            new java.util.LinkedHashMap<String, JComboBox<Object>>();
    private boolean updatingSpeechOutputCombo;
    private final com.aresstack.askai.tts.TtsSettingsStore ttsSettingsStore =
            new com.aresstack.askai.tts.TtsSettingsStore();
    private final com.aresstack.askai.tts.PiperTtsStore piperTtsStore =
            new com.aresstack.askai.tts.PiperTtsStore();
    /** The application's voice INSTALL section (Settings > AI models), shared by all chats. */
    private SpeechOutputModelsPanel speechVoicesPanel;
    // Playback output (recording preview + tests): persisted device across JavaSound/OpenAL/VLC.
    private final javax.swing.JComboBox<Object> playbackOutputCombo =
            ComicControls.<Object>comboBox();
    private boolean updatingPlaybackOutputCombo;
    private final com.aresstack.askai.audio.preview.AudioPlaybackSettingsStore
            audioPlaybackStore = new com.aresstack.askai.audio.preview.AudioPlaybackSettingsStore();
    private final com.aresstack.askai.audio.preview.VlcInstallation vlcInstallation =
            new com.aresstack.askai.audio.preview.VlcInstallation();
    private final javax.swing.JTextField vlcPathField = ComicControls.textField(20);
    private com.aresstack.askai.audio.preview.DispatchingAudioPreviewPlaybackService previewPlayback;
    // Hands-free dictation state (auto-stop on silence).
    private boolean recordingHadSignal;
    private long lastSignalAtMillis;
    /** The settings dialog's category list — kept so callers can open a specific category. */
    private javax.swing.JList<String> settingsNavigation;
    private final JTextArea techDetails = ComicControls.textArea(6, 40);

    private final List<OllamaChatTurn> history = new ArrayList<OllamaChatTurn>();
    private final ImageAttachmentContentLoader imageContentLoader = new ImageAttachmentContentLoader();
    // Durable per-chat persistence (may be null in tests): the record is built up as messages land.
    private final com.aresstack.askai.history.ChatHistoryStore historyStore;
    private com.aresstack.askai.history.ChatRecord chatRecord;
    private final StringBuilder streamingAssistant = new StringBuilder();
    private OllamaService.Task chatTask;
    // The UI's chat-busy state, decoupled from the technical chatTask handle so the dictation controls
    // re-enable reliably the moment a chat turn ends (regardless of when chatTask is nulled).
    private boolean chatBusy;
    private Timer elapsedTimer;
    private long requestStartedAtMillis;

    // Dictation runtime.
    private final ExecutorService dictationExecutor;
    private final SpeechDictationService dictation;
    private final SpeechToTextReadinessService readiness;
    private final File workDir;
    private DictationState dictationState = DictationState.IDLE;
    private Timer levelTimer;
    private long recordingStartedAtMillis;
    private boolean updatingAudioModelCombo;
    private boolean updatingAudioProfileCombo;
    private boolean updatingMicCombo;
    private boolean checkingReadiness;

    // Existing-file transcription (kept, but decoupled from the microphone path).
    private SpeechToTextService.Task fileTask;
    private boolean fileBusy;
    private File lastAudioDirectory;

    // Runs a one-off microphone test without touching the dictation flow.
    private MicrophoneRecorder.Session micTestSession;
    private Timer micTestTimer;

    /** Callbacks that dictate a "install an audio model" navigation (wired by the frame). */
    public interface InstallAudioModelHandler {
        void openInstall();
    }

    /** Callback to open the Audio processing profile editor from the chat settings (wired by the frame). */
    public interface AudioProcessingSettingsHandler {
        void openAudioProcessing();
    }

    private InstallAudioModelHandler installAudioModelHandler;
    private AudioProcessingSettingsHandler audioProcessingSettingsHandler;

    public OllamaChatPanel(AskAiModel model, OllamaService ollamaService,
                           SpeechToTextService speechToTextService) {
        this(model, ollamaService, speechToTextService, new FileAudioProfileRepository(), null);
    }

    public OllamaChatPanel(AskAiModel model, OllamaService ollamaService,
                           SpeechToTextService speechToTextService,
                           AudioProfileRepository audioProfileRepository) {
        this(model, ollamaService, speechToTextService, audioProfileRepository, null);
    }

    public OllamaChatPanel(AskAiModel model, OllamaService ollamaService,
                           SpeechToTextService speechToTextService,
                           AudioProfileRepository audioProfileRepository,
                           ApplicationStateService applicationState) {
        this(ChatSessionId.create(), model, ollamaService, speechToTextService,
                audioProfileRepository, applicationState, null);
    }

    public OllamaChatPanel(ChatSessionId sessionId, AskAiModel model, OllamaService ollamaService,
                           SpeechToTextService speechToTextService,
                           AudioProfileRepository audioProfileRepository,
                           ApplicationStateService applicationState) {
        this(sessionId, model, ollamaService, speechToTextService,
                audioProfileRepository, applicationState, null);
    }

    public OllamaChatPanel(ChatSessionId sessionId, AskAiModel model, OllamaService ollamaService,
                           SpeechToTextService speechToTextService,
                           AudioProfileRepository audioProfileRepository,
                           ApplicationStateService applicationState,
                           com.aresstack.askai.history.ChatHistoryStore historyStore) {
        this.sessionId = sessionId == null ? ChatSessionId.create() : sessionId;
        this.historyStore = historyStore;
        this.model = model;
        this.ollamaService = ollamaService;
        this.speechToTextService = speechToTextService;
        this.audioProfileRepository = audioProfileRepository;
        this.applicationState = applicationState;
        this.modelCombo = ComicControls.<String>comboBox();
        this.modelCombo.setRenderer(new javax.swing.DefaultListCellRenderer() {
            @Override
            public java.awt.Component getListCellRendererComponent(javax.swing.JList<?> list,
                    Object value, int index, boolean isSelected, boolean cellHasFocus) {
                Object display = value;
                if (value instanceof String && com.aresstack.askai.localmodels
                        .LocalModelNames.isLocalModelName((String) value)) {
                    display = "Local · " + ((String) value).substring(
                            com.aresstack.askai.localmodels.LocalModelNames
                                    .LOCAL_PREFIX.length());
                }
                return super.getListCellRendererComponent(list, display, index, isSelected,
                        cellHasFocus);
            }
        });
        this.systemPromptArea = ComicControls.textArea("You are a concise local assistant.", 2, 40);
        this.transcript = new ChatTranscript();
        this.transcript.applyColors(model.getChatColors());
        this.composer = new ChatComposerPanel(new ChatComposerPanel.Actions() {
            public void selectModel() {
                openModelPopup();
            }

            public void selectMode() {
                openModePopup();
            }

            public void selectReasoning() {
                openReasoningPopup();
            }

            public void toggleNotificationsMute() {
                OllamaChatPanel.this.toggleNotificationsMute();
            }

            public void send() {
                sendChat();
            }

            public void stop() {
                stopChat();
            }

            public void toggleRecording() {
                onRecordButton();
            }

            public void discardDictation() {
                onDiscardButton();
            }

            public void retryTranscription() {
                retryDictation();
            }

            public void saveRecording() {
                OllamaChatPanel.this.saveRecording();
            }

            public void playRecording() {
                playRecordingPreview();
            }

            public void installAudioModel() {
                openInstallAudioModel();
            }

            public void transcribeAudioFile() {
                onAudioFileAction();
            }

            public void attachImages() {
                onAttachImagesAction();
            }
        });
        // Slash-command completion for the shared composer; only active in Questing (empty registry in Yapping).
        this.slashPopup = new SlashCommandPopup(composer.getEditor());

        this.partySettings = new PartySettings(applicationState);
        this.notifier = new com.aresstack.askai.notify.DesktopNotifier();
        this.mentionCompletion = new MentionCompletionSupport(composer.getEditor());
        // Typing "@" re-queries the loaded models so the highlight is fresh, not join-time stale.
        this.mentionCompletion.setPopupRefreshHook(new Runnable() {
            public void run() {
                refreshRunningModelHighlight();
            }
        });
        this.dictationExecutor = Executors.newCachedThreadPool(new DaemonThreadFactory());
        this.workDir = new File(System.getProperty("java.io.tmpdir"), "askai-speech");
        this.dictation = buildDictationService();
        Supplier<String> baseUrl = new Supplier<String>() {
            public String get() {
                return model.getOllamaBaseUrl();
            }
        };
        this.readiness = new SpeechToTextReadinessService(new OllamaServerProbe(baseUrl), audioModelResolver());

        buildUserInterface();
        restoreChatPreferences();
        setBusy(false);
        if (!restoreChatHistory()) {
            showEmptyState();
        }
        cleanupOldRecordings();
        refreshModels();
        refreshMicrophones();
    }

    /**
     * Restores the mode, agent and thinking effort remembered in the application state and arms the last
     * model for restoration once the model list loads. Nothing is persisted here; only user actions write.
     */
    private void restoreChatPreferences() {
        if (applicationState == null) {
            return;
        }
        // Yapping/Questing and the selected agent are owned by the WorkspaceModeController (bound later);
        // the composer label follows it via reflectMode(). Only the LOCAL Partying mode is restored here.
        String mode = applicationState.get(STATE_MODE, GroupChatMode.YAPPING);
        if (GroupChatMode.PARTYING.equals(mode)
                && com.aresstack.askai.party.PartyModeGuard.acquire(this)) {
            // Restore into Partying mode and rejoin right away so the stored room history is
            // replayed without requiring a first (swallowed) send.  Only the first restored tab
            // wins the party; any further tabs fall back to Yapping.
            chatMode = GroupChatMode.PARTYING;
            selectedAgent = null;
            composer.setModeName("Partying");
            mentionCompletion.setActive(true);
            refreshMentionCompletionHandles();
            startPartySessionIfNeeded();
        } else {
            chatMode = GroupChatMode.YAPPING;
            selectedAgent = null;
            composer.setModeName("Yapping");
        }

        String effort = applicationState.get(STATE_REASONING, "off");
        reasoningEffort = isKnownReasoningLevel(effort) ? effort : "off";
        composer.setReasoningName(reasoningLabel(reasoningEffort));

        // The centrally-managed main model (set in the chat window, shared by all plugins) is the primary
        // restore target; the per-tab last model is only a fallback when nothing is centrally selected.
        String centralMainModel = model.getAiModelSelections().getMainModel();
        pendingRestoreModel = centralMainModel != null && centralMainModel.trim().length() > 0
                ? centralMainModel
                : applicationState.get(STATE_LAST_MODEL, null);
    }

    private static boolean isKnownReasoningLevel(String level) {
        for (String known : REASONING_LEVELS) {
            if (known.equals(level)) {
                return true;
            }
        }
        return false;
    }

    /** @return the remembered model to select, if it is present in the freshly loaded list; consumed once. */
    private String consumePendingRestoreModel(List<String> names) {
        String wanted = pendingRestoreModel;
        pendingRestoreModel = null;
        return wanted != null && names.contains(wanted) ? wanted : null;
    }

    private void rememberState(String key, String value) {
        if (applicationState != null) {
            applicationState.putAndSave(key, value);
        }
    }

    private boolean windowCleanupInstalled;
    // Tracks whether this panel's window is the foreground window (for "notify only in background").
    private volatile boolean windowActive = true;

    @Override
    public void addNotify() {
        super.addNotify();
        if (windowCleanupInstalled) {
            return;
        }
        Window window = SwingUtilities.getWindowAncestor(this);
        if (window != null) {
            windowCleanupInstalled = true;
            windowActive = window.isActive();
            window.addWindowListener(new WindowAdapter() {
                public void windowClosing(WindowEvent event) {
                    shutdownDictation();
                }
            });
            window.addWindowFocusListener(new java.awt.event.WindowFocusListener() {
                public void windowGainedFocus(WindowEvent event) {
                    windowActive = true;
                }

                public void windowLostFocus(WindowEvent event) {
                    windowActive = false;
                }
            });
        }
    }

    /** Lets the frame wire the "Install audio model" action to a screen switch. */
    public void setInstallAudioModelHandler(InstallAudioModelHandler handler) {
        this.installAudioModelHandler = handler;
    }

    public void setAudioProcessingSettingsHandler(AudioProcessingSettingsHandler handler) {
        this.audioProcessingSettingsHandler = handler;
    }

    /**
     * Called by the workspace when this chat's persisted record was deleted from the sidebar: stop
     * re-saving the just-deleted chat (a next message starts a fresh record).
     */
    public void detachFromPersistedChat() {
        chatRecord = null;
    }

    /**
     * UNDO of a sidebar delete: re-adopt the restored record so autosave continues on it — the
     * exact counterpart of {@link #detachFromPersistedChat}. Ignored for a foreign record.
     */
    public void reattachToPersistedChat(com.aresstack.askai.history.ChatRecord record) {
        if (record != null && sessionId.toString().equals(record.getId())) {
            chatRecord = record;
        }
    }

    // ------------------------------------------------------------------ dictation wiring

    private AudioModelResolver audioModelResolver() {
        Supplier<String> baseUrl = new Supplier<String>() {
            public String get() {
                return model.getOllamaBaseUrl();
            }
        };
        Supplier<String> lastModel = new Supplier<String>() {
            public String get() {
                return model.getSpeechToTextConfiguration().getLastAudioModel();
            }
        };
        return new OllamaAudioModelResolver(baseUrl, lastModel);
    }

    private SpeechDictationService buildDictationService() {
        Supplier<String> baseUrl = new Supplier<String>() {
            public String get() {
                return model.getOllamaBaseUrl();
            }
        };
        Supplier<Integer> timeout = new Supplier<Integer>() {
            public Integer get() {
                return model.getSpeechToTextConfiguration().getTimeoutSeconds();
            }
        };
        MicrophoneRecorder recorder = new JavaSoundMicrophoneRecorder();
        // Resolve the selected transcription profile just before EACH recording, so saved profile edits
        // take effect on the next capture without restarting or rebuilding the chat panel. An unknown or
        // missing id falls back to the built-in default inside DefaultRecordingNormalizer.
        RecordingNormalizer normalizer = new DefaultRecordingNormalizer(new Supplier<AudioProcessingProfile>() {
            public AudioProcessingProfile get() {
                return audioProfileRepository.findById(
                        model.getSpeechToTextConfiguration().getAudioProcessingProfileId());
            }
        });
        AudioModelResolver resolver = audioModelResolver();
        SpeechTranscriber transcriber = new OllamaSpeechTranscriber(baseUrl, timeout);
        ServerProbe probe = new OllamaServerProbe(baseUrl);
        DictationListener listener = new DictationListener() {
            public void onState(final DictationState state, final String message) {
                onUi(new Runnable() {
                    public void run() {
                        onDictationState(state, message);
                    }
                });
            }

            public void onResult(final DictationResult result) {
                onUi(new Runnable() {
                    public void run() {
                        onDictationResult(result);
                    }
                });
            }

            public void onFailure(final DictationFailure failure) {
                onUi(new Runnable() {
                    public void run() {
                        onDictationFailure(failure);
                    }
                });
            }
        };
        return new SpeechDictationService(dictationExecutor, recorder, normalizer, resolver, transcriber,
                RecordingQualityAnalyzer.withDefaults(), workDir, probe, listener);
    }

    // ------------------------------------------------------------------ UI construction

    private void buildUserInterface() {
        setLayout(new BorderLayout(8, 8));
        // No top/side padding anymore: the chat area runs edge-to-edge so a full-width overlay
        // strip (the sky STATUS BAR) can sit flush under the workspace top bar without white
        // flanks — the bubbles and the composer carry their own inner margins. Only a small
        // bottom breathing space remains.
        setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));
        // Sidebar + hamburger/gear live at the WORKSPACE level (ChatWorkspacePanel), full height.
        // The transcript sits inside a layered container so a diagram OVERLAY (comic plate with
        // the ✕) can cover the answer window without touching the transcript itself.
        transcriptLayers.add(transcript.getComponent(), javax.swing.JLayeredPane.DEFAULT_LAYER);
        JPanel chatColumn = new JPanel(new BorderLayout());
        chatColumn.setOpaque(false);
        chatColumn.add(transcriptLayers, BorderLayout.CENTER);
        chatColumn.add(buildBottomArea(), BorderLayout.SOUTH);
        add(chatColumn, BorderLayout.CENTER);
        // ONE calm scrollbar as the shared right axis of the whole chat column: it runs from the
        // top (beside the sky overlay) down to the composer's bottom edge, but it still drives
        // ONLY the transcript — suggestions and composer never scroll. Geometrically it lives
        // OUTSIDE the chat column; semantically it shares the transcript scroll pane's model.
        add(buildWorkspaceScrollBar(), BorderLayout.EAST);
    }

    /** The transcript's own wheel step (matches the scroll pane's former unit increment). */
    private static final int TRANSCRIPT_WHEEL_UNIT = 18;

    /** The transcript's vertical scrollbar, rehomed to the workspace's right edge (shared model). */
    private javax.swing.JScrollBar buildWorkspaceScrollBar() {
        javax.swing.JScrollPane transcriptScroll = transcript.getScrollPane();
        transcriptScroll.setVerticalScrollBarPolicy(
                JScrollPane.VERTICAL_SCROLLBAR_NEVER); // the classic in-pane Swing bar is gone
        final javax.swing.JScrollBar bar = new javax.swing.JScrollBar(javax.swing.JScrollBar.VERTICAL);
        bar.setModel(transcriptScroll.getVerticalScrollBar().getModel()); // one model, one truth
        bar.setUnitIncrement(TRANSCRIPT_WHEEL_UNIT);
        com.aresstack.comiccontrols.control.ComicScrollBarUI.install(bar);
        final javax.swing.BoundedRangeModel model = bar.getModel();
        // Hotfix 4.1: with the in-pane bar POLICY-NEVER'd, Swing's own scroll-pane wheel handling
        // goes dead (it drives the now-absent internal bar). Route the wheel straight into THE one
        // shared model instead — over bubbles and empty background (the scroll pane) and over the
        // sky overlay layer (events bubble up to the layered pane when no child consumes them).
        java.awt.event.MouseWheelListener wheelRouter =
                event -> routeWheelToModel(event, model, TRANSCRIPT_WHEEL_UNIT);
        transcriptScroll.addMouseWheelListener(wheelRouter);
        transcriptLayers.addMouseWheelListener(wheelRouter);
        // Only show the bar while there is something to scroll (the track is transparent anyway,
        // but a full-height idle thumb would suggest scrollable content that is not there).
        final Runnable syncVisibility = () -> bar.setVisible(
                model.getExtent() < model.getMaximum() - model.getMinimum());
        model.addChangeListener(event -> syncVisibility.run());
        syncVisibility.run();
        return bar;
    }

    /**
     * Move the shared transcript scroll model by one wheel event — never a second scroll position.
     * Honors precise (fractional) wheel rotation so fine-grained devices scroll smoothly, and
     * block scrolling (page-sized). Package-private for the regression test.
     */
    static void routeWheelToModel(java.awt.event.MouseWheelEvent event,
                                  javax.swing.BoundedRangeModel model, int unitIncrement) {
        int delta;
        if (event.getScrollType() == java.awt.event.MouseWheelEvent.WHEEL_BLOCK_SCROLL) {
            delta = (event.getWheelRotation() < 0 ? -1 : 1)
                    * Math.max(unitIncrement, model.getExtent() - unitIncrement);
        } else {
            delta = (int) Math.round(event.getPreciseWheelRotation()
                    * event.getScrollAmount() * unitIncrement);
            if (delta == 0 && event.getPreciseWheelRotation() != 0) {
                delta = event.getPreciseWheelRotation() < 0 ? -1 : 1; // tiny precise ticks still move
            }
        }
        model.setValue(model.getValue() + delta); // the model clamps to [min, max - extent]
        event.consume();
    }

    /** Both layers (transcript, overlay) always fill the whole area. */
    private final javax.swing.JLayeredPane transcriptLayers = new javax.swing.JLayeredPane() {
        @Override
        public void doLayout() {
            for (java.awt.Component child : getComponents()) {
                child.setBounds(0, 0, getWidth(), getHeight());
            }
        }
    };
    private com.aresstack.comiccontrols.control.ComicOverlayPanel transcriptOverlay;

    /**
     * Show content as a closable comic overlay over the transcript (the "Antwortfenster") —
     * e.g. the research mindmap. Replaces a previous overlay; the ✕ (top right) closes it.
     */
    public void showTranscriptOverlay(javax.swing.JComponent content, String title) {
        closeTranscriptOverlay();
        transcriptOverlay = new com.aresstack.comiccontrols.control.ComicOverlayPanel(
                title, content, new Runnable() {
                    public void run() {
                        closeTranscriptOverlay();
                    }
                });
        transcriptLayers.add(transcriptOverlay, javax.swing.JLayeredPane.PALETTE_LAYER);
        transcriptLayers.revalidate();
        transcriptLayers.repaint();
    }

    public void closeTranscriptOverlay() {
        if (transcriptOverlay != null) {
            transcriptLayers.remove(transcriptOverlay);
            transcriptOverlay = null;
            transcriptLayers.revalidate();
            transcriptLayers.repaint();
        }
    }

    /** Between the transcript (DEFAULT) and the ✕-closable diagram overlay (PALETTE). */
    private static final Integer TRANSCRIPT_SKY_LAYER = Integer.valueOf(50);
    private JComponent transcriptSkyAccessory;
    /** Observes the overlay's TRANSCRIPT_TOP_INSET client property (on it and direct children). */
    private final java.beans.PropertyChangeListener skyInsetListener =
            event -> applyTranscriptTopInsetFromSky();

    /**
     * Lay a host-provided SEE-THROUGH accessory over the transcript (e.g. the research
     * out-of-scope sky), replacing any previous one. The component fills the transcript area; it
     * must claim only its interactive zone via {@code contains}, so chat clicks/scrolling keep
     * working underneath. The accessory publishes how much top room the scroll geometry needs
     * (see {@code ComposerAccessory.TRANSCRIPT_TOP_INSET_PROPERTY}); the transcript applies it so
     * the FIRST message stays fully readable at scroll position 0. EDT only.
     */
    public void setTranscriptSkyAccessory(JComponent accessory) {
        clearTranscriptSkyAccessory();
        if (accessory != null) {
            transcriptSkyAccessory = accessory;
            transcriptLayers.add(accessory, TRANSCRIPT_SKY_LAYER);
            accessory.addPropertyChangeListener(
                    com.aresstack.askai.plugin.api.agent.composer.ComposerAccessory
                            .TRANSCRIPT_TOP_INSET_PROPERTY, skyInsetListener);
            for (java.awt.Component child : accessory.getComponents()) {
                if (child instanceof JComponent) {
                    ((JComponent) child).addPropertyChangeListener(
                            com.aresstack.askai.plugin.api.agent.composer.ComposerAccessory
                                    .TRANSCRIPT_TOP_INSET_PROPERTY, skyInsetListener);
                }
            }
            applyTranscriptTopInsetFromSky();
            transcriptLayers.revalidate();
            transcriptLayers.repaint();
        }
    }

    /** Remove the transcript accessory layer (no-op when none is shown). EDT only. */
    public void clearTranscriptSkyAccessory() {
        if (transcriptSkyAccessory != null) {
            transcriptSkyAccessory.removePropertyChangeListener(
                    com.aresstack.askai.plugin.api.agent.composer.ComposerAccessory
                            .TRANSCRIPT_TOP_INSET_PROPERTY, skyInsetListener);
            for (java.awt.Component child : transcriptSkyAccessory.getComponents()) {
                if (child instanceof JComponent) {
                    ((JComponent) child).removePropertyChangeListener(
                            com.aresstack.askai.plugin.api.agent.composer.ComposerAccessory
                                    .TRANSCRIPT_TOP_INSET_PROPERTY, skyInsetListener);
                }
            }
            transcriptLayers.remove(transcriptSkyAccessory);
            transcriptSkyAccessory = null;
            transcript.setTopInset(0); // the sky is gone — the chat gets its full height back
            transcriptLayers.revalidate();
            transcriptLayers.repaint();
        }
    }

    /** The largest published inset across the overlay component and its direct children. */
    private void applyTranscriptTopInsetFromSky() {
        int inset = 0;
        if (transcriptSkyAccessory != null) {
            inset = insetProperty(transcriptSkyAccessory);
            for (java.awt.Component child : transcriptSkyAccessory.getComponents()) {
                if (child instanceof JComponent) {
                    inset = Math.max(inset, insetProperty((JComponent) child));
                }
            }
        }
        transcript.setTopInset(inset);
    }

    private static int insetProperty(JComponent component) {
        Object value = component.getClientProperty(
                com.aresstack.askai.plugin.api.agent.composer.ComposerAccessory
                        .TRANSCRIPT_TOP_INSET_PROPERTY);
        return value instanceof Integer ? Math.max(0, (Integer) value) : 0;
    }

    /**
     * Show a Mermaid SOURCE in the full embedded viewer (zoom/pan, high-res re-render, copy,
     * save — the {@link com.aresstack.askai.ui.markdown.MermaidViewerPanel}) as an overlay.
     */
    public void showDiagramOverlay(String mermaidSource, String title) {
        showTranscriptOverlay(
                new com.aresstack.askai.ui.markdown.MermaidViewerPanel(mermaidSource), title);
    }

    /**
     * The bottom area of a chat: accessory slot, composer, and the BELOW-composer accessory slot,
     * all full width. The old collapsed "Technical details" strip is gone from here — the same log
     * lives in the settings dialog's "Technical Details" category now.
     */
    private JComponent buildBottomArea() {
        JPanel bottom = new JPanel(new BorderLayout());
        // Generic per-tab accessory slots directly ABOVE and BELOW the composer, populated by the host for
        // the active agent (e.g. research scoping controls / the phase-bound blacklist strip). Empty and
        // INVISIBLE by default, so they reserve no space.
        JPanel composerStack = new JPanel(new BorderLayout());
        composerAccessorySlot.setVisible(false);
        composerStack.add(composerAccessorySlot, BorderLayout.NORTH);
        composerStack.add(buildComposer(), BorderLayout.CENTER);
        belowComposerAccessorySlot.setVisible(false);
        composerStack.add(belowComposerAccessorySlot, BorderLayout.SOUTH);
        bottom.add(composerStack, BorderLayout.NORTH);
        return bottom;
    }

    /** Show a host-provided composer accessory above the composer (replacing any previous one). EDT only. */
    public void setComposerAccessory(JComponent accessory) {
        composerAccessorySlot.removeAll();
        if (accessory != null) {
            composerAccessorySlot.add(accessory, BorderLayout.CENTER);
            composerAccessorySlot.setVisible(true);
        } else {
            composerAccessorySlot.setVisible(false);
        }
        composerAccessorySlot.revalidate();
        composerAccessorySlot.repaint();
    }

    /** Remove any composer accessory so the slot reserves no space. EDT only. */
    public void clearComposerAccessory() {
        composerAccessorySlot.removeAll();
        composerAccessorySlot.setVisible(false);
        composerAccessorySlot.revalidate();
        composerAccessorySlot.repaint();
        setComposerPlaceholder(null); // an accessory-provided placeholder never outlives the accessory
    }

    /** Show a host-provided accessory BELOW the composer (replacing any previous one). EDT only. */
    public void setBelowComposerAccessory(JComponent accessory) {
        belowComposerAccessorySlot.removeAll();
        if (accessory != null) {
            belowComposerAccessorySlot.add(accessory, BorderLayout.CENTER);
            belowComposerAccessorySlot.setVisible(true);
        } else {
            belowComposerAccessorySlot.setVisible(false);
        }
        belowComposerAccessorySlot.revalidate();
        belowComposerAccessorySlot.repaint();
    }

    /** Remove any below-composer accessory so the slot reserves no space. EDT only. */
    public void clearBelowComposerAccessory() {
        setBelowComposerAccessory(null);
    }

    /** Override the composer's placeholder text (null restores the default). EDT only. */
    public void setComposerPlaceholder(String text) {
        composer.setEditorPlaceholder(text);
    }

    // ------------------------------------------------------------------ ChatSessionComponent

    public ChatSessionId getSessionId() {
        return sessionId;
    }

    public java.awt.Component getComponent() {
        return this;
    }

    /** Notified when this tab closes, so the host can END this tab's agent session (off-EDT), not just pause it. */
    public interface TabSessionCloser {
        void closeSessionsForTab(String scope);
    }

    private TabSessionCloser tabSessionCloser;
    private final JButton setupWizardButton = new ComicButton("Setup wizard…");

    public void setTabSessionCloser(TabSessionCloser closer) {
        this.tabSessionCloser = closer;
    }

    /** Opens the setup wizard again from Settings &gt; General &gt; "Setup wizard…" (set by the frame). */
    private Runnable setupWizardOpener;

    public void setSetupWizardOpener(Runnable opener) {
        this.setupWizardOpener = opener;
    }

    /** Release this session's resources when its tab closes: abort the chat, dictation and file work. */
    public void disposeSession() {
        stopChat();
        shutdownDictation();
        // Let go of the application-lifetime voice panel, or this closed chat would stay reachable.
        if (speechVoicesPanel != null) {
            speechVoicesPanel.removeVoicesChangedListener(speechVoicesListener);
        }
        // END (not just pause) THIS tab's agent session so its research run, browser, agent process, model
        // watcher and registry entry are torn down; a fresh tab starts a fresh session, never resumes this one.
        if (tabSessionCloser != null) {
            tabSessionCloser.closeSessionsForTab(sessionId.toString());
        }
    }

    /**
     * The always-available technical log of THIS chat. It used to be a collapsible strip under the
     * composer; now it fills the settings dialog's "Technical Details" category — same data, same
     * {@link #appendTech} feed, only the display place changed.
     */
    private JComponent buildTechnicalDetails() {
        techDetails.setEditable(false);
        techDetails.setLineWrap(true);
        techDetails.setWrapStyleWord(true);
        return new JScrollPane(techDetails);
    }

    /** The application-wide categories (group "SETTINGS"), before the application pages. */
    private static final String SETTINGS_GENERAL = "General";
    private static final String SETTINGS_APPEARANCE = "Appearance";
    /** The chat categories (group "CHAT"): the chat itself and the built-in Party mode. */
    public static final String SETTINGS_CHAT = "Chat";
    private static final String SETTINGS_PARTY = "Party";
    /** Dictation and speech output: application-wide, not chat-specific (its own category). */
    private static final String SETTINGS_SPEECH = "Speech";
    /** The application page that carries the read-aloud voice installation (owned by the frame). */
    public static final String SETTINGS_AI_MODELS = "AI models";
    private static final String[] APP_SETTINGS_CATEGORIES = {SETTINGS_GENERAL, SETTINGS_APPEARANCE, SETTINGS_SPEECH};
    private static final String[] CHAT_SETTINGS_CATEGORIES = {SETTINGS_CHAT, SETTINGS_PARTY};

    /**
     * The application's own configuration pages (Connections, AI models, Network, Plugins), shared by all
     * chat tabs: one Swing component each, so it is (re)attached to the settings of whichever tab shows
     * them. Owned and filled by the workspace; insertion order is the display order after Appearance.
     */
    private java.util.Map<String, JComponent> applicationSettingsPages =
            java.util.Collections.<String, JComponent>emptyMap();

    public void setApplicationSettingsPages(java.util.Map<String, JComponent> pages) {
        this.applicationSettingsPages = pages == null
                ? java.util.Collections.<String, JComponent>emptyMap() : pages;
    }

    private java.awt.CardLayout settingsCardLayout;
    private JPanel settingsCards;

    /**
     * The chat settings dialog content, Outlook-style: a category list on the left selects one
     * card on the right.
     */
    /**
     * The ACTIVE agent's settings pages for the gear dialog (plugin decides WHAT, session holds the
     * VALUES, the host only decides WHERE): resolved lazily on every dialog open, so the categories
     * appear exactly while this tab's agent is selected and disappear with it.
     */
    private java.util.function.Supplier<java.util.List<
            com.aresstack.askai.plugin.api.agent.AgentSettingsContribution>> agentSettingsContributions;
    private java.util.function.Supplier<com.aresstack.askai.plugin.api.agent.AgentSession>
            agentSettingsSession;

    public void setAgentSettingsSource(
            java.util.function.Supplier<java.util.List<
                    com.aresstack.askai.plugin.api.agent.AgentSettingsContribution>> contributions,
            java.util.function.Supplier<com.aresstack.askai.plugin.api.agent.AgentSession> session) {
        this.agentSettingsContributions = contributions;
        this.agentSettingsSession = session;
    }

    /** The settings category list (built once with the pages; shown in the workspace drawer). */
    private JComponent settingsNavigationPane;
    /** The settings pages (CardLayout; shown in the workspace's main area). */
    private JComponent settingsPagesPane;
    /**
     * Opens the settings in the WORKSPACE (category list in the drawer, page in the main area) at the given
     * category; set by the workspace. Without it (standalone/tests) the modeless dialog is the fallback.
     */
    private java.util.function.Consumer<String> settingsOpener;

    public void setSettingsOpener(java.util.function.Consumer<String> opener) {
        this.settingsOpener = opener;
    }

    /** The settings category list for the workspace drawer (refreshes the dynamic choices first). */
    public JComponent settingsNavigationComponent() {
        ensureSettingsPanes();
        return settingsNavigationPane;
    }

    /** The settings pages for the workspace's main area. */
    public JComponent settingsPagesComponent() {
        ensureSettingsPanes();
        attachApplicationSettingsPages();
        return settingsPagesPane;
    }

    /**
     * Moves the shared application pages into THIS tab's settings (another tab may have shown them last)
     * and re-shows the selected card, so the page is never missing after switching tabs.
     */
    private void attachApplicationSettingsPages() {
        if (settingsCards == null) {
            return;
        }
        boolean moved = false;
        for (java.util.Map.Entry<String, JComponent> page : applicationSettingsPages.entrySet()) {
            if (page.getValue().getParent() != settingsCards) {
                settingsCards.add(page.getValue(), page.getKey());
                moved = true;
            }
        }
        String selected = settingsNavigation == null ? null : settingsNavigation.getSelectedValue();
        if (moved && selected != null) {
            settingsCardLayout.show(settingsCards, selected);
            settingsCards.revalidate();
        }
    }

    /** The settings categories in order: the general ones, then the active agent's pages. */
    public java.util.List<String> settingsCategories() {
        ensureSettingsPanes();
        java.util.List<String> names = new java.util.ArrayList<String>();
        for (int i = 0; i < settingsNavigation.getModel().getSize(); i++) {
            names.add(settingsNavigation.getModel().getElementAt(i));
        }
        return names;
    }

    /**
     * Whether a category belongs to the application-wide group ("SETTINGS"): General, Appearance and the
     * application pages. Everything else (Chat, Party, the chat modes plugins contribute) is the "CHAT" group.
     */
    public boolean isApplicationSettingsCategory(String category) {
        return java.util.Arrays.asList(APP_SETTINGS_CATEGORIES).contains(category)
                || applicationSettingsPages.containsKey(category);
    }

    /** The category whose page is shown. */
    public String selectedSettingsCategory() {
        ensureSettingsPanes();
        return settingsNavigation.getSelectedValue();
    }

    /** Show this category's page (no-op for unknown names). */
    public void selectSettingsCategory(String category) {
        ensureSettingsPanes();
        attachApplicationSettingsPages();
        if (category != null && settingsNavigation != null) {
            settingsNavigation.setSelectedValue(category, true);
        }
    }

    private void ensureSettingsPanes() {
        refreshAudioProfiles(); // reload so profiles saved in the editor appear immediately
        refreshSpeechOutputVoices(); // voices installed in Models > Setup appear immediately
        refreshPlaybackOutputs(); // playback devices (incl. a newly located VLC) appear immediately
        if (settingsPagesPane == null) {
            buildSettingsPanes();
        }
    }

    private JComponent buildSettingsContent() {
        if (settingsPagesPane == null) {
            buildSettingsPanes();
        }
        JPanel root = new JPanel(new BorderLayout(8, 0));
        root.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        root.add(settingsNavigationPane, BorderLayout.WEST);
        root.add(settingsPagesPane, BorderLayout.CENTER);
        return root;
    }

    private void buildSettingsPanes() {
        final java.awt.CardLayout cardLayout = new java.awt.CardLayout();
        final JPanel cards = new JPanel(cardLayout);
        settingsCardLayout = cardLayout;
        settingsCards = cards;
        JComponent[] partyCards = buildPartyCards();
        java.util.List<String> categories = new java.util.ArrayList<String>();
        // SETTINGS group — application-wide. General carries the notifications and the audio devices;
        // Speech carries dictation and speech output (used in the chat, but not chat-specific).
        cards.add(settingsCard(buildGeneralCard()), SETTINGS_GENERAL);
        cards.add(settingsCard(buildAppearanceCard()), SETTINGS_APPEARANCE);
        cards.add(settingsCard(buildSpeechCard()), SETTINGS_SPEECH);
        categories.addAll(java.util.Arrays.asList(APP_SETTINGS_CATEGORIES));
        // The application pages (formerly separate screens in the gear menu), shared by all tabs.
        for (java.util.Map.Entry<String, JComponent> page : applicationSettingsPages.entrySet()) {
            cards.add(page.getValue(), page.getKey());
            categories.add(page.getKey());
        }
        // CHAT group. Chat: what is really THIS chat's — the system prompt and its technical log.
        cards.add(settingsCard(buildConversationCard()), SETTINGS_CHAT);
        // Party: the built-in chat MODE, its sub-pages as tabs.
        javax.swing.JTabbedPane party = ComicControls.tabbedPane();
        party.addTab("Identity & Room", settingsCard(partyCards[0]));
        party.addTab("Network", settingsCard(partyCards[1]));
        party.addTab("Bot", settingsCard(partyCards[2]));
        party.addTab("History", settingsCard(partyCards[3]));
        cards.add(party, SETTINGS_PARTY);
        categories.addAll(java.util.Arrays.asList(CHAT_SETTINGS_CATEGORIES));
        // Chat modes contributed by plugins (e.g. Research Agent), hooked in dynamically after Party:
        // only while the agent session is ACTIVE in this tab; each page gets the live session so its
        // values stay session-based (two tabs never reconfigure each other).
        if (agentSettingsContributions != null && agentSettingsSession != null) {
            com.aresstack.askai.plugin.api.agent.AgentSession session = agentSettingsSession.get();
            if (session != null) {
                for (com.aresstack.askai.plugin.api.agent.AgentSettingsContribution contribution
                        : agentSettingsContributions.get()) {
                    JComponent component = contribution.createSettingsComponent(session);
                    if (component != null) {
                        String name = contribution.getDisplayName();
                        cards.add(settingsCard(component), name);
                        categories.add(name);
                    }
                }
            }
        }

        final javax.swing.JList<String> navigation =
                ComicControls.<String>list(categories.toArray(new String[0]));
        settingsNavigation = navigation; // openSpeechOutputSettings jumps to a category directly
        navigation.setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION);
        navigation.setSelectedIndex(0);
        navigation.setFixedCellHeight(30);
        navigation.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        navigation.addListSelectionListener(event -> {
            String selected = navigation.getSelectedValue();
            if (selected != null) {
                cardLayout.show(cards, selected);
            }
        });

        JScrollPane navigationScroll = new JScrollPane(navigation);
        navigationScroll.setPreferredSize(new Dimension(180, 10));
        cards.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        settingsNavigationPane = navigationScroll;
        settingsPagesPane = cards;
    }

    /**
     * The Appearance card: the theme choice. Selecting an entry applies it IMMEDIATELY to every open
     * window (live preview, no restart) and persists it as {@code ui.theme}.
     */
    private JComponent buildAppearanceCard() {
        ComicForm theme = ComicForm.titled("Theme");
        final javax.swing.JComboBox<String> themes = com.aresstack.comiccontrols.control.ComicControls.comboBox(
                com.aresstack.askai.ui.theme.ThemeSwitcher.displayNames());
        themes.setSelectedIndex(com.aresstack.askai.ui.theme.ThemeSwitcher.indexOf(
                com.aresstack.comiccontrols.theme.AskAiTheme.active()));
        themes.addActionListener(event -> {
            com.aresstack.comiccontrols.theme.AskAiTheme chosen =
                    com.aresstack.askai.ui.theme.ThemeSwitcher.themeAt(themes.getSelectedIndex());
            if (chosen.name().equals(com.aresstack.comiccontrols.theme.AskAiTheme.active().name())) {
                return;
            }
            com.aresstack.askai.ui.theme.ThemeSwitcher.apply(chosen);
            model.setUiTheme(chosen.name());
            model.saveSettings();
        });
        theme.row("Theme", themes);
        theme.note("Applies immediately to every open window.");
        // The chat bubble colors belong to the look, too (moved here from General).
        return ComicForm.stack(theme, buildColorSettings());
    }

    /** Wraps a card in the vertical-only settings scroll pane so long cards stay usable at small sizes. */
    private static JComponent settingsCard(JComponent card) {
        return ComicForm.scroll(card);
    }

    /**
     * General: application-wide behaviour — currently the desktop notifications (formerly their own
     * category).
     */
    private JComponent buildGeneralCard() {
        ComicForm setup = ComicForm.titled("Setup");
        setupWizardButton.setToolTipText("Opens the setup wizard from the first start");
        setupWizardButton.addActionListener(event -> {
            if (setupWizardOpener != null) {
                setupWizardOpener.run();
            }
        });
        setup.note("Walk through network, AI runtime, models, knowledge and voice again.");
        setup.actions(setupWizardButton);

        JComponent notifications = buildNotificationsCard();
        JComponent devices = buildAudioDevicesForm();
        ComicForm.alignLabels((ComicForm) notifications, (ComicForm) devices);
        return ComicForm.stack(setup, notifications, devices);
    }

    /** Chat: this chat's system prompt and its technical log (both belong to THIS chat only). */
    private JComponent buildConversationCard() {
        ComicForm form = ComicForm.titled("Conversation");
        systemPromptArea.setLineWrap(true);
        systemPromptArea.setWrapStyleWord(true);
        systemPromptArea.setRows(5);
        JScrollPane systemScroll = new JScrollPane(systemPromptArea);
        systemScroll.setPreferredSize(new Dimension(100, 120));
        form.area("System prompt", systemScroll);
        form.note("Model settings (which model, how long it stays loaded) are under Settings > AI models.");
        ComicForm technical = ComicForm.titled("Technical details");
        JScrollPane logScroll = (JScrollPane) buildTechnicalDetails();
        logScroll.setPreferredSize(new Dimension(100, 320));
        technical.component(logScroll);
        technical.note("The technical log of this chat: requests, timings and errors.");
        return ComicForm.stack(form, technical);
    }

    /** Speech: dictation (input) and speech output — application-wide, used by every chat. */
    private JComponent buildSpeechCard() {
        ComicForm input = ComicForm.titled("Dictation");
        audioModelCombo.setEditable(false); // only /api/show-verified models, never free text
        audioModelCombo.addActionListener(event -> persistAudioModelSelection());
        input.row("Audio model", audioModelCombo);

        audioProfileCombo.setToolTipText("Choose the audio-processing profile used for microphone transcription.");
        audioProfileCombo.addActionListener(event -> persistAudioProfileSelection());
        JButton editProfilesButton = new ComicButton("Edit profiles…");
        editProfilesButton.addActionListener(event -> openAudioProcessingSettings());
        input.row("Transcription profile", audioProfileCombo, editProfilesButton);

        input.note("The microphone is chosen under Settings > General > Audio devices.");

        // Hands-free transcription — both ON gives the Gemini feel: talk, pause, it sends.
        final javax.swing.JCheckBox autoSendBox = ComicControls.checkBox(
                "Send transcription automatically",
                model.getSpeechToTextConfiguration().isAutoSendTranscription());
        autoSendBox.setToolTipText("On: the transcribed text is sent immediately after the"
                + " recording stops. Off: review the text first and press Send yourself.");
        autoSendBox.addActionListener(event -> {
            model.setSpeechToTextConfiguration(model.getSpeechToTextConfiguration()
                    .withAutoSendTranscription(autoSendBox.isSelected()));
            model.saveSettings();
        });
        input.check(autoSendBox);

        final javax.swing.JCheckBox autoStopBox = ComicControls.checkBox(
                "Stop recording after a silence of",
                model.getSpeechToTextConfiguration().isAutoStopOnSilence());
        autoStopBox.setToolTipText("On: when you pause speaking for the configured time, the"
                + " recording stops by itself (and transcribes).");
        final javax.swing.JSpinner silenceSpinner = ComicControls.integerSpinner(
                model.getSpeechToTextConfiguration().getAutoStopSilenceSeconds(), 1, 60, 1, 3);
        java.awt.event.ActionListener persistAutoStop = event -> {
            model.setSpeechToTextConfiguration(model.getSpeechToTextConfiguration()
                    .withAutoStopOnSilence(autoStopBox.isSelected())
                    .withAutoStopSilenceSeconds((Integer) silenceSpinner.getValue()));
            model.saveSettings();
        };
        autoStopBox.addActionListener(persistAutoStop);
        silenceSpinner.addChangeListener(event -> persistAutoStop.actionPerformed(null));
        // The same noise gate as the draggable line in the recording waveform — two handles,
        // ONE setting: levels below this percent count as background noise, not as speech.
        final javax.swing.JSpinner thresholdSpinner = ComicControls.integerSpinner(
                model.getSpeechToTextConfiguration().getSignalThresholdPercent(), 0, 95, 1, 3);
        thresholdSpinner.setToolTipText("Signal threshold (percent of full scale): what counts as"
                + " SPEECH. Also draggable as the line inside the recording waveform.");
        thresholdSpinner.addChangeListener(event -> {
            model.setSpeechToTextConfiguration(model.getSpeechToTextConfiguration()
                    .withSignalThresholdPercent((Integer) thresholdSpinner.getValue()));
            model.saveSettings();
        });
        input.inline(autoStopBox, silenceSpinner, new JLabel("seconds, when the signal stays below"),
                thresholdSpinner, new JLabel("%"));

        ComicForm speech = ComicForm.titled("Speech output");
        // One selector PER LANGUAGE (like the NLP models): the session's language switch decides
        // which of these voices reads an answer aloud.
        for (final String language : com.aresstack.askai.tts.TextToSpeechSettings.LANGUAGE_CODES) {
            JComboBox<Object> combo = ComicControls.<Object>comboBox();
            combo.setEditable(false); // Windows default or an INSTALLED Piper voice
            combo.setToolTipText("The read-aloud voice for " + speechLanguageLabel(language)
                    + " answers. Model voices run entirely on the CPU — the GPU stays free."
                    + " Install voices right below.");
            combo.addActionListener(event -> persistSpeechOutputSelection(language));
            speechOutputCombos.put(language, combo);
            JButton speechTestButton = new ComicButton("Test");
            speechTestButton.setToolTipText("Speak a sample sentence with the selected "
                    + speechLanguageLabel(language) + " voice — failures show their reason.");
            speechTestButton.addActionListener(event ->
                    runSpeechOutputTest(language, speechTestButton));
            speech.row("Voice · " + speechLanguageLabel(language), combo, speechTestButton);
        }
        // Read-aloud starts ACTIVE by default: new research answers are spoken without pressing
        // Play (engine-independent — applies to the Windows voice too).
        final javax.swing.JCheckBox readAloudAutoBox = ComicControls.checkBox(
                "Read answers aloud automatically (research chats)",
                ttsSettingsStore.load().isReadAloudAutoStart());
        readAloudAutoBox.setToolTipText("On: a research chat starts with read-aloud active —"
                + " every new answer is spoken. The Play/Pause orb still pauses it anytime.");
        readAloudAutoBox.addActionListener(event -> {
            try {
                ttsSettingsStore.save(ttsSettingsStore.load()
                        .withReadAloudAutoStart(readAloudAutoBox.isSelected()));
            } catch (java.io.IOException notSaved) {
                appendTech("read-aloud auto-start not saved: " + notSaved.getMessage());
            }
        });
        speech.check(readAloudAutoBox);
        // NLP-powered read-aloud refinements (both default ON; without the NLP models — Models >
        // Setup > NLP — everything silently degrades to the plain single-voice behavior).
        final javax.swing.JCheckBox mixedSplitBox = ComicControls.checkBox(
                "Route German/English passages to their own voice (NLP)",
                ttsSettingsStore.load().isMixedLanguageSplit());
        mixedSplitBox.setToolTipText("On: the text is scanned for German and English passages;"
                + " each passage is spoken by that language's voice, in order. Needs the NLP"
                + " language-detection model — without it the single selected voice speaks."
                + " Off: NLP language detection is not used at all.");
        mixedSplitBox.addActionListener(event -> {
            try {
                ttsSettingsStore.save(ttsSettingsStore.load()
                        .withMixedLanguageSplit(mixedSplitBox.isSelected()));
            } catch (java.io.IOException notSaved) {
                appendTech("mixed-language split not saved: " + notSaved.getMessage());
            }
        });
        speech.check(mixedSplitBox);
        // Chunk granularity is the USER'S choice; every chunk synthesizes in a background batch
        // (worker pool), only playback is strictly in reading order — no gaps from synthesis.
        final javax.swing.JComboBox<com.aresstack.askai.tts.TextToSpeechSettings.Chunking>
                chunkingCombo = ComicControls.<com.aresstack.askai.tts
                        .TextToSpeechSettings.Chunking>comboBox(
                        com.aresstack.askai.tts.TextToSpeechSettings.Chunking.values());
        chunkingCombo.setSelectedItem(ttsSettingsStore.load().getChunking());
        chunkingCombo.setToolTipText("How the read-aloud text is cut for synthesis: the whole"
                + " answer, per paragraph, or per sentence. All chunks synthesize in the"
                + " background batch; playback stays in reading order.");
        final javax.swing.JSpinner workersSpinner = ComicControls.integerSpinner(
                ttsSettingsStore.load().getSynthesisWorkers(), 1, 8, 1, 2);
        workersSpinner.setToolTipText("Parallel synthesis workers (CPU processes) preparing the"
                + " chunks in the background.");
        java.awt.event.ActionListener persistChunking = event -> {
            try {
                ttsSettingsStore.save(ttsSettingsStore.load()
                        .withChunking((com.aresstack.askai.tts.TextToSpeechSettings.Chunking)
                                chunkingCombo.getSelectedItem())
                        .withSynthesisWorkers((Integer) workersSpinner.getValue()));
            } catch (java.io.IOException notSaved) {
                appendTech("synthesis chunking not saved: " + notSaved.getMessage());
            }
        };
        chunkingCombo.addActionListener(persistChunking);
        workersSpinner.addChangeListener(event -> persistChunking.actionPerformed(null));
        speech.row("Synthesis chunks", chunkingCombo, new JLabel("Workers"), workersSpinner);
        speech.note("Voices are installed under Settings > AI models > Read-aloud voices.");
        ComicForm.alignLabels(input, speech);
        JComponent card = ComicForm.stack(input, speech);
        // Populate NOW: on the dialog's FIRST open the combos are created here, AFTER
        // openSettingsDialog's refresh already ran against the not-yet-built map — without this
        // call both selectors would sit empty until the dialog is opened a second time.
        refreshSpeechOutputVoices();
        return card;
    }

    /** General > Audio devices: microphone, playback output and the VLC executable (hardware, not chat). */
    private JComponent buildAudioDevicesForm() {
        ComicForm playback = ComicForm.titled("Audio devices");
        micCombo.addActionListener(event -> persistMicrophoneSelection());
        micRefreshButton.addActionListener(event -> refreshMicrophones());
        testMicButton.addActionListener(event -> testMicrophone());
        playback.row("Microphone", micCombo, micRefreshButton, testMicButton);
        // Playback output: the device the recording preview (Play beside Save) and the audio
        // tests use — JavaSound, OpenAL or the VLC sidecar. Persisted; VLC appears once vlc.exe
        // is known (below). This selection used to hide unpersisted in the DSP test panel.
        playbackOutputCombo.setToolTipText("Where recording previews and audio tests play:"
                + " Java Sound, OpenAL or VLC (external process).");
        playbackOutputCombo.addActionListener(event -> persistPlaybackOutputSelection());
        JButton playbackTestButton = new ComicButton("Test");
        playbackTestButton.setToolTipText("Play a short beep through the selected output.");
        playbackTestButton.addActionListener(event -> testPlaybackOutput());
        playback.row("Playback output", playbackOutputCombo, playbackTestButton);

        vlcPathField.setEditable(false);
        vlcPathField.setToolTipText("The vlc.exe used for the VLC output backend."
                + " Empty = automatic detection.");
        JButton vlcBrowseButton = new ComicButton("Browse…");
        vlcBrowseButton.setToolTipText("Select vlc.exe to enable the VLC output backend.");
        vlcBrowseButton.addActionListener(event -> browseVlcExecutable());
        JButton vlcClearButton = new ComicButton("Clear");
        vlcClearButton.setToolTipText("Remove the manual path and fall back to automatic detection.");
        vlcClearButton.addActionListener(event -> {
            vlcInstallation.setExecutable(null);
            updateVlcPathField();
            refreshPlaybackOutputs(); // the VLC entry follows the executable's availability
        });
        updateVlcPathField();
        playback.row("VLC executable", vlcPathField, vlcBrowseButton, vlcClearButton);

        refreshPlaybackOutputs();
        return playback;
    }

    /** Opens the Settings on AI models (where the voices are installed) and marks the recommended voice's row. */
    public void openSpeechOutputSettings(String voiceId) {
        if (settingsOpener != null) {
            settingsOpener.accept(SETTINGS_AI_MODELS);
        } else {
            openSettingsDialog();
            selectSettingsCategory(SETTINGS_AI_MODELS);
        }
        if (speechVoicesPanel != null) {
            speechVoicesPanel.highlightVoice(voiceId);
        }
    }

    /**
     * The application's ONE voice-install section (shown under Settings > AI models, owned by the frame):
     * this chat's voice selectors reload whenever a voice is installed or removed there.
     */
    public void setSpeechVoicesPanel(SpeechOutputModelsPanel panel) {
        if (speechVoicesPanel != null) {
            speechVoicesPanel.removeVoicesChangedListener(speechVoicesListener);
        }
        this.speechVoicesPanel = panel;
        if (panel != null) {
            panel.addVoicesChangedListener(speechVoicesListener);
        }
    }

    /** This chat's registration on the shared voice panel — removed again when the chat closes. */
    private final Runnable speechVoicesListener = new Runnable() {
        public void run() {
            refreshSpeechOutputVoices();
        }
    };

    /**
     * The speech-output "Test" run: speaks the persisted selection IN THIS APP (same classes and
     * audio path read-aloud uses) off the EDT; a failure surfaces with its concrete reason —
     * dialog + Technical Details — never as silence.
     */
    private void runSpeechOutputTest(final String language, final JButton button) {
        button.setEnabled(false);
        final String original = button.getText();
        button.setText("…");
        Thread runner = new Thread(new Runnable() {
            public void run() {
                final com.aresstack.askai.tts.SpeechOutputTester tester =
                        new com.aresstack.askai.tts.SpeechOutputTester(
                                ttsSettingsStore, piperTtsStore);
                String reason;
                try {
                    reason = tester.speakSample(language);
                } catch (RuntimeException unexpected) {
                    reason = "Test crashed: " + unexpected;
                }
                final String outcome = reason;
                onUi(new Runnable() {
                    public void run() {
                        button.setText(original);
                        button.setEnabled(true);
                        if (!tester.lastDetail().isEmpty()) {
                            // ALWAYS on record — 'played N bytes but inaudible' is a finding too.
                            appendTech("speech-output test (" + language + "): "
                                    + tester.lastDetail());
                        }
                        if (!outcome.isEmpty()) {
                            appendTech("speech-output test (" + language + ") failed: " + outcome);
                            javax.swing.JOptionPane.showMessageDialog(button, outcome,
                                    "Speech output test failed",
                                    javax.swing.JOptionPane.ERROR_MESSAGE);
                        }
                    }
                });
            }
        }, "askai-tts-test");
        runner.setDaemon(true);
        runner.start();
    }

    // ------------------------------------------------------------------ playback output

    private com.aresstack.askai.audio.preview.DispatchingAudioPreviewPlaybackService
            previewPlayback() {
        if (previewPlayback == null) {
            previewPlayback =
                    new com.aresstack.askai.audio.preview.DispatchingAudioPreviewPlaybackService();
        }
        return previewPlayback;
    }

    /** Reload the playback-output combo from the catalog and select the persisted device. */
    private void refreshPlaybackOutputs() {
        updatingPlaybackOutputCombo = true;
        try {
            java.util.List<com.aresstack.askai.audio.preview.AudioOutputDevice> devices;
            try {
                devices = new com.aresstack.askai.audio.preview.AudioOutputDeviceCatalog()
                        .findAll();
            } catch (Exception unavailable) {
                devices = java.util.Collections.emptyList();
            }
            playbackOutputCombo.removeAllItems();
            for (com.aresstack.askai.audio.preview.AudioOutputDevice device : devices) {
                playbackOutputCombo.addItem(device);
            }
            com.aresstack.askai.audio.preview.AudioOutputDevice selected =
                    audioPlaybackStore.resolve(devices);
            if (selected != null) {
                playbackOutputCombo.setSelectedItem(selected);
                previewPlayback().setOutputDevice(selected);
            }
        } finally {
            updatingPlaybackOutputCombo = false;
        }
    }

    private void persistPlaybackOutputSelection() {
        if (updatingPlaybackOutputCombo) {
            return;
        }
        Object selected = playbackOutputCombo.getSelectedItem();
        if (!(selected instanceof com.aresstack.askai.audio.preview.AudioOutputDevice)) {
            return;
        }
        com.aresstack.askai.audio.preview.AudioOutputDevice device =
                (com.aresstack.askai.audio.preview.AudioOutputDevice) selected;
        previewPlayback().setOutputDevice(device);
        try {
            audioPlaybackStore.persistSelection(device);
        } catch (java.io.IOException notSaved) {
            appendTech("playback-output selection not saved: " + notSaved.getMessage());
        }
    }

    /** A short beep through the selected playback output — audible confirmation, like the mic test. */
    private void testPlaybackOutput() {
        persistPlaybackOutputSelection();
        previewPlayback().play(com.aresstack.askai.audio.preview.TestTones.beep(),
                com.aresstack.askai.audio.preview.TestTones.BEEP_FORMAT, new Runnable() {
                    public void run() {
                        // nothing to update — the beep is its own feedback
                    }
                });
    }

    /** Show the currently configured VLC path, or a hint that automatic detection is in effect. */
    private void updateVlcPathField() {
        String configured = vlcInstallation.getConfiguredPath();
        vlcPathField.setText(configured.isEmpty() ? "(automatic detection)" : configured);
        vlcPathField.setCaretPosition(0);
    }

    private void browseVlcExecutable() {
        javax.swing.JFileChooser chooser = new javax.swing.JFileChooser();
        chooser.setDialogTitle("Select vlc.exe");
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                "Executables (*.exe)", "exe"));
        if (chooser.showOpenDialog(this) == javax.swing.JFileChooser.APPROVE_OPTION
                && chooser.getSelectedFile() != null) {
            vlcInstallation.setExecutable(chooser.getSelectedFile());
            updateVlcPathField();
            refreshPlaybackOutputs(); // the VLC entry follows the executable's availability
        }
    }

    /** Play/stop the LAST dictation recording through the configured playback output. */
    private void playRecordingPreview() {
        if (previewPlayback != null && previewPlayback.isPlaying()) {
            previewPlayback.stop();
            return;
        }
        final java.io.File wav = dictation.savedRecordingSource();
        if (wav == null) {
            return; // the button is only visible with a savable recording — belt and braces
        }
        Thread player = new Thread(new Runnable() {
            public void run() {
                try {
                    com.aresstack.audio.domain.AudioBuffer buffer =
                            new com.aresstack.askai.audio.preview.WavAudioTestSource(wav, true)
                                    .readBuffer();
                    com.aresstack.askai.audio.preview.DispatchingAudioPreviewPlaybackService
                            playback = previewPlayback();
                    playback.setOutputDevice(audioPlaybackStore.resolve(
                            new com.aresstack.askai.audio.preview.AudioOutputDeviceCatalog()
                                    .findAll()));
                    playback.play(buffer.getSamples(), buffer.getFormat(), new Runnable() {
                        public void run() {
                            // playback finished — nothing to update
                        }
                    });
                } catch (final Exception failed) {
                    onUi(new Runnable() {
                        public void run() {
                            appendTech("recording preview failed: " + failed);
                            setDictationStatus("Recording preview failed: " + failed.getMessage());
                        }
                    });
                }
            }
        }, "askai-recording-preview");
        player.setDaemon(true);
        player.start();
    }

    /** Tab-stop form alignment: every label gets the widest label's width (FlowLayout rows). */
    /** Human label for a speech-output language code (the selector row captions). */
    private static String speechLanguageLabel(String languageCode) {
        if ("de".equals(languageCode)) {
            return "German";
        }
        if ("en".equals(languageCode)) {
            return "English";
        }
        return languageCode;
    }

    /** Reloads every language's combo: the Windows default plus that language's INSTALLED voices. */
    private void refreshSpeechOutputVoices() {
        if (speechVoicesPanel != null) {
            speechVoicesPanel.refresh(); // install rows and combos always tell the same story
        }
        updatingSpeechOutputCombo = true;
        try {
            com.aresstack.askai.tts.TextToSpeechSettings tts = ttsSettingsStore.load();
            for (java.util.Map.Entry<String, JComboBox<Object>> entry
                    : speechOutputCombos.entrySet()) {
                String language = entry.getKey();
                JComboBox<Object> combo = entry.getValue();
                com.aresstack.askai.tts.TextToSpeechSettings.Selection selection =
                        tts.selectionFor(language);
                combo.removeAllItems();
                combo.addItem(SPEECH_OUTPUT_WINDOWS);
                Object desired = SPEECH_OUTPUT_WINDOWS;
                for (com.aresstack.askai.tts.PiperVoice voice
                        : piperTtsStore.installedVoices(language)) {
                    combo.addItem(voice);
                    if (selection.getEngine()
                            == com.aresstack.askai.tts.TextToSpeechSettings.Engine.PIPER
                            && voice.getId().equals(selection.getVoiceId())) {
                        desired = voice; // a selected-but-uninstalled voice falls back to Windows
                    }
                }
                combo.setSelectedItem(desired);
            }
        } finally {
            updatingSpeechOutputCombo = false;
        }
    }

    private void persistSpeechOutputSelection(String language) {
        if (updatingSpeechOutputCombo) {
            return;
        }
        JComboBox<Object> combo = speechOutputCombos.get(language);
        Object selected = combo == null ? null : combo.getSelectedItem();
        com.aresstack.askai.tts.TextToSpeechSettings current = ttsSettingsStore.load();
        com.aresstack.askai.tts.TextToSpeechSettings updated =
                selected instanceof com.aresstack.askai.tts.PiperVoice
                        ? current.withSelection(language,
                                com.aresstack.askai.tts.TextToSpeechSettings.Engine.PIPER,
                                ((com.aresstack.askai.tts.PiperVoice) selected).getId())
                        : current.withSelection(language,
                                com.aresstack.askai.tts.TextToSpeechSettings.Engine.WINDOWS, "");
        try {
            ttsSettingsStore.save(updated);
        } catch (java.io.IOException notSaved) {
            appendTech("speech-output selection not saved: " + notSaved.getMessage());
        }
    }

    /**
     * Desktop notifications for incoming party messages: independent text and sound switches, and
     * the output device for the sound.  When at least one switch is on, the composer shows the
     * mute bell.  Settings apply immediately.
     */
    private JComponent buildNotificationsCard() {
        ComicForm card = ComicForm.titled("Notifications");

        // Every control persists and re-applies immediately, so what you toggle takes effect at
        // once (no separate Apply needed) — and the notifier config always matches the UI.
        final javax.swing.JCheckBox textBox = ComicControls.checkBox(
                "Show a desktop text notification", partySettings.notifyText());
        card.check(textBox);

        final javax.swing.JCheckBox backgroundBox = ComicControls.checkBox(
                "Only when the window is in the background", partySettings.notifyBackgroundOnly());
        card.check(backgroundBox);

        final javax.swing.JCheckBox soundBox = ComicControls.checkBox(
                "Play a notification sound", partySettings.notifySound());
        card.check(soundBox);

        final JComboBox<String> soundTypeCombo = ComicControls.<String>comboBox(new String[] {
                com.aresstack.askai.notify.DesktopNotifier.SOUND_CLICK,
                com.aresstack.askai.notify.DesktopNotifier.SOUND_POP,
                com.aresstack.askai.notify.DesktopNotifier.SOUND_BEEP,
                com.aresstack.askai.notify.DesktopNotifier.SOUND_CHIME});
        soundTypeCombo.setSelectedItem(partySettings.notifySoundType());
        card.row("Sound", soundTypeCombo);
        final javax.swing.JSlider volumeSlider =
                ComicControls.slider(0, 100, partySettings.notifyVolume());
        volumeSlider.setPreferredSize(new Dimension(160, volumeSlider.getPreferredSize().height));
        card.row("Volume", volumeSlider);

        final JComboBox<String> deviceCombo = ComicControls.<String>comboBox();
        for (String name : com.aresstack.askai.notify.DesktopNotifier.outputDeviceNames()) {
            deviceCombo.addItem(name);
        }
        String device = partySettings.notifySoundDevice();
        deviceCombo.setSelectedItem(device == null || device.isEmpty()
                ? com.aresstack.askai.notify.DesktopNotifier.SYSTEM_DEFAULT_DEVICE : device);
        card.row("Sound device", deviceCombo);

        // Persist + re-apply on any change.
        final Runnable persist = new Runnable() {
            public void run() {
                partySettings.setNotifyText(textBox.isSelected());
                partySettings.setNotifyBackgroundOnly(backgroundBox.isSelected());
                partySettings.setNotifySound(soundBox.isSelected());
                partySettings.setNotifySoundType(String.valueOf(soundTypeCombo.getSelectedItem()));
                partySettings.setNotifyVolume(volumeSlider.getValue());
                Object selectedDevice = deviceCombo.getSelectedItem();
                partySettings.setNotifySoundDevice(
                        selectedDevice == null || com.aresstack.askai.notify.DesktopNotifier.SYSTEM_DEFAULT_DEVICE
                                .equals(selectedDevice) ? "" : String.valueOf(selectedDevice));
                applyNotificationSettings();
            }
        };
        java.awt.event.ActionListener onChange = event -> persist.run();
        textBox.addActionListener(onChange);
        backgroundBox.addActionListener(onChange);
        soundBox.addActionListener(onChange);
        soundTypeCombo.addActionListener(onChange);
        deviceCombo.addActionListener(onChange);
        volumeSlider.addChangeListener(event -> {
            if (!volumeSlider.getValueIsAdjusting()) {
                persist.run();
            }
        });

        JButton testButton = new ComicButton("Test sound");
        testButton.setToolTipText("Play the selected notification sound at the chosen volume");
        testButton.addActionListener(event -> {
            persist.run();
            boolean wasMuted = partySettings.notificationsMuted();
            notifier.setMuted(false);
            notifier.notifyMessage("AskAI", "This is a test notification.");
            notifier.setMuted(wasMuted);
        });
        card.actions(testButton);
        card.note("The mute bell in the composer appears while a switch is on; it silences everything.");
        return card;
    }

    /** No preferred participant color — the deterministic assignment picks a free one. */
    private static final String PARTY_COLOR_AUTOMATIC = "(automatic)";

    /**
     * The Partying settings, split over three cards: identity &amp; room, network, and bot.
     * All cards share one "Apply party settings" action; values apply on the next join except
     * the bot options, which are read live.
     *
     * @return the three cards in navigation order: identity &amp; room, network, bot
     */
    private JComponent[] buildPartyCards() {
        final JTextField nameField = ComicControls.textField(partySettings.displayName(), 12);
        final JComboBox<String> colorCombo = ComicControls.<String>comboBox();
        colorCombo.addItem(PARTY_COLOR_AUTOMATIC);
        for (String token : ParticipantColorPalette.tokens()) {
            colorCombo.addItem(token);
        }
        String preferred = partySettings.preferredColor();
        colorCombo.setSelectedItem(preferred != null ? preferred : PARTY_COLOR_AUTOMATIC);

        final javax.swing.JCheckBox discoveryBox = ComicControls.checkBox(
                "Automatic LAN discovery (UDP multicast)", partySettings.discoveryEnabled());
        final JTextField interfaceField = ComicControls.textField(
                partySettings.networkInterface() == null ? "" : partySettings.networkInterface(), 8);
        interfaceField.setToolTipText("Network interface to bind, empty for automatic selection");
        final JTextField peersField = ComicControls.textField(partySettings.manualPeersText(), 24);
        peersField.setToolTipText(
                "Manual peer addresses (host or host:port, comma-separated) when multicast is blocked");

        final JComboBox<String> botPolicyCombo = ComicControls.<String>comboBox();
        botPolicyCombo.addItem("Answer only when @AskAI is mentioned");
        botPolicyCombo.addItem("See every message and decide (always)");
        botPolicyCombo.addItem("Never answer");
        String policy = partySettings.botPolicy();
        botPolicyCombo.setSelectedIndex(PartySettings.BOT_POLICY_OFF.equals(policy) ? 2
                : PartySettings.BOT_POLICY_ALWAYS.equals(policy) ? 1 : 0);
        final javax.swing.JCheckBox modelMentionsBox = ComicControls.checkBox(
                "Allow @modelname mentions", partySettings.modelMentionsEnabled());
        modelMentionsBox.setToolTipText(
                "Address a specific installed model directly, e.g. @gemma4:e2b — loaded models are highlighted in the completion");
        final javax.swing.JCheckBox gateBox = ComicControls.checkBox(
                "Pre-check unprompted replies with a YES/NO gate", partySettings.chimeInGateEnabled());
        gateBox.setToolTipText(
                "Extra short model call deciding whether to chime in under the \"always\" policy. "
                        + "Recommended for small models; large models that follow the [SILENT] contract "
                        + "reliably can disable it and save the call.");
        final JComboBox<String> gateThinkingCombo = thinkingLevelCombo(partySettings.botGateThinking());
        gateThinkingCombo.setToolTipText("Thinking effort for the gate decision — Off keeps it fast.");
        final JComboBox<String> correctionThinkingCombo =
                thinkingLevelCombo(partySettings.botCorrectionThinking());
        correctionThinkingCombo.setToolTipText(
                "Thinking effort for the actual unprompted correction (only if the model can think).");
        final JComboBox<String> contextModeCombo = ComicControls.<String>comboBox();
        contextModeCombo.addItem("Users as one collective (merged chat turns)");
        contextModeCombo.addItem("Answer the mentioning message (transcript as context)");
        contextModeCombo.addItem("Every message as its own chat turn");
        String contextMode = partySettings.botContextMode();
        contextModeCombo.setSelectedIndex(
                PartySettings.BOT_CONTEXT_TRANSCRIPT.equals(contextMode) ? 1
                        : PartySettings.BOT_CONTEXT_CONVERSATION.equals(contextMode) ? 2 : 0);

        String customPrompt = partySettings.botSystemPrompt();
        final JTextArea botPromptArea = new GhostHintTextArea(customPrompt != null ? customPrompt : "",
                com.aresstack.askai.party.OllamaBotResponder.DEFAULT_SYSTEM_PROMPT, 6, 40);
        botPromptArea.setToolTipText(
                "Custom system prompt for the party bot. Leave empty for the shown built-in default.");
        String customAlwaysPrompt = partySettings.botAlwaysPrompt();
        final JTextArea alwaysPromptArea = new GhostHintTextArea(
                customAlwaysPrompt != null ? customAlwaysPrompt : "",
                com.aresstack.askai.party.OllamaBotResponder.DEFAULT_ALWAYS_PROMPT, 6, 40);
        alwaysPromptArea.setToolTipText(
                "Used with the \"always\" policy: explains when the bot should chime in unprompted. "
                        + "Leave empty for the shown built-in default.");

        final JTextField roomField = ComicControls.textField(partySettings.roomId(), 10);
        final JTextField secretField = ComicControls.textField(partySettings.roomSecret(), 10);
        secretField.setToolTipText("Room invitation secret: authenticates the join and encrypts traffic");
        final JTextField historyField = ComicControls.textField(
                partySettings.historyDirectory().getAbsolutePath(), 24);

        final javax.swing.JCheckBox ageCapBox = ComicControls.checkBox(
                "Delete history older than", partySettings.historyAgeCapEnabled());
        final javax.swing.JSpinner ageDaysSpinner = ComicControls.spinner(
                new javax.swing.SpinnerNumberModel(partySettings.historyMaxAgeDays(), 1, 3650, 1));
        final javax.swing.JCheckBox sizeCapBox = ComicControls.checkBox(
                "Keep total history under", partySettings.historySizeCapEnabled());
        final javax.swing.JSpinner sizeMbSpinner = ComicControls.spinner(
                new javax.swing.SpinnerNumberModel(partySettings.historyMaxSizeMb(), 1, 100000, 10));
        final javax.swing.JSpinner recordMbSpinner = ComicControls.spinner(
                new javax.swing.SpinnerNumberModel(partySettings.historyMaxRecordMb(), 1, 1024, 1));

        // One shared apply action; each card carries its own button for it.
        final java.awt.event.ActionListener applyAction = event -> {
            partySettings.setDisplayName(nameField.getText());
            Object color = colorCombo.getSelectedItem();
            partySettings.setPreferredColor(
                    PARTY_COLOR_AUTOMATIC.equals(color) ? null : String.valueOf(color));
            partySettings.setDiscoveryEnabled(discoveryBox.isSelected());
            partySettings.setNetworkInterface(interfaceField.getText());
            partySettings.setManualPeers(peersField.getText());
            int policyIndex = botPolicyCombo.getSelectedIndex();
            partySettings.setBotPolicy(policyIndex == 2 ? PartySettings.BOT_POLICY_OFF
                    : policyIndex == 1 ? PartySettings.BOT_POLICY_ALWAYS
                    : PartySettings.BOT_POLICY_MENTION);
            partySettings.setModelMentionsEnabled(modelMentionsBox.isSelected());
            partySettings.setChimeInGateEnabled(gateBox.isSelected());
            partySettings.setBotGateThinking((String) gateThinkingCombo.getSelectedItem());
            partySettings.setBotCorrectionThinking((String) correctionThinkingCombo.getSelectedItem());
            int contextIndex = contextModeCombo.getSelectedIndex();
            partySettings.setBotContextMode(contextIndex == 1 ? PartySettings.BOT_CONTEXT_TRANSCRIPT
                    : contextIndex == 2 ? PartySettings.BOT_CONTEXT_CONVERSATION
                    : PartySettings.BOT_CONTEXT_COLLECTIVE);
            partySettings.setBotSystemPrompt(botPromptArea.getText());
            partySettings.setBotAlwaysPrompt(alwaysPromptArea.getText());
            partySettings.setRoomId(roomField.getText());
            partySettings.setRoomSecret(secretField.getText());
            partySettings.setHistoryDirectory(historyField.getText());
            partySettings.setHistoryAgeCapEnabled(ageCapBox.isSelected());
            partySettings.setHistoryMaxAgeDays((Integer) ageDaysSpinner.getValue());
            partySettings.setHistorySizeCapEnabled(sizeCapBox.isSelected());
            partySettings.setHistoryMaxSizeMb((Integer) sizeMbSpinner.getValue());
            partySettings.setHistoryMaxRecordMb((Integer) recordMbSpinner.getValue());
            refreshMentionCompletionHandles();
            // A policy change flips this peer's bot capability; announce it to the room.
            final PartySession session = partySession;
            if (session != null) {
                dictationExecutor.execute(new Runnable() {
                    public void run() {
                        try {
                            session.updateBotReadiness();
                        } catch (Exception ignored) {
                        }
                    }
                });
            }
            setStatus("Party settings saved — network changes apply on the next join.");
        };

        // ---- Card 1: identity & room
        ComicForm identity = ComicForm.titled("Identity");
        nameField.setToolTipText("How the other participants see you.");
        identity.row("Display name", nameField);
        colorCombo.setToolTipText("Your bubble color in the room; automatic picks a free one.");
        identity.row("Preferred color", colorCombo);
        ComicForm room = ComicForm.titled("Room");
        room.row("Room", roomField);
        room.row("Secret", secretField);
        ComicForm identityActions = new ComicForm();
        identityActions.actions(partyApplyButton(applyAction));
        ComicForm.alignLabels(identity, room);
        JComponent identityCard = ComicForm.stack(identity, room, identityActions);

        // ---- Card 2: network
        ComicForm network = ComicForm.titled("Network");
        network.check(discoveryBox);
        network.row("Interface", interfaceField);
        network.row("Manual peers", peersField);
        network.note("Manual peers: host or host:port, comma-separated — for networks that block multicast.");
        JButton diagnosticsButton = new ComicButton("Network diagnostics");
        diagnosticsButton.setToolTipText("Check multicast/firewall readiness of the local network interfaces");
        diagnosticsButton.addActionListener(event -> {
            appendTech(JGroupsGroupChatTransport.diagnoseMulticast());
            setStatus("Network diagnostics written to Technical details.");
        });
        ComicForm networkActions = new ComicForm();
        networkActions.actions(partyApplyButton(applyAction), diagnosticsButton);
        JComponent networkCard = ComicForm.stack(network, networkActions);

        // ---- Card 3: bot
        ComicForm bot = ComicForm.titled("Bot");
        bot.row("Answers", botPolicyCombo);
        bot.check(modelMentionsBox);
        bot.check(gateBox);
        bot.row("Thinking for the gate", gateThinkingCombo);
        bot.row("Thinking for corrections", correctionThinkingCombo);
        bot.row("Context", contextModeCombo);
        ComicForm prompts = ComicForm.titled("Prompts");
        botPromptArea.setRows(5);
        JScrollPane promptScroll = new JScrollPane(botPromptArea);
        promptScroll.setPreferredSize(new Dimension(100, 110));
        prompts.area("Bot system prompt (empty = built-in default)", promptScroll);
        alwaysPromptArea.setRows(5);
        JScrollPane alwaysScroll = new JScrollPane(alwaysPromptArea);
        alwaysScroll.setPreferredSize(new Dimension(100, 110));
        prompts.area("When to chime in under the \"always\" policy (empty = built-in default)", alwaysScroll);
        ComicForm botActions = new ComicForm();
        botActions.actions(partyApplyButton(applyAction));
        JComponent botCard = ComicForm.stack(bot, prompts, botActions);

        // ---- Card 4: history
        ComicForm history = ComicForm.titled("History");
        history.row("History folder", historyField);
        history.inline(ageCapBox, ageDaysSpinner, new JLabel("days"));
        history.inline(sizeCapBox, sizeMbSpinner, new JLabel("MB"));
        history.row("Max single message", recordMbSpinner, new JLabel("MB"));
        history.note("Caps are applied when the room is (re)joined. Empty history folder = no persistence."
                + " History lives on the participants' machines; messages no reachable peer remembers"
                + " cannot be restored.");
        JButton clearHistoryButton = new ComicButton("Clear history now");
        clearHistoryButton.setToolTipText("Delete this room's stored history on this machine");
        clearHistoryButton.addActionListener(event -> clearPartyHistory());
        ComicForm historyActions = new ComicForm();
        historyActions.actions(partyApplyButton(applyAction), clearHistoryButton);
        JComponent historyCard = ComicForm.stack(history, historyActions);

        return new JComponent[] {identityCard, networkCard, botCard, historyCard};
    }

    /**
     * Clears this room's persisted history: through the live session when joined (so the open log
     * handle is reset safely), otherwise by deleting the on-disk log for the configured room.  The
     * on-screen transcript is cleared too when Partying is active.
     */
    private void clearPartyHistory() {
        final PartySession session = partySession;
        if (session != null) {
            dictationExecutor.execute(new Runnable() {
                public void run() {
                    try {
                        session.clearHistory();
                    } catch (Exception ignored) {
                    }
                }
            });
        } else {
            com.aresstack.askai.groupchat.FileRoomHistoryLog.deleteLog(
                    partySettings.historyDirectory(), partySettings.roomId());
        }
        if (GroupChatMode.PARTYING.equals(chatMode)) {
            transcript.clear();
            transcript.appendInfo("Party history cleared.");
        }
        setStatus("Party history cleared.");
    }

    /** A thinking-level dropdown (Off/Low/Medium/High) preselected to {@code current}. */
    private static JComboBox<String> thinkingLevelCombo(String current) {
        JComboBox<String> combo = ComicControls.<String>comboBox(new String[] {"off", "low", "medium", "high"});
        combo.setSelectedItem(current == null ? "off" : current);
        return combo;
    }

    /** The "Apply party settings" button wired to the shared apply action. */
    private static JButton partyApplyButton(java.awt.event.ActionListener applyAction) {
        JButton applyButton = new ComicButton("Apply party settings");
        applyButton.setToolTipText("Saved immediately; network changes take effect on the next join");
        applyButton.addActionListener(applyAction);
        return applyButton;
    }

    /**
     * A text area that shows the effective built-in default as muted, wrapped ghost text while it
     * is empty and unfocused — clicking in makes the hint disappear; leaving it empty keeps the
     * default active.
     */
    private static final class GhostHintTextArea extends JTextArea {
        private final String hint;

        GhostHintTextArea(String text, String hint, int rows, int columns) {
            super(text, rows, columns);
            this.hint = hint;
            setLineWrap(true);
            setWrapStyleWord(true);
            addFocusListener(new java.awt.event.FocusAdapter() {
                public void focusGained(java.awt.event.FocusEvent event) {
                    repaint();
                }

                public void focusLost(java.awt.event.FocusEvent event) {
                    repaint();
                }
            });
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            if (!getText().isEmpty() || isFocusOwner() || hint == null) {
                return;
            }
            java.awt.Graphics2D g2 = (java.awt.Graphics2D) graphics.create();
            g2.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING,
                    java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setColor(ThemeColor.of("chat.placeholderText"));
            g2.setFont(getFont().deriveFont(java.awt.Font.ITALIC));
            java.awt.FontMetrics metrics = g2.getFontMetrics();
            java.awt.Insets insets = getInsets();
            int available = Math.max(24, getWidth() - insets.left - insets.right);
            int y = insets.top + metrics.getAscent();
            StringBuilder line = new StringBuilder();
            for (String word : hint.split(" ")) {
                String candidate = line.length() == 0 ? word : line + " " + word;
                if (metrics.stringWidth(candidate) > available && line.length() > 0) {
                    g2.drawString(line.toString(), insets.left, y);
                    y += metrics.getHeight();
                    line = new StringBuilder(word);
                } else {
                    line = new StringBuilder(candidate);
                }
            }
            if (line.length() > 0) {
                g2.drawString(line.toString(), insets.left, y);
            }
            g2.dispose();
        }
    }

    /** Color pickers for the chat bubble colors — persisted and applied to the transcript immediately. */
    private JComponent buildColorSettings() {
        ComicForm colors = ComicForm.titled("Chat colors");
        java.util.List<JButton> swatches = new java.util.ArrayList<JButton>();
        swatches.add(colorButton("Background",
                () -> model.getChatColors().getTranscriptBackground(),
                c -> model.setChatColors(model.getChatColors().withTranscriptBackground(c))));
        swatches.add(colorButton("Your bubble",
                () -> model.getChatColors().getUserBackground(),
                c -> model.setChatColors(model.getChatColors().withUserBackground(c))));
        swatches.add(colorButton("Your text",
                () -> model.getChatColors().getUserForeground(),
                c -> model.setChatColors(model.getChatColors().withUserForeground(c))));
        swatches.add(colorButton("Reply bubble",
                () -> model.getChatColors().getAssistantBackground(),
                c -> model.setChatColors(model.getChatColors().withAssistantBackground(c))));
        swatches.add(colorButton("Reply text",
                () -> model.getChatColors().getAssistantForeground(),
                c -> model.setChatColors(model.getChatColors().withAssistantForeground(c))));
        JButton reset = new ComicButton("Reset");
        reset.setToolTipText("Restore the default chat colors");
        reset.addActionListener(event -> {
            model.setChatColors(com.aresstack.askai.config.ChatColorSettings.defaults());
            model.saveSettings();
            transcript.applyColors(model.getChatColors());
            for (Runnable refresh : colorSwatchRefreshers) {
                refresh.run();
            }
        });
        swatches.add(reset);
        colors.actions(swatches.toArray(new JButton[0]));
        colors.note("Click a swatch to pick a color; changes apply to the transcript immediately.");
        return colors;
    }

    /** A labelled button with a swatch of its current color; picking a new one persists + applies it live. */
    private JButton colorButton(final String label, final Supplier<Color> current, final Consumer<Color> apply) {
        final JButton button = new ComicButton(label, swatchIcon(current.get()));
        button.addActionListener(event -> {
            Color chosen = JColorChooser.showDialog(OllamaChatPanel.this, "Choose color: " + label, current.get());
            if (chosen != null) {
                apply.accept(chosen);
                model.saveSettings();
                transcript.applyColors(model.getChatColors());
                button.setIcon(swatchIcon(chosen));
            }
        });
        colorSwatchRefreshers.add(new Runnable() {
            public void run() {
                button.setIcon(swatchIcon(current.get()));
            }
        });
        return button;
    }

    /** @return a small square icon filled with {@code color} and a subtle border, for the color buttons. */
    private static Icon swatchIcon(final Color color) {
        return new Icon() {
            public void paintIcon(Component c, Graphics g, int x, int y) {
                g.setColor(color);
                g.fillRect(x, y, 14, 14);
                g.setColor(ThemeColor.of("chat.colorSwatchBorder"));
                g.drawRect(x, y, 14, 14);
            }

            public int getIconWidth() {
                return 15;
            }

            public int getIconHeight() {
                return 15;
            }
        };
    }

    private javax.swing.JDialog settingsDialog;
    /** One per color button: re-reads its current color into its swatch (used by "Reset"). */
    private final List<Runnable> colorSwatchRefreshers = new ArrayList<Runnable>();

    // The chat list's activity read-model: wired by the frame to the coordinator's per-scope state.
    private java.util.function.BooleanSupplier agentScopeBusyProbe;
    private java.util.function.Supplier<String> agentScopePhaseProbe;

    /** Wire this tab's agent activity probes (busy + phase label) to the authoritative runtime. */
    public void setAgentActivityProbes(java.util.function.BooleanSupplier busyProbe,
                                       java.util.function.Supplier<String> phaseProbe) {
        this.agentScopeBusyProbe = busyProbe;
        this.agentScopePhaseProbe = phaseProbe;
    }

    /**
     * True while THIS chat actually processes something right now: the local Ollama stream is
     * running, or one of this tab's agent sessions reports busy in its OWN state snapshot. A
     * merely existing/registered session is NOT busy — that distinction is the whole point.
     */
    public boolean isProcessingBusy() {
        if (chatBusy) {
            return true;
        }
        return agentScopeBusyProbe != null && agentScopeBusyProbe.getAsBoolean();
    }

    /** This tab's agent phase label (e.g. "SCOPING"), or "" when no session carries one. */
    public String describeAgentPhaseForList() {
        String label = agentScopePhaseProbe == null ? null : agentScopePhaseProbe.get();
        return label == null ? "" : label;
    }

    /**
     * The active agent's display label for the drawer's chat-list metadata line (Questing only),
     * or {@code null} — the mode is workspace-global, so this reads the shared controller.
     */
    public String describeActiveAgentForList() {
        if (modeController == null || !com.aresstack.askai.plugin.host.WorkspaceModeEntry.QUESTING_ID
                .equals(modeController.getInteractionMode())) {
            return null;
        }
        return modeController.getActiveAgentLabel();
    }

    /** Opens the (modeless) chat settings dialog — triggered by the drawer footer's gear button. */
    public void openSettingsDialog() {
        if (settingsOpener != null) {
            settingsOpener.accept(null); // the workspace shows the settings in drawer + main area
            return;
        }
        ensureSettingsPanes();
        if (settingsDialog == null) {
            java.awt.Window owner = SwingUtilities.getWindowAncestor(this);
            settingsDialog = new javax.swing.JDialog(
                    owner instanceof java.awt.Frame ? (java.awt.Frame) owner : null, "Chat settings", false);
            JComponent content = buildSettingsContent();
            settingsDialog.setContentPane(content); // cards scroll individually, no outer scroll
            settingsDialog.pack();
            settingsDialog.setSize(new Dimension(Math.max(760, settingsDialog.getWidth()), 520));
            settingsDialog.setLocationRelativeTo(this);
        }
        settingsDialog.setVisible(true);
        settingsDialog.toFront();
    }

    private JComponent buildComposer() {
        // The mode selector moved into the drawer's Chats FOOTER (next to the language pill);
        // its logic (openModePopupAt/currentModeLabel) stays here — only the button left.
        composer.setModeSelectorVisible(false);
        // Dragging the waveform's threshold line DURING a recording persists the noise gate
        // immediately — the very next timer tick judges signal/silence with the new value.
        composer.setWaveformThresholdListener(new ComposerWaveformOverlay.ThresholdListener() {
            public void thresholdChanged(int percent) {
                model.setSpeechToTextConfiguration(model.getSpeechToTextConfiguration()
                        .withSignalThresholdPercent(percent));
                model.saveSettings();
            }
        });
        composer.setChatStatus("Select a model and start chatting.");
        composer.setDictationStatus(" ");
        refreshDictationControls();
        applyNotificationSettings();
        return composer;
    }

    /** Push the persisted notification settings into the notifier and the composer bell. */
    private void applyNotificationSettings() {
        boolean text = partySettings.notifyText();
        boolean sound = partySettings.notifySound();
        boolean muted = partySettings.notificationsMuted();
        notifier.configure(text, sound, partySettings.notifySoundDevice(),
                partySettings.notifySoundType(), partySettings.notifyVolume());
        notifier.setMuted(muted);
        composer.setNotificationsButtonVisible(text || sound);
        composer.setNotificationsMuted(muted);
    }

    /**
     * Fire a desktop notification for an incoming message, honoring the "only when in background"
     * preference (skipped when that is on and this window is the foreground window).
     */
    private void fireMessageNotification(String title, String body) {
        if (partySettings.notifyBackgroundOnly() && windowActive) {
            return;
        }
        notifier.notifyMessage(title, body);
    }

    /** The composer bell toggles the persisted mute state and updates the notifier + icon. */
    private void toggleNotificationsMute() {
        boolean muted = !partySettings.notificationsMuted();
        partySettings.setNotificationsMuted(muted);
        notifier.setMuted(muted);
        composer.setNotificationsMuted(muted);
        setStatus(muted ? "Notifications muted." : "Notifications on.");
    }

    // ------------------------------------------------------------------ global catalog snapshot

    /**
     * Apply a globally-refreshed catalog to this chat without re-querying Ollama, preserving this tab's own
     * selection (model by name, audio model / profile by their persisted ids). Only parts that loaded
     * successfully are applied, so a partial failure never clears a working list.
     */
    public void applyCatalogSnapshot(GlobalCatalogSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        if (snapshot.isModelsLoaded()) {
            applyModelNames(snapshot.getChatModels());
        }
        if (snapshot.isAudioModelsLoaded()) {
            setAudioModelItems(snapshot.getAudioModels());
        }
        if (snapshot.isProfilesLoaded()) {
            refreshAudioProfiles(); // profiles are local; re-read the (just-refreshed) repository
        }
    }

    /** Repopulate the (off-screen) model selector, keeping the current model selected when it survives. */
    private void applyModelNames(List<String> names) {
        Object previous = modelCombo.getSelectedItem();
        modelCombo.removeAllItems();
        for (String name : names) {
            modelCombo.addItem(name);
        }
        String restored = consumePendingRestoreModel(names);
        if (restored != null) {
            modelCombo.setSelectedItem(restored);
            // Self-healing sync: the restored chat model IS the global main model for all plugins. Persist it
            // so ai.mainModel is never empty while the chat actually uses a model — otherwise the research
            // agent (which reads ai.mainModel) reports "no main model" even though Yapping works fine.
            model.persistMainModel(restored);
        } else if (previous != null) {
            modelCombo.setSelectedItem(previous);
        }
        composer.setModelName((String) modelCombo.getSelectedItem());
        // Cached for the party threads (@modelname mention handles) — safe to read off the EDT.
        installedModelNames = new ArrayList<String>(names);
    }

    // ------------------------------------------------------------------ chat (unchanged behaviour)

    private void refreshModels() {
        setStatus("Loading models from " + model.getOllamaBaseUrl() + " ...");
        // CHAT list: local models appear only when they can actually chat (capability-filtered).
        ollamaService.listChatModelNames(new OllamaService.ModelNamesListener() {
            public void onModelNames(final List<String> names) {
                onUi(new Runnable() {
                    public void run() {
                        applyModelNames(names);
                        refreshAudioModels(names);
                        refreshReasoningForModel((String) modelCombo.getSelectedItem());
                        setStatus(names.isEmpty() ? "No models installed. Open Install to add one." : " ");
                    }
                });
            }

            public void onError(final Exception ex) {
                onUi(new Runnable() {
                    public void run() {
                        setStatus("Server not reachable. Check Connections.");
                        transcript.appendInfo("Connection error: " + ex.getMessage());
                    }
                });
            }
        });
    }

    /** Selects {@code modelName} as the active chat model without touching the conversation. */
    public void useModel(String modelName) {
        if (modelName == null || modelName.trim().length() == 0) {
            return;
        }
        boolean present = false;
        for (int i = 0; i < modelCombo.getItemCount(); i++) {
            if (modelName.equals(modelCombo.getItemAt(i))) {
                present = true;
                break;
            }
        }
        if (!present) {
            modelCombo.addItem(modelName);
        }
        modelCombo.setSelectedItem(modelName);
        composer.setModelName(modelName);
        refreshReasoningForModel(modelName);
        rememberState(STATE_LAST_MODEL, modelName);
        model.persistMainModel(modelName); // the chat model IS the global main model for all plugins
        transcript.appendInfo("Now chatting with " + modelName + ".");
        setStatus("Model set to " + modelName + ".");
    }

    /** Opens the ChatGPT-style model picker anchored to the composer's model button. */
    private void openModelPopup() {
        javax.swing.JPopupMenu menu = new javax.swing.JPopupMenu();
        String current = (String) modelCombo.getSelectedItem();
        if (modelCombo.getItemCount() == 0) {
            javax.swing.JMenuItem none = new javax.swing.JMenuItem("No models — open Install");
            none.addActionListener(event -> openInstallAudioModel());
            menu.add(none);
        } else {
            for (int i = 0; i < modelCombo.getItemCount(); i++) {
                final String name = modelCombo.getItemAt(i);
                javax.swing.JRadioButtonMenuItem item =
                        new javax.swing.JRadioButtonMenuItem(name, name.equals(current));
                item.addActionListener(event -> {
                    modelCombo.setSelectedItem(name);
                    composer.setModelName(name);
                    refreshReasoningForModel(name);
                    rememberState(STATE_LAST_MODEL, name);
                    model.persistMainModel(name); // the chat model IS the global main model for all plugins
                });
                menu.add(item);
            }
        }
        menu.addSeparator();
        javax.swing.JMenuItem refresh = new javax.swing.JMenuItem("Refresh models");
        refresh.addActionListener(event -> refreshModels());
        menu.add(refresh);
        JComponent anchor = composer.getModelButton();
        menu.show(anchor, 0, anchor.getHeight());
    }

    /**
     * Binds the agent submission router. When an agent session is active (Questing), the same composer routes
     * plain prompts and stop to the agent instead of Ollama. Yapping keeps the existing Ollama path untouched.
     */
    public void setChatSubmissionRouter(com.aresstack.askai.plugin.host.ChatSubmissionRouter router) {
        if (this.chatSubmissionRouter != null) {
            this.chatSubmissionRouter.removeChangeListener(routerChangeListener);
        }
        this.chatSubmissionRouter = router;
        if (router != null) {
            router.addChangeListener(routerChangeListener);
        }
        refreshAgentComposerState();
    }

    /** Binds the active-agent command registry that powers slash-command completion in the shared composer. */
    public void setAgentCommandRegistry(com.aresstack.askai.plugin.host.ActiveAgentCommandRegistry registry) {
        this.agentCommandRegistry = registry;
        if (slashPopup != null) {
            slashPopup.setRegistry(registry);
        }
    }

    /**
     * @return the shared conversation sink an agent session pushes its activity into — the SAME transcript as
     *         the normal chat. Created lazily; there is never a second conversation surface.
     */
    public com.aresstack.askai.plugin.api.agent.AgentConversationSink getAgentConversationSink() {
        if (agentConversationSink == null) {
            agentConversationSink = new AskAiAgentConversationSink(transcript, new Runnable() {
                public void run() {
                    refreshAgentComposerState();
                }
            }, new AskAiAgentConversationSink.TechnicalLog() {
                public void line(String line) {
                    appendTech(line);
                }
            }, new AskAiAgentConversationSink.MessagePersister() {
                // Persist the agent CONVERSATION exactly like a normal chat, so it survives a restart. The
                // research phase/state is persisted separately by the plugin; here we only save the bubbles.
                public void persistUser(String messageId, String text) {
                    persistUserMessage(messageId, text,
                            java.util.Collections.<ImageAttachment>emptyList());
                }

                public void persistAssistant(String messageId, String text) {
                    persistAssistantMessage(messageId, text, "Agent");
                }

                public void persistInfo(String messageId, String text) {
                    persistInfoMessage(messageId, text);
                }
            });
        }
        return agentConversationSink;
    }

    /** Tracks the agent busy state so the busy→idle edge can restore the editor focus (like yapping). */
    private boolean agentComposerBusy;

    /** Reflects the active agent's availability onto the composer's busy (Send/Stop) state when routing to it. */
    private void refreshAgentComposerState() {
        if (chatSubmissionRouter != null && chatSubmissionRouter.isActive()) {
            boolean busy = chatSubmissionRouter.getAvailability()
                    == com.aresstack.askai.plugin.api.agent.SubmissionAvailability.BUSY;
            boolean turnJustEnded = agentComposerBusy && !busy;
            agentComposerBusy = busy;
            composer.setChatBusy(busy);
            if (turnJustEnded && isShowing()) {
                // The editor was disabled during the agent turn and lost the focus with that; give it
                // back exactly like the yapping path's setBusy(false) does, so the user can just type.
                composer.focusEditor();
            }
        } else {
            agentComposerBusy = false;
            // Not routing to an agent: the normal Ollama busy state governs Send/Stop again.
            composer.setChatBusy(chatBusy);
        }
    }

    /** Binds the existing composer mode selector to the shared host controller (single source of truth). */
    public void setWorkspaceModeController(com.aresstack.askai.plugin.host.WorkspaceModeController controller) {
        if (this.modeController != null) {
            this.modeController.removeChangeListener(modeChangeListener);
        }
        this.modeController = controller;
        if (controller != null) {
            controller.addChangeListener(modeChangeListener);
            reflectMode();
        }
    }

    /**
     * The in-composer mode selector. "Yapping" (direct model chat) and "Questing" (submenu of installed
     * agents) are driven by the {@link com.aresstack.askai.plugin.host.WorkspaceModeController};
     * "Partying" is the local decentralized LAN group-chat mode ({@link GroupChatMode#PARTYING}) that
     * overlays plain Yapping and never routes to an agent.
     */
    private void openModePopup() {
        openModePopupAt(composer.getModeButton());
    }

    /**
     * The ONE mode popup (Yapping / Questing agents / Partying), anchored wherever the caller
     * lives — the drawer's footer mode pill uses this; the logic (incl. the Partying handling
     * local to this tab) stays here, never duplicated host-side.
     */
    public void openModePopupAt(JComponent anchor) {
        javax.swing.JPopupMenu menu = new javax.swing.JPopupMenu();
        boolean questing = modeController != null
                && com.aresstack.askai.plugin.host.WorkspaceModeEntry.QUESTING_ID
                        .equals(modeController.getInteractionMode());

        javax.swing.JRadioButtonMenuItem yapping = new javax.swing.JRadioButtonMenuItem(
                "\uD83D\uDCAC " + YAPPING_MODE,
                !questing && !GroupChatMode.PARTYING.equals(chatMode));
        yapping.addActionListener(event -> selectYappingMode());
        menu.add(yapping);

        // "Questing" is a submenu (the arrow) listing the installed agent plugins to run.
        javax.swing.JMenu questingMenu = new javax.swing.JMenu("\uD83D\uDDFA Questing");
        List<com.aresstack.askai.plugin.host.WorkspaceModeEntry> agents = modeController == null
                ? java.util.Collections.<com.aresstack.askai.plugin.host.WorkspaceModeEntry>emptyList()
                : modeController.getAvailableAgents();
        if (agents.isEmpty()) {
            javax.swing.JMenuItem none = new javax.swing.JMenuItem("No agents installed");
            none.setEnabled(false);
            questingMenu.add(none);
        } else {
            String activeAgentId = modeController.getActiveAgentId();
            for (final com.aresstack.askai.plugin.host.WorkspaceModeEntry agent : agents) {
                javax.swing.JRadioButtonMenuItem item = new javax.swing.JRadioButtonMenuItem(
                        agent.getDisplayName(), questing && agent.getId().equals(activeAgentId));
                item.addActionListener(event -> {
                    // Questing never coexists with the LAN party: leave it before routing to the agent.
                    leavePartySession(false);
                    mentionCompletion.setActive(false);
                    chatMode = GroupChatMode.YAPPING;
                    selectedAgent = null;
                    rememberState(STATE_MODE, GroupChatMode.YAPPING);
                    modeController.selectAgent(agent.getId());
                    modeController.setInteractionMode(
                            com.aresstack.askai.plugin.host.WorkspaceModeEntry.QUESTING_ID);
                });
                questingMenu.add(item);
            }
        }
        menu.add(questingMenu);

        javax.swing.JRadioButtonMenuItem partying =
                new javax.swing.JRadioButtonMenuItem("\uD83D\uDC65 Partying", GroupChatMode.PARTYING.equals(chatMode));
        partying.setToolTipText("Partying \u2014 Chat with people and bots on your local network");
        partying.addActionListener(event -> selectPartyingMode());
        menu.add(partying);

        menu.show(anchor, 0, anchor.getHeight());
    }

    /**
     * The current mode's display label (Yapping / agent name / Partying) — the drawer footer's
     * mode pill renders this for the ACTIVE tab.
     */
    public String currentModeLabel() {
        if (modeController != null && com.aresstack.askai.plugin.host.WorkspaceModeEntry.QUESTING_ID
                .equals(modeController.getInteractionMode())) {
            String label = modeController.getActiveAgentLabel();
            return label != null && !label.isEmpty() ? label : "Questing";
        }
        if (GroupChatMode.PARTYING.equals(chatMode)) {
            return "Partying";
        }
        return YAPPING_MODE;
    }

    /** Updates the composer's mode label from the controller (Yapping/agent); Partying keeps its label. */
    private void reflectMode() {
        if (modeController == null) {
            return;
        }
        // The active agent's name resolves synchronously from the controller (live catalog, or the
        // persisted label on restore before the catalog loads) — so the label renders the exact
        // agent atomically with the mode, never a generic "Questing" flicker followed by a switch.
        // The LAN party overlays plain Yapping on the controller; its label must survive unrelated
        // controller change events — currentModeLabel() honors that.
        composer.setModeName(currentModeLabel());
    }

    private void selectYappingMode() {
        leavePartySession(false);
        mentionCompletion.setActive(false);
        chatMode = GroupChatMode.YAPPING;
        selectedAgent = null;
        rememberState(STATE_MODE, GroupChatMode.YAPPING);
        rememberState(STATE_AGENT, null);
        if (modeController != null) {
            modeController.setInteractionMode(
                    com.aresstack.askai.plugin.host.WorkspaceModeEntry.YAPPING_ID);
        }
        // reflectMode only fires on controller CHANGES — set the label directly for the no-op case.
        composer.setModeName(YAPPING_MODE);
    }

    // ------------------------------------------------------------------ Partying (LAN group chat)

    /**
     * Leaves the active party session; safe to call when not connected, always idempotent.
     *
     * @param synchronously {@code true} during shutdown (the executor is about to stop);
     *                      {@code false} to leave in the background on a mode switch
     */
    private void leavePartySession(boolean synchronously) {
        final PartySession session = partySession;
        partySession = null;
        partyJoinInFlight = false;
        partySendPending = false;
        if (session == null) {
            return;
        }
        Runnable leave = new Runnable() {
            public void run() {
                try {
                    session.leave();
                } catch (Exception ignored) {
                }
            }
        };
        if (synchronously) {
            // Called on the EDT during window close; session.leave() closes the JGroups channel,
            // which can block for seconds. Run it on a daemon thread with a short bound so the
            // window closes promptly even if the transport is slow to shut down.
            Thread leaver = new Thread(leave, "askai-party-leave");
            leaver.setDaemon(true);
            leaver.start();
            try {
                leaver.join(1500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        } else {
            dictationExecutor.execute(leave);
        }
        // Release the app-wide party so another tab can join.
        com.aresstack.askai.party.PartyModeGuard.release(this);
    }

    private void selectPartyingMode() {
        // Only one tab may be in the party at a time: the installation-scoped identity would
        // otherwise appear as one participant joining twice, breaking membership and colors.
        if (!com.aresstack.askai.party.PartyModeGuard.acquire(this)) {
            setStatus("Partying is already open in another chat tab — close it first.");
            return;
        }
        chatMode = GroupChatMode.PARTYING;
        selectedAgent = null;
        composer.setModeName("Partying");
        rememberState(STATE_MODE, GroupChatMode.PARTYING);
        rememberState(STATE_AGENT, null);
        mentionCompletion.setActive(true);
        refreshMentionCompletionHandles();
        startPartySessionIfNeeded();
    }

    /** Builds and joins a party session in the background unless one is already connected. */
    private void startPartySessionIfNeeded() {
        if (partyJoinInFlight || (partySession != null && partySession.isConnected())) {
            return;
        }
        partyJoinInFlight = true;
        setStatus("Looking for a party…");
        final PartySession session = buildPartySession();
        dictationExecutor.execute(new Runnable() {
            public void run() {
                onUi(new Runnable() {
                    public void run() {
                        if (GroupChatMode.PARTYING.equals(chatMode)) {
                            setStatus("Joining the party…");
                        }
                    }
                });
                try {
                    session.join();
                    onUi(new Runnable() {
                        public void run() {
                            partySession = session;
                            partyJoinInFlight = false;
                            refreshMentionCompletionHandles();
                            if (partySendPending) {
                                partySendPending = false;
                                if (GroupChatMode.PARTYING.equals(chatMode)
                                        && !composer.getMessage().trim().isEmpty()) {
                                    sendPartyChat();
                                }
                            }
                        }
                    });
                    session.updateBotReadiness();
                } catch (final Exception ex) {
                    onUi(new Runnable() {
                        public void run() {
                            partyJoinInFlight = false;
                            if (GroupChatMode.PARTYING.equals(chatMode)) {
                                setStatus("Party connection lost: " + messageOf(ex));
                                transcript.appendInfo("Could not join the party: " + messageOf(ex));
                            }
                        }
                    });
                }
            }
        });
    }

    /** Assembles the session from the persisted settings, the LAN transport and the bot port. */
    private PartySession buildPartySession() {
        String participantId = partySettings.participantId();
        String displayName = partySettings.displayName();
        String handle = MentionParser.computeUniqueHandle(
                displayName, java.util.Collections.<String>emptyList());
        Participant self = new Participant(participantId, displayName, handle,
                partySettings.preferredColor(), true, modelCombo.getSelectedItem() != null);
        GroupChatRoom room = new GroupChatRoom(
                partySettings.roomId(), partySettings.roomName(), partySettings.roomSecret());
        OllamaBotResponder responder = new OllamaBotResponder(ollamaService,
                new Supplier<String>() {
                    public String get() {
                        return (String) modelCombo.getSelectedItem();
                    }
                },
                new Supplier<String>() {
                    public String get() {
                        return model.getDefaultKeepAlive();
                    }
                },
                new Supplier<List<String>>() {
                    public List<String> get() {
                        return partySettings.modelMentionsEnabled()
                                ? installedModelNames
                                : Collections.<String>emptyList();
                    }
                },
                partySettings,
                new Supplier<ThinkingOption>() {
                    public ThinkingOption get() {
                        // Replies to explicit mentions mirror the composer's Think selector.
                        return modelSupportsThinking && !"off".equals(reasoningEffort)
                                ? ThinkingOption.ofLevel(reasoningEffort)
                                : ThinkingOption.defaultOption();
                    }
                },
                new Supplier<ThinkingOption>() {
                    public ThinkingOption get() {
                        return botThinkingFor(partySettings.botGateThinking());
                    }
                },
                new Supplier<ThinkingOption>() {
                    public ThinkingOption get() {
                        return botThinkingFor(partySettings.botCorrectionThinking());
                    }
                });
        return new PartySession(createPartyTransport(), room, self,
                new Supplier<String>() {
                    public String get() {
                        return partySettings.botPolicy();
                    }
                },
                responder, new PanelPartyUi());
    }

    /**
     * Resolve a party-bot thinking level to a request option: {@code "off"} explicitly disables
     * thinking (fast), a level enables it — but only when the current model supports thinking,
     * otherwise the field is omitted.
     */
    private ThinkingOption botThinkingFor(String level) {
        if (!modelSupportsThinking) {
            return ThinkingOption.defaultOption();
        }
        if (level == null || "off".equals(level)) {
            return ThinkingOption.of(ThinkingOption.Mode.DISABLED);
        }
        return ThinkingOption.ofLevel(level);
    }

    /** The real LAN transport (JGroups); discovery options come from the Partying settings. */
    private GroupChatTransport createPartyTransport() {
        JGroupsTransportConfig config = new JGroupsTransportConfig.Builder()
                .multicastDiscovery(partySettings.discoveryEnabled())
                .bindInterface(partySettings.networkInterface())
                .manualPeers(partySettings.manualPeers())
                .historyDirectory(partySettings.historyDirectory())
                .historyRetention(partySettings.historyRetentionPolicy())
                .build();
        return new JGroupsGroupChatTransport(config);
    }

    // The bot host's local thought bubble (same visualization as a Yapping thinking turn).
    private com.aresstack.askai.ui.bubble.BubbleTranscriptPanel.ThinkingHandle partyThinking;
    private final StringBuilder partyThinkingText = new StringBuilder();

    /** Routes the session's callbacks (transport threads) onto the EDT and into the shared shell. */
    private final class PanelPartyUi implements PartySession.Ui {
        public void onPartyMessage(final PartySession.PartyMessageView view) {
            // Ring only for messages newer than anything already seen in this room: local history
            // replay stays silent, while live arrivals AND messages missed offline (delivered by
            // the peers' history sync) notify. Local own messages just advance the stamp.
            String roomId = view.getMessage().getRoomId();
            long createdAt = view.getMessage().getCreatedAt();
            boolean unseen = createdAt > partySettings.lastSeenAt(roomId);
            partySettings.markSeen(roomId, createdAt);
            if (!view.isLocal() && unseen) {
                fireMessageNotification(
                        "Party — " + view.getSenderDisplayName(), view.getMessage().getMarkdown());
            }
            onUi(new Runnable() {
                public void run() {
                    if (!GroupChatMode.PARTYING.equals(chatMode)) {
                        return;
                    }
                    transcript.appendPartyMessage(
                            view.getSenderDisplayName(),
                            view.getMessage().getSenderParticipantId(),
                            view.getMessage().getMarkdown(),
                            partyColor(view.getColorToken()),
                            view.isLocal(),
                            view.getMessage().getCreatedAt());
                }
            });
        }

        public void onInfoLine(final String text) {
            onUi(new Runnable() {
                public void run() {
                    if (GroupChatMode.PARTYING.equals(chatMode)) {
                        transcript.appendInfo(text);
                    }
                }
            });
        }

        public void onStatus(final GroupChatConnectionState state) {
            onUi(new Runnable() {
                public void run() {
                    if (!GroupChatMode.PARTYING.equals(chatMode)) {
                        return;
                    }
                    if (state.isConnected()) {
                        int count = state.getMemberCount();
                        setStatus(count == 1
                                ? "1 party member (just you)"
                                : count + " party members");
                    } else if (state.hasError()) {
                        setStatus("Party connection lost: " + state.getErrorMessage());
                    } else {
                        setStatus("Not connected to party");
                    }
                }
            });
        }

        public void onHandlesChanged(final java.util.List<String> handles) {
            onUi(new Runnable() {
                public void run() {
                    mentionCompletion.setHandles(handles);
                }
            });
        }

        public void onBotThinkingDelta(final String delta) {
            onUi(new Runnable() {
                public void run() {
                    if (!GroupChatMode.PARTYING.equals(chatMode)) {
                        return;
                    }
                    if (partyThinking == null) {
                        partyThinkingText.setLength(0);
                        partyThinking = transcript.startAssistantThinking(
                                com.aresstack.askai.groupchat.GroupChatBot.DISPLAY_NAME);
                    }
                    partyThinkingText.append(delta);
                    transcript.appendAssistantThinkingDelta(partyThinking, delta);
                }
            });
        }

        public void onBotThinkingDone() {
            onUi(new Runnable() {
                public void run() {
                    if (partyThinking != null) {
                        transcript.completeAssistantThinking(partyThinking,
                                thinkingSummaryProvider.createSummary(partyThinkingText.toString()));
                        partyThinking = null;
                        partyThinkingText.setLength(0);
                    }
                }
            });
        }
    }

    /** Refreshes the {@code @}-completion handles from the current party membership. */
    private void refreshMentionCompletionHandles() {
        PartySession session = partySession;
        List<String> handles;
        if (session != null) {
            handles = session.mentionHandles();
        } else {
            handles = new ArrayList<String>();
            handles.add(MentionParser.BOT_HANDLE);
            if (partySettings.modelMentionsEnabled()) {
                handles.addAll(installedModelNames);
            }
        }
        mentionCompletion.setHandles(handles);
        refreshRunningModelHighlight();
    }

    /**
     * Highlights the currently loaded Ollama models in the completion popup — they answer
     * quickly because no model load is needed.
     */
    private long runningModelsQueriedAt;

    private void refreshRunningModelHighlight() {
        if (!partySettings.modelMentionsEnabled()) {
            mentionCompletion.setHighlighted(Collections.<String>emptySet());
            return;
        }
        // Throttle: the popup refresh hook fires on every keystroke inside a mention token.
        long now = System.currentTimeMillis();
        if (now - runningModelsQueriedAt < 2000) {
            return;
        }
        runningModelsQueriedAt = now;
        ollamaService.listRunningModels(new OllamaService.RunningModelsListener() {
            public void onRunningModels(final List<com.aresstack.askai.client.OllamaRunningModelInfo> models) {
                onUi(new Runnable() {
                    public void run() {
                        List<String> names = new ArrayList<String>();
                        for (com.aresstack.askai.client.OllamaRunningModelInfo info : models) {
                            names.add(info.getDisplayName());
                        }
                        mentionCompletion.setHighlighted(names);
                    }
                });
            }

            public void onError(Exception ex) {
                // Highlighting is a hint only; keep the previous state on failure.
            }
        });
    }

    /**
     * Maps a replicated palette color token to the theme-matched concrete color: the palette's
     * dark variant on dark transcript backgrounds, the light variant otherwise.
     */
    private Color partyColor(String token) {
        ParticipantColorPalette.Entry entry = ParticipantColorPalette.byToken(token);
        if (entry == null) {
            return null;
        }
        Color background = model.getChatColors().getTranscriptBackground();
        boolean darkTheme = background != null
                && (background.getRed() * 299 + background.getGreen() * 587
                        + background.getBlue() * 114) / 1000 < 128;
        try {
            return Color.decode(darkTheme ? entry.getDarkHex() : entry.getLightHex());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    // ------------------------------------------------------------------ reasoning effort

    /** The thinking-effort levels offered when the selected model supports thinking. */
    private static final String[] REASONING_LEVELS = {"off", "low", "medium", "high"};

    /** The effort selector: Off / Low / Medium / High. Only reachable when the model supports thinking. */
    private void openReasoningPopup() {
        javax.swing.JPopupMenu menu = new javax.swing.JPopupMenu();
        for (final String level : REASONING_LEVELS) {
            javax.swing.JRadioButtonMenuItem item =
                    new javax.swing.JRadioButtonMenuItem(reasoningLabel(level), level.equals(reasoningEffort));
            item.addActionListener(event -> selectReasoningEffort(level));
            menu.add(item);
        }
        JComponent anchor = composer.getReasoningButton();
        menu.show(anchor, 0, anchor.getHeight());
    }

    private void selectReasoningEffort(String level) {
        reasoningEffort = level;
        composer.setReasoningName(reasoningLabel(level));
        rememberState(STATE_REASONING, level);
    }

    private static String reasoningLabel(String level) {
        return "Think: " + Character.toUpperCase(level.charAt(0)) + level.substring(1);
    }

    /**
     * Probes the given model's {@code /api/show} capabilities and enables the reasoning selector only when
     * the model supports thinking. Stale callbacks (the user switched models meanwhile) are ignored.
     */
    private void refreshReasoningForModel(final String modelName) {
        reasoningProbeModel = modelName;
        if (modelName == null || modelName.trim().isEmpty()) {
            applyThinkingSupport(modelName, false);
            return;
        }
        ollamaService.getModelInfo(modelName, new OllamaService.ModelInfoListener() {
            public void onModelInfo(final com.aresstack.askai.client.OllamaModelInfoView info) {
                onUi(new Runnable() {
                    public void run() {
                        boolean supported = ModelCapability.fromOllamaTags(info.getCapabilities())
                                .contains(ModelCapability.THINKING);
                        applyThinkingSupport(modelName, supported);
                        // R0.4: rerank-only models stay VISIBLE and selectable in the dropdown
                        // (the user wants to see all models), but they cannot chat — sending is
                        // refused with a clear status instead of a server roundtrip error.
                        boolean rerankOnly = info.getCapabilities().contains("rerank")
                                && !info.getCapabilities().contains("completion");
                        selectedModelRerankOnly = rerankOnly;
                        if (rerankOnly) {
                            // The Send button is DISABLED with the reason as tooltip — a click
                            // that silently does nothing would look like a bug.
                            composer.setSendBlockedReason(
                                    "This local model supports reranking, not chat.");
                            setStatus("This local model supports reranking, not chat.");
                            if (!modelName.equals(rerankOnlyHintShownFor)) {
                                rerankOnlyHintShownFor = modelName;
                                transcript.appendInfo("\"" + modelName + "\" is a reranker — it "
                                        + "scores documents against a query instead of chatting. "
                                        + "Try it under Models > Installed > Local > Test "
                                        + "reranker.");
                            }
                        } else {
                            composer.setSendBlockedReason("");
                            rerankOnlyHintShownFor = null;
                        }
                    }
                });
            }

            public void onError(final Exception ex) {
                onUi(new Runnable() {
                    public void run() {
                        applyThinkingSupport(modelName, false); // unknown → keep it greyed out
                        // Unknown capabilities must never leave a STALE send block behind.
                        selectedModelRerankOnly = false;
                        composer.setSendBlockedReason("");
                    }
                });
            }
        });
    }

    private void applyThinkingSupport(String modelName, boolean supported) {
        if (modelName != null && !modelName.equals(reasoningProbeModel)) {
            return; // a newer selection is in flight; ignore this result
        }
        modelSupportsThinking = supported;
        composer.setReasoningEnabled(supported);
        if (!supported) {
            reasoningEffort = "off";
            composer.setReasoningName(reasoningLabel("off"));
        }
        // A model change may flip this peer's ability to host the party bot; announce it.
        final PartySession session = partySession;
        if (session != null) {
            dictationExecutor.execute(new Runnable() {
                public void run() {
                    try {
                        session.updateBotReadiness();
                    } catch (Exception ignored) {
                    }
                }
            });
        }
    }

    private void showEmptyState() {
        transcript.appendInfo("New conversation. Type a message below and press Enter.");
    }

    // ------------------------------------------------------------------ durable chat persistence

    /** @return the chat record for this session, created lazily on first use. */
    private com.aresstack.askai.history.ChatRecord chatRecord() {
        if (chatRecord == null) {
            chatRecord = new com.aresstack.askai.history.ChatRecord(
                    sessionId.toString(), System.currentTimeMillis());
        }
        return chatRecord;
    }

    /**
     * Persist a sent user message (with any image attachments) to the durable store. {@code messageId} is
     * the agent's stable id, or null for a plain chat turn (the normal chat needs no id).
     */
    private void persistUserMessage(String messageId, String text,
                                    java.util.List<ImageAttachment> attachments) {
        if (historyStore == null) {
            return;
        }
        java.util.List<com.aresstack.askai.history.AttachmentRecord> stored =
                new ArrayList<com.aresstack.askai.history.AttachmentRecord>();
        for (ImageAttachment attachment : attachments) {
            com.aresstack.askai.history.AttachmentRecord record = historyStore.storeAttachment(
                    sessionId.toString(), attachment.getFile().toFile(), attachment.getMediaType());
            if (record != null) {
                stored.add(record);
            }
        }
        com.aresstack.askai.history.ChatRecord chat = chatRecord();
        chat.getMessages().add(new com.aresstack.askai.history.ChatMessageRecord(
                messageId, com.aresstack.askai.history.ChatMessageRecord.ROLE_USER,
                text, System.currentTimeMillis(), null, stored));
        if (chat.getTitle() == null || chat.getTitle().trim().isEmpty()) {
            chat.setTitle(deriveTitle(text, attachments));
        }
        saveChatRecord();
    }

    /** Persist a completed assistant answer to the durable store. */
    private void persistAssistantMessage(String messageId, String text, String modelName) {
        if (historyStore == null) {
            return;
        }
        chatRecord().getMessages().add(new com.aresstack.askai.history.ChatMessageRecord(
                messageId, com.aresstack.askai.history.ChatMessageRecord.ROLE_ASSISTANT,
                text, System.currentTimeMillis(), modelName, null));
        saveChatRecord();
    }

    /** Persist a muted italic info/system breadcrumb (e.g. "Websuche: …") so it survives a restart. */
    private void persistInfoMessage(String messageId, String text) {
        if (historyStore == null || text == null || text.trim().isEmpty()) {
            return;
        }
        chatRecord().getMessages().add(new com.aresstack.askai.history.ChatMessageRecord(
                messageId, com.aresstack.askai.history.ChatMessageRecord.ROLE_INFO,
                text, System.currentTimeMillis(), null, null));
        saveChatRecord();
    }

    /**
     * Set this chat's display title explicitly (a chat created programmatically, e.g. by an agent). The
     * first user message no longer overwrites it — {@link #persistUserMessage} only derives a title when
     * none is set. An empty chat is not persisted, so the title becomes visible once the chat has content.
     */
    public void setChatTitle(String title) {
        if (title == null || title.trim().isEmpty()) {
            return;
        }
        chatRecord().setTitle(title.trim());
        saveChatRecord();
    }

    /**
     * Assign this OPEN chat to a project (null/blank removes it). Must go through the panel — it
     * owns the live {@link com.aresstack.askai.history.ChatRecord} instance, and a store-side
     * mutation on a loaded copy would be overwritten by the panel's next save.
     */
    public void setChatProject(String project) {
        chatRecord().setProject(project);
        saveChatRecord();
    }

    /** Notified after every persist so a visible chat list can pick up a new title/timestamp. */
    private Runnable historyChangedListener;

    public void setHistoryChangedListener(Runnable listener) {
        this.historyChangedListener = listener;
    }

    private void saveChatRecord() {
        if (historyStore == null || chatRecord == null) {
            return;
        }
        chatRecord.setModel((String) modelCombo.getSelectedItem());
        chatRecord.setMode(chatMode);
        chatRecord.setAgent(selectedAgent);
        chatRecord.setSystemPrompt(systemPromptArea.getText());
        chatRecord.setModifiedAt(System.currentTimeMillis());
        historyStore.save(chatRecord);
        // The persisted state just changed — an OPEN sidebar would otherwise keep its stale label until
        // the next tab switch (a chat created with an explicit title stayed "(new chat)" the whole time).
        if (historyChangedListener != null) {
            historyChangedListener.run();
        }
    }

    private static String deriveTitle(String text, java.util.List<ImageAttachment> attachments) {
        String base = text == null ? "" : text.trim();
        if (base.isEmpty() && attachments != null && !attachments.isEmpty()) {
            base = attachments.size() == 1 ? "Image" : attachments.size() + " images";
        }
        int newline = base.indexOf('\n');
        if (newline >= 0) {
            base = base.substring(0, newline);
        }
        return base.length() > 60 ? base.substring(0, 60) + "…" : base;
    }

    /**
     * Replays this session's persisted conversation into the transcript and the in-memory history.
     *
     * <p>Past image attachments are shown as thumbnails again but not re-encoded into the model
     * history (that would reload every past image); new turns still send images normally.</p>
     *
     * @return {@code true} when a non-empty conversation was restored
     */
    private boolean restoreChatHistory() {
        if (historyStore == null) {
            return false;
        }
        com.aresstack.askai.history.ChatRecord record = historyStore.load(sessionId.toString());
        if (record == null || record.isEmpty()) {
            return false;
        }
        this.chatRecord = record;
        if (record.getSystemPrompt() != null && !record.getSystemPrompt().trim().isEmpty()) {
            systemPromptArea.setText(record.getSystemPrompt());
        }
        if (record.getModel() != null && !record.getModel().trim().isEmpty()) {
            pendingRestoreModel = record.getModel(); // selected once the model list loads
        }
        transcript.clear();
        for (com.aresstack.askai.history.ChatMessageRecord message : record.getMessages()) {
            if (message.isInfo()) {
                // A muted italic breadcrumb (e.g. "Websuche: …") — restore the line, but it is NOT a model turn.
                transcript.appendInfo(message.getText());
            } else if (message.isAssistant()) {
                transcript.startAssistant(message.getModel() != null ? message.getModel() : "Assistant");
                transcript.appendAssistantDelta(message.getText());
                transcript.finishAssistant();
                history.add(OllamaChatTurn.assistant(message.getText()));
            } else {
                java.util.List<ImageAttachment> attachments = restoreAttachments(message);
                if (attachments.isEmpty()) {
                    transcript.appendUser(message.getText());
                } else if (message.getText().isEmpty()) {
                    transcript.appendUserImages(attachments);
                } else {
                    transcript.appendUser(message.getText(), attachments);
                }
                history.add(OllamaChatTurn.user(message.getText()));
            }
        }
        return true;
    }

    private java.util.List<ImageAttachment> restoreAttachments(
            com.aresstack.askai.history.ChatMessageRecord message) {
        java.util.List<ImageAttachment> attachments = new ArrayList<ImageAttachment>();
        for (com.aresstack.askai.history.AttachmentRecord record : message.getAttachments()) {
            File file = historyStore.attachmentFile(sessionId.toString(), record.getStoredName());
            if (file != null) {
                attachments.add(new ImageAttachment(file.toPath(), record.getFileName(), record.getMediaType()));
            }
        }
        return attachments;
    }

    private void sendChat() {
        if (!composer.isSendEnabled()) {
            return;
        }
        // Questing with an active agent: route the prompt to the agent session over the SHARED chat. The
        // agent echoes the user + assistant bubbles through the conversation sink; no Ollama call happens.
        if (chatSubmissionRouter != null && chatSubmissionRouter.isActive()) {
            String prompt = composer.getMessage().trim();
            if (prompt.isEmpty()) {
                setStatus("Write a message before sending.");
                return;
            }
            // Slash line: run it as an agent command (never sent to the model, never a normal user message).
            if (agentCommandRegistry != null && agentCommandRegistry.isCommandLine(prompt)) {
                executeSlashCommand(prompt);
                return;
            }
            composer.clearMessage();
            chatSubmissionRouter.submitText(prompt);
            return;
        }

        // In Partying mode, route through the GroupChatSubmissionTarget instead of Ollama.
        if (GroupChatMode.PARTYING.equals(chatMode)) {
            sendPartyChat();
            return;
        }

        final String modelName = (String) modelCombo.getSelectedItem();
        if (modelName == null || modelName.trim().isEmpty()) {
            setStatus("No model selected. Open Models or Install first.");
            return;
        }
        if (selectedModelRerankOnly) {
            setStatus("This local model supports reranking, not chat.");
            return;
        }
        final ChatDraft draft = composer.getDraft();
        if (draft.isEmpty()) {
            setStatus("Write a message or attach an image before sending.");
            return;
        }

        if (draft.hasAttachments()) {
            // Images may only go to a vision model, verified via /api/show. Keep the draft until it works.
            gateVisionThenSend(modelName, draft);
        } else {
            dispatchChat(modelName, draft.getText().trim(),
                    java.util.Collections.<String>emptyList(), java.util.Collections.<ImageAttachment>emptyList());
        }
    }

    /** Probe the model's capabilities; only a model reporting the exact "vision" capability may get images. */
    private void gateVisionThenSend(final String modelName, final ChatDraft draft) {
        setStatus("Checking vision support…");
        ollamaService.getModelInfo(modelName, new OllamaService.ModelInfoListener() {
            public void onModelInfo(final OllamaModelInfoView info) {
                onUi(new Runnable() {
                    public void run() {
                        if (VisionCapability.isVisionCapable(info.getCapabilities())) {
                            encodeThenSend(modelName, draft);
                        } else {
                            setStatus("\"" + modelName + "\" is a text-only model — switch to a vision model"
                                    + " or remove the images. Your message and images are kept.");
                        }
                    }
                });
            }

            public void onError(final Exception ex) {
                onUi(new Runnable() {
                    public void run() {
                        setStatus("Could not verify vision support (" + ex.getMessage()
                                + "). Images were not sent; your draft is kept.");
                    }
                });
            }
        });
    }

    /** Read + base64-encode the images off the EDT, then dispatch. On any image error the draft is kept. */
    private void encodeThenSend(final String modelName, final ChatDraft draft) {
        final java.util.List<ImageAttachment> attachments = draft.getAttachments();
        setStatus("Preparing " + attachments.size() + (attachments.size() == 1 ? " image…" : " images…"));
        new SwingWorker<List<String>, Void>() {
            private ImageAttachmentException failure;

            protected List<String> doInBackground() {
                try {
                    return imageContentLoader.encodeAll(attachments);
                } catch (ImageAttachmentException ex) {
                    failure = ex;
                    return null;
                }
            }

            protected void done() {
                if (failure != null) {
                    setStatus("Could not attach " + failure.getAttachment().getDisplayName() + ": "
                            + failure.getReason().getDescription() + ". Your draft is kept.");
                    return;
                }
                List<String> images;
                try {
                    images = get();
                } catch (Exception ex) {
                    setStatus("Could not read the attached images. Your draft is kept.");
                    return;
                }
                if (images == null) {
                    return;
                }
                dispatchChat(modelName, draft.getText().trim(), images, attachments);
            }
        }.execute();
    }

    private void dispatchChat(final String modelName, final String userPrompt,
                              List<String> images, java.util.List<ImageAttachment> attachments) {
        if (transcript.isEmpty() || history.isEmpty()) {
            transcript.clear();
        }
        // Success path: only now is the draft consumed, so any earlier failure left it fully intact.
        composer.clearDraft();
        if (attachments.isEmpty()) {
            transcript.appendUser(userPrompt);
        } else if (userPrompt.isEmpty()) {
            transcript.appendUserImages(attachments);
        } else {
            transcript.appendUser(userPrompt, attachments);
        }
        history.add(images.isEmpty()
                ? OllamaChatTurn.user(userPrompt)
                : OllamaChatTurn.user(userPrompt, images));
        persistUserMessage(null, userPrompt, attachments);

        // Do not open an assistant bubble yet: thinking (if any) opens a green thinking bubble first, and
        // the answer bubble only appears when real content arrives.
        streamingAssistant.setLength(0);
        streamingThinking.setLength(0);
        activeThinking = null;
        assistantBubbleStarted = false;
        streamingModelName = modelName;
        startElapsedTimer();
        setBusy(true);

        // Only request thinking when the model supports it and a level other than "off" is chosen.
        ThinkingOption thinking = modelSupportsThinking && !"off".equals(reasoningEffort)
                ? ThinkingOption.ofLevel(reasoningEffort) : ThinkingOption.defaultOption();
        OllamaService.ChatRequest request = new OllamaService.ChatRequest(
                modelName, model.getDefaultKeepAlive(), buildConversation(), thinking);
        chatTask = ollamaService.streamChat(request, new OllamaService.ChatListener() {
            public void onThinkingDelta(final String delta) {
                onUi(new Runnable() {
                    public void run() {
                        handleThinkingDelta(delta);
                    }
                });
            }

            public void onContent(final String content) {
                onUi(new Runnable() {
                    public void run() {
                        handleContentDelta(content);
                    }
                });
            }

            public void onStatus(final String status) {
                onUi(new Runnable() {
                    public void run() {
                        setStatus(status);
                    }
                });
            }

            public void onComplete(final OllamaService.ChatResult result) {
                onUi(new Runnable() {
                    public void run() {
                        finishTurn(result);
                    }
                });
            }

            public void onError(final Exception ex) {
                onUi(new Runnable() {
                    public void run() {
                        handleStreamError(ex);
                    }
                });
            }
        });
    }

    /**
     * Executes a slash command line against the active agent's registry and renders the outcome as a compact
     * line in the shared chat. The command never reaches the model. Research commands are local/non-blocking,
     * so this runs synchronously on the EDT; heavier future commands should marshal their result via UiExecutor.
     */
    private void executeSlashCommand(String input) {
        if (slashPopup != null) {
            slashPopup.hide();
        }
        composer.clearMessage();
        com.aresstack.askai.plugin.api.agent.command.CommandExecutionResult result =
                agentCommandRegistry.execute(input);
        switch (result.getStatus()) {
            case HANDLED:
                if (!result.getMessage().isEmpty()) {
                    transcript.appendInfo(result.getMessage());
                }
                break;
            case REJECTED:
                transcript.appendInfo("⚠ " + result.getMessage());
                break;
            case UNKNOWN:
            default:
                transcript.appendInfo("Unknown command: " + input.trim());
                break;
        }
    }

    /**
     * Routes a message submission in Partying mode through the party session, keeping the
     * existing transcript and composer for the shared chat shell.
     *
     * <p>The composer is cleared only when submission is accepted (lossless: a rejected message
     * stays in the composer so the user can retry or switch to a different mode).</p>
     */
    private void sendPartyChat() {
        final String userPrompt = composer.getMessage().trim();
        if (userPrompt.isEmpty()) {
            setStatus("Write a message before sending.");
            return;
        }
        PartySession session = partySession;
        if (session != null && session.submitMessage(userPrompt)) {
            composer.clearMessage();
        } else {
            // Lossless: the text stays in the composer and is submitted once the join succeeds.
            partySendPending = true;
            startPartySessionIfNeeded();
            setStatus("Joining the party — your message is sent as soon as you are connected.");
        }
    }

    /** Choose one or more images (PNG/JPEG/WebP) and queue them in the composer as attachments. */
    private void onAttachImagesAction() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Attach images");
        chooser.setMultiSelectionEnabled(true);
        chooser.setAcceptAllFileFilterUsed(false);
        chooser.setFileFilter(new FileNameExtensionFilter("Images (PNG, JPEG, WebP)", "png", "jpg", "jpeg", "webp"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        List<ImageAttachment> chosen = new ArrayList<ImageAttachment>();
        for (File file : chooser.getSelectedFiles()) {
            chosen.add(ImageAttachment.of(file));
        }
        composer.addAttachments(chosen);
        if (!chosen.isEmpty()) {
            setStatus(chosen.size() == 1 ? "1 image attached." : chosen.size() + " images attached.");
        }
    }

    /** First non-empty thinking delta opens the green thinking bubble; further deltas stream into it. */
    private void handleThinkingDelta(String delta) {
        if (delta == null || delta.isEmpty()) {
            return;
        }
        if (activeThinking == null) {
            activeThinking = transcript.startAssistantThinking(streamingModelName);
        }
        streamingThinking.append(delta);
        transcript.appendAssistantThinkingDelta(activeThinking, delta);
    }

    /** First real content ends thinking (burst + rising summary), then streams into the answer bubble. */
    private void handleContentDelta(String content) {
        if (content == null || content.isEmpty()) {
            return;
        }
        endThinkingIfActive();
        if (!assistantBubbleStarted) {
            transcript.startAssistant(streamingModelName);
            assistantBubbleStarted = true;
        }
        streamingAssistant.append(content);
        transcript.appendAssistantDelta(content);
    }

    private void endThinkingIfActive() {
        if (activeThinking != null) {
            transcript.completeAssistantThinking(activeThinking,
                    thinkingSummaryProvider.createSummary(streamingThinking.toString()));
            activeThinking = null;
        }
    }

    private void finishTurn(OllamaService.ChatResult result) {
        stopElapsedTimer();
        setBusy(false);
        chatTask = null;
        // Thinking that never produced an answer: close it neutrally, do not leave an empty answer bubble.
        endThinkingIfActive();

        String assistantText = streamingAssistant.toString();
        if (!assistantBubbleStarted && assistantText.trim().isEmpty() && !result.getFallbackText().isEmpty()) {
            // The answer arrived only as a fallback (no streamed deltas): open and fill a bubble now.
            assistantText = result.getFallbackText();
            transcript.startAssistant(streamingModelName);
            assistantBubbleStarted = true;
            transcript.appendAssistantDelta(assistantText);
        }

        if (assistantBubbleStarted) {
            transcript.finishAssistant();
            history.add(OllamaChatTurn.assistant(assistantText));
            persistAssistantMessage(null, assistantText, streamingModelName);
            if (result.hasMetrics()) {
                setStatus(String.format("Ready · %d tokens · %.1f tok/s",
                        result.getEvalCount(), result.tokensPerSecond()));
            } else {
                setStatus("Ready.");
            }
        } else {
            // Thinking-only turn: no answer, no empty bubble — a neutral status instead.
            setStatus("Thinking finished — no answer returned.");
        }
    }

    /**
     * Output-format contract appended to every system turn so the transcript can render Markdown/Mermaid.
     * Models otherwise tend to wrap the whole reply in a ```markdown fence or nest a Mermaid fence inside
     * one, which then shows as raw code instead of a rendered document/diagram.
     */
    private static final String OUTPUT_FORMAT_HINT =
            "Formatting: reply in normal Markdown directly. Do not wrap the whole response in a ```markdown "
            + "or ```md fence. For a diagram, emit a direct fenced Mermaid block:\n"
            + "```mermaid\ngraph TD\n  A --> B\n```\n"
            + "Never put a Mermaid fence inside a markdown fence.";

    private List<OllamaChatTurn> buildConversation() {
        List<OllamaChatTurn> conversation = new ArrayList<OllamaChatTurn>();
        String system = systemPromptArea.getText();
        String combined = system == null || system.trim().isEmpty()
                ? OUTPUT_FORMAT_HINT
                : system.trim() + "\n\n" + OUTPUT_FORMAT_HINT;
        conversation.add(OllamaChatTurn.system(combined));
        conversation.addAll(history);
        // Opt-in safe diagnostics (-Daskai.vision.diagnostics=true): confirms each image is
        // transmitted and bound to the intended turn, without logging Base64 or paths.
        com.aresstack.askai.vision.VisionDiagnostics.logConversation(conversation);
        return conversation;
    }

    private void stopChat() {
        // Questing with an active agent: stop routes to the agent session, not the Ollama task.
        if (chatSubmissionRouter != null && chatSubmissionRouter.isActive()) {
            chatSubmissionRouter.stop();
            return;
        }
        if (chatTask != null) {
            chatTask.cancel();
            chatTask = null;
            stopElapsedTimer();
            setBusy(false);
            if (activeThinking != null) {
                transcript.cancelAssistantThinking(activeThinking, "Stopped");
                activeThinking = null;
            }
            if (assistantBubbleStarted) {
                transcript.appendAssistantDelta(" [stopped]");
                transcript.finishAssistant();
                if (!streamingAssistant.toString().trim().isEmpty()) {
                    history.add(OllamaChatTurn.assistant(streamingAssistant.toString()));
                }
            }
            setStatus("Stopped.");
        }
    }

    private void handleStreamError(Exception ex) {
        stopElapsedTimer();
        setBusy(false);
        chatTask = null;
        if (activeThinking != null) {
            transcript.cancelAssistantThinking(activeThinking, "Failed");
            activeThinking = null;
        }
        if (assistantBubbleStarted) {
            transcript.appendAssistantDelta("[error: " + ex.getMessage() + "]");
            transcript.finishAssistant();
        }
        setStatus("Chat failed.");
    }

    // ------------------------------------------------------------------ audio-model dropdown

    /**
     * Populate the audio-model dropdown with {@code Automatic} first, then the models whose concrete
     * installation reports the exact "audio" capability via {@code /api/show} (never free text). The
     * current selection is restored from the persisted configuration.
     */
    private void refreshAudioModels(final List<String> names) {
        setAudioModelItems(new ArrayList<String>()); // Automatic only until queried
        if (names.isEmpty()) {
            refreshDictationControls();
            return;
        }
        final List<String> audioCapable = new ArrayList<String>();
        final int[] remaining = {names.size()};
        for (final String name : names) {
            ollamaService.getModelInfo(name, new OllamaService.ModelInfoListener() {
                public void onModelInfo(final com.aresstack.askai.client.OllamaModelInfoView info) {
                    onUi(new Runnable() {
                        public void run() {
                            if (AudioCapability.isAudioCapable(info.getCapabilities())) {
                                audioCapable.add(name);
                            }
                            done();
                        }
                    });
                }

                public void onError(Exception ex) {
                    onUi(new Runnable() {
                        public void run() {
                            done();
                        }
                    });
                }

                private void done() {
                    remaining[0]--;
                    if (remaining[0] == 0) {
                        setAudioModelItems(audioCapable);
                        refreshDictationControls();
                    }
                }
            });
        }
    }

    private void setAudioModelItems(List<String> audioModels) {
        updatingAudioModelCombo = true;
        try {
            SpeechToTextConfiguration stt = model.getSpeechToTextConfiguration();
            audioModelCombo.removeAllItems();
            audioModelCombo.addItem(AUDIO_MODEL_AUTOMATIC);
            for (String name : audioModels) {
                audioModelCombo.addItem(name);
            }
            String desired = AUDIO_MODEL_AUTOMATIC;
            if (!stt.isAudioModelAutomatic() && audioModels.contains(stt.getModelName())) {
                desired = stt.getModelName();
            }
            audioModelCombo.setSelectedItem(desired);
        } finally {
            updatingAudioModelCombo = false;
        }
    }

    private void persistAudioModelSelection() {
        if (updatingAudioModelCombo) {
            return;
        }
        Object selected = audioModelCombo.getSelectedItem();
        String choice = selected == null ? AUDIO_MODEL_AUTOMATIC : String.valueOf(selected);
        SpeechToTextConfiguration current = model.getSpeechToTextConfiguration();
        SpeechToTextConfiguration updated = AUDIO_MODEL_AUTOMATIC.equals(choice)
                ? current.withAudioModelAutomatic(true).withModelName("")
                : current.withAudioModelAutomatic(false).withModelName(choice);
        model.setSpeechToTextConfiguration(updated);
        model.saveSettings();
    }

    /** Reloads the transcription-profile combo from the repository and selects the persisted profile. */
    private void refreshAudioProfiles() {
        updatingAudioProfileCombo = true;
        try {
            String selectedId = model.getSpeechToTextConfiguration().getAudioProcessingProfileId();
            audioProfileCombo.removeAllItems();
            List<AudioProcessingProfile> profiles = audioProfileRepository.findAll();
            AudioProcessingProfile selected = null;
            for (int i = 0; i < profiles.size(); i++) {
                AudioProcessingProfile profile = profiles.get(i);
                audioProfileCombo.addItem(profile);
                if (profile.getId().equals(selectedId)) {
                    selected = profile;
                }
            }
            if (selected == null && audioProfileCombo.getItemCount() > 0) {
                selected = audioProfileCombo.getItemAt(0); // deleted/unknown id → fall back to the first
            }
            audioProfileCombo.setSelectedItem(selected);
        } finally {
            updatingAudioProfileCombo = false;
        }
    }

    /** Persists the chosen transcription profile id; the next recording resolves it fresh. */
    private void persistAudioProfileSelection() {
        if (updatingAudioProfileCombo) {
            return;
        }
        AudioProcessingProfile selected = (AudioProcessingProfile) audioProfileCombo.getSelectedItem();
        if (selected == null) {
            return;
        }
        SpeechToTextConfiguration current = model.getSpeechToTextConfiguration();
        model.setSpeechToTextConfiguration(current.withAudioProcessingProfileId(selected.getId()));
        model.saveSettings();
    }

    /** Closes the settings dialog and opens the Audio processing profile editor page. */
    private void openAudioProcessingSettings() {
        if (settingsDialog != null) {
            settingsDialog.setVisible(false);
        }
        if (audioProcessingSettingsHandler != null) {
            audioProcessingSettingsHandler.openAudioProcessing();
        }
    }

    /** @return the requested model for the resolver: "Automatic" or a specific verified model. */
    private String requestedAudioModel() {
        Object selected = audioModelCombo.getSelectedItem();
        return selected == null ? AUDIO_MODEL_AUTOMATIC : String.valueOf(selected);
    }

    // ------------------------------------------------------------------ microphone dropdown + test

    private void refreshMicrophones() {
        updatingMicCombo = true;
        try {
            String persisted = model.getSpeechToTextConfiguration().getMicrophoneDeviceId();
            micCombo.removeAllItems();
            micCombo.addItem(MIC_SYSTEM_DEFAULT);
            List<String> devices;
            try {
                devices = AvailableAudioDevices.listCaptureDeviceNames();
            } catch (Exception ex) {
                devices = new ArrayList<String>();
            }
            for (String device : devices) {
                micCombo.addItem(device);
            }
            // Fall back to System default when the saved device is gone.
            if (persisted != null && !persisted.isEmpty() && devices.contains(persisted)) {
                micCombo.setSelectedItem(persisted);
            } else {
                micCombo.setSelectedItem(MIC_SYSTEM_DEFAULT);
            }
        } finally {
            updatingMicCombo = false;
        }
    }

    private void persistMicrophoneSelection() {
        if (updatingMicCombo) {
            return;
        }
        SpeechToTextConfiguration current = model.getSpeechToTextConfiguration();
        model.setSpeechToTextConfiguration(current.withMicrophoneDeviceId(selectedMicDeviceId()));
        model.saveSettings();
    }

    /** @return the selected capture device id, or "" for the system default. */
    private String selectedMicDeviceId() {
        Object selected = micCombo.getSelectedItem();
        String value = selected == null ? "" : String.valueOf(selected);
        return MIC_SYSTEM_DEFAULT.equals(value) ? "" : value;
    }

    /** Opens the selected device briefly and shows the input level; nothing is sent to Ollama. */
    private void testMicrophone() {
        if (dictationState != DictationState.IDLE && !dictationState.canStartRecording()) {
            setDictationStatus("Finish the current dictation before testing the microphone.");
            return;
        }
        if (micTestSession != null) {
            stopMicTest("Microphone test stopped.");
            return;
        }
        final String device = selectedMicDeviceId();
        testMicButton.setText("Stop test");
        setDictationStatus("Testing microphone …");
        dictationExecutor.execute(new Runnable() {
            public void run() {
                MicrophoneRecorder.Session session;
                try {
                    session = new JavaSoundMicrophoneRecorder().start(device, workDir);
                } catch (final Exception ex) {
                    onUi(new Runnable() {
                        public void run() {
                            testMicButton.setText("Test microphone");
                            setDictationStatus("Microphone test failed: " + messageOf(ex));
                        }
                    });
                    return;
                }
                final MicrophoneRecorder.Session opened = session;
                onUi(new Runnable() {
                    public void run() {
                        micTestSession = opened;
                        refreshDictationControls();
                        startMicTestTimer(opened.getMeter());
                    }
                });
            }
        });
    }

    private void startMicTestTimer(final AudioLevelMeter meter) {
        final long endAt = System.currentTimeMillis() + 4000;
        micTestTimer = new Timer(100, event -> {
            int peak = meter.getPeak();
            composer.setAudioLevel(scaleLevel(peak));
            boolean signal = meter.getOverallRms() > 30 || peak > 500;
            setDictationStatus(signal ? "Microphone test: signal detected." : "Microphone test: no signal.");
            if (System.currentTimeMillis() > endAt) {
                stopMicTest(signal ? "Microphone works." : "Microphone test finished — no signal detected.");
            }
        });
        micTestTimer.start();
    }

    private void stopMicTest(String message) {
        if (micTestTimer != null) {
            micTestTimer.stop();
            micTestTimer = null;
        }
        final MicrophoneRecorder.Session session = micTestSession;
        micTestSession = null;
        testMicButton.setText("Test microphone");
        composer.setAudioLevel(0);
        setDictationStatus(message);
        refreshDictationControls();
        if (session != null) {
            dictationExecutor.execute(new Runnable() {
                public void run() {
                    session.discard();
                }
            });
        }
    }

    // ------------------------------------------------------------------ dictation control

    private void onRecordButton() {
        switch (dictationState) {
            case RECORDING:
                dictation.stopAndTranscribe(requestedAudioModel(), model.getSpeechToTextConfiguration().getLanguage(), "");
                break;
            case OPENING_MICROPHONE:
            case FINALIZING_RECORDING:
            case VERIFYING_MODEL:
            case UPLOADING_AUDIO:
            case TRANSCRIBING:
                dictation.cancel();
                break;
            default:
                startDictationWithReadiness();
        }
    }

    private void onDiscardButton() {
        if (dictationState == DictationState.RECORDING) {
            dictation.discard();
        } else if (isDictationInFlight()) {
            dictation.cancel();
        } else if (micTestSession != null) {
            stopMicTest("Microphone test stopped.");
        }
    }

    private boolean isDictationInFlight() {
        return dictationState == DictationState.OPENING_MICROPHONE
                || dictationState == DictationState.FINALIZING_RECORDING
                || dictationState == DictationState.VERIFYING_MODEL
                || dictationState == DictationState.UPLOADING_AUDIO
                || dictationState == DictationState.TRANSCRIBING;
    }

    private void startDictationWithReadiness() {
        if (chatBusy || fileBusy || micTestSession != null) {
            setDictationStatus("Busy — finish the chat / file transcription / mic test first.");
            return;
        }
        checkingReadiness = true;
        refreshDictationControls();
        setDictationStatus("Checking speech-to-text readiness …");
        final String requested = requestedAudioModel();
        dictationExecutor.execute(new Runnable() {
            public void run() {
                final ReadinessStatus status = readiness.check(requested);
                onUi(new Runnable() {
                    public void run() {
                        handleReadiness(status);
                    }
                });
            }
        });
    }

    private void handleReadiness(ReadinessStatus status) {
        checkingReadiness = false;
        if (status == ReadinessStatus.READY) {
            dictation.startRecording(selectedMicDeviceId());
            return;
        }
        switch (status) {
            case SERVER_UNREACHABLE:
                setDictationStatus("Ollama is not reachable — check Connections.");
                break;
            case SERVER_ENDPOINT_UNAVAILABLE:
                setDictationStatus("This Ollama server does not support speech-to-text.");
                break;
            case NO_AUDIO_MODEL:
                setDictationStatus("No audio-capable model installed.");
                break;
            case MODEL_CAPABILITY_UNKNOWN:
                setDictationStatus("The model's audio capability could not be confirmed.");
                break;
            case MODEL_NOT_AUDIO_CAPABLE:
                setDictationStatus("The selected model does not support audio.");
                break;
            default:
                setDictationStatus("Speech-to-text is not ready.");
        }
        refreshDictationControls();
    }

    /** Invalidate the readiness cache after server/model changes (called by the frame). */
    public void invalidateSpeechReadiness() {
        readiness.invalidate();
    }

    private void onDictationState(DictationState state, String message) {
        checkingReadiness = false;
        dictationState = state;
        if (state == DictationState.RECORDING) {
            recordingStartedAtMillis = System.currentTimeMillis();
            composer.setWaveformThreshold(
                    model.getSpeechToTextConfiguration().getSignalThresholdPercent());
            composer.setWaveformActive(true);
            startLevelTimer();
        } else {
            stopLevelTimer();
            composer.setWaveformActive(false);
            if (state.isTerminal() || state == DictationState.IDLE) {
                composer.setAudioLevel(0);
            }
        }
        setDictationStatus(message != null ? message : state.getDefaultStatusMessage());
        refreshDictationControls();
    }

    private void onDictationResult(DictationResult result) {
        // Insert at the caret, preserve existing text. The service delivers exactly one terminal
        // callback per operation, so this cannot double-insert.
        JTextArea editor = composer.getEditor();
        ComposerInserter.Insertion insertion = ComposerInserter.insert(
                editor.getText(), editor.getSelectionStart(), editor.getSelectionEnd(), result.getText());
        editor.setText(insertion.getText());
        editor.setCaretPosition(Math.min(insertion.getCaret(), editor.getText().length()));
        composer.focusEditor();
        persistLastAudioModel(result.getModelUsed());
        showDiagnostics(result.getDiagnostics());
        if (model.getSpeechToTextConfiguration().isAutoSendTranscription()
                && !editor.getText().trim().isEmpty()) {
            // Hands-free (settings opt-in): the transcript goes out immediately, no review stop.
            setDictationStatus("Transcription sent.");
            refreshDictationControls();
            sendChat();
            return;
        }
        setDictationStatus("Transcription ready. Review the text and press Send.");
        refreshDictationControls();
    }

    private void onDictationFailure(DictationFailure failure) {
        setDictationStatus(describeFailure(failure));
        appendTech("Dictation failed: " + failure.getKind() + (failure.getDetail().isEmpty()
                ? "" : " — " + failure.getDetail()));
        refreshDictationControls();
    }

    private String describeFailure(DictationFailure failure) {
        switch (failure.getKind()) {
            case CANCELLED:
                return "Dictation cancelled.";
            case MICROPHONE_OPEN_FAILED:
                return "Could not open the microphone (" + micLabel() + ").";
            case FINALIZE_FAILED:
                return "Could not finalize the recording.";
            case NORMALIZE_FAILED:
                return "Could not process the recording.";
            case QUALITY_TOO_SHORT:
                return "Recording too short — hold and speak a little longer.";
            case QUALITY_NO_SIGNAL:
                return "No speech detected — check the microphone and try again.";
            case NO_AUDIO_MODEL:
                return "No audio-capable model installed.";
            case MODEL_CAPABILITY_UNKNOWN:
                return "The model's audio capability could not be confirmed.";
            case MODEL_NOT_AUDIO:
                return "The selected model does not support audio.";
            case SERVER_ENDPOINT_UNAVAILABLE:
                return "This Ollama server does not support speech-to-text.";
            case SERVER_UNREACHABLE:
                return "Ollama is not reachable — check Connections.";
            case TRANSCRIPTION_EMPTY:
                return "The model returned no text — try speaking again.";
            case TRANSCRIPTION_FAILED:
            default:
                return "Transcription failed.";
        }
    }

    private String micLabel() {
        String id = selectedMicDeviceId();
        return id.isEmpty() ? "system default" : id;
    }

    private void persistLastAudioModel(String modelUsed) {
        if (modelUsed == null || modelUsed.trim().isEmpty()) {
            return; // a failed attempt must not overwrite the last successful model
        }
        SpeechToTextConfiguration current = model.getSpeechToTextConfiguration();
        if (modelUsed.equals(current.getLastAudioModel())) {
            return;
        }
        model.setSpeechToTextConfiguration(current.withLastAudioModel(modelUsed));
        model.saveSettings();
    }

    // ------------------------------------------------------------------ level meter timer

    private void startLevelTimer() {
        stopLevelTimer();
        recordingHadSignal = false;
        lastSignalAtMillis = System.currentTimeMillis();
        levelTimer = new Timer(100, event -> {
            AudioLevelMeter meter = dictation.getActiveMeter();
            long seconds = (System.currentTimeMillis() - recordingStartedAtMillis) / 1000L;
            if (meter == null) {
                setDictationStatus("● Recording — " + formatDuration(seconds));
                return;
            }
            // LIVE window, not the recording's all-time maximum — the cumulative peak froze
            // every display (and the silence detection) at the loudest moment forever.
            int level = scaleLevel(meter.consumeRecentPeak());
            composer.setAudioLevel(level);
            composer.pushWaveformLevel(level); // the floating sampler over the editor
            SpeechToTextConfiguration stt = model.getSpeechToTextConfiguration();
            // The USER-SET noise gate (the draggable line in the waveform): only levels above it
            // count as speech — a fixed threshold drowned in strong background noise and the
            // silence auto-stop never fired.
            boolean signal = level >= stt.getSignalThresholdPercent();
            boolean clipping = meter.getClippedSampleCount() > 0;
            // Hands-free auto-stop (settings opt-in): once the user HAS spoken, a long-enough
            // pause stops the recording exactly like pressing Stop — the transcript follows,
            // and with auto-send both together give the Gemini feel.
            if (signal) {
                recordingHadSignal = true;
                lastSignalAtMillis = System.currentTimeMillis();
            }
            if (stt.isAutoStopOnSilence() && recordingHadSignal
                    && dictationState == DictationState.RECORDING
                    && System.currentTimeMillis() - lastSignalAtMillis
                            >= stt.getAutoStopSilenceSeconds() * 1000L) {
                setDictationStatus("Silence — stopping and transcribing …");
                dictation.stopAndTranscribe(requestedAudioModel(), stt.getLanguage(), "");
                return;
            }
            setDictationStatus("● Recording — " + formatDuration(seconds) + " · " + micLabel()
                    + (clipping ? " · CLIPPING" : signal ? " · signal" : " · no signal")
                    + "  (Stop to transcribe · Discard/Esc to cancel)");
        });
        levelTimer.start();
    }

    private void stopLevelTimer() {
        if (levelTimer != null) {
            levelTimer.stop();
            levelTimer = null;
        }
    }

    /** LOGARITHMIC (dB) level: quiet speech becomes visible mountains, a knock stops dwarfing it. */
    private static int scaleLevel(int peak) {
        return com.aresstack.askai.stt.DecibelLevelScale.percentFromPeak(peak);
    }

    private static String formatDuration(long seconds) {
        long safe = Math.max(0, seconds);
        return (safe / 60) + ":" + String.format("%02d", safe % 60);
    }

    // ------------------------------------------------------------------ recovery actions


    private void retryDictation() {
        dictation.retryTranscription();
    }

    private void saveRecording() {
        File source = dictation.savedRecordingSource();
        if (source == null || !source.isFile()) {
            setDictationStatus("No recording to save.");
            return;
        }
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Save recording");
        chooser.setSelectedFile(new File("dictation.wav"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return; // cancelling the dialog must not change the dictation state
        }
        File dest = chooser.getSelectedFile();
        try {
            java.nio.file.Files.copy(source.toPath(), dest.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            setDictationStatus("Recording saved to " + dest.getName() + ".");
        } catch (Exception ex) {
            setDictationStatus("Could not save recording: " + messageOf(ex));
        }
    }

    private void openInstallAudioModel() {
        if (installAudioModelHandler != null) {
            installAudioModelHandler.openInstall();
        }
    }

    /** Render the complete dictation action state in the integrated composer. */
    private void refreshDictationControls() {
        boolean sttEnabled = model.getSpeechToTextConfiguration().isEnabled();
        boolean idle = dictationState.canStartRecording();
        boolean inFlight = isDictationInFlight();
        boolean recording = dictationState == DictationState.RECORDING;
        boolean canRecord = sttEnabled && !chatBusy && !fileBusy && micTestSession == null
                && !checkingReadiness && (idle || recording || inFlight);
        boolean retryable = dictation.hasRetryableRecording() && idle;
        boolean savable = dictation.hasSavableRecording() && idle;
        boolean installVisible = idle && dictationState == DictationState.FAILED && lastFailureNeedsModel;
        boolean audioFileEnabled = sttEnabled && !chatBusy && idle && !fileBusy;
        boolean levelVisible = recording || micTestSession != null;

        composer.setDictationView(new ChatComposerPanel.DictationView(
                dictationState.getMicButtonLabel(),
                canRecord,
                recording,
                inFlight || checkingReadiness,
                // Only a real "throw away the recording" affordance: while transcribing, the record
                // button itself already shows "Cancel", so no separate discard X is needed there.
                recording || micTestSession != null,
                retryable,
                savable,
                installVisible,
                audioFileEnabled,
                levelVisible));
        testMicButton.setEnabled(!chatBusy && idle && !fileBusy && !checkingReadiness);
    }

    private boolean lastFailureNeedsModel;

    // ------------------------------------------------------------------ existing-file transcription (decoupled)

    private void onAudioFileAction() {
        if (fileBusy || isDictationInFlight() || dictationState == DictationState.RECORDING) {
            setDictationStatus("Finish the current dictation first.");
            return;
        }
        JFileChooser chooser = new JFileChooser(lastAudioDirectory);
        chooser.setDialogTitle("Transcribe an audio file");
        chooser.setFileFilter(new FileNameExtensionFilter(
                SupportedAudioFormats.fileChooserDescription(), SupportedAudioFormats.extensionArray()));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File file = chooser.getSelectedFile();
        if (file == null) {
            return;
        }
        lastAudioDirectory = file.getParentFile();
        String sttModel = requestedAudioModel();
        if (AUDIO_MODEL_AUTOMATIC.equals(sttModel)) {
            setDictationStatus("Pick a specific audio model for file transcription.");
            return;
        }
        fileBusy = true;
        refreshDictationControls();
        setDictationStatus("Transcribing " + file.getName() + " …");
        SpeechToTextService.TranscriptionRequest request =
                new SpeechToTextService.TranscriptionRequest(file, sttModel, "", "");
        fileTask = speechToTextService.transcribe(request, new SpeechToTextService.TranscriptionListener() {
            public void onTranscription(final String text) {
                onUi(new Runnable() {
                    public void run() {
                        fileBusy = false;
                        fileTask = null;
                        JTextArea editor = composer.getEditor();
                        ComposerInserter.Insertion insertion = ComposerInserter.insert(editor.getText(),
                                editor.getSelectionStart(), editor.getSelectionEnd(), text);
                        editor.setText(insertion.getText());
                        editor.setCaretPosition(Math.min(insertion.getCaret(), editor.getText().length()));
                        composer.focusEditor();
                        setDictationStatus("Transcription ready. Review the text and press Send.");
                        refreshDictationControls();
                    }
                });
            }

            public void onError(final Exception ex) {
                onUi(new Runnable() {
                    public void run() {
                        fileBusy = false;
                        fileTask = null;
                        setDictationStatus("File transcription failed: " + messageOf(ex));
                        refreshDictationControls();
                    }
                });
            }
        });
    }

    // ------------------------------------------------------------------ diagnostics + cleanup

    private void showDiagnostics(DictationDiagnostics diagnostics) {
        if (diagnostics == null) {
            return;
        }
        StringBuilder builder = new StringBuilder("Dictation diagnostics:");
        for (Map.Entry<String, String> entry : diagnostics.asMap().entrySet()) {
            builder.append("\n  ").append(entry.getKey()).append(": ").append(entry.getValue());
        }
        appendTech(builder.toString());
    }

    private void appendTech(String line) {
        techDetails.append(line + "\n");
        techDetails.setCaretPosition(techDetails.getDocument().getLength());
    }

    private void cleanupOldRecordings() {
        long cutoff = System.currentTimeMillis() - TEMP_TTL_MILLIS;
        deleteOldSpeechFiles(workDir, cutoff);
        deleteOldSpeechFiles(new File(System.getProperty("java.io.tmpdir")), cutoff);
    }

    private static void deleteOldSpeechFiles(File directory, long cutoff) {
        if (directory == null || !directory.isDirectory()) {
            return;
        }
        File[] files = directory.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            String name = file.getName();
            if (file.isFile() && name.startsWith("askai-speech") && name.endsWith(".wav")
                    && file.lastModified() < cutoff) {
                file.delete();
            }
        }
    }

    /** Stop any recording, abort HTTP, stop timers and clean up (window closing / app exit). */
    public void shutdownDictation() {
        stopLevelTimer();
        stopElapsedTimer();
        if (micTestSession != null) {
            stopMicTest("");
        }
        try {
            dictation.discard();
        } catch (Exception ignored) {
        }
        if (fileTask != null) {
            try {
                fileTask.cancel();
            } catch (Exception ignored) {
            }
        }
        leavePartySession(true);
        notifier.dispose();
        cleanupOldRecordings();
        dictationExecutor.shutdownNow();
    }

    // ------------------------------------------------------------------ chat timers + busy

    private void startElapsedTimer() {
        requestStartedAtMillis = System.currentTimeMillis();
        elapsedTimer = new Timer(1000, event -> {
            long seconds = (System.currentTimeMillis() - requestStartedAtMillis) / 1000L;
            setStatus("Generating ... " + seconds + "s");
        });
        elapsedTimer.start();
    }

    private void stopElapsedTimer() {
        if (elapsedTimer != null) {
            elapsedTimer.stop();
            elapsedTimer = null;
        }
    }

    /** Switch the integrated composer between the Send and Stop states. */
    private void setBusy(boolean busy) {
        chatBusy = busy;
        composer.setChatBusy(busy);
        modelCombo.setEnabled(!busy);
        if (!busy) {
            composer.focusEditor();
            clearChatBusyDictationHint();
        }
        refreshDictationControls();
    }

    /** Drop a stale "busy — finish the chat" hint once the chat has finished. */
    private void clearChatBusyDictationHint() {
        if (composer.getDictationStatus() != null && composer.getDictationStatus().startsWith("Busy —")
                && dictationState.canStartRecording()) {
            setDictationStatus("Ready for dictation.");
        }
    }

    private void setStatus(String status) {
        composer.setChatStatus(status);
    }

    private void setDictationStatus(String status) {
        // Track whether the "Install audio model" action should be offered.
        lastFailureNeedsModel = status != null && status.contains("No audio-capable model");
        composer.setDictationStatus(status);
    }

    private static String messageOf(Throwable ex) {
        return ex.getMessage() == null ? ex.toString() : ex.getMessage();
    }

    private static void onUi(Runnable runnable) {
        SwingUtilities.invokeLater(runnable);
    }

    private static final class DaemonThreadFactory implements ThreadFactory {
        private final AtomicInteger counter = new AtomicInteger();

        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "askai-dictation-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }

}
