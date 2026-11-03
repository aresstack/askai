package com.aresstack.askai.ui.setup;

import com.aresstack.comiccontrols.control.ComicButton;
import com.aresstack.comiccontrols.control.ComicControls;
import com.aresstack.comiccontrols.control.ComicSectionPanel;
import com.aresstack.comiccontrols.paint.StrokeIcon;
import com.aresstack.comiccontrols.theme.ComicPalette;
import com.aresstack.comiccontrols.theme.ResearchUiPalette;
import com.aresstack.comiccontrols.theme.ResearchUiTypography;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JProgressBar;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.Timer;
import javax.swing.text.View;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.LayoutManager;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The setup wizard as a reusable component: six steps (network &amp; security, AI runtime, chat model, knowledge
 * &amp; research, final check, voice) with a progress trail, errors in the step concerned and “Back”, “Next”,
 * “Skip”, “Cancel”, at the end “Finish”. Witch stands in the free space of every step, talks in a speech bubble
 * (and with a voice, if one is installed) and answers questions when clicked. The wizard only uses
 * {@link SetupActions}; property keys and services appear nowhere. Everything on the EDT.
 *
 * <p>Taken over from the Enterprise AI Client's setup wizard with its concept unchanged – step trail, Witch with
 * speech bubbles and menu, error plate, skippable steps, final check before “Finish”, hand-over to the chat
 * assistant after the first setup, later calls from the settings. Only the steps are AskAI's: KeePass became
 * network &amp; security, the AI service became the AI runtime (Ollama or AskAI's local runtime), the model step
 * is split into the chat model and the knowledge roles (embeddings, reranker, OpenNLP sentence models), Confluence
 * is gone, and the last step installs a read-aloud voice instead of dictation resources.</p>
 */
public final class SetupWizardPanel extends JPanel {

    private static final long serialVersionUID = 1L;

    static final String[] STEP_NAMES = {"Connection & voice", "AI runtime", "Chat model", "Research", "Dictation",
            "Check"};
    /**
     * The first step sets up the network and downloads the Witch's voice in one go: the download through the
     * configured proxy and certificates is the network test, and from then on the Witch can speak.
     */
    static final int STEP_CONNECTION = 0;
    static final int STEP_NETWORK = STEP_CONNECTION;
    static final int STEP_VOICE = STEP_CONNECTION;
    static final int STEP_RUNTIME = 1;
    static final int STEP_CHAT = 2;
    static final int STEP_KNOWLEDGE = 3;
    /** Optional: microphone and audio model for speaking instead of typing. */
    static final int STEP_DICTATION = 4;
    /** The last step: saves and checks everything; “Finish” ends the setup here. */
    static final int STEP_CHECK = 5;

    /**
     * Witch's picture per step: cut-outs with a transparent background (copies of the third set in
     * docs/images/setup-wizard/originals/v3).
     */
    private static final String[] STEP_MASCOTS = {"wizard/v3-01-network-security.png", "wizard/v3-02-ai-runtime.png",
            "wizard/v3-03-chat-model.png", "wizard/v3-04-knowledge.png", "wizard/v3-06-voice.png",
            "wizard/v3-05-check.png"};
    /** The runtime step shows this picture until a runtime answers (the same cut-out as once it answers). */
    static final String RUNTIME_INSTALL_MASCOT = "wizard/v3-02-ai-runtime.png";
    /** The check step shows this picture once every check passed. */
    static final String CHECK_SUCCESS_MASCOT = "wizard/v3-07-success.png";
    /** Per step: Witch's place in the free space (alternating, so it feels alive). */
    private static final SetupWizardMascot.Placement[] MASCOT_PLACEMENTS = {SetupWizardMascot.Placement.RIGHT,
            SetupWizardMascot.Placement.LEFT, SetupWizardMascot.Placement.RIGHT, SetupWizardMascot.Placement.LEFT,
            SetupWizardMascot.Placement.RIGHT, SetupWizardMascot.Placement.CENTER};
    /**
     * Per step: Witch's largest height in logical pixels. Larger than any free space on purpose: Witch grows to
     * fill the free space of the step instead of standing small in a corner.
     */
    private static final int[] MASCOT_HEIGHTS = {900, 900, 900, 900, 900, 900};

    /** AskAI proxy modes the step offers directly; every other mode is kept and edited in the network settings. */
    static final String PROXY_NONE = "DISABLED";
    static final String PROXY_WINDOWS = "WINDOWS_NATIVE_PROXY_SETTINGS";
    static final String PROXY_MANUAL = "MANUAL_PROXY";
    static final String LABEL_PROXY_NONE = "No proxy";
    static final String LABEL_PROXY_WINDOWS = "Windows proxy settings";
    static final String LABEL_PROXY_MANUAL = "Manual proxy";
    private static final String LABEL_PROXY_OTHER = "Advanced: ";

    /** First entry of the voice choices: the zero-setup Windows voice. */
    static final String WINDOWS_VOICE = "Windows voice (no download)";
    /** First microphone choice: whatever Windows uses as its default recording device. */
    static final String MIC_DEFAULT = "System default microphone";
    /** First audio model choice: AskAI picks an installed audio-capable model itself. */
    static final String AUDIO_AUTOMATIC = "Automatic";
    /** No reranker / no sentence model: AskAI uses its fallbacks. */
    static final String NONE = "";

    private static final int READING_MILLIS_PER_CHARACTER = 60;
    private static final int MIN_READING_MILLIS = 1800;
    /** Label of the check {@link SetupActions#finish} reports when the settings could not be written. */
    static final String SAVING_CHECK = "Saving the settings";
    private static final String SKIP_STEP_HINT = "Go to the next step without a check; your entries stay";
    private static final String SKIP_VOICE_HINT = "Finish the setup now; your entries are saved";
    static final String VOICES_SKIPPED = "Ready. The voices stay as they are; change them later in the chat "
            + "settings under Audio & Dictation.";

    private final SetupActions setup;
    private final ComicPalette palette;
    private final SetupStepTrail trail;
    private final CardLayout cards = new CardLayout();
    private final JPanel deck = new JPanel(cards);
    private final boolean[] done = new boolean[STEP_NAMES.length];
    private final String[] errors = new String[STEP_NAMES.length];
    private final ComicSectionPanel errorPlate;
    private final JTextArea errorText = new WrappedErrorText();
    private final ComicButton networkButton = new ComicButton("Network settings…");
    private final ComicButton cancelButton = new ComicButton("Cancel");
    private final ComicButton backButton = new ComicButton("Back");
    private final ComicButton nextButton = new ComicButton("Next", ComicButton.Accent.ACTION);
    private final ComicButton finishButton = new ComicButton("Finish", ComicButton.Accent.ACTION);
    private final ComicButton skipButton = new ComicButton("Skip");
    /** Witch per step, indexed by step (not by the order the pages are built in). */
    private final SetupWizardMascot[] mascots = new SetupWizardMascot[STEP_NAMES.length];

    private SetupDraft draft;
    private int step;
    private Runnable onFinished;
    private Runnable onCancel;
    private boolean busy;
    private boolean closed;

    // Witch: fixed lines (WizardGuide), bubble or voice (WizardNarrator)
    private final WizardGuide guide = new WizardGuide();
    private final WizardNarrator narrator = new WizardNarrator(text -> mascotOf(step).say(text));
    /** Witch only talks once a runtime answers (or from the start when called again from the settings). */
    private boolean witchAwake;
    private boolean handoff;
    private boolean handingOff;
    private SetupVoices handoffVoices;
    private int readingPace = READING_MILLIS_PER_CHARACTER;

    // Step 1: network & security
    private final JComboBox<String> proxyMode = ComicControls.comboBox();
    private final JTextField proxyHost = ComicControls.textField(20);
    private final JTextField proxyPort = ComicControls.textField(6);
    private final List<JComponent> manualProxyParts = new ArrayList<JComponent>();
    private final JCheckBox trustWindows = ComicControls.checkBox("Trust the Windows certificate stores "
            + "(company networks with TLS inspection)");
    private final ComicButton networkTest = new ComicButton("Test connection", ComicButton.Accent.ACTION);
    private final ComicButton networkAdvanced = new ComicButton("Advanced network settings…");
    private final JLabel networkStatus = statusLabel();
    /** The proxy mode of the draft when it is none of the three the step offers. */
    private String otherProxyMode = "";
    private int networkGeneration;

    // Step 2: AI runtime
    private final JTextField ollamaUrl = ComicControls.textField(28);
    private final ComicButton runtimeTest = new ComicButton("Test connection", ComicButton.Accent.ACTION);
    private final ComicButton runtimeDownload = new ComicButton("Download Ollama…");
    private final JLabel ollamaStatus = statusLabel();
    /** The runtime step's two pictures: the install scene until a runtime answers, then the connecting one. */
    private SetupWizardMascot.Art runtimeInstallArt;
    private SetupWizardMascot.Art runtimeConnectedArt;
    private SetupWizardMascot.Art checkPendingArt;
    private SetupWizardMascot.Art checkSuccessArt;
    private boolean runtimeChecking;
    private int runtimeGeneration;

    // Step 3: chat model
    private final JComboBox<String> chatModel = ComicControls.comboBox();
    private final ComicButton modelsRefresh = new ComicButton("Reload models");
    private final ComicButton chatInstall = new ComicButton("Install…");
    private final ComicButton chatRecommended = new ComicButton("Install " + SetupActions.RECOMMENDED_CHAT_MODEL,
            ComicButton.Accent.ACTION);
    private final ComicButton chatAlternative = new ComicButton("Install " + SetupActions.ALTERNATIVE_CHAT_MODEL,
            ComicButton.Accent.ACTION);
    private final ComicButton embeddingInstall = new ComicButton("Install…");
    private final ComicButton audioInstall = new ComicButton("Install…");
    /** Models installed before the model browser opened: what is new afterwards gets selected. */
    private List<String> chatBeforeBrowser;
    private List<String> embeddingsBeforeBrowser;
    private final JLabel modelsStatus = statusLabel();
    private boolean modelsLoaded;
    private boolean modelsLoading;

    // Step 4: knowledge & research
    private final JComboBox<String> embeddingModel = ComicControls.comboBox();
    private final JTextField embeddingDimension = ComicControls.textField(7);
    private final ComicButton dimensionMeasure = new ComicButton("Measure");
    private final JComboBox<String> rerankerModel = ComicControls.comboBox();
    private final JComboBox<String> sentenceGerman = ComicControls.comboBox();
    private final JComboBox<String> sentenceEnglish = ComicControls.comboBox();
    private final ComicButton sentenceInstall = new ComicButton("Install sentence models",
            ComicButton.Accent.ACTION);
    private final JLabel knowledgeStatus = statusLabel();
    private String measuredFor = "";
    private boolean sentenceInstalling;
    private List<String> installedChat = Collections.emptyList();
    private Rows researchRows;
    private boolean pluginSectionsAdded;
    private final List<com.aresstack.askai.plugin.api.agent.setup.AgentSetupPage> pluginPages =
            new ArrayList<com.aresstack.askai.plugin.api.agent.setup.AgentSetupPage>();
    private List<String> installedEmbeddings = Collections.emptyList();
    private List<String> installedRerankers = Collections.emptyList();
    private List<String> installedSentenceGerman = Collections.emptyList();
    private List<String> installedSentenceEnglish = Collections.emptyList();

    // Step 5: final check
    private final JPanel checkRows = transparent(new GridBagLayout());
    private final JLabel checkStatus = statusLabel();
    private final ComicButton checkRetry = new ComicButton("Check again");
    /** On the check page when local models are missing: installs them right there, then checks again. */
    private final ComicButton checkInstallMissing = new ComicButton("Install missing models");
    /** Installs started by {@link #checkInstallMissing} that have not finished yet. */
    private int pendingFixes;
    private List<SetupCheck> lastChecks = Collections.emptyList();
    /** The draft the last passed final check saved (without the voices, which the last step saves itself). */
    private String checkedDraft = "";
    static final String CHECK_OUTDATED = "Settings changed since the check – open “Check” to save and check again.";

    // Step 6: voice (optional)
    private final JComboBox<String> voiceGerman = ComicControls.comboBox();
    private final JComboBox<String> voiceEnglish = ComicControls.comboBox();
    private final ComicButton voiceGermanAction = new ComicButton("Download", ComicButton.Accent.ACTION);
    private final ComicButton voiceEnglishAction = new ComicButton("Download", ComicButton.Accent.ACTION);
    private final ComicButton voiceRetry = new ComicButton("Reload voices");
    private final ModelDownload chatDownload = new ModelDownload("Download model");
    private final ModelDownload embeddingDownload = new ModelDownload("Download model");
    private final ModelDownload rerankerDownload = new ModelDownload("Install recommended reranker");
    // Dictation (optional)
    private final JComboBox<String> microphone = ComicControls.comboBox();
    private final ComicButton micTest = new ComicButton("Test microphone");
    private final JProgressBar micLevel = ComicControls.progressBar(0, 100);
    private final JLabel micStatus = statusLabel();
    private boolean micTesting;
    private final JComboBox<String> audioModel = ComicControls.comboBox();
    private final ModelDownload audioDownload = new ModelDownload("Download audio model");
    private final JLabel audioStatus = statusLabel();
    private List<String> installedAudio = Collections.emptyList();
    /** The microphones and audio models were read once; until then the draft's choice stays as it is. */
    private boolean dictationLoaded;
    private boolean audioLoading;
    /** The audio model choices were filled once; until then the draft's audio model stays as it is. */
    private boolean audioListed;
    private final JLabel voiceStatus = statusLabel();
    private final JProgressBar voiceProgress = ComicControls.progressBar(0, 1000);
    /** Label in the voice choices → voice id. */
    private final Map<String, SetupActions.VoiceOffer> voiceByLabel =
            new LinkedHashMap<String, SetupActions.VoiceOffer>();
    private boolean voicesLoaded;
    private boolean voicesLoadFailed;
    private boolean voiceInstalling;

    public SetupWizardPanel(SetupDraft initial, SetupActions setup, ComicPalette palette) {
        super(new BorderLayout(0, 8));
        if (initial == null || setup == null || palette == null) {
            throw new IllegalArgumentException("initial, setup and palette must not be null");
        }
        this.draft = initial;
        this.setup = setup;
        this.palette = palette;
        setOpaque(false);
        this.trail = new SetupStepTrail(STEP_NAMES, palette);
        this.errorPlate = new ComicSectionPanel(palette);
        networkButton.setToolTipText("Adjust proxy and certificates in AskAI's network settings");
        cancelButton.setToolTipText("Cancel the setup; nothing is saved");
        finishButton.setToolTipText("Finish the setup and start using AskAI");
        skipButton.setToolTipText(SKIP_STEP_HINT);
        buildUi();
        wire();
        load(initial);
        showStep(STEP_NETWORK);
    }

    // ------------------------------------------------------------------ layout

    private void buildUi() {
        JPanel north = transparent(new BorderLayout(0, 4));
        north.add(trail, BorderLayout.CENTER);
        deck.setOpaque(false);
        deck.add(connectionStep(), STEP_NAMES[STEP_CONNECTION]);
        deck.add(runtimeStep(), STEP_NAMES[STEP_RUNTIME]);
        deck.add(chatStep(), STEP_NAMES[STEP_CHAT]);
        deck.add(knowledgeStep(), STEP_NAMES[STEP_KNOWLEDGE]);
        deck.add(dictationStep(), STEP_NAMES[STEP_DICTATION]);
        deck.add(checkStep(), STEP_NAMES[STEP_CHECK]);
        runtimeConnectedArt = mascotOf(STEP_RUNTIME).art();
        checkPendingArt = mascotOf(STEP_CHECK).art();
        checkSuccessArt = SetupWizardMascot.Art.load(CHECK_SUCCESS_MASCOT);
        runtimeInstallArt = SetupWizardMascot.Art.load(RUNTIME_INSTALL_MASCOT);
        mascotOf(STEP_RUNTIME).setArt(runtimeInstallArt);

        errorPlate.setAccentStripe(palette.getAccentRed());
        errorPlate.setLayout(new BorderLayout(8, 0));
        errorText.setEditable(false);
        errorText.setFocusable(false);
        errorText.setLineWrap(true);
        errorText.setWrapStyleWord(true);
        errorText.setOpaque(false);
        errorText.setForeground(palette.getInk());
        errorText.setFont(ResearchUiTypography.regular(12f));
        errorText.getAccessibleContext().setAccessibleName("Error in this step");
        errorPlate.add(errorText, BorderLayout.CENTER);
        JPanel networkWrap = transparent(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        networkWrap.add(networkButton);
        errorPlate.add(networkWrap, BorderLayout.EAST);
        errorPlate.setVisible(false);

        JPanel buttons = transparent(new BorderLayout());
        JPanel left = transparent(new FlowLayout(FlowLayout.LEFT, 0, 0));
        left.add(cancelButton);
        left.add(javax.swing.Box.createHorizontalStrut(8));
        left.add(skipButton);
        JPanel right = transparent(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        right.add(backButton);
        right.add(nextButton);
        right.add(finishButton);
        buttons.add(left, BorderLayout.WEST);
        buttons.add(right, BorderLayout.EAST);

        JPanel south = transparent(new BorderLayout(0, 8));
        south.add(errorPlate, BorderLayout.CENTER);
        south.add(buttons, BorderLayout.SOUTH);
        add(north, BorderLayout.NORTH);
        add(deck, BorderLayout.CENTER);
        add(south, BorderLayout.SOUTH);
    }

    private JPanel connectionStep() {
        Rows rows = new Rows(STEP_CONNECTION, "Connection and voice");
        rows.note("AskAI downloads models and voices and searches the web. Choose how it reaches the internet; in "
                + "company networks AskAI can also trust the certificates of the Windows certificate stores. Then "
                + "download a voice: the download uses exactly these settings, so a moving progress bar means the "
                + "network works. Working offline? Keep the Windows voice and skip this step.");
        rows.stretched("Proxy", inline(proxyMode, networkTest, networkAdvanced));
        manualProxyParts.addAll(rows.field("Proxy host", proxyHost));
        manualProxyParts.addAll(rows.field("Proxy port", proxyPort));
        rows.field("", trustWindows);
        rows.stretched("", new StatusLine(null, networkStatus).panel);
        rows.heading("Voice");
        rows.stretched("German", inline(voiceGerman, voiceGermanAction));
        rows.stretched("English", inline(voiceEnglish, voiceEnglishAction));
        voiceProgress.setStringPainted(true);
        voiceProgress.setVisible(false);
        rows.stretched("", inline(new StatusLine(voiceProgress, voiceStatus).panel, voiceRetry));
        return rows.panel();
    }

    private JPanel runtimeStep() {
        Rows rows = new Rows(STEP_RUNTIME, "AI runtime");
        rows.note("AskAI runs its models in Ollama – on this computer or on a server – and in its own local model "
                + "runtime. One of them must answer; the final check shows which one does.");
        rows.stretched("Ollama address", inline(ollamaUrl, runtimeTest, runtimeDownload));
        rows.stretched("", new StatusLine(null, ollamaStatus).panel);
        return rows.panel();
    }

    private JPanel chatStep() {
        Rows rows = new Rows(STEP_CHAT, "Chat model");
        rows.note("The chat model answers in the chat and is the main model of all plugins (research agent, "
                + "summaries, tools). The list shows the models installed in Ollama and in AskAI's local runtime.");
        chatModel.setEditable(true);
        rows.stretched("Chat model", inline(chatModel, chatDownload.button, chatRecommended, chatAlternative, modelsRefresh,
                chatInstall));
        rows.stretched("", chatDownload.line.panel);
        rows.stretched("", new StatusLine(null, modelsStatus).panel);
        return rows.panel();
    }

    private JPanel knowledgeStep() {
        Rows rows = new Rows(STEP_KNOWLEDGE, "Knowledge and research");
        rows.note("The research agent finds matching passages with the embedding model, ranks them with the "
                + "reranker and splits sources into sentences with the OpenNLP sentence models. The reranker is "
                + "required for web research; it runs in AskAI's own local runtime, so install it here.");
        embeddingModel.setEditable(true);
        embeddingDimension.setEditable(false);
        embeddingDimension.setToolTipText("Vector length, measured with a real embedding call");
        dimensionMeasure.setToolTipText("Measure the vector length of the embedding model again");
        rows.stretched("Embedding model", inline(embeddingModel, embeddingDimension, dimensionMeasure,
                embeddingDownload.button, embeddingInstall));
        rows.stretched("", embeddingDownload.line.panel);
        rows.stretched("Reranker", inline(rerankerModel, rerankerDownload.button));
        rows.stretched("", rerankerDownload.line.panel);
        rows.stretched("Sentences (German)", inline(sentenceGerman, sentenceInstall));
        rows.stretched("Sentences (English)", inline(sentenceEnglish));
        rows.stretched("", new StatusLine(null, knowledgeStatus).panel);
        researchRows = rows;
        return rows.panel();
    }

    /**
     * Adds what the agent plugins need (search browser, search provider) the first time the research step
     * opens – the plugins load in the background at start and are there by then. They decide, the wizard shows.
     */
    private void addPluginSections() {
        if (pluginSectionsAdded || researchRows == null) {
            return;
        }
        com.aresstack.askai.plugin.api.agent.setup.AgentSetupContext context = setup.pluginSetupContext();
        List<com.aresstack.askai.plugin.api.agent.setup.AgentSetupContribution> contributions = setup.pluginSetup();
        if (context == null || contributions.isEmpty()) {
            return; // perhaps the plugins are not loaded yet: try again on the next visit
        }
        pluginSectionsAdded = true;
        for (com.aresstack.askai.plugin.api.agent.setup.AgentSetupContribution contribution : contributions) {
            try {
                com.aresstack.askai.plugin.api.agent.setup.AgentSetupPage page = contribution.createSetupPage(context);
                if (page != null && page.getComponent() != null) {
                    researchRows.section(contribution.getDisplayName(), page.getComponent());
                    pluginPages.add(page);
                }
            } catch (RuntimeException | LinkageError broken) {
                // A plugin's page must never break the setup.
            }
        }
        researchRows.panel.revalidate();
        researchRows.panel.repaint();
    }

    private JPanel dictationStep() {
        Rows rows = new Rows(STEP_DICTATION, "Dictation");
        rows.note("Optional: speak instead of typing. AskAI records with your microphone and an audio-capable "
                + "model in your AI runtime writes down what you said. Skip this step if you only type.");
        rows.stretched("Microphone", inline(microphone, micTest));
        micLevel.setVisible(false);
        rows.stretched("", new StatusLine(micLevel, micStatus).panel);
        rows.stretched("Audio model", inline(audioModel, audioDownload.button, audioInstall));
        rows.stretched("", audioDownload.line.panel);
        rows.stretched("", new StatusLine(null, audioStatus).panel);
        return rows.panel();
    }

    private JPanel checkStep() {
        Rows rows = new Rows(STEP_CHECK, "Final check");
        rows.note("The settings are saved and then checked for real. Nothing here blocks you: missing local models "
                + "can be installed right here, and “Finish” ends the setup either way.");
        rows.wide(checkRows);
        rows.field("", checkStatus);
        JPanel checkActions = transparent(new FlowLayout(FlowLayout.LEFT, 8, 0));
        checkActions.add(checkInstallMissing);
        checkActions.add(checkRetry);
        rows.field("", checkActions);
        return rows.panel();
    }

    // ------------------------------------------------------------------ behaviour

    private void wire() {
        cancelButton.addActionListener(e -> cancel());
        backButton.addActionListener(e -> showStep(step - 1));
        nextButton.addActionListener(e -> showStep(step + 1));
        trail.setOnSelect(this::jumpTo);
        finishButton.addActionListener(e -> finish());
        skipButton.addActionListener(e -> skip());
        networkButton.addActionListener(e -> editNetwork());
        networkAdvanced.addActionListener(e -> editNetwork());
        proxyMode.addActionListener(e -> {
            if (!busy) {
                networkChanged();
            }
        });
        SimpleDocumentListener networkEdited = new SimpleDocumentListener(() -> {
            if (!busy) {
                networkChanged();
            }
        });
        proxyHost.getDocument().addDocumentListener(networkEdited);
        proxyPort.getDocument().addDocumentListener(networkEdited);
        trustWindows.addActionListener(e -> networkChanged());
        networkTest.addActionListener(e -> testNetwork());
        ollamaUrl.getDocument().addDocumentListener(new SimpleDocumentListener(() -> {
            if (!busy) {
                runtimeGeneration++;
                runtimeChecking = false;
                done[STEP_RUNTIME] = false;
                updateButtons();
            }
        }));
        runtimeTest.addActionListener(e -> checkRuntime());
        runtimeDownload.addActionListener(e -> setup.openRuntimeDownload());
        modelsRefresh.addActionListener(e -> refreshModels());
        chatInstall.setToolTipText("Find and install a model from Hugging Face, the Ollama library or AskAI's "
                + "local runtime");
        embeddingInstall.setToolTipText(chatInstall.getToolTipText());
        audioInstall.setToolTipText(chatInstall.getToolTipText());
        chatInstall.addActionListener(e -> browseModels(false));
        embeddingInstall.addActionListener(e -> browseModels(false));
        audioInstall.addActionListener(e -> browseModels(true));
        chatModel.addActionListener(e -> updateButtons());
        embeddingModel.addActionListener(e -> {
            if (!busy) {
                measureDimension();
            }
        });
        dimensionMeasure.addActionListener(e -> {
            measuredFor = "";
            measureDimension();
        });
        sentenceInstall.addActionListener(e -> installSentenceModels());
        chatDownload.button.addActionListener(e -> pull(chatDownload, chatModel));
        chatRecommended.setToolTipText("Download the recommended chat model through Ollama and select it");
        chatRecommended.addActionListener(e -> {
            chatModel.setSelectedItem(SetupActions.RECOMMENDED_CHAT_MODEL);
            pull(chatDownload, chatModel);
        });
        chatAlternative.setToolTipText("Download " + SetupActions.ALTERNATIVE_CHAT_MODEL + " – answers better than "
                + SetupActions.RECOMMENDED_CHAT_MODEL + " but needs more memory");
        chatAlternative.addActionListener(e -> {
            chatModel.setSelectedItem(SetupActions.ALTERNATIVE_CHAT_MODEL);
            pull(chatDownload, chatModel);
        });
        micTest.addActionListener(e -> testMicrophone());
        audioDownload.button.addActionListener(e -> pullAudioModel());
        embeddingDownload.button.addActionListener(e -> pull(embeddingDownload, embeddingModel));
        rerankerDownload.button.addActionListener(e -> installReranker());
        checkRetry.addActionListener(e -> runFinalCheck());
        checkInstallMissing.addActionListener(e -> installMissingModels());
        voiceGerman.addActionListener(e -> updateButtons());
        voiceEnglish.addActionListener(e -> updateButtons());
        voiceGermanAction.addActionListener(e -> installVoice(voiceGerman));
        voiceEnglishAction.addActionListener(e -> installVoice(voiceEnglish));
        voiceRetry.addActionListener(e -> loadVoices());
    }

    public void setOnFinished(Runnable callback) {
        this.onFinished = callback;
    }

    public void setOnCancel(Runnable callback) {
        this.onCancel = callback;
    }

    /** The current draft (without secrets). */
    public SetupDraft draft() {
        return collect();
    }

    /** Cancels (also for the overlay's ✕ and Escape). */
    public void cancel() {
        close();
        if (onCancel != null) {
            onCancel.run();
        }
    }

    int step() {
        return step;
    }

    boolean isDone(int index) {
        return done[index];
    }

    String errorOf(int index) {
        return errors[index];
    }

    /**
     * Jumps to a step without a check (click in the trail, “Skip”): entries stay, the completion state of the steps
     * does not change. Not while a request runs, so its result does not land in another step.
     */
    void jumpTo(int index) {
        if (busy || installing() || handingOff || index == step) {
            return;
        }
        showStep(index);
    }

    // ------------------------------------------------------------------ Witch

    /**
     * A later call (from the settings): the runtime is set up already, Witch is awake from the start and greets as
     * a reunion instead of introducing himself a second time.
     */
    public void welcomeBack() {
        wakeWitch(true);
    }

    /** Witch's voice; without one he only uses speech bubbles. */
    public void setVoice(WizardVoice voice) {
        narrator.setVoice(voice);
    }

    /** Ends Witch's speech output (wizard closed). */
    void stopVoice() {
        narrator.stop();
        if (handoffVoices != null) {
            handoffVoices.close();
            handoffVoices = null;
        }
    }

    /**
     * Whether “Finish” plays the hand-over to the chat assistant: only on the first successful setup, not on every
     * later call from the settings.
     */
    public void setHandoff(boolean firstCompletion) {
        this.handoff = firstCompletion;
    }

    boolean isHandingOff() {
        return handingOff;
    }

    /** How long a line stays without a voice, per character (tests: 0 = immediately on). */
    void setReadingPace(int millisPerCharacter) {
        this.readingPace = Math.max(0, millisPerCharacter);
    }

    boolean isWitchAwake() {
        return witchAwake;
    }

    /** A free question needs Witch awake and a chat model. */
    boolean canAskWitch() {
        return witchAwake && !text(chatModel).isEmpty();
    }

    /** The menu on a click on Witch: fixed answers, skip and (with a chat model) a free question. */
    JPopupMenu witchMenu() {
        final int current = step;
        JPopupMenu menu = new JPopupMenu();
        JMenuItem what = new JMenuItem("What do I have to do here?");
        what.addActionListener(e -> narrator.say(guide.whatToDo(current)));
        menu.add(what);
        JMenuItem why = new JMenuItem("Why do I need this?");
        why.addActionListener(e -> narrator.say(guide.whyNeeded(current)));
        menu.add(why);
        JMenuItem skip = new JMenuItem("Skip this step");
        skip.setEnabled(skipButton.isEnabled());
        skip.addActionListener(e -> skip());
        menu.add(skip);
        JMenuItem ask = new JMenuItem("Ask your own question …");
        ask.setEnabled(canAskWitch());
        ask.setToolTipText(canAskWitch() ? null : "Possible once a runtime answers and a chat model is chosen");
        ask.addActionListener(e -> mascotOf(current).askQuestion(this::askWitch));
        menu.add(ask);
        return menu;
    }

    /** The free question to the draft's chat model; Witch's frame comes from {@link WizardGuide}. */
    void askWitch(String question) {
        if (!canAskWitch() || question == null || question.trim().isEmpty()) {
            return;
        }
        narrator.show(WizardGuide.THINKING);
        setup.ask(collect(), guide.systemPrompt(step), question.trim(), (answer, error) -> {
            if (closed) {
                return;
            }
            boolean answered = answer != null && !answer.trim().isEmpty();
            narrator.say(answered ? answer.trim() : WizardGuide.NO_ANSWER);
        });
    }

    private void wakeWitch(boolean returning) {
        if (witchAwake) {
            return;
        }
        witchAwake = true;
        narrator.say(guide.greeting(returning));
    }

    /** “Finish” with the setup saved: the first time the hand-over plays first, then the wizard closes. */
    private void complete() {
        if (handingOff) {
            return;
        }
        if (!handoff) {
            closeWizard();
            return;
        }
        handingOff = true;
        updateButtons();
        trail.setNavigable(false);
        setup.voices(collect(), voices -> {
            handoffVoices = voices == null ? SetupVoices.silent(null) : voices;
            narrator.stop();
            handoffLine(0);
        });
    }

    /**
     * Line {@code index} of the hand-over: Witch with his voice, the chat assistant with hers and her name. Without
     * a voice the next line follows after a reading pause; after the last one the wizard closes.
     */
    private void handoffLine(final int index) {
        final SetupVoices voices = handoffVoices;
        if (voices == null) {
            return; // cancelled meanwhile
        }
        if (index >= WizardGuide.handoffLines()) {
            handoffVoices = null;
            voices.close();
            closeWizard();
            return;
        }
        WizardGuide.Line line = WizardGuide.handoff(index);
        mascotOf(step).say(line.witch ? WizardGuide.NAME : voices.assistantName(), line.text);
        WizardVoice voice = line.witch ? voices.witch() : voices.assistant();
        if (voice != null && voice.isAvailable()) {
            voice.speak(line.text);
        }
        Runnable next = () -> {
            if (handoffVoices == voices) {
                handoffLine(index + 1);
            }
        };
        if (readingPace == 0) {
            next.run();
        } else {
            Timer pause = new Timer(Math.max(MIN_READING_MILLIS, line.text.length() * readingPace),
                    e -> next.run());
            pause.setRepeats(false);
            pause.start();
        }
    }

    private void closeWizard() {
        if (onFinished != null) {
            onFinished.run();
        }
    }

    // ------------------------------------------------------------------ navigation

    /** “Skip”: on to the next step; in the last step finish the setup right away. */
    private void skip() {
        if (step == STEP_CHECK) {
            finish();
        } else {
            jumpTo(step + 1);
        }
    }

    void showStep(int index) {
        if (index < 0 || index >= STEP_NAMES.length) {
            return;
        }
        draft = collect();
        setup.useNetwork(draft); // plugin downloads (search browser) use the proxy entered above, even unsaved
        if (index == STEP_KNOWLEDGE) {
            addPluginSections();
        }
        if (step == STEP_VOICE && index != STEP_VOICE) {
            saveVoicesOnLeaving();
        }
        step = index;
        cards.show(deck, STEP_NAMES[index]);
        for (SetupWizardMascot mascot : mascots) {
            mascot.say(null);
        }
        if (!witchAwake && narrator.canSpeak()) {
            wakeWitch(false); // a downloaded Piper voice is enough: the wizard speaks without any AI runtime
        } else if (witchAwake) {
            narrator.say(guide.enteredStep(index));
        } else {
            narrator.show(WizardGuide.THOUGHT_SILENT); // only a thought: he has no voice yet
        }
        if (index == STEP_RUNTIME && !done[STEP_RUNTIME] && !runtimeChecking && !busy) {
            checkRuntime(); // the default address usually works: check right away instead of waiting for a click
        } else if ((index == STEP_CHAT || index == STEP_KNOWLEDGE) && !modelsLoaded && !modelsLoading) {
            refreshModels();
        } else if (index == STEP_CHECK) {
            runFinalCheck();
        } else if (index == STEP_VOICE && !voicesLoaded) {
            loadVoices();
        } else if (index == STEP_DICTATION && !dictationLoaded) {
            loadDictation();
        }
        updateButtons();
    }

    private void updateButtons() {
        if (done[STEP_CHECK] && !checkedKey(collect()).equals(checkedDraft)) {
            // Something the final check saved and checked changed afterwards: save and check again first.
            done[STEP_CHECK] = false;
            checkStatus.setText(CHECK_OUTDATED);
        }
        done[STEP_CHAT] = !text(chatModel).isEmpty();
        done[STEP_KNOWLEDGE] = !text(embeddingModel).isEmpty() && positive(embeddingDimension.getText());
        trail.show(step, done);
        boolean voice = step == STEP_CHECK; // the last step: “Finish” instead of “Next”
        backButton.setEnabled(step > 0 && !busy && !installing());
        nextButton.setVisible(!voice);
        finishButton.setVisible(voice);
        // Every step can be passed over: a missing or failed check is only a hint, never a lock.
        // The chat model step goes on only with an installed model chosen; "Skip" always goes on.
        nextButton.setEnabled(!voice && !busy && !installing() && (step != STEP_CHAT || chatModelReady()));
        finishButton.setEnabled(voice && !busy && !installing());
        cancelButton.setVisible(!voice);
        // “Skip” in every step: on without a check; in the last step finish without changing the voices.
        skipButton.setEnabled(!busy && !installing());
        skipButton.setToolTipText(voice ? SKIP_VOICE_HINT : SKIP_STEP_HINT);
        trail.setNavigable(!busy && !installing() && !handingOff);
        boolean manual = PROXY_MANUAL.equals(selectedProxyMode());
        for (JComponent part : manualProxyParts) {
            part.setVisible(manual);
        }
        // While a request runs its inputs stay put, so its answer always belongs to what is shown.
        proxyMode.setEnabled(!busy);
        proxyHost.setEnabled(!busy);
        proxyPort.setEnabled(!busy);
        trustWindows.setEnabled(!busy);
        networkTest.setEnabled(!busy);
        networkAdvanced.setEnabled(!busy);
        runtimeTest.setEnabled(!busy && !runtimeChecking);
        modelsRefresh.setEnabled(!busy && !modelsLoading);
        chatInstall.setEnabled(!busy && !modelsLoading);
        embeddingInstall.setEnabled(!busy && !modelsLoading);
        audioInstall.setEnabled(!busy && !audioLoading);
        dimensionMeasure.setEnabled(!busy && !text(embeddingModel).isEmpty());
        // Offer a download only for a model that is not there yet.
        chatDownload.button.setEnabled(!chatDownload.running && !text(chatModel).isEmpty());
        chatDownload.button.setVisible(!text(chatModel).isEmpty() && !installedChat.contains(text(chatModel))
                && !SetupActions.RECOMMENDED_CHAT_MODEL.equals(text(chatModel))
                && !SetupActions.ALTERNATIVE_CHAT_MODEL.equals(text(chatModel)));
        chatRecommended.setVisible(!installedChat.contains(SetupActions.RECOMMENDED_CHAT_MODEL));
        chatRecommended.setEnabled(!chatDownload.running);
        chatAlternative.setVisible(!installedChat.contains(SetupActions.ALTERNATIVE_CHAT_MODEL));
        chatAlternative.setEnabled(!chatDownload.running);
        embeddingDownload.button.setEnabled(!embeddingDownload.running && !text(embeddingModel).isEmpty());
        embeddingDownload.button.setVisible(!text(embeddingModel).isEmpty()
                && !installedEmbeddings.contains(text(embeddingModel)));
        rerankerDownload.button.setEnabled(!rerankerDownload.running);
        micTest.setEnabled(!busy && !micTesting);
        audioDownload.button.setVisible(dictationLoaded && !audioLoading && installedAudio.isEmpty());
        audioDownload.button.setEnabled(!audioDownload.running);
        done[STEP_DICTATION] = dictationLoaded && !installedAudio.isEmpty();
        // The reranker is a local model: offer the installation whenever the chosen one is not installed.
        rerankerDownload.button.setVisible(!installedRerankers.contains(text(rerankerModel)));
        sentenceInstall.setEnabled(!busy && !installing());
        sentenceInstall.setVisible(sentenceModelsMissing());
        checkInstallMissing.setVisible(pendingFixes > 0 || !missingLocalModels().isEmpty());
        checkInstallMissing.setEnabled(!busy && pendingFixes == 0);
        voiceAction(voiceGerman, voiceGermanAction);
        voiceAction(voiceEnglish, voiceEnglishAction);
        voiceRetry.setVisible(voicesLoadFailed);
        voiceRetry.setEnabled(!busy && !installing());
        voiceGerman.setEnabled(!installing());
        voiceEnglish.setEnabled(!installing());
        if (handingOff) { // the hand-over runs to its end, then the wizard closes itself
            backButton.setEnabled(false);
            nextButton.setEnabled(false);
            finishButton.setEnabled(false);
            skipButton.setEnabled(false);
        }
        String error = errors[step];
        errorPlate.setVisible(error != null && !error.isEmpty());
        errorText.setText(error == null ? "" : error);
        networkButton.setVisible((step == STEP_NETWORK || step == STEP_RUNTIME) && error != null
                && !error.isEmpty());
        revalidate();
        repaint();
    }

    private void fail(int index, String message) {
        errors[index] = message;
        updateButtons();
        if (witchAwake && index == step) {
            narrator.say(guide.stepFailed(index));
        }
    }

    private void clearError(int index) {
        errors[index] = null;
    }

    // --- step 1: network & security

    private void networkChanged() {
        networkGeneration++;
        done[STEP_NETWORK] = false;
        networkStatus.setText(" ");
        updateButtons();
    }

    private void testNetwork() {
        final int generation = ++networkGeneration;
        clearError(STEP_NETWORK);
        done[STEP_NETWORK] = false;
        networkStatus.setText("Testing the connection …");
        setBusy(true);
        setup.testNetwork(collect(), (message, error) -> {
            if (closed || generation != networkGeneration) {
                return;
            }
            setBusy(false);
            if (error == null) {
                done[STEP_NETWORK] = true;
                networkStatus.setText(message == null || message.isEmpty() ? "The internet answers." : message);
                updateButtons();
            } else {
                networkStatus.setText("No connection.");
                fail(STEP_NETWORK, error);
            }
        });
    }

    private void editNetwork() {
        SetupDraft changed = setup.editNetwork(this, collect());
        if (changed != null) {
            setProxy(changed);
            trustWindows.setSelected(changed.trustWindowsStores());
            networkChanged();
        }
    }

    private String selectedProxyMode() {
        String label = text(proxyMode);
        if (LABEL_PROXY_NONE.equals(label)) {
            return PROXY_NONE;
        }
        if (LABEL_PROXY_WINDOWS.equals(label)) {
            return PROXY_WINDOWS;
        }
        if (LABEL_PROXY_MANUAL.equals(label)) {
            return PROXY_MANUAL;
        }
        return otherProxyMode.isEmpty() ? PROXY_NONE : otherProxyMode;
    }

    private void setProxy(SetupDraft source) {
        boolean wasBusy = busy;
        busy = true; // filling the fields is no user change
        try {
            String mode = source.proxyMode();
            otherProxyMode = mode.isEmpty() || PROXY_NONE.equals(mode) || PROXY_WINDOWS.equals(mode)
                    || PROXY_MANUAL.equals(mode) ? "" : mode;
            proxyMode.removeAllItems();
            proxyMode.addItem(LABEL_PROXY_NONE);
            proxyMode.addItem(LABEL_PROXY_WINDOWS);
            proxyMode.addItem(LABEL_PROXY_MANUAL);
            if (!otherProxyMode.isEmpty()) {
                proxyMode.addItem(LABEL_PROXY_OTHER + otherProxyMode);
            }
            proxyMode.setSelectedItem(!otherProxyMode.isEmpty() ? LABEL_PROXY_OTHER + otherProxyMode
                    : PROXY_WINDOWS.equals(mode) ? LABEL_PROXY_WINDOWS
                    : PROXY_MANUAL.equals(mode) ? LABEL_PROXY_MANUAL : LABEL_PROXY_NONE);
            proxyHost.setText(source.proxyHost());
            proxyPort.setText(source.proxyPort() > 0 ? Integer.toString(source.proxyPort()) : "");
        } finally {
            busy = wasBusy;
        }
    }

    // --- step 2: AI runtime

    private void checkRuntime() {
        final int generation = ++runtimeGeneration;
        clearError(STEP_RUNTIME);
        runtimeChecking = true;
        done[STEP_RUNTIME] = false;
        ollamaStatus.setText("Asking Ollama …");
        updateButtons();
        setup.checkRuntime(collect(), status -> {
            if (closed || generation != runtimeGeneration) {
                return;
            }
            runtimeChecking = false;
            state(ollamaStatus, status.ollamaVersion() != null, status.ollamaVersion() != null
                    ? "Connected (version " + status.ollamaVersion() + ")"
                    : "Not reachable" + (status.ollamaProblem().isEmpty() ? "" : ": "
                    + shorten(status.ollamaProblem())));
            // AskAI's local runtime has nothing to set up here; the final check reports it.
            done[STEP_RUNTIME] = status.reachable();
            mascotOf(STEP_RUNTIME).setArt(status.reachable() ? runtimeConnectedArt : runtimeInstallArt);
            if (!status.reachable()) {
                fail(STEP_RUNTIME, "Neither Ollama nor AskAI's local runtime can serve models. Start Ollama "
                        + "(or install it with “Download Ollama…”), check the address and test again."
                        + (status.ollamaProblem().isEmpty() ? "" : "\nOllama: " + status.ollamaProblem()));
                return;
            }
            runtimeAnswered();
            updateButtons();
        });
    }

    /** The runtime answers: Witch wakes up (or reports), and the model lists are prepared in the background. */
    private void runtimeAnswered() {
        if (!witchAwake) {
            wakeWitch(false);
        } else if (step == STEP_RUNTIME) {
            narrator.say(WizardGuide.RUNTIME_OK);
        }
        modelsLoaded = false;
        refreshModels();
        if (audioListed) {
            loadAudioModels(choice(audioModel, AUDIO_AUTOMATIC)); // another runtime has other audio models
        }
    }

    // --- steps 3 and 4: models

    private void refreshModels() {
        if (modelsLoading) {
            return;
        }
        modelsLoading = true;
        modelsStatus.setText("Loading models …");
        updateButtons();
        setup.loadModels(collect(), models -> {
            if (closed) {
                return;
            }
            modelsLoading = false;
            modelsLoaded = true;
            applyModels(models);
        });
    }

    /**
     * Opens AskAI's model browser (Hugging Face, Ollama library, local runtime) over the wizard; afterwards the
     * lists are read again and a model installed meanwhile is selected.
     */
    private void browseModels(boolean audio) {
        final List<String> chatBefore = new ArrayList<String>(installedChat);
        final List<String> embeddingsBefore = new ArrayList<String>(installedEmbeddings);
        final List<String> audioBefore = new ArrayList<String>(installedAudio);
        setup.openModelBrowser(this); // modal: returns when the browser is closed
        if (closed) {
            return;
        }
        if (audio) {
            final String current = choice(audioModel, AUDIO_AUTOMATIC);
            setup.loadAudioModels(collect(), models -> {
                if (closed) {
                    return;
                }
                String added = firstNew(models, audioBefore);
                loadAudioModels(added == null ? current : added);
            });
            return;
        }
        chatBeforeBrowser = chatBefore;
        embeddingsBeforeBrowser = embeddingsBefore;
        modelsLoaded = false;
        refreshModels();
    }

    /** An installed chat model is chosen: only then may "Next" leave the chat model step. */
    private boolean chatModelReady() {
        return installedChat.contains(text(chatModel));
    }

    /** The first entry of {@code now} that was not in {@code before}; {@code null} if none. */
    static String firstNew(List<String> now, List<String> before) {
        if (now != null) {
            for (String name : now) {
                if (!before.contains(name)) {
                    return name;
                }
            }
        }
        return null;
    }

    private void applyModels(SetupActions.SetupModels models) {
        if (chatBeforeBrowser != null) { // back from the model browser: select what was installed there
            String chat = firstNew(models.chat(), chatBeforeBrowser);
            String embedding = firstNew(models.embeddings(), embeddingsBeforeBrowser);
            chatBeforeBrowser = null;
            embeddingsBeforeBrowser = null;
            if (chat != null) {
                chatModel.setSelectedItem(chat);
            }
            if (embedding != null) {
                embeddingModel.setSelectedItem(embedding);
            }
        }
        boolean wasBusy = busy;
        busy = true; // filling the lists is no user change
        try {
            setChoices(chatModel, models.chat(), preferred(text(chatModel), models.chat()), false);
            setChoices(embeddingModel, models.embeddings(), preferred(text(embeddingModel), models.embeddings()),
                    false);
            setChoices(rerankerModel, models.rerankers(), text(rerankerModel), true);
            setChoices(sentenceGerman, models.sentenceGerman(),
                    preferred(text(sentenceGerman), models.sentenceGerman()), true);
            setChoices(sentenceEnglish, models.sentenceEnglish(),
                    preferred(text(sentenceEnglish), models.sentenceEnglish()), true);
        } finally {
            busy = wasBusy;
        }
        int count = models.chat().size();
        if (text(chatModel).isEmpty() && count == 0) {
            chatModel.setSelectedItem(SetupActions.RECOMMENDED_CHAT_MODEL); // a suggestion, downloaded on request
        }
        if (text(embeddingModel).isEmpty() && models.embeddings().isEmpty()) {
            embeddingModel.setSelectedItem(SetupActions.RECOMMENDED_EMBEDDING_MODEL);
        }
        installedChat = models.chat();
        installedEmbeddings = models.embeddings();
        installedRerankers = models.rerankers();
        installedSentenceGerman = models.sentenceGerman();
        installedSentenceEnglish = models.sentenceEnglish();
        modelsStatus.setText(!models.problem().isEmpty() ? shorten(models.problem())
                : count == 0 ? "No chat model installed yet – “Install " + SetupActions.RECOMMENDED_CHAT_MODEL
                + "” fetches the recommended one."
                : count == 1 ? "1 chat model available." : count + " chat models available.");
        updateButtons();
        measureDimension();
    }

    /** The current value if set, else the first available one, else empty. */
    static String preferred(String current, List<String> available) {
        if (current != null && !current.trim().isEmpty()) {
            return current.trim();
        }
        return available == null || available.isEmpty() ? "" : available.get(0);
    }

    private void measureDimension() {
        final String model = text(embeddingModel);
        if (model.isEmpty()) {
            embeddingDimension.setText("");
            updateButtons();
            return;
        }
        if (model.equals(measuredFor) && positive(embeddingDimension.getText())) {
            return;
        }
        measuredFor = model;
        embeddingDimension.setText("");
        clearError(STEP_KNOWLEDGE);
        knowledgeStatus.setText("Measuring the embedding model …");
        updateButtons();
        setup.embeddingDimension(collect(), (dimension, error) -> {
            if (closed || !model.equals(text(embeddingModel))) {
                return;
            }
            if (dimension != null && dimension > 0) {
                embeddingDimension.setText(Integer.toString(dimension));
                knowledgeStatus.setText("The embedding model works.");
                updateButtons();
            } else {
                measuredFor = "";
                knowledgeStatus.setText(" ");
                fail(STEP_KNOWLEDGE, "The embedding model “" + model + "” did not answer"
                        + (error == null || error.isEmpty() ? "." : ": " + error));
            }
        });
    }

    private void installSentenceModels() {
        installSentenceModels(null);
    }

    private void installSentenceModels(final Runnable after) {
        if (sentenceInstalling) {
            return;
        }
        sentenceInstalling = true;
        clearError(STEP_KNOWLEDGE);
        knowledgeStatus.setText("Installing sentence models …");
        updateButtons();
        setup.installSentenceModels(line -> {
            if (!closed) {
                knowledgeStatus.setText(shorten(line));
            }
        }, (message, error) -> {
            if (closed) {
                return;
            }
            sentenceInstalling = false;
            if (error != null) {
                knowledgeStatus.setText(" ");
                fail(STEP_KNOWLEDGE, "The sentence models could not be installed: " + error);
                if (after != null) {
                    fixFailures.append("Sentence models: ").append(error).append('\n');
                    after.run();
                }
                return;
            }
            knowledgeStatus.setText(message == null || message.isEmpty() ? "Sentence models installed." : message);
            if (witchAwake && step == STEP_KNOWLEDGE) {
                narrator.say(WizardGuide.SENTENCE_MODELS_OK);
            }
            modelsLoaded = false;
            refreshModels();
            if (after != null) {
                after.run();
            }
        });
    }

    // --- step 5: final check

    /** Stores what the plugin sections hold; a failing plugin does not stop the rest. */
    private void savePluginPages() {
        for (com.aresstack.askai.plugin.api.agent.setup.AgentSetupPage page : pluginPages) {
            try {
                page.save();
            } catch (RuntimeException ignored) {
                // the plugin reports its own problems in its section
            }
        }
    }

    private void runFinalCheck() {
        savePluginPages();
        done[STEP_CHECK] = false;
        clearError(STEP_CHECK);
        checkRows.removeAll();
        draft = collect();
        checkStatus.setText("Saving and checking …");
        setBusy(true);
        setup.finish(draft, checks -> {
            if (closed) {
                return;
            }
            setBusy(false);
            lastChecks = checks == null ? Collections.<SetupCheck>emptyList() : checks;
            showChecks(lastChecks);
            boolean allOk = !lastChecks.isEmpty();
            StringBuilder failed = new StringBuilder();
            for (SetupCheck check : lastChecks) {
                if (!check.isOk()) {
                    allOk = false;
                    failed.append(check.label()).append(": ").append(check.detail()).append('\n');
                }
            }
            done[STEP_CHECK] = allOk;
            mascotOf(STEP_CHECK).setArt(allOk && checkSuccessArt != null ? checkSuccessArt : checkPendingArt);
            checkedDraft = allOk ? checkedKey(draft) : "";
            checkStatus.setText(allOk ? "Everything is ready." : "Not ready yet.");
            if (!allOk) {
                fail(STEP_CHECK, failed.length() == 0 ? "The check returned no result." : failed.toString().trim());
            } else {
                updateButtons();
            }
        });
    }

    List<SetupCheck> lastChecks() {
        return lastChecks;
    }

    private void showChecks(List<SetupCheck> checks) {
        checkRows.removeAll();
        GridBagConstraints c = new GridBagConstraints();
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(2, 0, 2, 8);
        int row = 0;
        for (SetupCheck check : checks) {
            c.gridx = 0;
            c.gridy = row;
            c.weightx = 0;
            JLabel icon = new JLabel(check.isOk() ? new CheckGlyph() : new CrossGlyph());
            icon.setForeground(check.isOk() ? SetupStepTrail.DONE : ResearchUiPalette.DANGER_RED);
            checkRows.add(icon, c);
            c.gridx = 1;
            c.weightx = 1;
            JLabel label = new JLabel(check.label() + (check.detail().isEmpty() ? "" : " – "
                    + shorten(check.detail())));
            label.setForeground(palette.getInk());
            label.setFont(ResearchUiTypography.regular(12.5f));
            label.setToolTipText(check.detail().isEmpty() ? null : check.detail());
            checkRows.add(label, c);
            row++;
        }
        checkRows.revalidate();
        checkRows.repaint();
    }

    // --- step 6: voice (optional)

    private void loadVoices() {
        voicesLoadFailed = false;
        voiceStatus.setText("Loading voices …");
        updateButtons();
        setup.loadVoices(offers -> {
            if (closed) {
                return;
            }
            voicesLoaded = true;
            if (offers == null) {
                voicesLoadFailed = true;
                voiceStatus.setText("The voices could not be determined.");
                updateButtons();
                return;
            }
            showVoices(offers);
        });
    }

    private void showVoices(List<SetupActions.VoiceOffer> offers) {
        String german = draft.voiceGerman();
        String english = draft.voiceEnglish();
        if (!voiceByLabel.isEmpty()) { // keep what the user picked in this step
            german = voiceId(voiceGerman);
            english = voiceId(voiceEnglish);
        }
        voiceByLabel.clear();
        for (SetupActions.VoiceOffer offer : offers) {
            voiceByLabel.put(offer.toString(), offer);
        }
        fillVoices(voiceGerman, offers, "de", german);
        fillVoices(voiceEnglish, offers, "en", english);
        voiceStatus.setText(" ");
        updateButtons();
    }

    private void fillVoices(JComboBox<String> combo, List<SetupActions.VoiceOffer> offers, String language,
                            String selectedId) {
        combo.removeAllItems();
        combo.addItem(WINDOWS_VOICE);
        String selected = WINDOWS_VOICE;
        for (SetupActions.VoiceOffer offer : offers) {
            if (language.equals(offer.languageCode())) {
                combo.addItem(offer.toString());
                if (offer.id().equals(selectedId)) {
                    selected = offer.toString();
                }
            }
        }
        combo.setSelectedItem(selected);
    }

    /** The voice id of a choice; empty for the Windows voice. */
    private String voiceId(JComboBox<String> combo) {
        SetupActions.VoiceOffer offer = voiceByLabel.get(text(combo));
        return offer == null ? "" : offer.id();
    }

    private List<SetupActions.VoiceOffer> missingVoices() {
        List<SetupActions.VoiceOffer> missing = new ArrayList<SetupActions.VoiceOffer>();
        for (JComboBox<String> combo : java.util.Arrays.asList(voiceGerman, voiceEnglish)) {
            SetupActions.VoiceOffer offer = voiceByLabel.get(text(combo));
            if (offer != null && !offer.isInstalled() && !missing.contains(offer)) {
                missing.add(offer);
            }
        }
        return missing;
    }

    private boolean voicesReady() {
        return voicesLoaded && missingVoices().isEmpty();
    }

    /** The button behind a voice choice: "Download" for a missing Piper voice, "Reinstall" for an installed one. */
    private void voiceAction(JComboBox<String> combo, ComicButton button) {
        SetupActions.VoiceOffer offer = voiceByLabel.get(text(combo));
        button.setVisible(offer != null); // the Windows voice needs no download
        if (offer != null) {
            button.setText(offer.isInstalled() ? "Reinstall" : "Download");
            button.setToolTipText(offer.isInstalled() ? "Download " + offer.label() + " again"
                    : "Download " + offer.label() + " through the network settings above");
        }
        button.setEnabled(!busy && !installing());
    }

    private void installVoice(JComboBox<String> combo) {
        SetupActions.VoiceOffer offer = voiceByLabel.get(text(combo));
        if (offer == null || installing()) {
            return;
        }
        voiceInstalling = true;
        clearError(STEP_VOICE);
        updateButtons();
        installVoice(offer, offer.isInstalled());
    }

    private void installVoice(final SetupActions.VoiceOffer offer, final boolean reinstall) {
        if (closed) {
            return;
        }
        voiceStatus.setText("Connecting for " + offer.label() + " …");
        voiceProgress.setIndeterminate(true);
        voiceProgress.setString("Connecting …");
        voiceProgress.setVisible(true);
        setup.installVoice(collect(), offer.id(), reinstall, (stage, bytesDone, bytesTotal) -> {
            if (closed) {
                return;
            }
            voiceStatus.setText(offer.label() + ": " + shorten(stage));
            if (bytesTotal > 0) {
                voiceProgress.setIndeterminate(false);
                voiceProgress.setValue((int) Math.min(1000, 1000 * bytesDone / bytesTotal));
                voiceProgress.setString((100 * bytesDone / bytesTotal) + " %");
            }
        }, (message, error) -> {
            if (closed) {
                return;
            }
            if (error != null) {
                voiceInstalling = false;
                voiceProgress.setVisible(false);
                voiceStatus.setText(" ");
                fail(STEP_VOICE, offer.label() + " could not be downloaded – check the proxy and certificate "
                        + "settings above: " + error);
                loadVoices();
                return;
            }
            voiceInstalling = false;
            voiceProgress.setVisible(false);
            voiceStatus.setText(offer.label() + (reinstall ? " reinstalled." : " installed."));
            // The download went through the configured network: that is as good as a passed network test.
            done[STEP_NETWORK] = true;
            clearError(STEP_NETWORK);
            loadVoices();
        });
    }

    private void finish() {
        if (busy || installing() || handingOff) {
            return;
        }
        if (!voicesReady()) { // no voice ready: finish anyway, the voices stay as they are
            saveDraftThen(() -> {
                close();
                complete();
            });
            return;
        }
        saveDraftThen(this::saveVoicesAndClose);
    }

    /**
     * Makes sure the entries are in AskAI's settings before the wizard ends: the final check saves them, but a
     * user who jumped past it (or changed something afterwards) has not been saved yet. A failed check never
     * blocks; the entries are written all the same.
     */
    private void saveDraftThen(Runnable then) {
        savePluginPages();
        SetupDraft current = collect();
        if (done[STEP_CHECK] && checkedKey(current).equals(checkedDraft)) {
            then.run();
            return;
        }
        setBusy(true);
        setup.finish(current, checks -> {
            if (closed) {
                return;
            }
            setBusy(false);
            for (SetupCheck check : checks == null ? Collections.<SetupCheck>emptyList() : checks) {
                if (SAVING_CHECK.equals(check.label()) && !check.isOk()) {
                    // A failed readiness check never blocks, but unsaved entries must not be lost silently.
                    fail(step, "Your entries could not be saved: " + check.detail());
                    return;
                }
            }
            then.run();
        });
    }

    /**
     * Leaving the voice step stores the chosen, installed voices right away, so Witch speaks with them from the
     * next step on. Voices that are not installed yet are left out silently; the step stays skippable.
     */
    private void saveVoicesOnLeaving() {
        if (!voicesReady() || installing()) {
            return;
        }
        setup.saveVoices(collect(), error -> {
            if (!closed && error != null) {
                fail(STEP_VOICE, "The voices could not be saved: " + error);
            }
        });
    }

    private void saveVoicesAndClose() {
        if (!voicesReady()) { // the choice changed while the entries were saved: finish without the voices
            close();
            complete();
            return;
        }
        setBusy(true);
        setup.saveVoices(collect(), error -> {
            if (closed) {
                return;
            }
            setBusy(false);
            if (error != null) {
                fail(STEP_VOICE, "The voices could not be saved: " + error);
                return;
            }
            close();
            complete();
        });
    }

    private void close() {
        closed = true; // late results of running requests must not touch the wizard any more
        busy = false;
        setup.useNetwork(null); // from now on the saved network settings apply
        for (com.aresstack.askai.plugin.api.agent.setup.AgentSetupPage page : pluginPages) {
            try {
                page.dispose();
            } catch (RuntimeException ignored) {
                // closing anyway
            }
        }
    }

    /** Lets Ollama download the model typed or chosen in {@code field}; the lists reload afterwards. */
    private void pull(final ModelDownload download, final JComboBox<String> field) {
        final String name = text(field);
        if (name.isEmpty() || download.running) {
            return;
        }
        download.begin();
        setup.pullModel(collect(), name, download.progress(), (model, error) -> {
            if (closed) {
                return;
            }
            download.end(error == null ? model + " is installed." : "Download failed: " + error);
            if (error == null) {
                // Keep the name Ollama lists it under (e.g. with ":latest"); the refresh keeps the field's value.
                field.setSelectedItem(model);
                modelsLoaded = false;
                refreshModels();
            }
        });
    }

    /** Installs the recommended reranker into AskAI's local runtime and selects it. */
    private void installReranker() {
        installReranker(null);
    }

    private void installReranker(final Runnable after) {
        if (rerankerDownload.running) {
            return;
        }
        rerankerDownload.begin();
        setup.installReranker(collect(), rerankerDownload.progress(), (model, error) -> {
            if (closed) {
                return;
            }
            rerankerDownload.end(error == null ? "Reranker installed." : "Installation failed: " + error);
            if (after != null) {
                if (error == null) {
                    installedRerankers = append(installedRerankers, model);
                } else {
                    fixFailures.append("Reranker: ").append(error).append('\n');
                }
            }
            if (error == null) {
                if (((javax.swing.DefaultComboBoxModel<String>) rerankerModel.getModel()).getIndexOf(model) < 0) {
                    rerankerModel.addItem(model);
                }
                rerankerModel.setSelectedItem(model);
                modelsLoaded = false;
                refreshModels();
            }
            if (after != null) {
                after.run();
            }
        });
    }

    // ------------------------------------------------------------------ dictation

    /** Reads the microphones and the audio-capable models the first time the step opens. */
    private void loadDictation() {
        dictationLoaded = true;
        String chosen = draft.microphone();
        microphone.removeAllItems();
        microphone.addItem(MIC_DEFAULT);
        for (String device : setup.microphones()) {
            microphone.addItem(device);
        }
        // A saved device that is gone falls back to the system default, as in the chat settings.
        microphone.setSelectedItem(((javax.swing.DefaultComboBoxModel<String>) microphone.getModel())
                .getIndexOf(chosen) >= 0 ? chosen : MIC_DEFAULT);
        loadAudioModels(draft.audioModel());
    }

    private void loadAudioModels(final String select) {
        audioLoading = true;
        audioStatus.setText("Looking for models that understand audio …");
        updateButtons();
        setup.loadAudioModels(collect(), models -> {
            if (closed) {
                return;
            }
            audioLoading = false;
            audioListed = true;
            installedAudio = models == null ? Collections.<String>emptyList() : models;
            audioModel.removeAllItems();
            audioModel.addItem(AUDIO_AUTOMATIC);
            for (String name : installedAudio) {
                audioModel.addItem(name);
            }
            if (!select.isEmpty() && !installedAudio.contains(select)) {
                audioModel.addItem(select); // chosen before but not (or no longer) there: the check says so
            }
            audioModel.setSelectedItem(select.isEmpty() ? AUDIO_AUTOMATIC : select);
            audioStatus.setText(installedAudio.isEmpty()
                    ? "No model that understands audio yet – “Download audio model” fetches "
                    + SetupActions.RECOMMENDED_AUDIO_MODEL + "."
                    : installedAudio.size() == 1 ? "1 audio model installed."
                    : installedAudio.size() + " audio models installed.");
            updateButtons();
        });
    }

    /** Listens to the chosen microphone for a few seconds and shows its level; nothing is recorded. */
    private void testMicrophone() {
        if (micTesting) {
            return;
        }
        micTesting = true;
        micLevel.setValue(0);
        micLevel.setVisible(true);
        micStatus.setText("Say something …");
        updateButtons();
        setup.testMicrophone(choice(microphone, MIC_DEFAULT), level -> {
            if (!closed) {
                micLevel.setValue(level);
            }
        }, (heard, error) -> {
            if (closed) {
                return;
            }
            micTesting = false;
            micLevel.setVisible(false);
            micStatus.setText(error != null ? shorten("Microphone test failed: " + error)
                    : Boolean.TRUE.equals(heard) ? "The microphone works – I can hear you."
                    : "No signal – check the microphone or pick another one.");
            updateButtons();
        });
    }

    private void pullAudioModel() {
        if (audioDownload.running) {
            return;
        }
        audioDownload.begin();
        setup.pullModel(collect(), SetupActions.RECOMMENDED_AUDIO_MODEL, audioDownload.progress(), (model, error) -> {
            if (closed) {
                return;
            }
            audioDownload.end(error == null ? model + " is installed." : "Download failed: " + error);
            if (error == null) {
                loadAudioModels(model);
            }
        });
    }

    /** The combo's value; empty for its first, “nothing specific” entry. */
    private static String choice(JComboBox<String> combo, String none) {
        Object selected = combo.getSelectedItem();
        String value = selected == null ? "" : String.valueOf(selected).trim();
        return none.equals(value) ? "" : value;
    }

    private final StringBuilder fixFailures = new StringBuilder();

    /** The local models the last check found missing: reranker and/or sentence models. */
    private List<String> missingLocalModels() {
        List<String> missing = new ArrayList<String>();
        for (SetupCheck check : lastChecks) {
            if (!check.isOk() && (check.label().equals("Reranker") || check.label().startsWith("Sentence model"))) {
                missing.add(check.label());
            }
        }
        return missing;
    }

    /**
     * Installs on the check page what the last check found missing (the reranker into AskAI's local runtime, the
     * sentence models into the model folder), then checks again – the user never has to go back for it.
     */
    private void installMissingModels() {
        List<String> missing = missingLocalModels();
        if (missing.isEmpty() || pendingFixes > 0) {
            return;
        }
        boolean reranker = missing.contains("Reranker");
        boolean sentences = missing.size() > (reranker ? 1 : 0);
        fixFailures.setLength(0);
        clearError(STEP_CHECK);
        pendingFixes = (reranker ? 1 : 0) + (sentences ? 1 : 0);
        checkStatus.setText(reranker && sentences ? "Installing the reranker and the sentence models …"
                : reranker ? "Installing the reranker …" : "Installing the sentence models …");
        Runnable oneDone = () -> {
            pendingFixes--;
            if (pendingFixes > 0 || closed) {
                updateButtons();
                return;
            }
            if (fixFailures.length() > 0) {
                checkStatus.setText("Not everything could be installed.");
                fail(STEP_CHECK, fixFailures.toString().trim());
                updateButtons();
            } else {
                runFinalCheck();
            }
        };
        if (reranker) {
            installReranker(oneDone);
        }
        if (sentences) {
            installSentenceModels(oneDone);
        }
        updateButtons();
    }

    private boolean sentenceModelsMissing() {
        return installedSentenceGerman.isEmpty() || installedSentenceEnglish.isEmpty()
                || (!text(sentenceGerman).isEmpty() && !installedSentenceGerman.contains(text(sentenceGerman)))
                || (!text(sentenceEnglish).isEmpty() && !installedSentenceEnglish.contains(text(sentenceEnglish)));
    }

    private static List<String> append(List<String> list, String value) {
        List<String> copy = new ArrayList<String>(list);
        copy.add(value);
        return copy;
    }

    private boolean installing() {
        return sentenceInstalling || voiceInstalling;
    }

    // ------------------------------------------------------------------ draft

    private void load(SetupDraft initial) {
        setProxy(initial);
        trustWindows.setSelected(initial.trustWindowsStores());
        ollamaUrl.setText(initial.ollamaBaseUrl());
        boolean wasBusy = busy;
        busy = true;
        try {
            setChoices(chatModel, Collections.<String>emptyList(), initial.chatModel(), false);
            setChoices(embeddingModel, Collections.<String>emptyList(), initial.embeddingModel(), false);
            setChoices(rerankerModel, Collections.<String>emptyList(), initial.rerankerModel(), true);
            setChoices(sentenceGerman, Collections.<String>emptyList(), initial.nlpSentenceGerman(), true);
            setChoices(sentenceEnglish, Collections.<String>emptyList(), initial.nlpSentenceEnglish(), true);
        } finally {
            busy = wasBusy;
        }
        networkStatus.setText(" ");
        ollamaStatus.setText(" ");
    }

    private SetupDraft collect() {
        SetupDraft.Builder b = (draft == null ? SetupDraft.builder() : draft.toBuilder())
                .ollamaBaseUrl(ollamaUrl.getText())
                .proxy(selectedProxyMode(), proxyHost.getText(), number(proxyPort.getText()))
                .trustWindowsStores(trustWindows.isSelected())
                .chatModel(text(chatModel))
                .embeddingModel(text(embeddingModel))
                .rerankerModel(text(rerankerModel))
                .nlpSentence(text(sentenceGerman), text(sentenceEnglish));
        if (voicesLoaded && !voiceByLabel.isEmpty()) {
            b.voices(voiceId(voiceGerman), voiceId(voiceEnglish));
        }
        if (dictationLoaded) {
            b.dictation(choice(microphone, MIC_DEFAULT),
                    audioListed ? choice(audioModel, AUDIO_AUTOMATIC) : (draft == null ? "" : draft.audioModel()));
        }
        return b.build();
    }

    /** What the final check covers: everything but the voices. */
    private static String checkedKey(SetupDraft value) {
        return value.toBuilder().voices("", "").build().toString();
    }

    private void setBusy(boolean value) {
        busy = value;
        updateButtons();
    }

    // ------------------------------------------------------------------ helpers

    private static int number(String text) {
        try {
            return Integer.parseInt(text == null ? "" : text.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static boolean positive(String text) {
        return number(text) > 0;
    }

    private static String text(JComboBox<String> combo) {
        Object value = combo.isEditable() ? combo.getEditor().getItem() : combo.getSelectedItem();
        if (combo.isEditable() && (value == null || value.toString().trim().isEmpty())) {
            value = combo.getSelectedItem();
        }
        return value == null ? "" : value.toString().trim();
    }

    /** Rebuilds a choice: (optionally) “none” first, then the available names, plus a stored one not available now. */
    private static void setChoices(JComboBox<String> combo, List<String> items, String selected, boolean withNone) {
        combo.removeAllItems();
        if (withNone) {
            combo.addItem(NONE);
        }
        for (String item : items) {
            if (((javax.swing.DefaultComboBoxModel<String>) combo.getModel()).getIndexOf(item) < 0) {
                combo.addItem(item);
            }
        }
        String value = selected == null ? "" : selected.trim();
        if (!value.isEmpty() && ((javax.swing.DefaultComboBoxModel<String>) combo.getModel()).getIndexOf(value) < 0) {
            combo.addItem(value); // never lose a stored choice silently
        }
        combo.setSelectedItem(value.isEmpty() ? (withNone ? NONE : null) : value);
        if (combo.isEditable()) {
            combo.getEditor().setItem(value);
        }
    }

    private void state(JLabel label, boolean ok, String text) {
        label.setText(text);
        label.setIcon(ok ? new CheckGlyph() : new CrossGlyph());
        label.setForeground(ok ? SetupStepTrail.DONE : ResearchUiPalette.LIGHT_CONTROL_TEXT);
    }

    private static String shorten(String text) {
        String value = text == null ? "" : text.replace('\n', ' ').trim();
        return value.length() > 140 ? value.substring(0, 137) + "…" : value;
    }

    private JLabel statusLabel() {
        JLabel label = new JLabel(" ");
        label.setForeground(ResearchUiPalette.LIGHT_CONTROL_TEXT);
        label.setFont(ResearchUiTypography.regular(12f));
        return label;
    }

    private JLabel fieldLabel(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(palette.getInk());
        label.setFont(ResearchUiTypography.semiBold(12.5f));
        return label;
    }

    private static JPanel transparent(LayoutManager layout) {
        JPanel panel = new JPanel(layout);
        panel.setOpaque(false);
        return panel;
    }

    private static JPanel buttonRow(JComponent button) {
        JPanel row = transparent(new FlowLayout(FlowLayout.LEFT, 0, 0));
        row.add(button);
        return row;
    }

    // ------------------------------------------------------------------ test access

    SetupStepTrail trail() {
        return trail;
    }

    /** Witch of a step (in step order). */
    SetupWizardMascot mascotOf(int index) {
        return mascots[index];
    }

    static String mascotResource(int index) {
        return STEP_MASCOTS[index];
    }

    JButton nextButton() {
        return nextButton;
    }

    JButton backButton() {
        return backButton;
    }

    JButton skipButton() {
        return skipButton;
    }

    JButton finishButton() {
        return finishButton;
    }

    JButton runtimeTestButton() {
        return runtimeTest;
    }

    JButton networkTestButton() {
        return networkTest;
    }

    JComboBox<String> microphoneChoice() {
        return microphone;
    }

    JComboBox<String> audioModelChoice() {
        return audioModel;
    }

    JButton micTestButton() {
        return micTest;
    }

    JLabel micStatusLabel() {
        return micStatus;
    }

    JButton audioDownloadButton() {
        return audioDownload.button;
    }

    JButton checkInstallMissingButton() {
        return checkInstallMissing;
    }

    JButton sentenceInstallButton() {
        return sentenceInstall;
    }

    JCheckBox trustWindowsBox() {
        return trustWindows;
    }

    JComboBox<String> proxyModeChoice() {
        return proxyMode;
    }

    JTextField ollamaUrlField() {
        return ollamaUrl;
    }

    JComboBox<String> chatModelChoice() {
        return chatModel;
    }

    JComboBox<String> embeddingModelChoice() {
        return embeddingModel;
    }

    JComboBox<String> rerankerModelChoice() {
        return rerankerModel;
    }

    ComicButton chatDownloadButton() {
        return chatDownload.button;
    }

    ComicButton rerankerInstallButton() {
        return rerankerDownload.button;
    }

    ComicButton voiceGermanButton() {
        return voiceGermanAction;
    }

    ComicButton voiceEnglishButton() {
        return voiceEnglishAction;
    }

    JButton chatRecommendedButton() {
        return chatRecommended;
    }

    JButton chatAlternativeButton() {
        return chatAlternative;
    }

    JButton chatInstallButton() {
        return chatInstall;
    }

    JButton audioInstallButton() {
        return audioInstall;
    }

    JComboBox<String> voiceEnglishChoice() {
        return voiceEnglish;
    }

    JComboBox<String> voiceGermanChoice() {
        return voiceGerman;
    }

    JTextField embeddingDimensionField() {
        return embeddingDimension;
    }

    String ollamaStatusText() {
        return ollamaStatus.getText();
    }

    String checkStatusText() {
        return checkStatus.getText();
    }

    boolean manualProxyShown() {
        return proxyHost.isVisible();
    }

    // ------------------------------------------------------------------ helper classes

    /** Rows of a step: heading, note, then label and field. */
    /** A model download with its own button, progress bar and status line; never blocks the navigation. */
    /**
     * A field with its actions on the same line: the field takes the width, the buttons sit to its right (as
     * “Measure” next to the embedding model).
     */
    private static JPanel inline(JComponent field, JComponent... actions) {
        JPanel line = transparent(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        line.add(field, c);
        for (int i = 0; i < actions.length; i++) {
            c = new GridBagConstraints();
            c.gridx = i + 1;
            c.insets = new Insets(0, 8, 0, 0);
            line.add(actions[i], c);
        }
        return line;
    }

    /**
     * A progress bar (optional) and a message under a field. Takes no room while both are empty, so a step
     * shows no gaps between its fields.
     */
    private static final class StatusLine {
        final JPanel panel = transparent(new GridBagLayout());
        private final JComponent bar;
        private final JLabel status;

        StatusLine(JComponent bar, JLabel status) {
            this.bar = bar;
            this.status = status;
            GridBagConstraints c = new GridBagConstraints();
            c.gridx = 0;
            c.gridy = 0;
            c.anchor = GridBagConstraints.WEST;
            c.fill = GridBagConstraints.HORIZONTAL;
            c.weightx = 1;
            if (bar != null) {
                panel.add(bar, c);
                c = (GridBagConstraints) c.clone();
                c.gridy = 1;
            }
            panel.add(status, c);
            status.addPropertyChangeListener("text", e -> sync());
            if (bar != null) {
                bar.addComponentListener(new java.awt.event.ComponentAdapter() {
                    @Override
                    public void componentShown(java.awt.event.ComponentEvent e) {
                        sync();
                    }

                    @Override
                    public void componentHidden(java.awt.event.ComponentEvent e) {
                        sync();
                    }
                });
            }
            sync();
        }

        private void sync() {
            String text = status.getText();
            boolean message = text != null && !text.trim().isEmpty();
            status.setVisible(message);
            panel.setVisible(message || (bar != null && bar.isVisible()));
            panel.revalidate();
        }
    }

    private final class ModelDownload {
        final ComicButton button;
        final JProgressBar bar = ComicControls.progressBar(0, 1000);
        final JLabel status = statusLabel();
        /** Progress bar and message below the field; hidden while there is nothing to show. */
        final StatusLine line;
        boolean running;

        ModelDownload(String label) {
            button = new ComicButton(label, ComicButton.Accent.ACTION);
            bar.setStringPainted(true);
            bar.setVisible(false);
            line = new StatusLine(bar, status);
        }

        SetupActions.Progress progress() {
            return (stage, done, total) -> {
                if (closed) {
                    return;
                }
                status.setText(shorten(stage == null ? "" : stage));
                if (total > 0) {
                    bar.setIndeterminate(false);
                    bar.setValue((int) Math.min(1000, 1000 * done / total));
                    bar.setString((100 * done / total) + " %");
                }
            };
        }

        void begin() {
            running = true;
            bar.setIndeterminate(true);
            bar.setString("Connecting …");
            bar.setVisible(true);
            status.setText("Connecting …");
            updateButtons();
        }

        void end(String message) {
            running = false;
            bar.setVisible(false);
            status.setText(message == null ? " " : shorten(message));
            updateButtons();
        }
    }

    private final class Rows {
        private final JPanel panel = transparent(new GridBagLayout());
        private final int step;
        private int row;

        Rows(int step, String heading) {
            this.step = step;
            panel.setBorder(BorderFactory.createEmptyBorder(6, 4, 6, 4));
            JLabel title = new JLabel(heading);
            title.setFont(ResearchUiTypography.semiBold(15f));
            title.setForeground(palette.getInk());
            GridBagConstraints c = constraints(0);
            c.gridwidth = 2;
            c.insets = new Insets(0, 0, 6, 0);
            panel.add(title, c);
            row++;
        }

        /** A full-width block (e.g. a plugin's setup section). */
        void block(JComponent component) {
            GridBagConstraints c = constraints(0);
            c.gridwidth = 2;
            c.fill = GridBagConstraints.HORIZONTAL;
            c.weightx = 1;
            c.insets = new Insets(2, 0, 4, 0);
            panel.add(component, c);
            row++;
        }

        /** A plugin's section: its name in the label column, its content flush with the fields above. */
        void section(String label, JComponent component) {
            GridBagConstraints c = constraints(0);
            c.anchor = GridBagConstraints.NORTHWEST;
            c.insets = new Insets(12, 0, 3, 10);
            panel.add(fieldLabel(label), c);
            c = constraints(1);
            c.fill = GridBagConstraints.HORIZONTAL;
            c.weightx = 1;
            c.insets = new Insets(10, 0, 3, 0);
            panel.add(component, c);
            row++;
        }

        /** A second section in the same step, e.g. the voice below the network settings. */
        void heading(String text) {
            JLabel title = new JLabel(text);
            title.setFont(ResearchUiTypography.semiBold(15f));
            title.setForeground(palette.getInk());
            GridBagConstraints c = constraints(0);
            c.gridwidth = 2;
            c.insets = new Insets(14, 0, 6, 0);
            panel.add(title, c);
            row++;
        }

        void note(String text) {
            JTextArea note = ComicControls.textArea(text);
            note.setEditable(false);
            note.setFocusable(false);
            note.setLineWrap(true);
            note.setWrapStyleWord(true);
            note.setOpaque(false);
            note.setBorder(BorderFactory.createEmptyBorder());
            note.setForeground(ResearchUiPalette.LIGHT_CONTROL_TEXT);
            note.setFont(ResearchUiTypography.regular(12f));
            GridBagConstraints c = constraints(0);
            c.gridwidth = 2;
            c.fill = GridBagConstraints.HORIZONTAL;
            c.weightx = 1;
            c.insets = new Insets(0, 0, 8, 0);
            panel.add(note, c);
            row++;
        }

        /** Label (if any) and field, so a step can show and hide them together. */
        List<JComponent> field(String label, JComponent component) {
            List<JComponent> parts = new ArrayList<JComponent>();
            parts.add(component);
            GridBagConstraints c = constraints(0);
            c.insets = new Insets(3, 0, 3, 10);
            if (!label.isEmpty()) {
                JLabel caption = fieldLabel(label);
                parts.add(caption);
                panel.add(caption, c);
            }
            c = constraints(1);
            c.insets = new Insets(3, 0, 3, 0);
            c.fill = component instanceof JTextField || component instanceof JComboBox
                    ? GridBagConstraints.HORIZONTAL : GridBagConstraints.NONE;
            c.weightx = 1;
            panel.add(component, c);
            row++;
            return parts;
        }

        /** Like {@link #field}, but the component fills the field column, flush with the fields above it. */
        void stretched(String label, JComponent component) {
            field(label, component);
            GridBagLayout layout = (GridBagLayout) panel.getLayout();
            GridBagConstraints c = layout.getConstraints(component);
            c.fill = GridBagConstraints.HORIZONTAL;
            layout.setConstraints(component, c);
        }

        void wide(JComponent component) {
            GridBagConstraints c = constraints(0);
            c.gridwidth = 2;
            c.fill = GridBagConstraints.HORIZONTAL;
            c.weightx = 1;
            c.insets = new Insets(3, 0, 3, 0);
            panel.add(component, c);
            row++;
        }

        private GridBagConstraints constraints(int column) {
            GridBagConstraints c = new GridBagConstraints();
            c.gridx = column;
            c.gridy = row;
            c.anchor = GridBagConstraints.WEST;
            return c;
        }

        JPanel panel() {
            JPanel page = transparent(new BorderLayout());
            page.add(panel, BorderLayout.NORTH);
            int index = step;
            SetupWizardMascot.Art stepArt = SetupWizardMascot.Art.load(STEP_MASCOTS[index]);
            final SetupWizardMascot mascot = new SetupWizardMascot(stepArt != null ? stepArt
                    : SetupWizardMascot.Art.load(), MASCOT_HEIGHTS[index], MASCOT_PLACEMENTS[index],
                    SetupWizardMascot.DEFAULT_GAP, SetupWizardMascot.DEFAULT_SIDE_INSET, palette);
            mascot.setOnFigureClicked(point -> witchMenu().show(mascot, point.x, point.y));
            mascots[index] = mascot;
            page.add(mascot, BorderLayout.CENTER);
            // Wrapping texts know their height only once they know their width: lay out once more when the
            // width changes, so the form takes exactly its room and the Witch gets the rest.
            page.addComponentListener(new java.awt.event.ComponentAdapter() {
                private int width = -1;

                @Override
                public void componentResized(java.awt.event.ComponentEvent e) {
                    if (page.getWidth() != width) {
                        width = page.getWidth();
                        javax.swing.SwingUtilities.invokeLater(page::revalidate);
                    }
                }
            });
            return page;
        }
    }

    /**
     * Error text that wraps at the wizard's width even before its first layout. A wrapping {@link JTextArea}
     * without a width of its own otherwise reports one word per line as its preferred height; the error plate would
     * take the whole page on a step's first error and push away fields and Witch.
     */
    private final class WrappedErrorText extends JTextArea {
        private static final long serialVersionUID = 1L;

        @Override
        public Dimension getPreferredSize() {
            int width = getWidth() > 0 ? getWidth() : expectedWidth();
            if (width <= 0 || !getLineWrap()) {
                return super.getPreferredSize();
            }
            Insets own = getInsets();
            View root = getUI().getRootView(this);
            root.setSize(Math.max(1, width - own.left - own.right), Integer.MAX_VALUE);
            int height = (int) Math.ceil(root.getPreferredSpan(View.Y_AXIS)) + own.top + own.bottom;
            return new Dimension(width, height);
        }

        private int expectedWidth() {
            Insets wizard = SetupWizardPanel.this.getInsets();
            Insets plate = errorPlate.getInsets();
            int width = SetupWizardPanel.this.getWidth() - wizard.left - wizard.right - plate.left - plate.right;
            BorderLayout layout = (BorderLayout) errorPlate.getLayout();
            Component east = layout.getLayoutComponent(BorderLayout.EAST);
            if (east != null && east.isVisible()) {
                width -= east.getPreferredSize().width + layout.getHgap();
            }
            return width;
        }
    }

    /** Tick of the final check (Java2D, no font glyph). */
    private static final class CheckGlyph extends StrokeIcon {
        @Override
        protected void paint(Graphics2D g2) {
            g2.drawPolyline(new int[] {2, 6, 13}, new int[] {8, 12, 3}, 3);
        }
    }

    /** Cross of the final check (Java2D, no font glyph). */
    private static final class CrossGlyph extends StrokeIcon {
        @Override
        protected void paint(Graphics2D g2) {
            g2.drawLine(3, 3, 12, 12);
            g2.drawLine(12, 3, 3, 12);
        }
    }

    /** A DocumentListener that runs the same action on every change. */
    private static final class SimpleDocumentListener implements javax.swing.event.DocumentListener {
        private final Runnable action;

        SimpleDocumentListener(Runnable action) {
            this.action = action;
        }

        @Override
        public void insertUpdate(javax.swing.event.DocumentEvent e) {
            action.run();
        }

        @Override
        public void removeUpdate(javax.swing.event.DocumentEvent e) {
            action.run();
        }

        @Override
        public void changedUpdate(javax.swing.event.DocumentEvent e) {
            action.run();
        }
    }
}
