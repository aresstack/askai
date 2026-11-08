package com.aresstack.askai.research.agent;

import com.aresstack.comiccontrols.control.ComicControls;
import com.aresstack.askai.research.concept.ConceptProjection;
import com.aresstack.askai.research.concept.ConceptTopicScanner;
import com.aresstack.comiccontrols.control.ComicButton;
import com.aresstack.comiccontrols.control.ComicSearchBar;
import com.aresstack.comiccontrols.theme.ComicPalette;
import com.aresstack.comiccontrols.theme.ResearchUiMetrics;
import com.aresstack.comiccontrols.theme.ResearchUiTypography;
import com.aresstack.comiccontrols.theme.ThemeColor;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.List;
import java.util.Locale;

/**
 * The Konzept tab — since K4 the ONE scoping work surface, and now DIRECTLY EDITABLE (the
 * Zielbild raw-JSON mode): the user types into the document, [Save] runs the strict
 * parse→validate→pretty-print→commit pipeline (invalid JSON never reaches the store), [Discard]
 * returns to the last saved state. Unsaved text STAYS in the editor — a background refresh from
 * an agent edit never clobbers the user's typing; only Save or Discard resolve it. The ◀ ▶
 * controls browse the working-revision history: an older state loads into the editor and
 * becomes the new head via the very same Save.
 *
 * <p>The footer keeps the concept search (Enter filters cards by name — blocked while unsaved
 * edits exist, so a search can never eat them; empty Enter restores the document), the revision
 * witness and the manual reload. All methods EDT.</p>
 */
public final class ConceptPaperView extends JPanel {

    /** How the owner persists a manual edit; mirrors ConceptBranchService.replaceDocument. */
    public interface SaveHandler {
        /** @return {@code null} on success, else the honest rejection text (diagnostic). */
        String save(String documentJson, long expectedRevision);
    }

    /** Read one working-history revision; {@code null} when no history exists for it. */
    public interface HistoryReader {
        String content(long revision);
    }

    /**
     * Restore an UNCHANGED browsed revision as the new head (ID-sidecar V3 §5): document and
     * identity come back as a validated pair — the historical epoch and UUIDs live again. Only
     * a dirty text goes through {@link SaveHandler} (raw save = a fresh identity epoch).
     */
    public interface RestoreHandler {
        /** @return {@code null} on success, else the honest rejection text. */
        String restore(long revision);
    }

    /** Saves the user's mission edit (PR 07); {@code null} on success, else the reason. */
    public interface MissionSaver {
        String save(String mission);
    }

    private final javax.swing.JTextField missionField = ComicControls.textField();
    private final ComicButton missionSaveButton = new ComicButton("Save mission");
    private final JLabel modeLabel = new JLabel(" ");
    private MissionSaver missionSaver;
    private String shownMission = "";
    /** PR 07: the concept is frozen (outside scoping/running) — every editor is read-only. */
    private boolean frozen;

    private static final String EMPTY =
            "_No concept yet. Describe in the chat what you want to research._";

    private final JTextArea editor = ComicControls.textArea();
    private final JLabel statusLabel = new JLabel(" ");
    private final ComicButton saveButton = new ComicButton("Save");
    private final ComicButton discardButton = new ComicButton("Discard");
    private final ComicButton olderButton = new ComicButton("◀");
    private final ComicButton newerButton = new ComicButton("▶");
    private final ComicButton refreshButton = new ComicButton("⟳");
    private final ComicButton treeButton = new ComicButton("Tree");
    private final ComicButton jsonButton = new ComicButton("JSON");
    private final ConceptTreeView treeView = new ConceptTreeView();
    private final JScrollPane treeScroll = new JScrollPane(treeView);
    /** Tree first — the pretty surface; JSON stays the power/recovery mode. */
    private boolean treeMode = true;
    private ConceptTreeView.BlacklistSource blacklistSource;
    private ConceptTreeView.IdentityContext identityContext;
    private final ComicSearchBar searchBar =
            new ComicSearchBar("Search concept…", "Filter the concept cards by name (Enter; "
                    + "empty Enter shows the document again)");

    private ConceptProjection lastProjection;
    private long loadedRevision;
    private boolean dirty;
    /** The working revision currently shown from history, or {@code -1} = the live head. */
    private long browsingRevision = -1;
    private boolean searchView;
    private boolean settingText;

    private SaveHandler saveHandler;
    private HistoryReader historyReader;
    private RestoreHandler restoreHandler;

