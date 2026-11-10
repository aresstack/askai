package com.aresstack.askai.research.agent;

import com.aresstack.askai.research.outline.Outline;
import com.aresstack.askai.research.outline.OutlineBlock;
import com.aresstack.askai.research.outline.OutlineChapter;
import com.aresstack.askai.research.outline.OutlineEdits;
import com.aresstack.askai.research.outline.OutlineEntry;
import com.aresstack.askai.research.outline.OutlineJson;
import com.aresstack.askai.research.outline.OutlineSection;
import com.aresstack.askai.research.tabs.RowSignal;
import com.aresstack.askai.research.tabs.TabLists;
import com.aresstack.comiccontrols.control.ComicButton;
import com.aresstack.comiccontrols.control.ComicControls;
import com.aresstack.comiccontrols.control.ComicSearchBar;
import com.aresstack.comiccontrols.control.ComicSegmentedToggle;
import com.aresstack.comiccontrols.control.ComicTableSupport;
import com.aresstack.comiccontrols.control.WrapLayout;
import com.aresstack.comiccontrols.paint.ComposerIcons;
import com.aresstack.comiccontrols.paint.ResearchIcons;
import com.aresstack.comiccontrols.theme.ComicPalette;
import com.aresstack.comiccontrols.theme.ThemeColor;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableColumnModel;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * The Outline tab of the sidebar: the plan of the book as a list. The search bar is the topmost element (as
 * in the Chats tab), below it ONE view at full height, and at the bottom a switch between the three faces of
 * the same outline:
 *
 * <ul>
 *   <li><b>Outline</b> — chapters and sections in book order; the colour says where each one stands (grey =
 *       no brief yet, green = ready, yellow = stale or thin, red = brief failed). The icon buttons below
 *       rename, add, delete, move, indent and regenerate the selected entry.</li>
 *   <li><b>Text</b> — the outline to read, with the purpose and key points of every chapter; before an outline
 *       exists, the topics the research found so far.</li>
 *   <li><b>JSON</b> — the raw document with Save and Discard (unsaved text survives every refresh, the parser
 *       is strict and a save names the revision it was based on).</li>
 * </ul>
 *
 * Earlier revisions are previewed with the two arrow buttons; Save makes the previewed revision current,
 * Discard returns. Every write goes through {@link Actions}; the view holds no outline logic beyond the pure
 * {@link OutlineEdits} gestures. Nothing sits above the search bar, all buttons are icon buttons with a
 * tooltip. EDT only.
 */
public final class OutlineEditorView extends JPanel {

    private static final long serialVersionUID = 1L;

    /** The writes of the tab; each answers {@code null} when applied, otherwise the reason it was refused. */
    public interface Actions {
        String save(Outline draft, long expectedRevision);

        String restore(long revision, long expectedRevision);

        /** The working revision's JSON, or {@code null}. */
        String history(long revision);

        void generate();

        /** Stop the generation that runs (topic clustering); the previous state is kept. */
        default void cancelGenerate() {
        }

        /**
         * Plan PR 24: regenerate one chapter ({@code sections} 0 = new briefs, 2 to 6 = re-split a cluster
         * chapter). Runs off the EDT; returns the outcome line ("Brief not generated ..." on a failed brief).
         * @throws RuntimeException with the reason it was refused
         */
        default JobOutcome regenerateChapter(String chapterId, int sections, String comment) {
            throw new UnsupportedOperationException("Chapter regeneration is not available.");
        }

        /** Plan PR 24: adopt a suggested chapter (candidate cluster). Runs off the EDT. */
        default JobOutcome adoptCandidate(String clusterId) {
            throw new UnsupportedOperationException("Suggested chapters are not available.");
        }
    }

    /** A chapter operation's outcome: the chapter it touched, the line to show, whether its brief failed. */
    public static final class JobOutcome {
        final String chapterId;
        final String message;
        final boolean briefFailed;

        public JobOutcome(String chapterId, String message, boolean briefFailed) {
            this.chapterId = chapterId;
            this.message = message;
            this.briefFailed = briefFailed;
        }
    }

    /** One consistent read of the session for a repaint. */
    public static final class Snapshot {
        final Outline outline;
        final long revision;
        final String refusal;
        final String changeRequest;
        /** The outline to read as markdown; before an outline exists, the topics found so far. */
        final String text;
        /** The line of the generation run ("" when idle), whether it runs, whether it failed. */
        final String runLine;
        final boolean running;
        final boolean runFailed;

        public Snapshot(Outline outline, long revision, String refusal, String changeRequest) {
            this(outline, revision, refusal, changeRequest, null, "", false, false);
        }

