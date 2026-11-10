package com.aresstack.askai.research.sources;

import com.aresstack.askai.research.plan.SearchPlanEntry;
import com.aresstack.askai.research.tabs.TabLists;
import com.aresstack.comiccontrols.control.ComicButton;
import com.aresstack.comiccontrols.control.ComicControls;
import com.aresstack.comiccontrols.control.ComicSearchBar;
import com.aresstack.comiccontrols.control.ComicSectionPanel;
import com.aresstack.comiccontrols.control.ComicSegmentedToggle;
import com.aresstack.comiccontrols.control.ComicTableSupport;
import com.aresstack.comiccontrols.control.WrapLayout;
import com.aresstack.comiccontrols.paint.ComposerIcons;
import com.aresstack.comiccontrols.paint.ResearchIcons;
import com.aresstack.comiccontrols.theme.ComicPalette;
import com.aresstack.comiccontrols.theme.ResearchUiTypography;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableColumnModel;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

/**
 * The Sources tab of the sidebar: a list, nothing else. The search bar is the topmost element (as in the
 * Chats tab), below it ONE table at full height, and at the bottom a switch between the two things the
 * research produces:
 *
 * <ul>
 *   <li><b>Results</b> — the sources that were found. Selecting one opens it in the reader in the main area
 *       ({@link SourceFullTextView}); everything about a single source (address, author, sections, ratings,
 *       texts, comment) lives there, not in the sidebar.</li>
 *   <li><b>Searches</b> — the planned web searches (pending, running, done), with the icon buttons that run,
 *       stop, edit, drop or repeat them.</li>
 * </ul>
 *
 * Both tables speak one colour code ({@link SourceRowState}): grey = still to do, blue = running, green =
 * done, yellow = read after a solved security check, red = blocked or failed. Rows are ordered by that state.
 * All buttons are icon buttons with a tooltip at the bottom; nothing sits above the search bar.
 */
public final class ResearchSourcesView extends JPanel {

    private static final long serialVersionUID = 1L;

    static final int CARD_RESULTS = 0;
    static final int CARD_SEARCHES = 1;
    private static final String[] CARD_NAMES = {"results", "searches"};

    /** Edge length of the plus glyph, the size of the other line icons. */
    private static final int ICON_SIZE = 15;
    private static final int STAR_COLUMN_WIDTH = 28;
    private static final int TITLE_COLUMN_WIDTH = 240;
    private static final int SITE_COLUMN_WIDTH = 110;
    private static final int STATUS_COLUMN_WIDTH = 90;
    private static final int STATUS_COLUMN_MIN_WIDTH = 64;
    private static final int SCORE_COLUMN_WIDTH = 52;
    private static final int COUNT_COLUMN_WIDTH = 64;

    private final ResearchSourceRepository repository;
    private final Set<String> knownSectionIds;
    private final ComicPalette palette = ComicPalette.defaultPalette();
    private final TabLists lists = new TabLists(palette);

    private final ResultsTableModel resultsModel = new ResultsTableModel();
    /** Cell tooltips carry what the columns cannot: the full title, the address and the security-check note. */
    private final JTable resultsTable = new JTable(resultsModel) {
        private static final long serialVersionUID = 1L;

        @Override
        public String getToolTipText(java.awt.event.MouseEvent event) {
            int viewRow = rowAtPoint(event.getPoint());
            if (viewRow < 0) {
                return null;
            }
            SourceTableRow row = resultsModel.rowAt(convertRowIndexToModel(viewRow));
            String access = accessNote(row.access());
            return "<html><b>" + escape(row.title()) + "</b><br>"
                    + escape(SourceTableRow.addressOf(row.source()))
                    + (access.isEmpty() ? "" : "<br><i>" + escape(access) + "</i>") + "</html>";
        }

        @Override
        public Component prepareRenderer(TableCellRenderer renderer, int row, int column) {
            return dressRow(this, super.prepareRenderer(renderer, row, column), isRowSelected(row),
                    resultsModel.rowAt(convertRowIndexToModel(row)).state(),
                    convertColumnIndexToModel(column) == ResultsTableModel.COLUMN_STAR);
        }
    };