    /** How long the comic error overlay stays before fading out on its own. */
    private static final int ERROR_OVERLAY_MILLIS = 4000;

    private final javax.swing.JLayeredPane layers = new javax.swing.JLayeredPane();
    private final JScrollPane editorScroll = new JScrollPane(editor);
    private final com.aresstack.comiccontrols.control.ComicSectionPanel errorOverlay =
            new com.aresstack.comiccontrols.control.ComicSectionPanel();
    private final JTextArea errorText = ComicControls.textArea();
    private final javax.swing.Timer errorHideTimer =
            new javax.swing.Timer(ERROR_OVERLAY_MILLIS, new ActionListener() {
                public void actionPerformed(ActionEvent event) {
                    hideError();
                }
            });

    public ConceptPaperView() {
        super(new BorderLayout());
        setOpaque(false);
        editor.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        editor.setLineWrap(false);
        editor.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent event) {
                userTyped();
            }

            public void removeUpdate(DocumentEvent event) {
                userTyped();
            }

            public void changedUpdate(DocumentEvent event) {
                userTyped();
            }
        });
        // The search sits ON TOP like the chats drawer's "Chats durchsuchen…" — never below.
        JPanel searchRow = new JPanel(new BorderLayout());
        searchRow.setOpaque(false);
        searchRow.setBorder(BorderFactory.createEmptyBorder(
                ResearchUiMetrics.FOOTER_PADDING_V, ResearchUiMetrics.FOOTER_PADDING_H,
                ResearchUiMetrics.FOOTER_PADDING_V, ResearchUiMetrics.FOOTER_PADDING_H));
        searchRow.add(searchBar, BorderLayout.CENTER);
        JPanel modeToggle = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        modeToggle.setOpaque(false);
        for (ComicButton button : new ComicButton[] {treeButton, jsonButton}) {
            button.setFocusable(false);
            button.setBorder(BorderFactory.createEmptyBorder(3, 10, 3, 10));
            modeToggle.add(button);
        }
        treeButton.setToolTipText("Show the concept as an editable card tree");
        jsonButton.setToolTipText("Show and edit the raw JSON document");
        searchRow.add(modeToggle, BorderLayout.EAST);
        JPanel north = new JPanel(new BorderLayout());
        north.setOpaque(false);
        north.add(buildMissionRow(), BorderLayout.NORTH);
        north.add(searchRow, BorderLayout.CENTER);
        add(north, BorderLayout.NORTH);

        // Editor + the comic error overlay share the center: a rejected save POPS over the text
        // in a comic plate (bigger type, red stripe) and fades out by itself.
        buildErrorOverlay();
        layers.setLayout(null);
        layers.add(editorScroll, javax.swing.JLayeredPane.DEFAULT_LAYER);
        layers.add(treeScroll, javax.swing.JLayeredPane.DEFAULT_LAYER);
        layers.add(errorOverlay, javax.swing.JLayeredPane.PALETTE_LAYER);
        layers.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentResized(java.awt.event.ComponentEvent event) {
                layoutLayers();
            }
        });
        add(layers, BorderLayout.CENTER);
        add(buildFooter(), BorderLayout.SOUTH);
        wireActions();
        updateControls();
    }

    /** The mission as one editable row above the tree (PR 07): label left, field, button in the same row. */
    private JPanel buildMissionRow() {
        JPanel row = new JPanel(new BorderLayout(6, 0));
        row.setOpaque(false);
        row.setBorder(BorderFactory.createEmptyBorder(ResearchUiMetrics.FOOTER_PADDING_V,
                ResearchUiMetrics.FOOTER_PADDING_H, 0, ResearchUiMetrics.FOOTER_PADDING_H));
        row.add(new JLabel("Mission"), BorderLayout.WEST);
        row.add(missionField, BorderLayout.CENTER);
        missionField.setToolTipText("What the book is for; your first message becomes the mission");
        missionSaveButton.setFocusable(false);
        missionSaveButton.setBorder(BorderFactory.createEmptyBorder(3, 10, 3, 10));
        row.add(missionSaveButton, BorderLayout.EAST);
        modeLabel.setVisible(false);
        JPanel block = new JPanel(new BorderLayout());
        block.setOpaque(false);
        block.add(row, BorderLayout.CENTER);
        JPanel modeRow = new JPanel(new BorderLayout());
        modeRow.setOpaque(false);
        modeRow.setBorder(BorderFactory.createEmptyBorder(2, ResearchUiMetrics.FOOTER_PADDING_H, 0,
                ResearchUiMetrics.FOOTER_PADDING_H));
        modeRow.add(modeLabel, BorderLayout.CENTER);
        block.add(modeRow, BorderLayout.SOUTH);
        ActionListener saveMission = new ActionListener() {
            public void actionPerformed(ActionEvent event) {
                saveMission();
            }
        };
        missionSaveButton.addActionListener(saveMission);
        missionField.addActionListener(saveMission);
        return block;
    }

    public void setMissionSaver(MissionSaver saver) {
        this.missionSaver = saver;
    }

    /**
     * Show the mission and the view's mode (D4): an empty {@code modeMessage} means editable; otherwise the
     * mission and the concept are read-only and the message says why. A mission the user is typing is kept.
     */
    public void renderMission(String mission, String modeMessage) {
        String value = mission == null ? "" : mission;
        boolean editable = modeMessage == null || modeMessage.isEmpty();
        if (!missionField.getText().equals(shownMission) && editable) {
            // the user is typing: keep the edit, only remember the stored value
            shownMission = value;
        } else {
            shownMission = value;
            missionField.setText(value);
        }
        missionField.setEditable(editable);
        if (frozen != !editable) {
            frozen = !editable;
            treeView.setPreview(frozen || browsingRevision >= 0);
            editor.setEditable(!frozen && lastProjection != null && browsingRevision < 0);
            updateControls();
        }
        missionSaveButton.setEnabled(editable && missionSaver != null);
        modeLabel.setText(editable ? " " : modeMessage);
        modeLabel.setVisible(!editable);
    }

    String missionText() {
        return missionField.getText();
    }

    boolean isFrozen() {
        return frozen;
    }

    boolean isJsonEditable() {
        return editor.isEditable();
    }

    String modeText() {
        return modeLabel.isVisible() ? modeLabel.getText() : "";
    }

    void typeMission(String text) {
        missionField.setText(text);
    }

    void clickSaveMission() {
        missionSaveButton.doClick();
    }

    private void saveMission() {
        if (missionSaver == null) {
            return;
        }
        String problem = missionSaver.save(missionField.getText());
        if (problem != null) {
            showError(problem);
        } else {
            shownMission = missionField.getText().trim();
        }
    }

    private void buildErrorOverlay() {
        errorOverlay.setPlateFill(ThemeColor.of("comic.buttonPlate"));
        errorOverlay.setAccentStripe(ComicPalette.defaultPalette().getAccentRed());
        errorOverlay.setLayout(new BorderLayout());
        errorText.setEditable(false);
        errorText.setOpaque(false);
        errorText.setLineWrap(true);
        errorText.setWrapStyleWord(true);
        errorText.setFont(ResearchUiTypography.semiBold(14.5f));
        errorText.setForeground(ComicPalette.defaultPalette().getInk());
        errorOverlay.add(errorText, BorderLayout.CENTER);
        errorOverlay.setVisible(false);
        errorHideTimer.setRepeats(false);
        // A click dismisses it immediately — nobody waits out a timer they have already read.
        java.awt.event.MouseAdapter dismiss = new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent event) {
                hideError();
            }
        };
        errorOverlay.addMouseListener(dismiss);
        errorText.addMouseListener(dismiss);
    }

    private void layoutLayers() {
        int width = layers.getWidth();
        int height = layers.getHeight();
        editorScroll.setBounds(0, 0, width, height);
        treeScroll.setBounds(0, 0, width, height);
        int overlayWidth = Math.max(120, width - 32);
        int overlayHeight = Math.min(Math.max(60, errorText.getPreferredSize().height + 28),
                Math.max(60, height - 24));
        errorOverlay.setBounds((width - overlayWidth) / 2, 12, overlayWidth, overlayHeight);
    }

    /** The owner wires persistence + history (absent in the clickdummy — editor stays view-only). */
    public void setEditActions(SaveHandler save, HistoryReader history) {
        setEditActions(save, history, null);
    }

    /** As above, plus the identity-preserving restore path for clean history browsing. */
    public void setEditActions(SaveHandler save, HistoryReader history, RestoreHandler restore) {
        this.saveHandler = save;
        this.historyReader = history;
        this.restoreHandler = restore;
        updateControls();
    }

    /**
     * Wire the tree editor's gestures (tree-editor slice 1+2): every hover action is ONE
     * ConceptBranchService call through the SAME seams the agent tools use — never past the
     * store. Errors pop in the shared comic overlay.
     */
    public void setTreeActions(ConceptTreeView.Actions actions,
                               ConceptTreeView.BlacklistSource blacklist,
                               ConceptTreeView.IdentityContext identity) {
        this.blacklistSource = blacklist;
        this.identityContext = identity;
        treeView.setActions(actions, new ConceptTreeView.ErrorSink() {
            public void error(String message) {
                showError(message);
            }
        });
    }

    /** Switch between the card tree and the raw JSON editor; unsaved states are protected. */
    private void setTreeMode(boolean tree) {
        if (browsingRevision >= 0) {
            showError("Save or discard the previewed revision first.");
            return;
        }
        if (tree && (dirty || searchView)) {
            showError("Save or discard your JSON edits (or leave the search) first.");
            return;
        }
        treeMode = tree;
        applyModeVisibility();
        updateControls();
    }

    private void applyModeVisibility() {
        treeScroll.setVisible(treeMode);
        editorScroll.setVisible(!treeMode);
        treeButton.setEnabled(!treeMode);
        jsonButton.setEnabled(treeMode);
    }

    /** Wire the manual ⟳ button to the owner's re-read (the same runnable the listeners use). */
    public void setRefreshAction(final Runnable refresh) {
        for (ActionListener old : refreshButton.getActionListeners()) {
            refreshButton.removeActionListener(old);
        }
        refreshButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent event) {
                refresh.run();
            }
        });
    }

    private JPanel buildFooter() {
        JPanel footer = new JPanel(new BorderLayout(8, 0));
        footer.setOpaque(false);
        footer.setBorder(BorderFactory.createEmptyBorder(
                ResearchUiMetrics.FOOTER_PADDING_V, ResearchUiMetrics.FOOTER_PADDING_H,
                ResearchUiMetrics.FOOTER_PADDING_V, ResearchUiMetrics.FOOTER_PADDING_H));

        // Layout ruling: reload LEFT, the history arrows CENTERED with Save/Discard, the status and the
        // "Visualize concept" button RIGHT (where reload used to be).
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        left.setOpaque(false);
        JPanel saveActions = new JPanel(new FlowLayout(FlowLayout.CENTER, 6, 0));
        saveActions.setOpaque(false);
        for (ComicButton button : new ComicButton[] {olderButton, newerButton,
                saveButton, discardButton}) {
            button.setFocusable(false);
            button.setBorder(BorderFactory.createEmptyBorder(3, 10, 3, 10));
        }
        saveActions.add(olderButton);
        saveActions.add(newerButton);
        saveActions.add(saveButton);
        saveActions.add(discardButton);
        saveButton.setToolTipText("Save: apply the edit (JSON) or restore the previewed "
                + "revision as the new head");
        discardButton.setToolTipText("Discard the edits / leave the revision preview");
        olderButton.setToolTipText("Preview the previous working revision");
        newerButton.setToolTipText("Preview the next working revision");
        refreshButton.setFocusable(false);
        refreshButton.setToolTipText("Reload view");
        refreshButton.setBorder(BorderFactory.createEmptyBorder(3, 10, 3, 10));
        left.add(refreshButton);
        footer.add(left, BorderLayout.WEST);
        footer.add(saveActions, BorderLayout.CENTER);

        JPanel status = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        status.setOpaque(false);
        statusLabel.setFont(ResearchUiTypography.regular(11.5f));
        status.add(statusLabel);
        status.add(visualizeSlot);
        footer.add(status, BorderLayout.EAST);
        return footer;
    }

    /** Holds the "Visualize concept" button (right of the status), set by the view contribution. */
    private final JPanel visualizeSlot = new JPanel(new BorderLayout());

    {
        visualizeSlot.setOpaque(false);
    }

    /** Places the "Visualize concept" button at the right end of the footer. */
    public void setVisualizeButton(javax.swing.JComponent button) {
        visualizeSlot.removeAll();
        if (button != null) {
            visualizeSlot.add(button, BorderLayout.CENTER);
        }
        visualizeSlot.revalidate();
        visualizeSlot.repaint();
    }

    private void wireActions() {
        saveButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent event) {
                saveEdits();
            }
        });
        discardButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent event) {
                dirty = false;
                leavePreview();
            }
        });
        olderButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent event) {
                browseHistory(-1);
            }
        });
        newerButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent event) {
                browseHistory(+1);
            }
        });
        searchBar.addSearchAction(new ActionListener() {
            public void actionPerformed(ActionEvent event) {
                applySearch(searchBar.getText().trim());
            }
        });
        treeButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent event) {
                setTreeMode(true);
            }
        });
        jsonButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent event) {
                setTreeMode(false);
            }
        });
        applyModeVisibility();
    }

    // ------------------------------------------------------------------ editing

    private void userTyped() {
        if (settingText || searchView) {
            return;
        }
        if (!dirty) {
            dirty = true;
            quietStatus();
            updateControls();
        }
    }

    private void saveEdits() {
        if (saveHandler == null) {
            showError("This session cannot save the concept (no concept service).");
            return;
        }
        if (browsingRevision >= 0 && !dirty && restoreHandler != null) {
            // Clean browse + Save = the identity-preserving RESTORE path: document and sidecar
            // return as the validated historical pair (same epoch, same UUIDs). Only a dirty
            // text is a raw save and deliberately opens a fresh identity epoch.
            String restoreError = restoreHandler.restore(browsingRevision);
            if (restoreError != null) {
                showError(restoreError);
                return;
            }
            browsingRevision = -1;
            treeView.setPreview(frozen);
            quietStatus();
            // The restore commit notifies the listeners; their refresh re-renders the head.
            return;
        }
        String error = saveHandler.save(editor.getText(), loadedRevision);
        if (error != null) {
            // The text STAYS — the user fixes it or discards explicitly; the store is untouched.
            showError(error);
            return;
        }
        dirty = false;
        browsingRevision = -1;
        quietStatus();
        // The commit notifies the change listeners, whose refresh re-renders with the
        // pretty-printed, persisted state; nothing to set by hand here.
    }

    private void browseHistory(int step) {
        if (historyReader == null) {
            showError("This session has no revision history.");
            return;
        }
        if (dirty) {
            showError("Save or discard your edits before browsing revisions.");
            return;
        }
        long shown = browsingRevision >= 0 ? browsingRevision : loadedRevision;
        long target = shown + step;
        if (target < 1 || target > loadedRevision) {
            showError(step < 0 ? "No earlier revision." : "Already at the newest revision.");
            return;
        }
        if (target == loadedRevision) {
            leavePreview();
            return;
        }
        String content = historyReader.content(target);
        if (content == null) {
            showError("No stored history for revision " + target
                    + " (older than the history feature).");
            return;
        }
        browsingRevision = target;
        searchView = false;
        if (treeMode) {
            // The preview stays IN the tree: read-only (its identities belong to another
            // snapshot), Save = the identity-preserving restore, Discard = back to head.
            treeView.render(content, java.util.Collections.<String>emptyList(), null);
            treeView.setPreview(true);
        } else {
            // Old revisions were committed COMPACT (only the raw save pretty-prints on
            // write) — browsing pretty-prints for DISPLAY only; the stored bytes and the
            // restore path (which reads the store) stay untouched.
            setEditorText(prettyForDisplay(content), true);
        }
        quietStatus();
        updateControls();
    }

    /** Back to the live head in the CURRENT mode (browse-forward past the end, Discard). */
    private void leavePreview() {
        browsingRevision = -1;
        treeView.setPreview(frozen);
        quietStatus();
        renderCurrent();
        renderTreeHead();
        updateControls();
    }

    private void renderTreeHead() {
        ConceptProjection projection = lastProjection;
        treeView.render(projection != null && projection.isReadable()
                        ? projection.getPrettyJson() : "",
                blacklistSource == null ? java.util.Collections.<String>emptyList()
                        : blacklistSource.terms(),
                identityContext);
    }

    /** Pretty-print a JSON document for display; unparseable content comes back raw. */
    static String prettyForDisplay(String documentJson) {
        try {
            return new com.google.gson.GsonBuilder().setPrettyPrinting().disableHtmlEscaping()
                    .create().toJson(com.google.gson.JsonParser.parseString(documentJson));
        } catch (RuntimeException notJson) {
            return documentJson;
        }
    }

    private void applySearch(String query) {
        if (query.isEmpty()) {
            if (searchView) {
                searchView = false;
                renderCurrent();
            }
            return;
        }
        if (dirty || browsingRevision >= 0) {
            showError("Save or discard your edits before searching.");
            return;
        }
        ConceptProjection projection = lastProjection;
        if (projection == null || !projection.isReadable()) {
            showError("Nothing searchable yet.");
            return;
        }
        if (treeMode) {
            treeMode = false; // the filter list renders in the editor surface
            applyModeVisibility();
        }
        searchView = true;
        setEditorText(filterText(projection.getPrettyJson(), query), false);
        updateControls();
    }

    // ------------------------------------------------------------------ rendering

    /**
     * Render one atomic snapshot. Unsaved edits, an open history view and the search view are
     * NEVER clobbered by a background refresh — only the revision witness updates.
     */
    public void render(ConceptProjection projection) {
        lastProjection = projection;
        // The tree has NO dirty state (every gesture commits) and shows the live head —
        // except while the user previews a browsed revision, which a background refresh
        // must not clobber (the revision witness still updates via renderCurrent).
        if (browsingRevision < 0) {
            renderTreeHead();
        }
        renderCurrent();
    }

    private void renderCurrent() {
        ConceptProjection projection = lastProjection;
        if (projection != null) {
            loadedRevision = projection.getWorkingRevision();
        }
        updateControls();
        if (dirty || browsingRevision >= 0 || searchView) {
            return; // the user's state wins until they save, discard or leave the view
        }
        if (projection == null) {
            setEditorText(EMPTY, false);
            return;
        }
        if (!projection.isReadable()) {
            // Never creative repair — but the raw text IS editable, so the user can fix it.
            showError("Concept not readable: " + projection.getDiagnosticText());
            setEditorText(projection.getPrettyJson(), true);
            return;
        }
        setEditorText(projection.getPrettyJson(), true);
    }

    private void setEditorText(String text, boolean editable) {
        settingText = true;
        try {
            editor.setText(text);
            editor.setEditable(editable && !frozen);
            editor.setCaretPosition(0);
        } finally {
            settingText = false;
        }
    }

    private void updateControls() {
        boolean editWired = saveHandler != null;
        boolean previewing = browsingRevision >= 0;
        // In the tree, Save/Discard exist ONLY while a revision preview is open (Save =
        // explicit restore of the shown state); the JSON mode keeps them permanently.
        saveButton.setVisible(!treeMode || previewing);
        discardButton.setVisible(!treeMode || previewing);
        saveButton.setEnabled(!frozen && editWired && (previewing || (!treeMode && dirty)));
        discardButton.setEnabled(dirty || previewing || searchView);
        olderButton.setEnabled(historyReader != null && !dirty);
        newerButton.setEnabled(historyReader != null && !dirty && previewing);
        quietStatus();
    }

    private void quietStatus() {
        statusLabel.setForeground(null);
        statusLabel.setEnabled(false); // quiet gray, diagnostic value only
        String text = "rev " + loadedRevision;
        if (browsingRevision >= 0) {
            text = "viewing rev " + browsingRevision + " of " + loadedRevision;
        } else if (dirty) {
            text = "rev " + loadedRevision + " — edited";
        } else if (searchView) {
            text = "search view";
        }
        statusLabel.setText(text);
    }

    /** The comic error overlay: pops over the text, fades out on its own, click dismisses. */
    private void showError(String message) {
        errorText.setText(message);
        errorOverlay.setVisible(true);
        layoutLayers();
        errorOverlay.repaint();
        errorHideTimer.restart();
    }

    private void hideError() {
        errorHideTimer.stop();
        errorOverlay.setVisible(false);
    }

    /** The filter result: every card path whose NAME contains the query, case-insensitive. */
    static String filterText(String documentJson, String query) {
        String needle = query.toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder();
        int matches = 0;
        for (List<String> path : ConceptTopicScanner.collectCardPaths(documentJson)) {
            if (!path.get(path.size() - 1).toLowerCase(Locale.ROOT).contains(needle)) {
                continue;
            }
            matches++;
            sb.append("  ");
            for (int index = 0; index < path.size(); index++) {
                if (index > 0) {
                    sb.append(" › ");
                }
                sb.append(path.get(index));
            }
            sb.append('\n');
        }
        String header = matches == 0
                ? "No card matches \"" + query + "\""
                : matches + " card" + (matches == 1 ? "" : "s") + " matching \"" + query + "\"";
        return header + "\n\n" + sb
                + "\nPress Enter with an empty search field to show the document again.";
    }

    public void dispose() {
        errorHideTimer.stop(); // the plain-text editor itself holds no external resources
    }
}