        private Snapshot(Outline outline, long revision, String refusal, String changeRequest, String text,
                         String runLine, boolean running, boolean runFailed) {
            this.outline = outline == null ? Outline.EMPTY : outline;
            this.revision = revision;
            this.refusal = refusal;
            this.changeRequest = changeRequest == null ? "" : changeRequest.trim();
            this.text = text == null ? OutlineBlock.markdown(this.outline) : text;
            this.runLine = runLine == null ? "" : runLine.trim();
            this.running = running;
            this.runFailed = runFailed;
        }

        /** The same read with the text of the Text card (the stored outline or the topics found so far). */
        public Snapshot withText(String markdown) {
            return new Snapshot(outline, revision, refusal, changeRequest, markdown, runLine, running, runFailed);
        }

        /** The same read with the state of the generation run. */
        public Snapshot withRun(String line, boolean isRunning, boolean failed) {
            return new Snapshot(outline, revision, refusal, changeRequest, text, line, isRunning, failed);
        }
    }

    static final int CARD_OUTLINE = 0;
    static final int CARD_TEXT = 1;
    static final int CARD_JSON = 2;
    private static final String[] CARD_NAMES = {"outline", "text", "json"};

    private static final String NO_TEXT = "No outline yet. Generate it with the button below.";
    /** Edge length of the plus glyph, the size of the other line icons. */
    private static final int ICON_SIZE = 15;
    private static final int NUMBER_COLUMN_WIDTH = 44;
    private static final int TITLE_COLUMN_WIDTH = 240;
    private static final int STATUS_COLUMN_WIDTH = 90;
    private static final int STATUS_COLUMN_MIN_WIDTH = 64;
    /** How far a section's title steps in under its chapter. */
    private static final int SECTION_INDENT = 12;
    private static final int BUTTON_GAP = 4;
    private static final int FOOTER_GAP = 4;
    /** The bounds of the "split into sections" spinner of the regenerate dialog. */
    private static final int MIN_SPLIT_SECTIONS = 2;
    private static final int MAX_SPLIT_SECTIONS = 6;
    private static final int COMMENT_ROWS = 3;

    private final ComicPalette palette = ComicPalette.defaultPalette();
    private final TabLists lists = new TabLists(palette);

    private final RowsModel rowsModel = new RowsModel();
    /** The tooltip carries what the columns cannot: the full title, why a row is marked, what it is for. */
    private final JTable table = new JTable(rowsModel) {
        private static final long serialVersionUID = 1L;

        @Override
        public String getToolTipText(java.awt.event.MouseEvent event) {
            int row = rowAtPoint(event.getPoint());
            if (row < 0) {
                return null;
            }
            OutlineRow line = rowsModel.rowAt(row);
            String note = line.note();
            return "<html><b>" + TabLists.escape(line.number() + "  " + line.title()) + "</b>"
                    + (note.isEmpty() ? "" : "<br>" + TabLists.escape(note).replace("\n", "<br>")) + "</html>";
        }

        @Override
        public Component prepareRenderer(TableCellRenderer renderer, int row, int column) {
            Component cell = super.prepareRenderer(renderer, row, column);
            OutlineRow line = rowsModel.rowAt(row);
            cell.setFont(getFont().deriveFont(line.isChapter() ? Font.BOLD : Font.PLAIN));
            if (!line.isChapter() && cell instanceof JComponent
                    && convertColumnIndexToModel(column) == RowsModel.COLUMN_TITLE) {
                // A section sits one step in under its chapter.
                JComponent label = (JComponent) cell;
                label.setBorder(BorderFactory.createCompoundBorder(label.getBorder(),
                        BorderFactory.createEmptyBorder(0, SECTION_INDENT, 0, 0)));
            }
            return lists.dress(this, cell, isRowSelected(row), line.signal(), false, false);
        }
    };

    private final ComicSearchBar filterField = new ComicSearchBar("Filter outline…",
            "Filter chapters and sections by title (Enter applies)");
    private final JPanel cards = new JPanel(new CardLayout());
    private final JPanel textCard = new JPanel(new BorderLayout());
    private final JTextArea plainText = ComicControls.textArea();
    private Consumer<String> textSink;
    private final JTextArea jsonArea = ComicControls.textArea();
    private final ComicSegmentedToggle cardSwitch = new ComicSegmentedToggle("Outline", "Text", "JSON");

