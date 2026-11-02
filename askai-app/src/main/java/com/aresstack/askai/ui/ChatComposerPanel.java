package com.aresstack.askai.ui;

import com.aresstack.comiccontrols.control.ComicControls;
import com.aresstack.comiccontrols.theme.ResearchUiPainter;
import com.aresstack.comiccontrols.theme.ResearchUiPalette;
import com.aresstack.comiccontrols.theme.ThemeColor;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import javax.swing.UIManager;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;

/**
 * Render the complete chat input as one reusable, state-aware Swing component.
 *
 * <p>Follow the MainframeMate search-bar principle: paint one rounded container, keep the editor
 * borderless, place secondary actions inside the control, and switch contextual actions instead of
 * stacking unrelated buttons beside the text field.</p>
 */
public final class ChatComposerPanel extends JPanel {

    private static final int ARC = 18;
    private static final int MIN_EDITOR_HEIGHT = 62;
    private static final Color BORDER_NORMAL = ThemeColor.of("chat.composerBorder");
    // The accent/danger tokens are single-sourced in ResearchUiPalette so the phase selector and
    // the drawer controls use EXACTLY the navigation blue — never a second palette.
    private static final Color BORDER_FOCUSED =
            com.aresstack.comiccontrols.theme.ResearchUiPalette.ACCENT_BLUE;
    private static final Color BORDER_DICTATION = ThemeColor.of("chat.composerDictationAccent");
    private static final Color BACKGROUND_NORMAL = ThemeColor.of("chat.composerRestBackground");
    private static final Color BACKGROUND_FOCUSED = ThemeColor.of("comic.fieldBg");
    private static final Color BACKGROUND_DICTATION = ThemeColor.of("comic.warmFocus");
    private static final Color TEXT_MUTED = ThemeColor.of("chat.composerMutedText");
    private static final Color PLACEHOLDER = ThemeColor.of("chat.placeholderText");
    private static final Color PRIMARY =
            com.aresstack.comiccontrols.theme.ResearchUiPalette.ACCENT_BLUE;
    private static final Color DANGER =
            com.aresstack.comiccontrols.theme.ResearchUiPalette.DANGER_RED;
    private static final Color RECORDING = ThemeColor.of("chat.composerRecordingAccent");

    /** Route semantic user actions without exposing the internal buttons. */
    public interface Actions {
        void selectModel();

        void selectMode();

        void selectReasoning();

        void toggleNotificationsMute();

        void send();

        void stop();

        void toggleRecording();

        void discardDictation();

        void retryTranscription();

        void saveRecording();

        /** Play (or stop) the last recording through the configured playback output. */
        void playRecording();

        void installAudioModel();

        void transcribeAudioFile();

        void attachImages();
    }

    /** Carry the complete dictation presentation in one immutable value. */
    public static final class DictationView {
        private final String recordLabel;
        private final boolean recordEnabled;
        private final boolean recordingActive;
        private final boolean dictationWorking;
        private final boolean discardEnabled;
        private final boolean retryVisible;
        private final boolean saveVisible;
        private final boolean installVisible;
        private final boolean audioFileEnabled;
        private final boolean audioLevelVisible;

        public DictationView(String recordLabel, boolean recordEnabled, boolean recordingActive,
                             boolean dictationWorking, boolean discardEnabled, boolean retryVisible,
                             boolean saveVisible, boolean installVisible, boolean audioFileEnabled,
                             boolean audioLevelVisible) {
            this.recordLabel = recordLabel == null ? "Record" : recordLabel;
            this.recordEnabled = recordEnabled;
            this.recordingActive = recordingActive;
            this.dictationWorking = dictationWorking;
            this.discardEnabled = discardEnabled;
            this.retryVisible = retryVisible;
            this.saveVisible = saveVisible;
            this.installVisible = installVisible;
            this.audioFileEnabled = audioFileEnabled;
            this.audioLevelVisible = audioLevelVisible;
        }
    }

    private final Actions actions;
    private final PlaceholderTextArea editor;
    private final JScrollPane editorScroll;
    /** The ChatGPT-style recording sampler + Skype-style threshold line over the editor. */
    private final ComposerWaveformOverlay waveformOverlay = new ComposerWaveformOverlay();
    private final JPanel statusPanel;
    private final JLabel chatStatusLabel;
    private final JLabel dictationStatusLabel;
    private final JButton modelButton;
    private final JButton modeButton;
    private final JButton reasoningButton;
    private final JButton muteButton;
    private final JButton recordButton;
    private final JButton audioFileButton;
    private final JButton attachButton;
    private final ChatAttachmentStrip attachmentStrip;
    private final JButton discardButton;
    private final JButton retryButton;
    private final JButton saveButton;
    private final JButton playRecordingButton;
    private final JButton installButton;
    private final JButton sendButton;
    private final JButton stopButton;
    private final JProgressBar levelBar;

    private boolean editorFocused;
    private boolean chatBusy;
    private boolean dictationActive;