    private final SearchesTableModel searchesModel = new SearchesTableModel();
    private final JTable searchesTable = new JTable(searchesModel) {
        private static final long serialVersionUID = 1L;

        @Override
        public String getToolTipText(java.awt.event.MouseEvent event) {
            int viewRow = rowAtPoint(event.getPoint());
            if (viewRow < 0) {
                return null;
            }
            SearchPlanEntry entry = searchesModel.rowAt(convertRowIndexToModel(viewRow));
            return "<html><b>" + escape(entry.getQuery()) + "</b><br>" + escape(entry.getNodeLabel())
                    + "</html>";
        }

        @Override
        public Component prepareRenderer(TableCellRenderer renderer, int row, int column) {
            return dressRow(this, super.prepareRenderer(renderer, row, column), isRowSelected(row),
                    SourceRowState.of(searchesModel.rowAt(convertRowIndexToModel(row)).getStatus()), false);
        }
    };

    private final ComicSearchBar filterField = new ComicSearchBar("Filter sources…",
            "Filter by title or address; in Searches by query or topic (Enter applies)");
    private final JPanel cards = new JPanel(new CardLayout());
    private final ComicSegmentedToggle cardSwitch = new ComicSegmentedToggle("Results", "Searches");
    private final JPanel switchRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
    private final JPanel searchButtons = new JPanel(new WrapLayout(FlowLayout.LEFT, 4, 0));
    final ComicButton run = ComicControls.iconButton(ComposerIcons.play(), "Run the planned searches");
    final ComicButton stop = ComicControls.iconButton(ComposerIcons.stop(), "Stop the running search");
    final ComicButton again = ComicControls.iconButton(ComposerIcons.refresh(), "Run all searches again");
    final ComicButton edit = ComicControls.iconButton(ComposerIcons.pencil(), "Edit the selected search");
    /** One button for the selected search: drop it while it waits, run it again once it ended. */
    final ComicButton dropOrRerun = ComicControls.iconButton(ResearchIcons.trash(), "Drop the selected search");
    private final JLabel summary = new JLabel(" ");
    private final JPanel visualizeSlot = new JPanel(new BorderLayout());

    private SourceSearchPlan plan;
    private int card = CARD_RESULTS;
    private String selectedSourceId;
    private Consumer<ResearchSourceRecord> selectionPublisher;
    /** Websites that demanded a manual security check (wired by the contribution; empty by default). */
    private Supplier<SourceAccessLog> accessLog = new Supplier<SourceAccessLog>() {
        public SourceAccessLog get() {
            return SourceAccessLog.EMPTY;
        }
    };

    public ResearchSourcesView(ResearchSourceRepository repository, Set<String> knownSectionIds) {
        super(new BorderLayout(0, 6));
        this.repository = repository;
        this.knownSectionIds = knownSectionIds;
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

        // The search bar is the topmost element; the add button sits beside it, never above.
        JPanel top = new JPanel(new BorderLayout(4, 0));
        top.setOpaque(false);
        top.add(filterField, BorderLayout.CENTER);
        filterField.addSearchAction(e -> refresh());
        ComicButton addSource = ComicControls.iconButton(ComposerIcons.plus(ICON_SIZE),
                "Add your own source (raw HTML, plain text or PDF) to the research corpus");
        addSource.addActionListener(e -> openImportDialog());
        top.add(addSource, BorderLayout.EAST);

        configureResultsTable();
        configureSearchesTable();
        cards.setOpaque(false);
        cards.add(lists.scroll(resultsTable), CARD_NAMES[CARD_RESULTS]);
        cards.add(lists.scroll(searchesTable), CARD_NAMES[CARD_SEARCHES]);

        // Search and list sit on ONE quiet plate, like the other sidebar tabs.
        ComicSectionPanel plate = lists.plate(top, cards);

        add(plate, BorderLayout.CENTER);
        add(buildFooter(), BorderLayout.SOUTH);
        wireSearchButtons();
        showCard(CARD_RESULTS);
        refresh();
    }

    // ------------------------------------------------------------------ layout

    /** The bottom of the tab: the Results/Searches switch, the icon buttons, one line of summary. */
    private JPanel buildFooter() {
        switchRow.setOpaque(false);
        switchRow.add(cardSwitch);
        switchRow.setVisible(false); // shown once a search plan is wired
        cardSwitch.setSelectionListener(new IntConsumer() {
            public void accept(int index) {
                showCard(index);
            }
        });

        searchButtons.setOpaque(false);
        for (ComicButton button : new ComicButton[] {run, stop, again, edit, dropOrRerun}) {
            searchButtons.add(button);
        }
        visualizeSlot.setOpaque(false);
        JPanel actionRow = new JPanel(new BorderLayout(6, 0));
        actionRow.setOpaque(false);
        actionRow.add(searchButtons, BorderLayout.CENTER);
        actionRow.add(visualizeSlot, BorderLayout.EAST);

        summary.setFont(ResearchUiTypography.regular(11.5f));
        summary.setForeground(lists.mutedInk());

        JPanel footer = new JPanel(new BorderLayout(0, 4));
        footer.setOpaque(false);
        footer.add(switchRow, BorderLayout.NORTH);
        footer.add(actionRow, BorderLayout.CENTER);
        footer.add(summary, BorderLayout.SOUTH);
        return footer;
    }