    private final ComicButton addChapterButton =
            ComicControls.iconButton(ComposerIcons.plus(ICON_SIZE), "Add chapter");
    private final ComicButton renameButton = ComicControls.iconButton(ComposerIcons.pencil(), "Rename");
    private final ComicButton addSectionButton =
            ComicControls.iconButton(ResearchIcons.addIndented(), "Add section");
    private final ComicButton deleteButton = ComicControls.iconButton(ResearchIcons.trash(), "Delete");
    private final ComicButton upButton = ComicControls.iconButton(ResearchIcons.arrowUp(), "Move up");
    private final ComicButton downButton = ComicControls.iconButton(ResearchIcons.arrowDown(), "Move down");
    private final ComicButton indentButton = ComicControls.iconButton(ResearchIcons.indent(), "Indent");
    private final ComicButton outdentButton = ComicControls.iconButton(ResearchIcons.outdent(), "Outdent");
    private final ComicButton regenerateButton =
            ComicControls.iconButton(ComposerIcons.refresh(), "Regenerate chapter");
    private final ComicButton retryBriefButton = ComicControls.iconButton(ResearchIcons.undo(), "Retry brief");
    private final ComicButton generateButton =
            ComicControls.iconButton(ResearchIcons.sparkle(), "Generate outline");
    private final ComicButton cancelButton = ComicControls.iconButton(ComposerIcons.stop(), "Stop generating");
    private final ComicButton suggestedButton =
            ComicControls.iconButton(ResearchIcons.lightBulb(), "Add suggested chapter");
    private final ComicButton olderButton =
            ComicControls.iconButton(ComposerIcons.chevronLeft(), "Preview the previous revision");
    private final ComicButton newerButton =
            ComicControls.iconButton(ComposerIcons.chevronRight(), "Preview the next revision");
    private final ComicButton saveButton = ComicControls.iconButton(ComposerIcons.floppyDisk(), "Save");
    private final ComicButton discardButton = ComicControls.iconButton(ComposerIcons.close(), "Discard");
    private final JPanel entryButtons = new JPanel(new WrapLayout(FlowLayout.LEFT, BUTTON_GAP, BUTTON_GAP));
    private final JPanel outlineButtons = new JPanel(new WrapLayout(FlowLayout.LEFT, BUTTON_GAP, BUTTON_GAP));
    /** What the user has to know right now: why the outline is frozen, the change request, the last outcome. */
    private final JTextArea notes = ComicControls.textArea();
    private final JLabel summary = lists.summaryLabel();

    /** Chapter ids whose last brief call failed ("Brief not generated"); cleared on a successful retry. */
    private final java.util.Set<String> briefFailures = new java.util.HashSet<String>();
    private boolean chapterJobRunning;
    private Actions actions;
    private Snapshot snapshot = new Snapshot(Outline.EMPTY, 0, "Not started yet.", "");
    private int card = CARD_OUTLINE;
    private boolean dirty;
    private long browsingRevision = -1;
    /** The outline of the previewed revision (shown read-only), or {@code null} on the live head. */
    private Outline browsingOutline;
    private boolean settingText;
    /** The revision the unsaved JSON text was based on; a save names it, so a concurrent change conflicts. */
    private long draftRevision;
    private String message = "";

    public OutlineEditorView() {
        super(new BorderLayout(0, TabLists.TAB_MARGIN));
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(TabLists.TAB_MARGIN, TabLists.TAB_MARGIN, TabLists.TAB_MARGIN,
                TabLists.TAB_MARGIN));

        // The search bar is the topmost element; "Add chapter" sits beside it, never above.
        JPanel top = new JPanel(new BorderLayout(BUTTON_GAP, 0));
        top.setOpaque(false);
        top.add(filterField, BorderLayout.CENTER);
        top.add(addChapterButton, BorderLayout.EAST);
        filterField.addSearchAction(e -> rebuildRows(selectedId()));