    public ChatComposerPanel(Actions actions) {
        if (actions == null) {
            throw new IllegalArgumentException("actions must not be null");
        }
        this.actions = actions;
        this.editor = new PlaceholderTextArea("Ask anything…");
        this.editorScroll = createEditorScroll(editor);
        this.chatStatusLabel = createStatusLabel();
        this.dictationStatusLabel = createStatusLabel();
        this.statusPanel = createStatusPanel();
        this.modelButton = createModelButton();
        this.modeButton = createModeButton();
        this.reasoningButton = createReasoningButton();
        this.muteButton = createIconButton(new SpeakerIcon(), "Mute notifications");
        this.muteButton.setVisible(false);
        this.recordButton = createIconButton(new MicrophoneIcon(), "Record or stop dictation");
        this.audioFileButton = createIconButton(new AudioFileIcon(), "Transcribe audio file");
        this.attachButton = createIconButton(new PaperclipIcon(), "Attach images");
        this.attachmentStrip = new ChatAttachmentStrip(new ChatAttachmentStrip.ChangeListener() {
            public void onAttachmentsChanged() {
                refreshAttachmentState();
            }
        });
        this.discardButton = createIconButton(new CloseIcon(), "Discard or cancel dictation");
        this.retryButton = createSecondaryButton(new RetryIcon(), "Retry", "Retry transcription");
        this.saveButton = createSecondaryButton(new SaveIcon(), "Save", "Save recording");
        this.playRecordingButton = createSecondaryButton(new PlayIcon(), "Play",
                "Play the recording (through the configured playback output)");
        this.installButton = createSecondaryButton(new InstallIcon(), "Install", "Install an audio model");
        this.sendButton = createPrimaryButton(new SendIcon(), "Send", PRIMARY);
        this.stopButton = createPrimaryButton(new StopIcon(), "Stop", DANGER);
        this.levelBar = createLevelBar();

        buildUi();
        wireActions();
        installKeyBindings();
        installFocusTracking();
        updatePrimaryAction();
        updateMessageAvailability();
    }

    private void buildUi() {
        setOpaque(false);
        setLayout(new BorderLayout(0, 0));
        setBorder(new EmptyBorder(9, 11, 8, 8));

        add(attachmentStrip, BorderLayout.NORTH);
        // The editor gets a LAYERED wrapper so the recording waveform can float OVER the text:
        // background-less bars + the draggable signal-threshold line; every non-line pixel stays
        // click-through, the typed text and the caret remain fully visible and editable.
        javax.swing.JLayeredPane editorLayers = new javax.swing.JLayeredPane() {
            @Override
            public void doLayout() {
                for (java.awt.Component child : getComponents()) {
                    child.setBounds(0, 0, getWidth(), getHeight());
                }
            }

            @Override
            public Dimension getPreferredSize() {
                return editorScroll.getPreferredSize();
            }

            @Override
            public Dimension getMinimumSize() {
                return editorScroll.getMinimumSize();
            }
        };
        editorLayers.add(editorScroll, javax.swing.JLayeredPane.DEFAULT_LAYER);
        editorLayers.add(waveformOverlay, javax.swing.JLayeredPane.PALETTE_LAYER);
        add(editorLayers, BorderLayout.CENTER);
        add(buildFooter(), BorderLayout.SOUTH);

        setMinimumSize(new Dimension(320, 104));
    }

    private JComponent buildFooter() {
        JPanel footer = new JPanel(new BorderLayout(8, 0));
        footer.setOpaque(false);
        footer.setBorder(new EmptyBorder(5, 0, 0, 0));
        JPanel west = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        west.setOpaque(false);
        west.add(modelButton);
        west.add(modeButton);
        west.add(reasoningButton);
        west.add(buildLeftActions());
        footer.add(west, BorderLayout.WEST);
        // The notification mute bell sits in the middle of the footer, between the settings gear
        // (left) and the Send button (right); it is only shown while a channel is enabled.
        JPanel center = new JPanel(new BorderLayout(6, 0));
        center.setOpaque(false);
        center.add(muteButton, BorderLayout.WEST);
        center.add(statusPanel, BorderLayout.CENTER);
        footer.add(center, BorderLayout.CENTER);
        footer.add(buildPrimaryActions(), BorderLayout.EAST);
        return footer;
    }

