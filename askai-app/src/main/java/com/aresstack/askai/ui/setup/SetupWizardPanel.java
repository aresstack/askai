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
 * “Skip”, “Cancel”, at the end “Finish”. Merlin stands in the free space of every step, talks in a speech bubble
 * (and with a voice, if one is installed) and answers questions when clicked. The wizard only uses
 * {@link SetupActions}; property keys and services appear nowhere. Everything on the EDT.
 *
 * <p>Taken over from the Enterprise AI Client's setup wizard with its concept unchanged – step trail, Merlin with
 * speech bubbles and menu, error plate, skippable steps, final check before “Finish”, hand-over to the chat
 * assistant after the first setup, later calls from the settings. Only the steps are AskAI's: KeePass became
 * network &amp; security, the AI service became the AI runtime (Ollama or AskAI's local runtime), the model step
 * is split into the chat model and the knowledge roles (embeddings, reranker, OpenNLP sentence models), Confluence
 * is gone, and the last step installs a read-aloud voice instead of dictation resources.</p>
 */
public final class SetupWizardPanel extends JPanel {

    private static final long serialVersionUID = 1L;

    static final String[] STEP_NAMES = {"Network", "Voice", "AI runtime", "Chat model", "Knowledge", "Check"};
    static final int STEP_NETWORK = 0;
    /** Right after the network: the voices must be downloaded first, then Merlin can speak in every later step. */
    static final int STEP_VOICE = 1;
    static final int STEP_RUNTIME = 2;
    static final int STEP_CHAT = 3;
    static final int STEP_KNOWLEDGE = 4;
    /** The last step: saves and checks everything; “Finish” ends the setup here. */
    static final int STEP_CHECK = 5;

    /**
     * Merlin's picture per step: cut-outs with a transparent background (copies of the originals in
     * docs/images/setup-wizard).
     */
    private static final String[] STEP_MASCOTS = {"wizard/merlin-1-network.png", "wizard/merlin-6-voice.png",
            "wizard/merlin-2-ai-runtime.png", "wizard/merlin-3-chat-model.png", "wizard/merlin-4-knowledge.png",
            "wizard/merlin-5-check.png"};
    /** The runtime step shows this picture until a runtime answers (the same cut-out as once it answers). */
    static final String RUNTIME_INSTALL_MASCOT = "wizard/merlin-2-ai-runtime.png";
    /** Per step: Merlin's place in the free space (alternating, so it feels alive). */
    private static final SetupWizardMascot.Placement[] MASCOT_PLACEMENTS = {SetupWizardMascot.Placement.RIGHT,
            SetupWizardMascot.Placement.LEFT, SetupWizardMascot.Placement.RIGHT, SetupWizardMascot.Placement.LEFT,
            SetupWizardMascot.Placement.RIGHT, SetupWizardMascot.Placement.CENTER};
    /**
     * Per step: Merlin's largest height in logical pixels. Larger than any free space on purpose: Merlin grows to
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
    private final List<SetupWizardMascot> mascots = new ArrayList<SetupWizardMascot>();

    private SetupDraft draft;
    private int step;
    private Runnable onFinished;
    private Runnable onCancel;
    private boolean busy;
    private boolean closed;

    // Merlin: fixed lines (WizardGuide), bubble or voice (WizardNarrator)
    private final WizardGuide guide = new WizardGuide();
    private final WizardNarrator narrator = new WizardNarrator(text -> mascotOf(step).say(text));
    /** Merlin only talks once a runtime answers (or from the start when called again from the settings). */
    private boolean merlinAwake;
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
    private boolean runtimeChecking;
    private int runtimeGeneration;

    // Step 3: chat model
    private final JComboBox<String> chatModel = ComicControls.comboBox();
    private final ComicButton modelsRefresh = new ComicButton("Reload models");
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

    // Step 5: final check
    private final JPanel checkRows = transparent(new GridBagLayout());
    private final JLabel checkStatus = statusLabel();
    private final ComicButton checkRetry = new ComicButton("Check again");
    private List<SetupCheck> lastChecks = Collections.emptyList();
    /** The draft the last passed final check saved (without the voices, which the last step saves itself). */
    private String checkedDraft = "";
    static final String CHECK_OUTDATED = "Settings changed since the check – open “Check” to save and check again.";

    // Step 6: voice (optional)
    private final JComboBox<String> voiceGerman = ComicControls.comboBox();
    private final JComboBox<String> voiceEnglish = ComicControls.comboBox();
    private final ComicButton voiceInstall = new ComicButton("Download selected voices", ComicButton.Accent.ACTION);
    private final ComicButton voiceRetry = new ComicButton("Reload voices");
    private final JLabel voiceStatus = statusLabel();
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
        deck.add(networkStep(), STEP_NAMES[STEP_NETWORK]);
        deck.add(runtimeStep(), STEP_NAMES[STEP_RUNTIME]);
        deck.add(chatStep(), STEP_NAMES[STEP_CHAT]);
        deck.add(knowledgeStep(), STEP_NAMES[STEP_KNOWLEDGE]);
        deck.add(checkStep(), STEP_NAMES[STEP_CHECK]);
        deck.add(voiceStep(), STEP_NAMES[STEP_VOICE]);
        runtimeConnectedArt = mascotOf(STEP_RUNTIME).art();
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

    private JPanel networkStep() {
        Rows rows = new Rows("Network and security");
        rows.note("AskAI downloads models and voices and searches the web. Choose how it reaches the internet; in "
                + "company networks AskAI can also trust the certificates of the Windows certificate stores. "
                + "Working offline? Skip this step.");
        rows.field("Proxy", proxyMode);
        manualProxyParts.addAll(rows.field("Proxy host", proxyHost));
        manualProxyParts.addAll(rows.field("Proxy port", proxyPort));
        rows.field("", trustWindows);
        JPanel actions = transparent(new FlowLayout(FlowLayout.LEFT, 8, 0));
        actions.add(networkTest);
        actions.add(networkAdvanced);
        rows.field("", actions);
        rows.field("", networkStatus);
        return rows.panel();
    }

    private JPanel runtimeStep() {
        Rows rows = new Rows("AI runtime");
        rows.note("AskAI runs its models in Ollama – on this computer or on a server – and in its own local model "
                + "runtime. One of them must answer; the final check shows which one does.");
        rows.field("Ollama address", ollamaUrl);
        JPanel actions = transparent(new FlowLayout(FlowLayout.LEFT, 8, 0));
        actions.add(runtimeTest);
        actions.add(runtimeDownload);
        rows.field("", actions);
        rows.field("Ollama", ollamaStatus);
        return rows.panel();
    }

    private JPanel chatStep() {
        Rows rows = new Rows("Chat model");
        rows.note("The chat model answers in the chat and is the main model of all plugins (research agent, "
                + "summaries, tools). The list shows the models installed in Ollama and in AskAI's local runtime.");
        chatModel.setEditable(true);
        rows.field("Chat model", chatModel);
        JPanel reload = transparent(new FlowLayout(FlowLayout.LEFT, 8, 0));
        reload.add(modelsRefresh);
        reload.add(modelsStatus);
        rows.field("", reload);
        return rows.panel();
    }

    private JPanel knowledgeStep() {
        Rows rows = new Rows("Knowledge and research");
        rows.note("The research agent finds matching passages with the embedding model, ranks them with the "
                + "optional reranker and splits sources into sentences with the OpenNLP sentence models. Without "
                + "them AskAI falls back to simpler methods.");
        embeddingModel.setEditable(true);
        embeddingDimension.setEditable(false);
        embeddingDimension.setToolTipText("Vector length, measured with a real embedding call");
        dimensionMeasure.setToolTipText("Measure the vector length of the embedding model again");
        JPanel embedding = transparent(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        embedding.add(embeddingModel, c);
        c = new GridBagConstraints();
        c.gridx = 1;
        c.insets = new Insets(0, 8, 0, 4);
        embedding.add(embeddingDimension, c);
        c = new GridBagConstraints();
        c.gridx = 2;
        embedding.add(dimensionMeasure, c);
        rows.stretched("Embedding model", embedding);
        rows.field("Reranker (optional)", rerankerModel);
        rows.field("Sentences (German)", sentenceGerman);
        rows.field("Sentences (English)", sentenceEnglish);
        JPanel actions = transparent(new FlowLayout(FlowLayout.LEFT, 8, 0));
        actions.add(sentenceInstall);
        actions.add(knowledgeStatus);
        rows.field("", actions);
        return rows.panel();
    }

    private JPanel checkStep() {
        Rows rows = new Rows("Final check");
        rows.note("The settings are saved and then checked for real. “Next” is only possible once AskAI is ready.");
        rows.wide(checkRows);
        rows.field("", checkStatus);
        rows.field("", buttonRow(checkRetry));
        return rows.panel();
    }

    private JPanel voiceStep() {
        Rows rows = new Rows("Voice");
        rows.note("Optional. AskAI can read answers aloud with a local Piper voice that runs on this computer; "
                + "without one it uses the Windows voice. Change it later in the chat settings under "
                + "Audio & Dictation.");
        rows.field("German", voiceGerman);
        rows.field("English", voiceEnglish);
        JPanel actions = transparent(new FlowLayout(FlowLayout.LEFT, 8, 0));
        actions.add(voiceInstall);
        actions.add(voiceRetry);
        rows.field("", actions);
        rows.field("", voiceStatus);
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
        checkRetry.addActionListener(e -> runFinalCheck());
        voiceGerman.addActionListener(e -> updateButtons());
        voiceEnglish.addActionListener(e -> updateButtons());
        voiceInstall.addActionListener(e -> installVoices());
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

    // ------------------------------------------------------------------ Merlin

    /**
     * A later call (from the settings): the runtime is set up already, Merlin is awake from the start and greets as
     * a reunion instead of introducing himself a second time.
     */
    public void welcomeBack() {
        wakeMerlin(true);
    }

    /** Merlin's voice; without one he only uses speech bubbles. */
    public void setVoice(WizardVoice voice) {
        narrator.setVoice(voice);
    }

    /** Ends Merlin's speech output (wizard closed). */
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

    boolean isMerlinAwake() {
        return merlinAwake;
    }

    /** A free question needs Merlin awake and a chat model. */
    boolean canAskMerlin() {
        return merlinAwake && !text(chatModel).isEmpty();
    }

    /** The menu on a click on Merlin: fixed answers, skip and (with a chat model) a free question. */
    JPopupMenu merlinMenu() {
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
        ask.setEnabled(canAskMerlin());
        ask.setToolTipText(canAskMerlin() ? null : "Possible once a runtime answers and a chat model is chosen");
        ask.addActionListener(e -> mascotOf(current).askQuestion(this::askMerlin));
        menu.add(ask);
        return menu;
    }

    /** The free question to the draft's chat model; Merlin's frame comes from {@link WizardGuide}. */
    void askMerlin(String question) {
        if (!canAskMerlin() || question == null || question.trim().isEmpty()) {
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

    private void wakeMerlin(boolean returning) {
        if (merlinAwake) {
            return;
        }
        merlinAwake = true;
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
     * Line {@code index} of the hand-over: Merlin with his voice, the chat assistant with hers and her name. Without
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
        mascotOf(step).say(line.merlin ? WizardGuide.NAME : voices.assistantName(), line.text);
        WizardVoice voice = line.merlin ? voices.merlin() : voices.assistant();
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
        if (step == STEP_VOICE && index != STEP_VOICE) {
            saveVoicesOnLeaving();
        }
        step = index;
        cards.show(deck, STEP_NAMES[index]);
        for (SetupWizardMascot mascot : mascots) {
            mascot.say(null);
        }
        if (merlinAwake) {
            narrator.say(guide.enteredStep(index));
        }
        if (index == STEP_RUNTIME && !done[STEP_RUNTIME] && !runtimeChecking && !busy) {
            checkRuntime(); // the default address usually works: check right away instead of waiting for a click
        } else if ((index == STEP_CHAT || index == STEP_KNOWLEDGE) && !modelsLoaded && !modelsLoading) {
            refreshModels();
        } else if (index == STEP_CHECK) {
            runFinalCheck();
        } else if (index == STEP_VOICE && !voicesLoaded) {
            loadVoices();
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
        done[STEP_VOICE] = voicesReady();
        trail.show(step, done);
        boolean voice = step == STEP_CHECK; // the last step: “Finish” instead of “Next”
        backButton.setEnabled(step > 0 && !busy && !installing());
        nextButton.setVisible(!voice);
        finishButton.setVisible(voice);
        // Every step can be passed over: a missing or failed check is only a hint, never a lock.
        nextButton.setEnabled(!voice && !busy && !installing());
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
        dimensionMeasure.setEnabled(!busy && !text(embeddingModel).isEmpty());
        sentenceInstall.setEnabled(!busy && !installing());
        voiceInstall.setVisible(!missingVoices().isEmpty());
        voiceInstall.setEnabled(!busy && !installing());
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
        if (merlinAwake && index == step) {
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

    /** The runtime answers: Merlin wakes up (or reports), and the model lists are prepared in the background. */
    private void runtimeAnswered() {
        if (!merlinAwake) {
            wakeMerlin(false);
        } else if (step == STEP_RUNTIME) {
            narrator.say(WizardGuide.RUNTIME_OK);
        }
        modelsLoaded = false;
        refreshModels();
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

    private void applyModels(SetupActions.SetupModels models) {
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
        modelsStatus.setText(!models.problem().isEmpty() ? shorten(models.problem())
                : count == 0 ? "No chat model installed yet – install one under Models."
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
                return;
            }
            knowledgeStatus.setText(message == null || message.isEmpty() ? "Sentence models installed." : message);
            if (merlinAwake && step == STEP_KNOWLEDGE) {
                narrator.say(WizardGuide.SENTENCE_MODELS_OK);
            }
            modelsLoaded = false;
            refreshModels();
        });
    }

    // --- step 5: final check

    private void runFinalCheck() {
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

    private void installVoices() {
        final List<SetupActions.VoiceOffer> queue = missingVoices();
        if (queue.isEmpty()) {
            return;
        }
        voiceInstalling = true;
        clearError(STEP_VOICE);
        updateButtons();
        installVoice(queue, 0);
    }

    private void installVoice(final List<SetupActions.VoiceOffer> queue, final int index) {
        if (closed) {
            return;
        }
        if (index >= queue.size()) {
            voiceInstalling = false;
            voiceStatus.setText("Voices installed.");
            loadVoices();
            return;
        }
        final SetupActions.VoiceOffer offer = queue.get(index);
        voiceStatus.setText("Installing " + offer.label() + " …");
        setup.installVoice(offer.id(), line -> {
            if (!closed) {
                voiceStatus.setText(offer.label() + ": " + shorten(line));
            }
        }, (message, error) -> {
            if (closed) {
                return;
            }
            if (error != null) {
                voiceInstalling = false;
                voiceStatus.setText(" ");
                fail(STEP_VOICE, offer.label() + " could not be installed: " + error);
                loadVoices();
                return;
            }
            installVoice(queue, index + 1);
        });
    }

    private void finish() {
        if (busy || installing() || handingOff) {
            return;
        }
        if (!done[STEP_VOICE]) { // no voice ready: finish anyway, the voices stay as they are
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
     * Leaving the voice step stores the chosen, installed voices right away, so Merlin speaks with them from the
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

    /** Merlin of a step (in step order). */
    SetupWizardMascot mascotOf(int index) {
        return mascots.get(index);
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
    private final class Rows {
        private final JPanel panel = transparent(new GridBagLayout());
        private int row;

        Rows(String heading) {
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
            int index = mascots.size();
            SetupWizardMascot.Art stepArt = SetupWizardMascot.Art.load(STEP_MASCOTS[index]);
            final SetupWizardMascot mascot = new SetupWizardMascot(stepArt != null ? stepArt
                    : SetupWizardMascot.Art.load(), MASCOT_HEIGHTS[index], MASCOT_PLACEMENTS[index],
                    SetupWizardMascot.DEFAULT_GAP, SetupWizardMascot.DEFAULT_SIDE_INSET, palette);
            mascot.setOnFigureClicked(point -> merlinMenu().show(mascot, point.x, point.y));
            mascots.add(mascot);
            page.add(mascot, BorderLayout.CENTER);
            return page;
        }
    }

    /**
     * Error text that wraps at the wizard's width even before its first layout. A wrapping {@link JTextArea}
     * without a width of its own otherwise reports one word per line as its preferred height; the error plate would
     * take the whole page on a step's first error and push away fields and Merlin.
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