        configureTable();
        plainText.setEditable(false);
        plainText.setLineWrap(true);
        plainText.setWrapStyleWord(true);
        textCard.setOpaque(false);
        textCard.add(lists.scroll(plainText), BorderLayout.CENTER);
        jsonArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, jsonArea.getFont().getSize()));
        jsonArea.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) {
                edited();
            }

            public void removeUpdate(DocumentEvent e) {
                edited();
            }

            public void changedUpdate(DocumentEvent e) {
                edited();
            }
        });
        cards.setOpaque(false);
        cards.add(lists.scroll(table), CARD_NAMES[CARD_OUTLINE]);
        cards.add(textCard, CARD_NAMES[CARD_TEXT]);
        JScrollPane jsonScroll = lists.scroll(jsonArea);
        jsonScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        cards.add(jsonScroll, CARD_NAMES[CARD_JSON]);

        add(lists.plate(top, cards), BorderLayout.CENTER);
        add(buildFooter(), BorderLayout.SOUTH);
        wire();
        showCard(CARD_OUTLINE);
        render(snapshot);
    }

    // ------------------------------------------------------------------ layout

    private void configureTable() {
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        ComicTableSupport.style(table, palette);
        TableColumnModel columns = table.getColumnModel();
        columns.getColumn(RowsModel.COLUMN_NUMBER).setPreferredWidth(NUMBER_COLUMN_WIDTH);
        columns.getColumn(RowsModel.COLUMN_NUMBER).setMaxWidth(NUMBER_COLUMN_WIDTH);
        columns.getColumn(RowsModel.COLUMN_TITLE).setPreferredWidth(TITLE_COLUMN_WIDTH);
        columns.getColumn(RowsModel.COLUMN_STATUS).setPreferredWidth(STATUS_COLUMN_WIDTH);
        // The status stays readable in a narrow sidebar; the title gives way first.
        columns.getColumn(RowsModel.COLUMN_STATUS).setMinWidth(STATUS_COLUMN_MIN_WIDTH);
        table.getSelectionModel().addListSelectionListener(e -> updateButtons());
        table.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent event) {
                if (event.getClickCount() == 2 && renameButton.isEnabled()) {
                    renameButton.doClick();
                }
            }
        });
    }

    /** The bottom of the tab: the switch, the icon buttons, what the user has to know, one line of summary. */
    private JPanel buildFooter() {
        JPanel switchRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
        switchRow.setOpaque(false);
        switchRow.add(cardSwitch);
        cardSwitch.setSelectionListener(new IntConsumer() {
            public void accept(int index) {
                showCard(index);
            }
        });

        entryButtons.setOpaque(false);
        // First row: the structure of the selected entry. Second row: generating and the revisions.
        for (JButton button : new JButton[] {renameButton, addSectionButton, deleteButton, upButton, downButton,
                indentButton, outdentButton}) {
            entryButtons.add(button);
        }
        outlineButtons.setOpaque(false);
        for (JButton button : new JButton[] {generateButton, cancelButton, regenerateButton, retryBriefButton,
                suggestedButton, olderButton, newerButton, saveButton, discardButton}) {
            outlineButtons.add(button);
        }
        JPanel buttons = new JPanel(new BorderLayout());
        buttons.setOpaque(false);
        buttons.add(entryButtons, BorderLayout.NORTH);
        buttons.add(outlineButtons, BorderLayout.CENTER);

        notes.setEditable(false);
        notes.setLineWrap(true);
        notes.setWrapStyleWord(true);
        notes.setOpaque(false);
        notes.setBorder(BorderFactory.createEmptyBorder());
        notes.setFont(summary.getFont());
        JPanel lines = new JPanel(new BorderLayout(0, FOOTER_GAP));
        lines.setOpaque(false);
        lines.add(notes, BorderLayout.CENTER);
        lines.add(summary, BorderLayout.SOUTH);

        JPanel footer = new JPanel(new BorderLayout(0, FOOTER_GAP));
        footer.setOpaque(false);
        footer.add(switchRow, BorderLayout.NORTH);
        footer.add(buttons, BorderLayout.CENTER);
        footer.add(lines, BorderLayout.SOUTH);
        return footer;
    }

    /** Every button of the view (tests: icon-only, each with a tooltip). */
    JButton[] allButtons() {
        return new JButton[] {addChapterButton, renameButton, addSectionButton, deleteButton, upButton,
                downButton, indentButton, outdentButton, regenerateButton, retryBriefButton, generateButton,
                cancelButton, suggestedButton, olderButton, newerButton, saveButton, discardButton};
    }

    public void setActions(Actions actions) {
        this.actions = actions;
        updateButtons();
    }

    /**
     * Shows the Text card in the host's markdown viewer instead of the plain fallback.
     *
     * @param component the viewer
     * @param sink      takes the markdown to show
     */
    public void setTextView(JComponent component, Consumer<String> sink) {
        textCard.removeAll();
        textCard.add(component, BorderLayout.CENTER);
        textSink = sink;
        sink.accept(plainText.getText());
        textCard.revalidate();
        textCard.repaint();
    }

    /** Repaint from the session. Unsaved JSON text and a previewed revision are never replaced. */
    public void render(Snapshot next) {
        String selected = selectedId();
        snapshot = next;
        if (browsingRevision > next.revision) {
            leavePreview();
        }
        rebuildRows(selected);
        if (!dirty && browsingRevision < 0) {
            setJson(OutlineJson.write(next.outline));
        }
        if (dirty && !editable()) {
            message = "Unsaved text kept.";
        }
        showText();
        updateButtons();
    }

    // ------------------------------------------------------------------ for tests

    String jsonText() {
        return jsonArea.getText();
    }

    void typeJson(String text) {
        selectCard(CARD_JSON);
        jsonArea.setText(text);
    }

    boolean isDirty() {
        return dirty;
    }

    boolean isEditable() {
        return editable();
    }

    /** The last outcome line ("Saved.", a refusal, ...). */
    String statusText() {
        return message;
    }

    String summaryText() {
        return summary.getText();
    }

    /** The notes above the summary ("" while there is nothing to say). */
    String notesText() {
        return notes.isVisible() ? notes.getText() : "";
    }

    /** The text of the Text card. */
    String shownText() {
        return plainText.getText();
    }

    int rowCount() {
        return rowsModel.getRowCount();
    }

    Object cellAt(int row, int column) {
        return rowsModel.getValueAt(row, column);
    }

    RowSignal signalAt(int row) {
        return rowsModel.rowAt(row).signal();
    }

    int shownCard() {
        return card;
    }

    void selectCard(int index) {
        cardSwitch.setSelectedIndex(index);
        showCard(index);
    }

    void typeFilter(String text) {
        filterField.setText(text);
        rebuildRows(selectedId());
    }

    void select(String id) {
        int row = rowsModel.indexOf(id);
        if (row >= 0) {
            table.getSelectionModel().setSelectionInterval(row, row);
        } else {
            table.clearSelection();
        }
    }

    /** A button by its accessible name (the buttons show icons; the name says what they do). */
    JButton button(String text) {
        for (JButton b : allButtons()) {
            if (text.equals(b.getAccessibleContext().getAccessibleName())) {
                return b;
            }
        }
        return null;
    }

    /** The revision that is previewed, or {@code -1} on the live head. */
    long previewedRevision() {
        return browsingRevision;
    }

    /** The gesture without the dialog (tests). */
    String applyGesture(Outline draft) {
        return save(draft);
    }

    // ------------------------------------------------------------------ internals

    private boolean editable() {
        return snapshot.refusal == null;
    }

    /** The outline the list and the text show: the previewed revision, otherwise the live head. */
    private Outline shownOutline() {
        return browsingOutline != null ? browsingOutline : snapshot.outline;
    }

    private void wire() {
        generateButton.addActionListener(e -> {
            if (actions != null) {
                actions.generate();
            }
        });
        cancelButton.addActionListener(e -> {
            if (actions != null) {
                actions.cancelGenerate();
            }
        });
        regenerateButton.addActionListener(e -> regenerateSelected());
        retryBriefButton.addActionListener(e -> {
            OutlineEntry entry = selectedEntry();
            if (entry instanceof OutlineChapter) {
                runChapterJob(() -> actions.regenerateChapter(entry.getId(), 0, ""));
            }
        });
        suggestedButton.addActionListener(e -> adoptSuggested());
        renameButton.addActionListener(e -> {
            OutlineEntry entry = selectedEntry();
            if (entry == null) {
                return;
            }
            String title = (String) JOptionPane.showInputDialog(this, "New title", "Rename",
                    JOptionPane.PLAIN_MESSAGE, null, null, entry.getTitle());
            if (title != null) {
                gesture(() -> OutlineEdits.rename(snapshot.outline, entry.getId(), title));
            }
        });
        addChapterButton.addActionListener(e -> {
            String title = JOptionPane.showInputDialog(this, "Chapter title", "Add chapter",
                    JOptionPane.PLAIN_MESSAGE);
            if (title != null) {
                OutlineEntry entry = selectedEntry();
                String after = entry == null ? null
                        : entry instanceof OutlineChapter ? entry.getId() : entry.getParentId();
                gesture(() -> OutlineEdits.addChapter(snapshot.outline, after, title));
            }
        });
        addSectionButton.addActionListener(e -> {
            OutlineEntry entry = selectedEntry();
            if (entry == null) {
                return;
            }
            String title = JOptionPane.showInputDialog(this, "Section title", "Add section",
                    JOptionPane.PLAIN_MESSAGE);
            if (title != null) {
                String chapter = entry instanceof OutlineChapter ? entry.getId() : entry.getParentId();
                gesture(() -> OutlineEdits.addSection(snapshot.outline, chapter, title));
            }
        });
        deleteButton.addActionListener(e -> {
            OutlineEntry entry = selectedEntry();
            if (entry == null) {
                return;
            }
            String what = entry instanceof OutlineChapter ? "the chapter \"" + entry.getTitle()
                    + "\" with its sections" : "the section \"" + entry.getTitle() + "\"";
            if (JOptionPane.showConfirmDialog(this, "Delete " + what + "?", "Delete",
                    JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) {
                gesture(() -> OutlineEdits.delete(snapshot.outline, entry.getId()));
            }
        });
        upButton.addActionListener(e -> moveSelected(-1));
        downButton.addActionListener(e -> moveSelected(1));
        indentButton.addActionListener(e -> {
            OutlineEntry entry = selectedEntry();
            if (entry != null) {
                gesture(() -> OutlineEdits.indent(snapshot.outline, entry.getId()));
            }
        });
        outdentButton.addActionListener(e -> {
            OutlineEntry entry = selectedEntry();
            if (entry != null) {
                gesture(() -> OutlineEdits.outdent(snapshot.outline, entry.getId()));
            }
        });
        saveButton.addActionListener(e -> saveJson());
        discardButton.addActionListener(e -> discard());
        olderButton.addActionListener(e -> browse(-1));
        newerButton.addActionListener(e -> browse(1));
    }

    private void regenerateSelected() {
        OutlineEntry entry = selectedEntry();
        if (!(entry instanceof OutlineChapter)) {
            return;
        }
        OutlineChapter chapter = (OutlineChapter) entry;
        JTextArea comment = ComicControls.textArea();
        comment.setRows(COMMENT_ROWS);
        comment.setLineWrap(true);
        comment.setWrapStyleWord(true);
        boolean clusters = com.aresstack.askai.research.outline.brief.ChapterRegenerator
                .isSubClusterChapter(chapter);
        javax.swing.JSpinner count = ComicControls.integerSpinner(
                Math.max(MIN_SPLIT_SECTIONS, Math.min(MAX_SPLIT_SECTIONS, chapter.getSections().size())),
                MIN_SPLIT_SECTIONS, MAX_SPLIT_SECTIONS, 1, 2);
        javax.swing.JCheckBox resplit = ComicControls.checkBox("Split into sections");
        resplit.setEnabled(clusters);
        count.setEnabled(false);
        resplit.addActionListener(ev -> count.setEnabled(resplit.isSelected()));
        JPanel form = new JPanel(new BorderLayout(BUTTON_GAP, BUTTON_GAP));
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, BUTTON_GAP, 0));
        row.add(resplit);
        row.add(count);
        form.add(new JLabel("Comment (optional)"), BorderLayout.NORTH);
        form.add(new JScrollPane(comment), BorderLayout.CENTER);
        form.add(row, BorderLayout.SOUTH);
        int answer = JOptionPane.showConfirmDialog(this, form, "Regenerate \"" + chapter.getTitle() + "\"",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (answer == JOptionPane.OK_OPTION) {
            int sections = resplit.isSelected() ? (Integer) count.getValue() : 0;
            runChapterJob(() -> actions.regenerateChapter(chapter.getId(), sections, comment.getText().trim()));
        }
    }

    private void adoptSuggested() {
        List<com.aresstack.askai.research.outline.OutlineCandidate> candidates = snapshot.outline.getCandidates();
        if (candidates.isEmpty()) {
            return;
        }
        String[] titles = new String[candidates.size()];
        for (int i = 0; i < titles.length; i++) {
            com.aresstack.askai.research.outline.OutlineCandidate candidate = candidates.get(i);
            titles[i] = (i + 1) + ". " + candidate.getSuggestedTitle() + " (" + candidate.getSourceCount()
                    + " sources)";
        }
        Object chosen = JOptionPane.showInputDialog(this, "Suggested chapter", "Add suggested chapter",
                JOptionPane.PLAIN_MESSAGE, null, titles, titles[0]);
        if (chosen != null) {
            int index = java.util.Arrays.asList(titles).indexOf(chosen);
            String clusterId = candidates.get(index).getClusterId();
            runChapterJob(() -> actions.adoptCandidate(clusterId));
        }
    }

    private interface Gesture {
        Outline draft();
    }

    private void gesture(Gesture gesture) {
        Outline draft;
        try {
            draft = gesture.draft();
        } catch (IllegalArgumentException refused) {
            say(refused.getMessage());
            return;
        }
        String id = selectedId();
        String refusal = save(draft);
        if (refusal == null && id != null) {
            select(id);
        }
    }

    private void moveSelected(int delta) {
        OutlineEntry entry = selectedEntry();
        if (entry != null) {
            gesture(() -> OutlineEdits.move(snapshot.outline, entry.getId(), delta));
        }
    }

    private String save(Outline draft) {
        return save(draft, snapshot.revision);
    }

    private String save(Outline draft, long expectedRevision) {
        String refusal = actions == null ? "This session has no outline store."
                : actions.save(draft, expectedRevision);
        say(refusal == null ? "Saved." : refusal);
        return refusal;
    }

    private void saveJson() {
        if (browsingRevision >= 0 && !dirty) {
            restorePreviewed();
            return;
        }
        Outline draft;
        try {
            draft = OutlineJson.read(jsonArea.getText());
            OutlineEdits.validate(draft);
        } catch (IllegalArgumentException invalid) {
            say(invalid.getMessage());
            return;
        }
        String refusal = save(draft, draftRevision);
        if (refusal == null) {
            dirty = false;
            setJson(OutlineJson.write(snapshot.outline));
            updateButtons();
        }
    }

    private void discard() {
        dirty = false;
        boolean wasPreview = browsingRevision >= 0;
        leavePreview();
        setJson(OutlineJson.write(snapshot.outline));
        rebuildRows(selectedId());
        showText();
        say(wasPreview ? "Back on the current revision." : "Discarded.");
        updateButtons();
    }

    /** Step through the working revisions: {@code -1} = older, {@code +1} = newer; the newest is the live head. */
    private void browse(int delta) {
        if (actions == null || dirty) {
            return;
        }
        long from = browsingRevision >= 0 ? browsingRevision : snapshot.revision;
        long target = from + delta;
        if (target < 1 || target > snapshot.revision) {
            return;
        }
        if (target == snapshot.revision) {
            discard();
            return;
        }
        String json = actions.history(target);
        Outline outline;
        try {
            outline = json == null ? null : OutlineJson.read(json);
        } catch (IllegalArgumentException unreadable) {
            outline = null;
        }
        if (outline == null) {
            say("Revision " + target + " is not readable.");
            return;
        }
        browsingRevision = target;
        browsingOutline = outline;
        setJson(json);
        rebuildRows(selectedId());
        showText();
        say("Previewing revision " + target + ". Save makes it current, Discard returns.");
        updateButtons();
    }

    private void restorePreviewed() {
        if (browsingRevision < 0 || actions == null || dirty) {
            return;
        }
        long revision = browsingRevision;
        String refusal = actions.restore(revision, snapshot.revision);
        if (refusal == null) {
            leavePreview();
            setJson(OutlineJson.write(snapshot.outline));
            rebuildRows(selectedId());
            showText();
        }
        say(refusal == null ? "Restored revision " + revision + "." : refusal);
        updateButtons();
    }

    private void leavePreview() {
        browsingRevision = -1;
        browsingOutline = null;
    }

    private void showCard(int index) {
        card = index;
        ((CardLayout) cards.getLayout()).show(cards, CARD_NAMES[index]);
        if (cardSwitch.getSelectedIndex() != index) {
            cardSwitch.setSelectedIndex(index);
        }
        updateButtons();
    }

    private void showText() {
        String markdown = browsingOutline != null ? OutlineBlock.markdown(browsingOutline) : snapshot.text;
        String text = markdown == null || markdown.trim().isEmpty() ? NO_TEXT : markdown;
        if (text.equals(plainText.getText())) {
            return;
        }
        plainText.setText(text);
        plainText.setCaretPosition(0);
        if (textSink != null) {
            textSink.accept(text);
        }
    }

    private void edited() {
        if (!settingText && !dirty) {
            dirty = true;
            draftRevision = snapshot.revision;
            updateButtons();
        }
    }

    private void setJson(String text) {
        settingText = true;
        try {
            if (!text.equals(jsonArea.getText())) {
                jsonArea.setText(text);
                jsonArea.setCaretPosition(0);
            }
        } finally {
            settingText = false;
        }
    }

    /** Runs a chapter operation off the EDT; the notes show its outcome or refusal. */
    private void runChapterJob(final java.util.concurrent.Callable<JobOutcome> job) {
        if (actions == null || chapterJobRunning) {
            return;
        }
        chapterJobRunning = true;
        say("Working on the outline...");
        updateButtons();
        new javax.swing.SwingWorker<JobOutcome, Void>() {
            @Override
            protected JobOutcome doInBackground() throws Exception {
                return job.call();
            }

            @Override
            protected void done() {
                chapterJobRunning = false;
                try {
                    JobOutcome outcome = get();
                    if (outcome != null && outcome.chapterId != null) {
                        if (outcome.briefFailed) {
                            briefFailures.add(outcome.chapterId);
                        } else {
                            briefFailures.remove(outcome.chapterId);
                        }
                    }
                    say(outcome == null || outcome.message == null ? "" : outcome.message);
                    rebuildRows(selectedId());
                } catch (java.util.concurrent.ExecutionException failed) {
                    Throwable cause = failed.getCause() == null ? failed : failed.getCause();
                    say(cause.getMessage() == null ? cause.toString() : cause.getMessage());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                updateButtons();
            }
        }.execute();
    }

    /** Whether the last brief call of this chapter failed (the "Brief not generated" marker). */
    boolean briefNotGenerated(String chapterId) {
        return briefFailures.contains(chapterId);
    }

    /** Marks a chapter's brief as failed without running the job (tests). */
    void markBriefFailed(String chapterId) {
        briefFailures.add(chapterId);
        rebuildRows(selectedId());
        updateButtons();
    }

    private void updateButtons() {
        boolean wired = actions != null;
        boolean editable = editable() && wired;
        OutlineEntry entry = selectedEntry();
        boolean chapter = entry instanceof OutlineChapter;
        boolean preview = browsingRevision >= 0;
        boolean clean = !dirty && !preview;
        boolean onList = card == CARD_OUTLINE;
        entryButtons.setVisible(onList);
        addChapterButton.setEnabled(editable && clean);
        renameButton.setEnabled(editable && clean && entry != null);
        addSectionButton.setEnabled(editable && clean && entry != null);
        deleteButton.setEnabled(editable && clean && entry != null);
        upButton.setEnabled(editable && clean && entry != null);
        downButton.setEnabled(editable && clean && entry != null);
        indentButton.setEnabled(editable && clean && chapter
                && snapshot.outline.getChapters().indexOf(entry) > 0);
        outdentButton.setEnabled(editable && clean && entry instanceof OutlineSection);
        regenerateButton.setEnabled(editable && clean && onList && chapter && !chapterJobRunning);
        retryBriefButton.setVisible(onList && chapter && briefFailures.contains(entry.getId()));
        retryBriefButton.setEnabled(editable && clean && !chapterJobRunning);
        generateButton.setEnabled(editable && !snapshot.running);
        cancelButton.setVisible(snapshot.running);
        cancelButton.setEnabled(wired && snapshot.running);
        suggestedButton.setEnabled(editable && clean && !chapterJobRunning
                && !snapshot.outline.getCandidates().isEmpty());
        long shown = preview ? browsingRevision : snapshot.revision;
        olderButton.setEnabled(wired && !dirty && shown > 1);
        newerButton.setEnabled(wired && !dirty && preview);
        jsonArea.setEditable(editable && !preview);
        // Save and Discard appear only when there is something to save or to leave.
        saveButton.setVisible(dirty || preview);
        discardButton.setVisible(dirty || preview);
        saveButton.setEnabled(editable && (dirty || preview));
        discardButton.setEnabled(dirty || preview);
        saveButton.setToolTipText(preview && !dirty ? "Make the previewed revision current" : "Save the JSON text");
        discardButton.setToolTipText(preview && !dirty ? "Leave the preview" : "Discard the unsaved text");
        updateLines();
    }

    private void say(String line) {
        message = line == null ? "" : line.trim();
        updateLines();
    }

    /** The notes (frozen reason, change request, generation run, last outcome) and the summary line. */
    private void updateLines() {
        List<String> lines = new ArrayList<String>();
        if (!editable()) {
            lines.add(snapshot.refusal);
        }
        if (!snapshot.changeRequest.isEmpty()) {
            lines.add("Change request: " + snapshot.changeRequest);
        }
        if (!snapshot.runLine.isEmpty()) {
            lines.add(snapshot.runLine);
        }
        if (!message.isEmpty()) {
            lines.add(message);
        }
        notes.setText(String.join("\n", lines));
        notes.setForeground(snapshot.runFailed ? ThemeColor.of("research.settingsErrorText") : lists.mutedInk());
        notes.setVisible(!lines.isEmpty());
        summary.setText(summaryOf(shownOutline(), snapshot.revision, browsingRevision));
        revalidate();
        repaint();
    }

    /** "7 chapters · 23 sections · revision 4", or the previewed revision. */
    static String summaryOf(Outline outline, long revision, long previewed) {
        int chapters = outline.getChapters().size();
        int sections = 0;
        for (OutlineChapter chapter : outline.getChapters()) {
            sections += chapter.getSections().size();
        }
        if (chapters == 0 && revision <= 0) {
            return "No outline yet";
        }
        return chapters + (chapters == 1 ? " chapter" : " chapters") + " · " + sections
                + (sections == 1 ? " section" : " sections") + " · "
                + (previewed >= 0 ? "preview of revision " + previewed + " of " + revision : "revision " + revision);
    }

    private void rebuildRows(String selected) {
        rowsModel.setRows(OutlineRow.rows(shownOutline(), briefFailures, filterField.getText()));
        if (selected != null) {
            select(selected);
        }
        updateLines();
    }

    private OutlineEntry selectedEntry() {
        int row = table.getSelectedRow();
        return row < 0 || row >= rowsModel.getRowCount() ? null : rowsModel.rowAt(row).entry();
    }

    private String selectedId() {
        OutlineEntry entry = selectedEntry();
        return entry == null ? null : entry.getId();
    }

    /** The list: number in the book, title, where the entry stands. */
    private static final class RowsModel extends AbstractTableModel {
        private static final long serialVersionUID = 1L;
        static final int COLUMN_NUMBER = 0;
        static final int COLUMN_TITLE = 1;
        static final int COLUMN_STATUS = 2;

        private final String[] columns = {"#", "Title", "Status"};
        private List<OutlineRow> rows = new ArrayList<OutlineRow>();

        void setRows(List<OutlineRow> next) {
            rows = new ArrayList<OutlineRow>(next);
            fireTableDataChanged();
        }

        OutlineRow rowAt(int row) {
            return rows.get(row);
        }

        int indexOf(String id) {
            for (int i = 0; i < rows.size(); i++) {
                if (rows.get(i).entry().getId().equals(id)) {
                    return i;
                }
            }
            return -1;
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return columns.length;
        }

        @Override
        public String getColumnName(int column) {
            return columns[column];
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            OutlineRow row = rows.get(rowIndex);
            switch (columnIndex) {
                case COLUMN_NUMBER:
                    return row.number();
                case COLUMN_TITLE:
                    return row.title();
                default:
                    return row.status();
            }
        }
    }
}