    private void configureResultsTable() {
        resultsTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        // The model order IS the state order (to do, done, check solved, blocked); a header click re-sorts.
        resultsTable.setAutoCreateRowSorter(true);
        ComicTableSupport.style(resultsTable, palette);
        TableColumnModel columns = resultsTable.getColumnModel();
        columns.getColumn(ResultsTableModel.COLUMN_STAR).setMaxWidth(STAR_COLUMN_WIDTH);
        columns.getColumn(ResultsTableModel.COLUMN_TITLE).setPreferredWidth(TITLE_COLUMN_WIDTH);
        columns.getColumn(ResultsTableModel.COLUMN_SITE).setPreferredWidth(SITE_COLUMN_WIDTH);
        columns.getColumn(ResultsTableModel.COLUMN_STATUS).setPreferredWidth(STATUS_COLUMN_WIDTH);
        columns.getColumn(ResultsTableModel.COLUMN_SCORE).setPreferredWidth(SCORE_COLUMN_WIDTH);
        // Status and score stay readable in a narrow sidebar; title and website give way first.
        columns.getColumn(ResultsTableModel.COLUMN_STATUS).setMinWidth(STATUS_COLUMN_MIN_WIDTH);
        columns.getColumn(ResultsTableModel.COLUMN_SCORE).setMinWidth(SCORE_COLUMN_WIDTH);

        DefaultTableCellRenderer starRenderer = new DefaultTableCellRenderer();
        starRenderer.setHorizontalAlignment(SwingConstants.CENTER);
        columns.getColumn(ResultsTableModel.COLUMN_STAR).setCellRenderer(starRenderer);
        columns.getColumn(ResultsTableModel.COLUMN_SCORE).setCellRenderer(new DefaultTableCellRenderer() {
            private static final long serialVersionUID = 1L;

            {
                setHorizontalAlignment(SwingConstants.RIGHT);
            }

            @Override
            protected void setValue(Object value) {
                setText(value instanceof Double ? String.format(Locale.ROOT, "%.2f", (Double) value) : "—");
            }
        });
        resultsTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                onResultSelected();
            }
        });
    }

    private void configureSearchesTable() {
        searchesTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        searchesTable.setAutoCreateRowSorter(true);
        ComicTableSupport.style(searchesTable, palette);
        TableColumnModel columns = searchesTable.getColumnModel();
        columns.getColumn(SearchesTableModel.COLUMN_QUERY).setPreferredWidth(TITLE_COLUMN_WIDTH);
        columns.getColumn(SearchesTableModel.COLUMN_NODE).setPreferredWidth(SITE_COLUMN_WIDTH);
        columns.getColumn(SearchesTableModel.COLUMN_STATUS).setPreferredWidth(STATUS_COLUMN_WIDTH);
        columns.getColumn(SearchesTableModel.COLUMN_SOURCES).setPreferredWidth(COUNT_COLUMN_WIDTH);
        columns.getColumn(SearchesTableModel.COLUMN_STATUS).setMinWidth(STATUS_COLUMN_MIN_WIDTH);
        columns.getColumn(SearchesTableModel.COLUMN_SOURCES).setMinWidth(COUNT_COLUMN_WIDTH);
        searchesTable.getSelectionModel().addListSelectionListener(e -> updateSearchButtons());
    }

    private void wireSearchButtons() {
        run.addActionListener(e -> {
            plan.run();
            refresh();
        });
        stop.addActionListener(e -> {
            plan.stop();
            refresh();
        });
        again.addActionListener(e -> {
            plan.searchAgain();
            refresh();
        });
        edit.addActionListener(e -> editSelectedSearch());
        dropOrRerun.addActionListener(e -> {
            SearchPlanEntry entry = selectedSearch();
            if (entry == null) {
                return;
            }
            if (entry.getStatus() == SearchPlanEntry.Status.PENDING) {
                plan.drop(entry.getId());
            } else {
                plan.rerun(entry.getId());
            }
            refresh();
        });
    }

    private void editSelectedSearch() {
        SearchPlanEntry entry = selectedSearch();
        if (entry == null) {
            return;
        }
        Object query = javax.swing.JOptionPane.showInputDialog(this, "Query", "Edit the planned search",
                javax.swing.JOptionPane.PLAIN_MESSAGE, null, null, entry.getQuery());
        if (query != null && !query.toString().trim().isEmpty()) {
            plan.edit(entry.getId(), query.toString().trim());
            refresh();
        }
    }

    // ------------------------------------------------------------------ wiring from the contribution

    /** Wire the search plan: the Searches list and its buttons appear, with the switch to reach them. */
    public void setSearchPlan(SourceSearchPlan plan) {
        this.plan = plan;
        switchRow.setVisible(plan != null);
        if (plan == null) {
            showCard(CARD_RESULTS);
        }
        refresh();
    }

    /**
     * Wire the access log: sources of a website that demanded a security check are RED (never solved — pages
     * may be missing) or YELLOW (solved — worth a second look), so a protected source is never lost.
     */
    public void setAccessLogSupplier(Supplier<SourceAccessLog> supplier) {
        if (supplier != null) {
            this.accessLog = supplier;
            refresh();
        }
    }

    /** "Visualize sources" sits right-aligned in the bottom row. */
    public void setVisualizeButton(javax.swing.JComponent button) {
        visualizeSlot.removeAll();
        if (button != null) {
            visualizeSlot.add(button, BorderLayout.CENTER);
        }
        visualizeSlot.revalidate();
        visualizeSlot.repaint();
    }

    /**
     * Receives every selected source ({@code null} when there is none): the contribution shows it in the
     * reader in the main area.
     */
    public void setSelectionPublisher(Consumer<ResearchSourceRecord> publisher) {
        this.selectionPublisher = publisher;
        if (publisher != null && selectedSourceId != null) {
            ResearchSourceRecord current = repository.get(selectedSourceId);
            if (current != null) {
                publisher.accept(current); // a row selected while building the view counts too
            }
        }
    }

    /** The outline sections a source may be linked to (the reader marks every other link as orphan). */
    public Set<String> knownSectionIds() {
        return knownSectionIds;
    }

    // ------------------------------------------------------------------ refresh

    /** Re-read the sources and the search plan (a session state change, a filter, a save in the reader). */
    public void refresh() {
        refreshResults();
        refreshSearches();
        updateSummary();
    }

    private void refreshResults() {
        String keep = selectedSourceId;
        List<SourceTableRow> rows = SourceTableRow.rows(
                repository.find(new SourceQuery(filterField.getText(), null)), currentAccessLog());
        resultsModel.setRows(rows);
        if (rows.isEmpty()) {
            selectedSourceId = null;
            publish(null);
            return;
        }
        int select = indexOfSource(keep);
        if (select < 0) {
            select = firstReadableRow(rows);
        }
        selectResultRow(select);
    }

    /** The row a fresh list opens with: the first source that was read, else the first row. */
    private static int firstReadableRow(List<SourceTableRow> rows) {
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).state() != SourceRowState.PENDING) {
                return i;
            }
        }
        return 0;
    }

    private void refreshSearches() {
        SearchPlanEntry selected = selectedSearch();
        String keep = selected == null ? null : selected.getId();
        List<SearchPlanEntry> entries = filtered(planEntries());
        searchesModel.setRows(entries);
        for (int row = 0; keep != null && row < entries.size(); row++) {
            if (entries.get(row).getId().equals(keep)) {
                int viewRow = searchesTable.convertRowIndexToView(row);
                searchesTable.getSelectionModel().setSelectionInterval(viewRow, viewRow);
            }
        }
        updateSearchButtons();
    }

    private List<SearchPlanEntry> planEntries() {
        if (plan == null) {
            return Collections.emptyList();
        }
        try {
            List<SearchPlanEntry> entries = plan.entries();
            return entries == null ? Collections.<SearchPlanEntry>emptyList() : entries;
        } catch (RuntimeException unreadable) {
            return Collections.emptyList();
        }
    }

    /** The searches that match the filter, ordered by state (to do, running, done, failed); plan order within. */
    private List<SearchPlanEntry> filtered(List<SearchPlanEntry> entries) {
        String needle = filterField.getText() == null ? "" : filterField.getText().trim().toLowerCase(Locale.ROOT);
        List<SearchPlanEntry> shown = new ArrayList<SearchPlanEntry>();
        for (SearchPlanEntry entry : entries) {
            if (needle.isEmpty() || entry.getQuery().toLowerCase(Locale.ROOT).contains(needle)
                    || entry.getNodeLabel().toLowerCase(Locale.ROOT).contains(needle)) {
                shown.add(entry);
            }
        }
        Collections.sort(shown, new Comparator<SearchPlanEntry>() {
            public int compare(SearchPlanEntry a, SearchPlanEntry b) {
                return SourceRowState.of(a.getStatus()).compareTo(SourceRowState.of(b.getStatus()));
            }
        });
        return shown;
    }

    private SourceAccessLog currentAccessLog() {
        try {
            SourceAccessLog log = accessLog.get();
            return log == null ? SourceAccessLog.EMPTY : log;
        } catch (RuntimeException ex) {
            return SourceAccessLog.EMPTY;
        }
    }

    private void updateSummary() {
        summary.setText(card == CARD_SEARCHES
                ? searchSummary(planEntries(), plan == null ? null : uncovered())
                : resultSummary(resultsModel.rows));
        summary.setToolTipText(summary.getText());
    }

    private List<String> uncovered() {
        try {
            return plan.uncovered();
        } catch (RuntimeException unreadable) {
            return Collections.emptyList();
        }
    }

    static String resultSummary(List<SourceTableRow> rows) {
        int pending = 0;
        int checked = 0;
        int blocked = 0;
        for (SourceTableRow row : rows) {
            switch (row.state()) {
                case PENDING:
                    pending++;
                    break;
                case CHECK_SOLVED:
                    checked++;
                    break;
                case FAILED:
                    blocked++;
                    break;
                default:
                    break;
            }
        }
        StringBuilder text = new StringBuilder(rows.size() + (rows.size() == 1 ? " source" : " sources"));
        if (pending > 0) {
            text.append(" · ").append(pending).append(" not read yet");
        }
        if (checked > 0) {
            text.append(" · ").append(checked).append(" after a solved check");
        }
        if (blocked > 0) {
            text.append(" · ").append(blocked).append(" blocked");
        }
        return text.toString();
    }

    static String searchSummary(List<SearchPlanEntry> entries, List<String> uncovered) {
        int done = 0;
        for (SearchPlanEntry entry : entries) {
            if (entry.getStatus() == SearchPlanEntry.Status.DONE) {
                done++;
            }
        }
        StringBuilder text = new StringBuilder(done + " of " + entries.size() + " searches done");
        if (uncovered != null && !uncovered.isEmpty()) {
            text.append(" · uncovered: ");
            for (int i = 0; i < uncovered.size(); i++) {
                text.append(i > 0 ? ", " : "").append(uncovered.get(i));
            }
        }
        return text.toString();
    }

    // ------------------------------------------------------------------ cards and buttons

    private void showCard(int index) {
        card = index;
        if (cardSwitch.getSelectedIndex() != index) {
            cardSwitch.setSelectedIndex(index);
        }
        ((CardLayout) cards.getLayout()).show(cards, CARD_NAMES[index]);
        searchButtons.setVisible(index == CARD_SEARCHES);
        updateSearchButtons();
        updateSummary();
        revalidate();
        repaint();
    }

    private void updateSearchButtons() {
        boolean running = false;
        boolean runnable = false;
        if (plan != null) {
            try {
                running = plan.running();
                runnable = plan.runnable();
            } catch (RuntimeException unreadable) {
                running = false;
                runnable = false;
            }
        }
        run.setEnabled(runnable && !running);
        stop.setEnabled(running);
        again.setEnabled(runnable);
        SearchPlanEntry entry = selectedSearch();
        SearchPlanEntry.Status status = entry == null ? null : entry.getStatus();
        boolean pending = status == SearchPlanEntry.Status.PENDING;
        boolean ended = status != null && !pending && status != SearchPlanEntry.Status.RUNNING;
        edit.setEnabled(pending);
        dropOrRerun.setIcon(ended ? ResearchIcons.undo() : ResearchIcons.trash());
        String action = ended ? "Run the selected search again" : "Drop the selected search";
        dropOrRerun.setToolTipText(action);
        dropOrRerun.getAccessibleContext().setAccessibleName(action);
        dropOrRerun.setEnabled(pending || (ended && runnable));
    }

    private SearchPlanEntry selectedSearch() {
        int viewRow = searchesTable.getSelectedRow();
        return viewRow < 0 || viewRow >= searchesModel.getRowCount()
                ? null : searchesModel.rowAt(searchesTable.convertRowIndexToModel(viewRow));
    }

    // ------------------------------------------------------------------ selection

    /** Reveal a source (a Document [n] badge): switch to the results and select it. */
    public void focusSource(String sourceId) {
        if (sourceId == null || sourceId.trim().isEmpty()) {
            return;
        }
        int modelRow = indexOfSource(sourceId);
        if (modelRow < 0) {
            return;
        }
        showCard(CARD_RESULTS);
        selectResultRow(modelRow);
        int viewRow = resultsTable.convertRowIndexToView(modelRow);
        if (viewRow >= 0) {
            resultsTable.scrollRectToVisible(resultsTable.getCellRect(viewRow, 0, true));
        }
    }

    private int indexOfSource(String sourceId) {
        for (int i = 0; sourceId != null && i < resultsModel.getRowCount(); i++) {
            if (sourceId.equals(resultsModel.rowAt(i).source().getSourceId())) {
                return i;
            }
        }
        return -1;
    }

    private void selectResultRow(int modelRow) {
        int viewRow = resultsTable.convertRowIndexToView(modelRow);
        if (viewRow >= 0) {
            resultsTable.setRowSelectionInterval(viewRow, viewRow);
        }
    }

    private void onResultSelected() {
        int viewRow = resultsTable.getSelectedRow();
        if (viewRow < 0 || viewRow >= resultsModel.getRowCount()) {
            return;
        }
        ResearchSourceRecord record = resultsModel.rowAt(resultsTable.convertRowIndexToModel(viewRow)).source();
        selectedSourceId = record.getSourceId();
        publish(record);
    }

    private void publish(ResearchSourceRecord record) {
        if (selectionPublisher != null) {
            selectionPublisher.accept(record);
        }
    }

    // ------------------------------------------------------------------ dressing

    /** Colour one cell of a row by its state; the relevance star speaks in the action accent. */
    private Component dressRow(JTable table, Component cell, boolean selected, SourceRowState state,
                               boolean star) {
        return lists.dress(table, cell, selected, state.signal(), state == SourceRowState.SET_ASIDE, star);
    }

    /** The tooltip line for a marked row ("" when unmarked). */
    static String accessNote(SourceAccessLog.Entry entry) {
        if (entry == null || entry.getState() == SourceAccessState.NONE) {
            return "";
        }
        String page = entry.getUrl().isEmpty() ? "" : " (" + entry.getUrl() + ")";
        return entry.getState() == SourceAccessState.BLOCKED
                ? "Security check on " + entry.getFamily() + " not solved" + page
                        + " — pages of this website may be missing. Check it manually."
                : "Security check on " + entry.getFamily() + " was solved" + page
                        + " — verify the source was considered.";
    }

    /** #39: the session-side import runner the contribution wires in (returns the outcome). */
    public interface ImportHandler {
        String importSource(
                com.aresstack.askai.research.capture.SourceImportService.SourceInput input);
    }

    private ImportHandler importHandler;

    public void setImportHandler(ImportHandler handler) {
        this.importHandler = handler;
    }

    /** The import dialog: kind + optional title/origin + content; the outcome is shown honestly. */
    private void openImportDialog() {
        if (importHandler == null) {
            javax.swing.JOptionPane.showMessageDialog(this,
                    "This session has no import capability.", "Add source",
                    javax.swing.JOptionPane.WARNING_MESSAGE);
            return;
        }
        javax.swing.JComboBox<String> kind =
                ComicControls.<String>comboBox(
                        new String[] {"Plain text", "Raw HTML", "PDF file"});
        javax.swing.JTextField title = ComicControls.textField();
        javax.swing.JTextField origin = ComicControls.textField();
        origin.setToolTipText("Optional: source URL / base URI (HTML) or a path label");
        javax.swing.JTextArea content = ComicControls.textArea(12, 60);
        content.setLineWrap(true);
        content.setWrapStyleWord(true);
        JPanel form = new JPanel(new BorderLayout(4, 4));
        JPanel head = new JPanel(new java.awt.GridLayout(0, 1, 2, 2));
        head.add(new javax.swing.JLabel("Kind"));
        head.add(kind);
        head.add(new javax.swing.JLabel("Title (optional)"));
        head.add(title);
        head.add(new javax.swing.JLabel("Origin URL / base URI (optional)"));
        head.add(origin);
        head.add(new javax.swing.JLabel("Content"));
        form.add(head, BorderLayout.NORTH);
        form.add(new JScrollPane(content), BorderLayout.CENTER);
        int choice = javax.swing.JOptionPane.showConfirmDialog(this, form, "Add source",
                javax.swing.JOptionPane.OK_CANCEL_OPTION,
                javax.swing.JOptionPane.PLAIN_MESSAGE);
        if (choice != javax.swing.JOptionPane.OK_OPTION) {
            return;
        }
        // Everything the background work needs is read from the Swing widgets HERE, on the
        // EDT — the worker thread never touches a component.
        final String providedTitle = title.getText();
        final String providedOrigin = origin.getText();
        final String textContent = content.getText();
        final java.io.File pdfFile;
        if (kind.getSelectedIndex() == 2) {
            // PDF is a BYTE delivery: pick the file; reading + extraction run off the EDT.
            javax.swing.JFileChooser chooser = new javax.swing.JFileChooser();
            chooser.setDialogTitle("Import PDF file");
            chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                    "PDF documents (*.pdf)", "pdf"));
            if (chooser.showOpenDialog(this) != javax.swing.JFileChooser.APPROVE_OPTION) {
                return;
            }
            pdfFile = chooser.getSelectedFile();
        } else {
            pdfFile = null;
        }
        final com.aresstack.askai.research.capture.SourceImportService.Kind selected =
                pdfFile != null
                        ? com.aresstack.askai.research.capture.SourceImportService.Kind.PDF
                        : kind.getSelectedIndex() == 1
                                ? com.aresstack.askai.research.capture.SourceImportService.Kind.HTML
                                : com.aresstack.askai.research.capture.SourceImportService.Kind.TEXT;
        final ImportHandler handler = importHandler;
        // File read + extraction + acceptance can take seconds on a big PDF — never on the
        // EDT. Only the outcome dialog and the table refresh marshal back.
        Thread importWorker = new Thread(new Runnable() {
            public void run() {
                com.aresstack.askai.research.capture.SourceImportService.SourceInput input;
                if (pdfFile != null) {
                    byte[] rawBytes;
                    try {
                        rawBytes = java.nio.file.Files.readAllBytes(pdfFile.toPath());
                    } catch (java.io.IOException unreadable) {
                        showImportOutcomeLater("rejected: could not read the file: "
                                + unreadable.getMessage());
                        return;
                    }
                    String pdfOrigin = providedOrigin.trim().isEmpty()
                            ? pdfFile.getAbsolutePath() : providedOrigin.trim();
                    input = new com.aresstack.askai.research.capture.SourceImportService
                            .SourceInput(selected, providedTitle, pdfOrigin, rawBytes);
                } else {
                    input = new com.aresstack.askai.research.capture.SourceImportService
                            .SourceInput(selected, providedTitle, providedOrigin, textContent);
                }
                showImportOutcomeLater(handler.importSource(input));
            }
        }, "source-import-ui");
        importWorker.setDaemon(true);
        importWorker.start();
    }

    /** Marshal the import outcome back to the EDT: refresh the table, tell the user. */
    private void showImportOutcomeLater(final String outcome) {
        javax.swing.SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                refresh();
                javax.swing.JOptionPane.showMessageDialog(ResearchSourcesView.this,
                        outcome == null ? "Import produced no result." : outcome, "Add source",
                        outcome != null && outcome.startsWith("handled")
                                ? javax.swing.JOptionPane.INFORMATION_MESSAGE
                                : javax.swing.JOptionPane.WARNING_MESSAGE);
            }
        });
    }

    private static String escape(String value) {
        return TabLists.escape(value);
    }

    // ------------------------------------------------------------------ tests

    /** Rows of the results table. */
    int rowCount() {
        return resultsModel.getRowCount();
    }

    /** A cell of the results table. */
    Object cellAt(int row, int col) {
        return resultsModel.getValueAt(row, col);
    }

    SourceRowState stateAt(int row) {
        return resultsModel.rowAt(row).state();
    }

    int searchCount() {
        return searchesModel.getRowCount();
    }

    Object searchCellAt(int row, int col) {
        return searchesModel.getValueAt(row, col);
    }

    void selectSearch(int row) {
        searchesTable.getSelectionModel().setSelectionInterval(row, row);
    }

    void selectCard(int index) {
        showCard(index);
    }

    int shownCard() {
        return card;
    }

    boolean switchShown() {
        return switchRow.isVisible();
    }

    String summaryText() {
        return summary.getText();
    }

    // ------------------------------------------------------------------ table models

    private static final class ResultsTableModel extends AbstractTableModel {
        private static final long serialVersionUID = 1L;
        static final int COLUMN_STAR = 0;
        static final int COLUMN_TITLE = 1;
        static final int COLUMN_SITE = 2;
        static final int COLUMN_STATUS = 3;
        static final int COLUMN_SCORE = 4;

        private final String[] columns = {"", "Title", "Website", "Status", "Score"};
        private List<SourceTableRow> rows = new ArrayList<SourceTableRow>();

        void setRows(List<SourceTableRow> rows) {
            this.rows = new ArrayList<SourceTableRow>(rows);
            fireTableDataChanged();
        }

        SourceTableRow rowAt(int row) {
            return rows.get(row);
        }

        public int getRowCount() {
            return rows.size();
        }

        public int getColumnCount() {
            return columns.length;
        }

        @Override
        public String getColumnName(int column) {
            return columns[column];
        }

        @Override
        public Class<?> getColumnClass(int columnIndex) {
            return columnIndex == COLUMN_SCORE ? Double.class : String.class;
        }

        public Object getValueAt(int rowIndex, int columnIndex) {
            SourceTableRow row = rows.get(rowIndex);
            switch (columnIndex) {
                case COLUMN_STAR: return row.source().isUserRelevant() ? "★" : "";
                case COLUMN_TITLE: return row.title();
                case COLUMN_SITE: return row.site();
                case COLUMN_STATUS: return row.statusText();
                case COLUMN_SCORE:
                    return row.source().hasRerankScore() ? Double.valueOf(row.source().getRerankScore()) : null;
                default: return "";
            }
        }
    }

    private static final class SearchesTableModel extends AbstractTableModel {
        private static final long serialVersionUID = 1L;
        static final int COLUMN_QUERY = 0;
        static final int COLUMN_NODE = 1;
        static final int COLUMN_STATUS = 2;
        static final int COLUMN_SOURCES = 3;

        private final String[] columns = {"Search", "Topic", "Status", "Sources"};
        private List<SearchPlanEntry> rows = new ArrayList<SearchPlanEntry>();

        void setRows(List<SearchPlanEntry> rows) {
            this.rows = new ArrayList<SearchPlanEntry>(rows);
            fireTableDataChanged();
        }

        SearchPlanEntry rowAt(int row) {
            return rows.get(row);
        }

        public int getRowCount() {
            return rows.size();
        }

        public int getColumnCount() {
            return columns.length;
        }

        @Override
        public String getColumnName(int column) {
            return columns[column];
        }

        @Override
        public Class<?> getColumnClass(int columnIndex) {
            return columnIndex == COLUMN_SOURCES ? Integer.class : String.class;
        }

        public Object getValueAt(int rowIndex, int columnIndex) {
            SearchPlanEntry entry = rows.get(rowIndex);
            switch (columnIndex) {
                case COLUMN_QUERY: return entry.getQuery();
                case COLUMN_NODE: return entry.getNodeLabel();
                case COLUMN_STATUS: return searchStatusText(entry);
                case COLUMN_SOURCES: return Integer.valueOf(entry.getAcceptedSourceIds().size());
                default: return "";
            }
        }
    }

    /** The status of a search in words; a note of the entry (why it failed, what it ranked) follows it. */
    static String searchStatusText(SearchPlanEntry entry) {
        String label;
        switch (entry.getStatus()) {
            case PENDING:
                label = "Pending";
                break;
            case RUNNING:
                label = "Running";
                break;
            case DONE:
                label = "Done";
                break;
            case FAILED:
                label = "Failed";
                break;
            default:
                label = "Skipped";
                break;
        }
        String note = entry.getNote();
        return note == null || note.trim().isEmpty() ? label : label + " (" + note.trim() + ")";
    }

    /** The section ids of the demo outline (until the outline wires the real ones). */
    public static Set<String> demoKnownSections() {
        return new java.util.LinkedHashSet<String>(Arrays.asList("s1", "s2", "s2a", "s3", "s4"));
    }
}