    private JComponent buildLeftActions() {
        JPanel actionsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 0));
        actionsPanel.setOpaque(false);
        actionsPanel.add(discardButton);
        actionsPanel.add(retryButton);
        actionsPanel.add(playRecordingButton); // preview first, then persist — natural order
        actionsPanel.add(saveButton);
        actionsPanel.add(installButton);

        discardButton.setVisible(false);
        retryButton.setVisible(false);
        saveButton.setVisible(false);
        playRecordingButton.setVisible(false);
        installButton.setVisible(false);
        levelBar.setVisible(false);
        return actionsPanel;
    }

    private JComponent buildPrimaryActions() {
        JPanel actionsPanel = new JPanel();
        actionsPanel.setOpaque(false);
        actionsPanel.setLayout(new BoxLayout(actionsPanel, BoxLayout.X_AXIS));
        actionsPanel.add(Box.createHorizontalGlue());
        actionsPanel.add(levelBar);
        actionsPanel.add(Box.createHorizontalStrut(4));
        actionsPanel.add(attachButton);   // image attachments, left of the transcribe-file button
        actionsPanel.add(Box.createHorizontalStrut(4));
        actionsPanel.add(audioFileButton);   // transcribe-file, just left of the mic
        actionsPanel.add(Box.createHorizontalStrut(4));
        actionsPanel.add(recordButton);   // mic sits just left of Send, ChatGPT-style
        actionsPanel.add(Box.createHorizontalStrut(4));
        actionsPanel.add(sendButton);
        actionsPanel.add(stopButton);
        return actionsPanel;
    }

    private JPanel createStatusPanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        panel.setOpaque(false);
        panel.add(chatStatusLabel);
        panel.add(dictationStatusLabel);
        return panel;
    }

    private static JLabel createStatusLabel() {
        JLabel label = new JLabel(" ");
        label.setForeground(TEXT_MUTED);
        label.setFont(label.getFont().deriveFont(label.getFont().getSize2D() - 1f));
        return label;
    }

    private static JScrollPane createEditorScroll(JTextArea editor) {
        JScrollPane scroll = new JScrollPane(editor,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setViewportBorder(BorderFactory.createEmptyBorder());
        scroll.setPreferredSize(new Dimension(520, MIN_EDITOR_HEIGHT));
        return scroll;
    }

    private static JProgressBar createLevelBar() {
        JProgressBar bar = ComicControls.progressBar(0, 100);
        bar.setOpaque(false);
        bar.setBorderPainted(false);
        bar.setPreferredSize(new Dimension(64, 6));
        bar.setMaximumSize(new Dimension(64, 6));
        bar.setToolTipText("Microphone level");
        return bar;
    }

    /** The ChatGPT-style model selector shown at the left of the footer (text + a down chevron). */
    private JButton createModelButton() {
        ComposerButton button = new ComposerButton(new ChevronDownIcon(), "Select model", false);
        button.setHorizontalTextPosition(SwingConstants.LEFT); // name first, chevron after
        configureButton(button, "Choose the chat model");
        return button;
    }

    private JButton createModeButton() {
        ComposerButton button = new ComposerButton(new ChevronDownIcon(), "Yapping", false);
        button.setHorizontalTextPosition(SwingConstants.LEFT); // mode first, chevron after
        configureButton(button, "Choose the interaction mode");
        return button;
    }

    private JButton createReasoningButton() {
        ComposerButton button = new ComposerButton(new ChevronDownIcon(), "Think: Off", false);
        button.setHorizontalTextPosition(SwingConstants.LEFT); // effort first, chevron after
        configureButton(button, "Thinking effort (only for models that support it)");
        button.setEnabled(false); // enabled once a thinking-capable model is selected
        return button;
    }

    private JButton createIconButton(Icon icon, String tooltip) {
        ComposerButton button = new ComposerButton(icon, null, false);
        configureButton(button, tooltip);
        button.setPreferredSize(new Dimension(30, 28));
        return button;
    }

    private JButton createSecondaryButton(Icon icon, String text, String tooltip) {
        ComposerButton button = new ComposerButton(icon, text, false);
        configureButton(button, tooltip);
        return button;
    }

    private JButton createPrimaryButton(Icon icon, String text, Color accent) {
        ComposerButton button = new ComposerButton(icon, text, true);
        button.setAccent(accent);
        configureButton(button, text);
        return button;
    }

    private static void configureButton(JButton button, String tooltip) {
        button.setFocusable(false);
        button.setBorderPainted(false);
        button.setContentAreaFilled(false);
        button.setOpaque(false);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.setMargin(new Insets(4, 8, 4, 8));
        button.setToolTipText(tooltip);
        button.getAccessibleContext().setAccessibleName(tooltip);
    }

    private void wireActions() {
        modelButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent event) {
                actions.selectModel();
            }
        });
        modeButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent event) {
                actions.selectMode();
            }
        });
        reasoningButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent event) {
                actions.selectReasoning();
            }
        });
        muteButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent event) {
                actions.toggleNotificationsMute();
            }
        });
        sendButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent event) {
                actions.send();
            }
        });
        stopButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent event) {
                actions.stop();
            }
        });
        recordButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent event) {
                actions.toggleRecording();
            }
        });
        discardButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent event) {
                actions.discardDictation();
            }
        });
        retryButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent event) {
                actions.retryTranscription();
            }
        });
        playRecordingButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent event) {
                actions.playRecording();
            }
        });
        saveButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent event) {
                actions.saveRecording();
            }
        });
        installButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent event) {
                actions.installAudioModel();
            }
        });
        audioFileButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent event) {
                actions.transcribeAudioFile();
            }
        });
        attachButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent event) {
                actions.attachImages();
            }
        });
        editor.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent event) {
                updateMessageAvailability();
            }

            public void removeUpdate(DocumentEvent event) {
                updateMessageAvailability();
            }

            public void changedUpdate(DocumentEvent event) {
                updateMessageAvailability();
            }
        });
    }

    private void installKeyBindings() {
        editor.getInputMap().put(KeyStroke.getKeyStroke("ENTER"), "composer.send");
        editor.getInputMap().put(KeyStroke.getKeyStroke("shift ENTER"), "composer.newline");
        editor.getInputMap().put(KeyStroke.getKeyStroke("control shift M"), "composer.dictation");
        editor.getInputMap().put(KeyStroke.getKeyStroke("ESCAPE"), "composer.discard");
        editor.getActionMap().put("composer.send", new AbstractAction() {
            public void actionPerformed(ActionEvent event) {
                if (sendButton.isEnabled() && sendButton.isVisible()) {
                    actions.send();
                }
            }
        });
        editor.getActionMap().put("composer.newline", new AbstractAction() {
            public void actionPerformed(ActionEvent event) {
                editor.insert("\n", editor.getCaretPosition());
            }
        });
        editor.getActionMap().put("composer.dictation", new AbstractAction() {
            public void actionPerformed(ActionEvent event) {
                if (recordButton.isEnabled()) {
                    actions.toggleRecording();
                }
            }
        });
        editor.getActionMap().put("composer.discard", new AbstractAction() {
            public void actionPerformed(ActionEvent event) {
                if (discardButton.isEnabled() && discardButton.isVisible()) {
                    actions.discardDictation();
                }
            }
        });
    }

    private void installFocusTracking() {
        editor.addFocusListener(new FocusAdapter() {
            public void focusGained(FocusEvent event) {
                editorFocused = true;
                repaint();
            }

            public void focusLost(FocusEvent event) {
                editorFocused = false;
                repaint();
            }
        });
        addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent event) {
                editor.requestFocusInWindow();
            }
        });
    }

    /** Return the editor for caret-aware insertion and existing integration code. */
    public JTextArea getEditor() {
        return editor;
    }

    /** Set the selected model name shown on the in-composer selector (empty → "Select model"). */
    public void setModelName(String name) {
        modelButton.setText(name == null || name.trim().length() == 0 ? "Select model" : name.trim());
        revalidate();
        repaint();
    }

    /** Return the model selector button, so the panel can anchor its model popup to it. */
    public JComponent getModelButton() {
        return modelButton;
    }

    /**
     * A hamburger button in the composer's icon style. The burger moved from the composer footer to
     * the TOP-LEFT of the chat area (it toggles the sidebar there); this factory keeps the icon and
     * styling single-sourced here.
     */
    /** Client-property key holding a plugin's hamburger-replacement glyph (or absent). */
    private static final String MENU_OVERRIDE_ICON = "askai.menuOverrideIcon";
    private static final Icon MENU_ICON = new MenuIcon();

    public static JButton createSidebarToggleButton() {
        final ComposerButton button = new ComposerButton(MENU_ICON, null, false);
        configureButton(button, "Chats & tools sidebar");
        // Latched (menu pinned open) it fills with the DARK "New Chat" accent, not blue — the
        // same near-black the phase selector and the active ribbon entry speak.
        button.setAccent(com.aresstack.comiccontrols.theme.ResearchUiPalette.SECONDARY_SURFACE);
        button.setPreferredSize(new Dimension(30, 28));
        // HOVER always brings the plain hamburger back, so the user knows what the click does
        // even while a plugin brands the resting glyph (e.g. the research flask).
        button.getModel().addChangeListener(e -> applySidebarToggleIcon(button));
        return button;
    }

    /**
     * Let the ACTIVE plugin replace the resting hamburger glyph ({@code null} restores it). The
     * button's function is untouched, and hovering always shows the ordinary hamburger again.
     */
    public static void setSidebarToggleOverrideIcon(JButton button, Icon override) {
        if (button instanceof ComposerButton) {
            ((ComposerButton) button).putClientProperty(MENU_OVERRIDE_ICON, override);
            applySidebarToggleIcon((ComposerButton) button);
        }
    }

    private static void applySidebarToggleIcon(ComposerButton button) {
        Object override = button.getClientProperty(MENU_OVERRIDE_ICON);
        Icon resting = override instanceof Icon ? (Icon) override : MENU_ICON;
        Icon wanted = button.getModel().isRollover() ? MENU_ICON : resting;
        if (button.getIcon() != wanted) {
            button.setIcon(wanted);
        }
    }

    /**
     * The gear button in the composer's icon style. The gear moved from the composer footer to the
     * TOP-RIGHT of the chat area (opposite the hamburger); the icon stays single-sourced here.
     */
    public static JButton createSettingsIconButton() {
        ComposerButton button = new ComposerButton(new GearIcon(), null, false);
        configureButton(button, "Chat settings");
        button.setPreferredSize(new Dimension(30, 28));
        return button;
    }

    /** The New-chat "+" in the same icon style — it sits next to the gear in the top bar. */
    public static JButton createNewChatIconButton() {
        ComposerButton button = new ComposerButton(new PlusIcon(12), null, false);
        configureButton(button, "Open a new chat");
        button.setPreferredSize(new Dimension(30, 28));
        return button;
    }

    /**
     * The bare gear GLYPH (paints with the owning component's foreground) — for buttons that manage
     * their own surface, e.g. the dark chats-footer settings button.
     */
    public static Icon createModelsGlyphIcon() {
        return new ModelsIcon();
    }

    /** The back glyph (same contract as {@link #createGearGlyphIcon()}). */
    public static Icon createBackGlyphIcon() {
        return new BackIcon();
    }

    /** The trash glyph (same contract as {@link #createGearGlyphIcon()}). */
    public static Icon createTrashGlyphIcon() {
        return new TrashIcon();
    }

    /** The new-chat glyph (same contract as {@link #createGearGlyphIcon()}). */
    public static Icon createNewChatGlyphIcon() {
        return new NewChatIcon();
    }

    /** The bare gear glyph (see {@link #createNewChatGlyphIcon()} for the same contract). */
    public static Icon createGearGlyphIcon() {
        return new GearIcon();
    }

    /**
     * Override the editor's placeholder text (e.g. the research scoping query proposed by the agent);
     * null or blank restores the default prompt. The placeholder never touches typed text.
     */
    public void setEditorPlaceholder(String text) {
        editor.setPlaceholder(text == null || text.trim().isEmpty() ? "Ask anything…" : text.trim());
    }

    /**
     * Latch/unlatch a toolbar button created by the factories above: latched it paints filled
     * ("pressed in"), e.g. the hamburger while the ribbon menu is locked open.
     */
    public static void setToolbarButtonLatched(JButton button, boolean latched) {
        if (button instanceof ComposerButton) {
            ((ComposerButton) button).setEmphasized(latched);
        }
    }

    /** One entry of the unfolding sidebar tab ribbon, in the composer's Java2D button style. */
    public static JButton createRibbonEntryButton(String text, boolean active) {
        ComposerButton button = new ComposerButton(null, text, false);
        // The ACTIVE entry fills with the dark "New Chat" accent (shared with burger + phase pill).
        button.setAccent(com.aresstack.comiccontrols.theme.ResearchUiPalette.SECONDARY_SURFACE);
        button.setEmphasized(active);
        configureButton(button, text);
        return button;
    }

    /** A slim ‹/› scroll arrow for the sidebar tab ribbon (hovering it scrolls the ribbon). */
    public static JButton createRibbonArrowButton(boolean leftDirection) {
        ComposerButton button = new ComposerButton(
                leftDirection ? new ChevronLeftIcon() : new ChevronRightIcon(), null, false);
        configureButton(button, leftDirection ? "Scroll left" : "Scroll right");
        button.setPreferredSize(new Dimension(18, 28));
        return button;
    }

    /** Hide/show the in-composer mode selector (it moved into the drawer's Chats footer). */
    public void setModeSelectorVisible(boolean visible) {
        modeButton.setVisible(visible);
        revalidate();
        repaint();
    }

    /** Set the label shown on the in-composer mode selector (e.g. "Yapping" or an agent name). */
    public void setModeName(String name) {
        modeButton.setText(name == null || name.trim().length() == 0 ? "Yapping" : name.trim());
        revalidate();
        repaint();
    }

    /** Return the mode selector button, so the panel can anchor its mode popup to it. */
    public JComponent getModeButton() {
        return modeButton;
    }

    /** Show or hide the notification mute bell (shown when at least one channel is enabled). */
    public void setNotificationsButtonVisible(boolean visible) {
        muteButton.setVisible(visible);
        revalidate();
        repaint();
    }

    /** Reflect the mute state on the bell: muted shows a struck-through speaker. */
    public void setNotificationsMuted(boolean muted) {
        muteButton.setIcon(muted ? new SpeakerMutedIcon() : new SpeakerIcon());
        muteButton.setToolTipText(muted ? "Notifications muted — click to unmute" : "Mute notifications");
        repaint();
    }

    /** Set the label shown on the reasoning-effort selector (e.g. "Think: High"). */
    public void setReasoningName(String name) {
        reasoningButton.setText(name == null || name.trim().length() == 0 ? "Think: Off" : name.trim());
        revalidate();
        repaint();
    }

    /** Enable/grey the reasoning-effort selector — enabled only when the model supports thinking. */
    public void setReasoningEnabled(boolean enabled) {
        reasoningButton.setEnabled(enabled);
    }

    /** Return the reasoning-effort selector button, so the panel can anchor its popup to it. */
    public JComponent getReasoningButton() {
        return reasoningButton;
    }

    /** Return the untrimmed message text. */
    public String getMessage() {
        return editor.getText();
    }

    /** Replace the message text. */
    public void setMessage(String message) {
        editor.setText(message == null ? "" : message);
    }

    /** Clear the message text. */
    public void clearMessage() {
        editor.setText("");
    }

    /** The full outgoing draft: the editor text plus any queued image attachments. */
    public com.aresstack.askai.vision.ChatDraft getDraft() {
        return new com.aresstack.askai.vision.ChatDraft(editor.getText(), attachmentStrip.getAttachments());
    }

    /** Queue image attachments for the next message (already-queued files are ignored). */
    public void addAttachments(java.util.List<com.aresstack.askai.vision.ImageAttachment> attachments) {
        attachmentStrip.addAttachments(attachments);
    }

    /** The currently queued attachments. */
    public java.util.List<com.aresstack.askai.vision.ImageAttachment> getAttachments() {
        return attachmentStrip.getAttachments();
    }

    /** Clear both the message text and the queued attachments (after a successful send). */
    public void clearDraft() {
        editor.setText("");
        attachmentStrip.clear();
    }

    private void refreshAttachmentState() {
        int count = attachmentStrip.count();
        attachButton.setText(count > 0 ? String.valueOf(count) : null);
        attachButton.setToolTipText(count > 0 ? "Attach images (" + count + " attached)" : "Attach images");
        updateMessageAvailability();
        revalidate();
        repaint();
    }

    /** Focus the message editor. */
    public void focusEditor() {
        editor.requestFocusInWindow();
    }

    /** Return whether the primary send action currently accepts input. */
    public boolean isSendEnabled() {
        return sendButton.isEnabled();
    }

    /** Switch the primary action between Send and Stop. */
    public void setChatBusy(boolean busy) {
        this.chatBusy = busy;
        editor.setEnabled(!busy);
        updatePrimaryAction();
        updateMessageAvailability();
        repaint();
    }

    /** Present the complete dictation action state atomically. */
    public void setDictationView(DictationView view) {
        if (view == null) {
            throw new IllegalArgumentException("view must not be null");
        }
        dictationActive = view.recordingActive || view.dictationWorking || view.audioLevelVisible;
        dictationStatusLabel.setForeground(dictationActive ? BORDER_DICTATION : TEXT_MUTED);
        // Icon-only: recording → stop square, working/cancel → X, otherwise the microphone. The action
        // (Record / Stop / Cancel) is conveyed by the tooltip, not by button text.
        recordButton.setEnabled(view.recordEnabled);
        recordButton.setIcon(view.recordingActive ? new StopRecordingIcon()
                : view.dictationWorking ? new CloseIcon() : new MicrophoneIcon());
        recordButton.setToolTipText(view.recordLabel);
        if (recordButton instanceof ComposerButton) {
            ((ComposerButton) recordButton).setAccent(view.recordingActive ? RECORDING : null);
            ((ComposerButton) recordButton).setEmphasized(view.recordingActive);
        }
        levelBar.setVisible(view.audioLevelVisible);
        discardButton.setVisible(view.discardEnabled);
        discardButton.setEnabled(view.discardEnabled);
        retryButton.setVisible(view.retryVisible);
        saveButton.setVisible(view.saveVisible);
        playRecordingButton.setVisible(view.saveVisible); // exists exactly when Save does
        installButton.setVisible(view.installVisible);
        audioFileButton.setEnabled(view.audioFileEnabled);
        updateMessageAvailability();
        revalidate();
        repaint();
    }

    /** Update the live microphone level. */
    public void setAudioLevel(int value) {
        levelBar.setValue(Math.max(0, Math.min(100, value)));
    }

    /** Recording lifecycle of the waveform sampler floating over the editor. */
    public void setWaveformActive(boolean active) {
        waveformOverlay.setActive(active);
    }

    /** One live level sample (0-100) for the waveform bars. */
    public void pushWaveformLevel(int level) {
        waveformOverlay.pushLevel(level);
    }

    /** The signal-threshold line's position (percent of full scale). */
    public void setWaveformThreshold(int percent) {
        waveformOverlay.setThresholdPercent(percent);
    }

    /** Live threshold changes while the user DRAGS the line during a recording. */
    public void setWaveformThresholdListener(ComposerWaveformOverlay.ThresholdListener listener) {
        waveformOverlay.setThresholdListener(listener);
    }

    /** Update the normal chat status shown inside the composer. */
    public void setChatStatus(String status) {
        setStatus(chatStatusLabel, status);
    }

    /** Update the dictation status shown inside the composer. */
    public void setDictationStatus(String status) {
        setStatus(dictationStatusLabel, status);
        dictationStatusLabel.setForeground(dictationActive ? BORDER_DICTATION : TEXT_MUTED);
    }

    /** Return the current dictation status for stale-hint handling. */
    public String getDictationStatus() {
        return dictationStatusLabel.getText();
    }

    private static void setStatus(JLabel label, String status) {
        String value = status == null || status.trim().length() == 0 ? " " : status;
        label.setText(value);
        label.setToolTipText(value.trim().length() == 0 ? null : value);
    }

    private void updatePrimaryAction() {
        sendButton.setVisible(!chatBusy);
        stopButton.setVisible(chatBusy);
        stopButton.setEnabled(chatBusy);
        revalidate();
    }

    /** Non-empty = sending is blocked entirely (e.g. a rerank-only local model is selected). */
    private String sendBlockedReason = "";

    /**
     * Block or unblock sending with a visible reason: the Send button is DISABLED (not just
     * refused on click) and carries the reason as tooltip until an empty reason unblocks it.
     */
    public void setSendBlockedReason(String reason) {
        this.sendBlockedReason = reason == null ? "" : reason.trim();
        sendButton.setToolTipText(sendBlockedReason.isEmpty() ? "Send" : sendBlockedReason);
        updateMessageAvailability();
    }

    private void updateMessageAvailability() {
        boolean hasContent = editor.getText().trim().length() > 0 || !attachmentStrip.isEmpty();
        sendButton.setEnabled(!chatBusy && !dictationActive && editor.isEnabled() && hasContent
                && sendBlockedReason.isEmpty());
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g2 = (Graphics2D) graphics.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        int inset = 1;
        int width = getWidth() - 2 * inset;
        int height = getHeight() - 2 * inset;
        RoundRectangle2D background = new RoundRectangle2D.Float(
                inset, inset, width, height, ARC, ARC);

        Color fill = dictationActive ? BACKGROUND_DICTATION
                : editorFocused ? BACKGROUND_FOCUSED : resolveBackground();
        Color border = dictationActive ? BORDER_DICTATION
                : editorFocused || chatBusy ? resolveFocusColor() : BORDER_NORMAL;

        g2.setColor(fill);
        g2.fill(background);
        g2.setColor(border);
        g2.setStroke(new BasicStroke(editorFocused || dictationActive || chatBusy ? 1.6f : 1f));
        g2.draw(background);
        g2.dispose();
    }

    private static Color resolveBackground() {
        Color color = UIManager.getColor("TextArea.background");
        return color == null ? BACKGROUND_NORMAL : blend(color, BACKGROUND_NORMAL, 0.35f);
    }

    private static Color resolveFocusColor() {
        Color color = UIManager.getColor("Component.focusColor");
        return color == null ? BORDER_FOCUSED : color;
    }

    private static Color blend(Color first, Color second, float secondWeight) {
        // the same rounding blend as ResearchUiPainter.mix; evaluated at paint time, never stored
        return ResearchUiPainter.mix(first, second, secondWeight);
    }

    private static final class PlaceholderTextArea extends JTextArea {
        private String placeholder;

        private void setPlaceholder(String placeholder) {
            this.placeholder = placeholder;
            repaint();
        }

        private PlaceholderTextArea(String placeholder) {
            super(3, 40);
            this.placeholder = placeholder;
            setOpaque(false);
            setLineWrap(true);
            setWrapStyleWord(true);
            setBorder(new EmptyBorder(3, 4, 3, 4));
            setFont(getFont().deriveFont(13f));
            setToolTipText("Enter: send · Shift+Enter: new line · Ctrl+Shift+M: dictation");
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            if (getText().length() != 0 || placeholder == null) {
                return;
            }
            Graphics2D g2 = (Graphics2D) graphics.create();
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setColor(PLACEHOLDER);
            g2.setFont(getFont().deriveFont(Font.PLAIN));
            FontMetrics metrics = g2.getFontMetrics();
            Insets insets = getInsets();
            g2.drawString(placeholder, insets.left, insets.top + metrics.getAscent());
            g2.dispose();
        }
    }

    private static final class ComposerButton extends JButton {
        private final boolean primary;
        private Color accent;
        private boolean emphasized;

        private ComposerButton(Icon icon, String text, boolean primary) {
            super(text, icon);
            this.primary = primary;
            setHorizontalTextPosition(SwingConstants.RIGHT);
            setIconTextGap(5);
            setFont(getFont().deriveFont(Font.BOLD, 11.5f));
        }

        private void setAccent(Color accent) {
            this.accent = accent;
            repaint();
        }

        private void setEmphasized(boolean emphasized) {
            this.emphasized = emphasized;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g2 = (Graphics2D) graphics.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color active = accent == null ? PRIMARY : accent;
            boolean hovered = getModel().isRollover();
            boolean pressed = getModel().isPressed();

            if (primary || emphasized) {
                Color fill = isEnabled() ? active
                        : ThemeColor.of("chat.composerButtonDisabledFill");
                if (pressed && isEnabled()) {
                    fill = fill.darker();
                } else if (hovered && isEnabled()) {
                    fill = fill.brighter();
                }
                g2.setColor(fill);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 14, 14);
                setForeground(ThemeColor.of("bubble.userForeground"));
            } else if (!isEnabled()) {
                setForeground(ThemeColor.of("chat.composerButtonDisabledText"));
            } else if (hovered) {
                g2.setColor(ThemeColor.withAlpha(ThemeColor.of("chat.composerButtonHoverShade"), 18));
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 12, 12);
                setForeground(resolveButtonForeground());
            } else {
                setForeground(resolveButtonForeground());
            }
            g2.dispose();
            super.paintComponent(graphics);
        }


        private static Color resolveButtonForeground() {
            Color foreground = UIManager.getColor("Button.foreground");
            return foreground == null ? ResearchUiPalette.LIGHT_CONTROL_TEXT : foreground;
        }

        @Override
        public Dimension getPreferredSize() {
            Dimension size = super.getPreferredSize();
            int height = Math.max(28, size.height);
            return new Dimension(Math.max(primary ? 72 : 30, size.width), height);
        }
    }

    private abstract static class StrokeIcon implements Icon {
        private final int width;
        private final int height;

        private StrokeIcon() {
            this(15, 15);
        }

        private StrokeIcon(int width, int height) {
            this.width = width;
            this.height = height;
        }

        public int getIconWidth() {
            return width;
        }

        public int getIconHeight() {
            return height;
        }

        public final void paintIcon(Component component, Graphics graphics, int x, int y) {
            Graphics2D g2 = (Graphics2D) graphics.create();
            g2.translate(x, y);
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(component.getForeground());
            g2.setStroke(new BasicStroke(1.7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            paint(g2);
            g2.dispose();
        }

        protected abstract void paint(Graphics2D g2);
    }

    private static final class SendIcon extends StrokeIcon {
        protected void paint(Graphics2D g2) {
            int[] x = {2, 13, 6};
            int[] y = {2, 7, 13};
            g2.drawPolygon(x, y, 3);
            g2.drawLine(3, 7, 11, 7);
        }
    }

    /**
     * The NEW-CHAT glyph: the send paper plane (smaller, top-left) with a "+" at the bottom right —
     * "send something new". Painted with the owning component's foreground like every composer glyph.
     */
    private static final class NewChatIcon extends StrokeIcon {
        protected void paint(Graphics2D g2) {
            int[] x = {1, 10, 4};
            int[] y = {1, 5, 10};
            g2.drawPolygon(x, y, 3);
            g2.drawLine(2, 5, 8, 5);
            g2.drawLine(12, 9, 12, 14);
            g2.drawLine(9, 12, 14, 12);
        }
    }

    /** The MODELS glyph: an isometric cube (a packaged model), stroke-drawn like every composer glyph. */
    private static final class ModelsIcon extends StrokeIcon {
        protected void paint(Graphics2D g2) {
            g2.drawPolygon(new int[]{7, 13, 7, 1}, new int[]{1, 4, 7, 4}, 4); // top face
            g2.drawLine(1, 4, 1, 10);
            g2.drawLine(13, 4, 13, 10);
            g2.drawLine(7, 7, 7, 13);
            g2.drawLine(1, 10, 7, 13);
            g2.drawLine(13, 10, 7, 13);
        }
    }

    /** The BACK glyph: a left arrow (shaft + head), like the "back to the app" arrow of chat apps. */
    private static final class BackIcon extends StrokeIcon {
        protected void paint(Graphics2D g2) {
            g2.drawLine(2, 7, 13, 7);
            g2.drawPolyline(new int[]{7, 2, 7}, new int[]{2, 7, 12}, 3);
        }
    }

    /** The TRASH glyph: lid with handle over a tapered bin with two ribs (delete all chats). */
    private static final class TrashIcon extends StrokeIcon {
        protected void paint(Graphics2D g2) {
            g2.drawLine(2, 4, 13, 4);
            g2.drawPolyline(new int[]{6, 6, 9, 9}, new int[]{4, 2, 2, 4}, 4);
            g2.drawPolyline(new int[]{3, 4, 11, 12}, new int[]{4, 13, 13, 4}, 4);
            g2.drawLine(6, 6, 6, 11);
            g2.drawLine(9, 6, 9, 11);
        }
    }

    private static final class StopIcon extends StrokeIcon {
        protected void paint(Graphics2D g2) {
            g2.fillRoundRect(4, 4, 8, 8, 2, 2);
        }
    }

    private static final class MicrophoneIcon extends StrokeIcon {
        protected void paint(Graphics2D g2) {
            g2.drawRoundRect(5, 1, 6, 9, 6, 6);
            g2.drawArc(3, 5, 10, 8, 180, 180);
            g2.drawLine(8, 13, 8, 15);
            g2.drawLine(5, 15, 11, 15);
        }
    }

    private static final class StopRecordingIcon extends StrokeIcon {
        protected void paint(Graphics2D g2) {
            g2.fillRoundRect(4, 4, 8, 8, 2, 2);
        }
    }

    private static final class AudioFileIcon extends StrokeIcon {
        protected void paint(Graphics2D g2) {
            g2.drawRoundRect(2, 1, 10, 13, 2, 2);
            g2.drawLine(9, 1, 12, 4);
            g2.drawLine(9, 1, 9, 4);
            g2.drawLine(9, 4, 12, 4);
            g2.drawLine(6, 7, 6, 11);
            g2.drawLine(6, 7, 10, 6);
            g2.fillOval(4, 10, 3, 3);
            g2.fillOval(8, 9, 3, 3);
        }
    }

    private static final class PaperclipIcon extends StrokeIcon {
        protected void paint(Graphics2D g2) {
            // A simple paperclip: an open hook curving up on the left and back down on the right.
            g2.drawLine(4, 4, 4, 11);
            g2.drawArc(4, 2, 6, 5, 90, 180);
            g2.drawLine(10, 4, 10, 12);
            g2.drawArc(3, 9, 7, 6, 0, -180);
        }
    }

    private static final class CloseIcon extends StrokeIcon {
        protected void paint(Graphics2D g2) {
            g2.drawLine(4, 4, 12, 12);
            g2.drawLine(12, 4, 4, 12);
        }
    }

    private static final class RetryIcon extends StrokeIcon {
        protected void paint(Graphics2D g2) {
            g2.drawArc(2, 2, 11, 11, 35, 285);
            g2.drawLine(2, 3, 2, 8);
            g2.drawLine(2, 3, 7, 3);
        }
    }

    private static final class PlayIcon extends StrokeIcon {
        protected void paint(Graphics2D g2) {
            g2.drawLine(5, 3, 5, 13);   // play triangle, stroke style like the other icons
            g2.drawLine(5, 3, 12, 8);
            g2.drawLine(5, 13, 12, 8);
        }
    }

    private static final class SaveIcon extends StrokeIcon {
        protected void paint(Graphics2D g2) {
            g2.drawRoundRect(2, 2, 12, 12, 2, 2);
            g2.drawRect(5, 2, 6, 4);
            g2.drawRect(5, 9, 6, 5);
        }
    }

    private static final class InstallIcon extends StrokeIcon {
        protected void paint(Graphics2D g2) {
            g2.drawLine(8, 1, 8, 10);
            g2.drawLine(4, 7, 8, 11);
            g2.drawLine(12, 7, 8, 11);
            g2.drawLine(3, 13, 13, 13);
        }
    }

    private static final class ChevronDownIcon extends StrokeIcon {
        protected void paint(Graphics2D g2) {
            g2.drawLine(3, 6, 7, 10);
            g2.drawLine(7, 10, 11, 6);
        }
    }

    private static final class MenuIcon extends StrokeIcon {
        protected void paint(Graphics2D g2) {
            g2.drawLine(2, 4, 13, 4);
            g2.drawLine(2, 8, 13, 8);
            g2.drawLine(2, 12, 13, 12);
        }
    }

    private static final class ChevronLeftIcon extends StrokeIcon {
        protected void paint(Graphics2D g2) {
            g2.drawLine(9, 3, 5, 7);
            g2.drawLine(5, 7, 9, 11);
        }
    }

    private static final class ChevronRightIcon extends StrokeIcon {
        protected void paint(Graphics2D g2) {
            g2.drawLine(6, 3, 10, 7);
            g2.drawLine(10, 7, 6, 11);
        }
    }

    private static final class GearIcon extends StrokeIcon {
        protected void paint(Graphics2D g2) {
            int cx = 7;
            int cy = 7;
            int r = 4;
            g2.drawOval(cx - r, cy - r, 2 * r, 2 * r);
            g2.drawOval(cx - 2, cy - 2, 4, 4);
            for (int i = 0; i < 8; i++) {
                double a = Math.PI * 2 * i / 8;
                int x1 = (int) Math.round(cx + Math.cos(a) * r);
                int y1 = (int) Math.round(cy + Math.sin(a) * r);
                int x2 = (int) Math.round(cx + Math.cos(a) * (r + 2.5));
                int y2 = (int) Math.round(cy + Math.sin(a) * (r + 2.5));
                g2.drawLine(x1, y1, x2, y2);
            }
        }
    }

    /** A speaker with two sound waves — the "notifications on" bell. */
    private static class SpeakerIcon extends StrokeIcon {
        protected void paint(Graphics2D g2) {
            paintSpeaker(g2);
            g2.drawArc(9, 4, 4, 7, -60, 120);
            g2.drawArc(9, 2, 7, 11, -55, 110);
        }

        final void paintSpeaker(Graphics2D g2) {
            g2.drawLine(2, 6, 4, 6);
            g2.drawLine(2, 6, 2, 9);
            g2.drawLine(2, 9, 4, 9);
            int[] x = {4, 7, 7, 4};
            int[] y = {6, 3, 12, 9};
            g2.drawPolygon(x, y, 4);
        }
    }

    /** The muted speaker — same body with a diagonal strike-through, no sound waves. */
    private static final class SpeakerMutedIcon extends SpeakerIcon {
        protected void paint(Graphics2D g2) {
            paintSpeaker(g2);
            g2.drawLine(9, 4, 14, 11);
        }
    }
}
