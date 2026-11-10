package com.aresstack.askai.research.host;

import com.aresstack.askai.browser.search.engine.BrowserSearchEngineSelection;
import com.aresstack.askai.browser.search.engine.SearchEngineCatalog;
import com.aresstack.askai.browser.search.engine.SearchEngineCatalogEntry;
import com.aresstack.askai.browser.search.engine.SearchEngineImplementationStatus;
import com.aresstack.askai.browser.search.engine.SearchLanguages;
import com.aresstack.comiccontrols.control.ComicButton;
import com.aresstack.comiccontrols.theme.ThemeColor;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.UIManager;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableColumn;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The engine table of Search → Engines: one row per engine CONFIGURATION, any number of rows, the same engine as
 * often as wanted. Columns: Active · Engine · Priority · Delay (s) · Pages · Languages · More · Further languages.
 * <p>
 * Priority 0 runs first; rows with the same priority form one group. The table shows the stored order (priority
 * ascending, the stored order within a priority, which is also the order the search uses); a committed priority edit
 * (Enter or leaving the cell, never while typing) or an added row re-sorts it by priority, then engine name, then the
 * order the rows came in. Languages are picked from a check-box menu (click the cell); "More" switches on the
 * free "Further languages" codes ({@code pl, uk, id}), which are validated and normalised on commit — a piece that
 * is not a language code is named below the table and not stored.
 * <p>
 * "Add engine…" offers the whole {@link SearchEngineCatalog}; engines AskAI cannot search with yet are listed but
 * disabled, with the reason as tooltip. A row of an engine this build does not know (written by a newer build) is
 * kept and shown as such: nothing disappears from the user's table silently.
 */
final class SearchEngineTableEditor extends JPanel {

    static final int COL_ACTIVE = 0;
    static final int COL_ENGINE = 1;
    static final int COL_PRIORITY = 2;
    static final int COL_DELAY = 3;
    static final int COL_PAGES = 4;
    static final int COL_LANGUAGES = 5;
    static final int COL_MORE = 6;
    static final int COL_FURTHER = 7;

    private static final String[] COLUMNS = {
            "Active", "Engine", "Priority", "Delay (s)", "Pages", "Languages", "More", "Further languages"};

    /** Preferred column widths (pixels) — the table still shrinks to the dialog width, never scrolls sideways. */
    private static final int[] WIDTHS = {44, 110, 50, 56, 44, 110, 40, 100};

    /** One configuration row as the table edits it. */
    static final class Row {
        final String engineId;
        boolean enabled;
        int priority;
        int delayMillis;
        int resultPages;
        List<String> languages;
        boolean furtherOn;
        List<String> further;

        Row(BrowserSearchEngineSelection.Entry entry) {
            this.engineId = entry.getEngineId();
            this.enabled = entry.isEnabled();
            this.priority = Math.max(0, entry.getPriority());
            this.delayMillis = entry.getDelayMillis();
            this.resultPages = entry.getResultPages();
            this.languages = new ArrayList<String>(entry.getLanguages());
            this.furtherOn = entry.isFurtherLanguagesEnabled();
            this.further = new ArrayList<String>(entry.getFurtherLanguages());
        }

        BrowserSearchEngineSelection.Entry toEntry() {
            return new BrowserSearchEngineSelection.Entry(engineId, enabled, resultPages, delayMillis, priority,
                    languages, furtherOn, further);
        }
    }

    private final List<Row> rows = new ArrayList<Row>();
    private final Model model = new Model();
    private final JTable table = new JTable(model);
    private final JLabel status = new JLabel(" ");
    private final JPopupMenu addMenu = new JPopupMenu();

    SearchEngineTableEditor(String encodedValue) {
        super(new BorderLayout(0, 4));
        setAlignmentX(Component.LEFT_ALIGNMENT);

        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setFillsViewportHeight(true);
        table.setRowHeight(Math.max(table.getRowHeight(), 22));
        table.getTableHeader().setReorderingAllowed(false);
        table.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE); // leaving a cell commits it
        table.setSelectionBackground(selectionBackground());
        table.setSelectionForeground(selectionForeground());
        for (int i = 0; i < WIDTHS.length; i++) {
            TableColumn column = table.getColumnModel().getColumn(i);
            column.setPreferredWidth(WIDTHS[i]);
        }
        table.getColumnModel().getColumn(COL_ENGINE).setMinWidth(70);
        table.setToolTipText("Priority 0 runs first; rows with the same priority are searched together. "
                + "Click Languages to pick, tick More to add further language codes.");
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                int row = table.rowAtPoint(event.getPoint());
                int column = table.columnAtPoint(event.getPoint());
                if (row >= 0 && column == COL_LANGUAGES) {
                    showLanguageMenu(row, event.getX(), event.getY());
                }
            }
        });
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                describeSelection();
            }
        });

        JScrollPane scroll = new JScrollPane(table);
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setPreferredSize(new Dimension(420, 150));
        scroll.getViewport().setBackground(listBackground());
        add(scroll, BorderLayout.CENTER);

        buildAddMenu();
        final JButton add = new ComicButton("Add engine…");
        add.setToolTipText("Add a row for an engine of the catalog (the same engine may have several rows)");
        add.addActionListener(e -> addMenu.show(add, 0, add.getHeight()));
        JButton remove = new ComicButton("Remove");
        remove.setToolTipText("Remove the selected row");
        remove.addActionListener(e -> removeRow(table.getSelectedRow()));

        JPanel buttons = new JPanel();
        buttons.setLayout(new BoxLayout(buttons, BoxLayout.X_AXIS));
        buttons.setOpaque(false);
        buttons.add(add);
        buttons.add(Box.createHorizontalStrut(6));
        buttons.add(remove);
        buttons.add(Box.createHorizontalStrut(8));
        status.setForeground(ThemeColor.of("research.settingsHintText"));
        buttons.add(status);
        buttons.add(Box.createHorizontalGlue());
        buttons.setBorder(BorderFactory.createEmptyBorder(2, 0, 0, 0));
        add(buttons, BorderLayout.SOUTH);

        set(encodedValue);
    }

    // ------------------------------------------------------------------ value

    /** The flat form the settings codec stores, rows in table order. */
    String get() {
        stopEditing();
        List<BrowserSearchEngineSelection.Entry> entries = new ArrayList<BrowserSearchEngineSelection.Entry>();
        for (Row row : rows) {
            entries.add(row.toEntry());
        }
        return new BrowserSearchEngineSelection(entries, null).encodeEntries();
    }

    void set(String encodedValue) {
        stopEditing();
        rows.clear();
        for (BrowserSearchEngineSelection.Entry entry : new BrowserSearchEngineSelection(
                BrowserSearchEngineSelection.parseEntries(encodedValue), null).getEntries()) {
            rows.add(new Row(entry));
        }
        model.fireTableDataChanged();
        status.setText(" ");
    }

    // ------------------------------------------------------------------ actions (package-private for tests)

    /** Add a row for a catalog engine: active, after the last priority, the catalog's languages. */
    void addEngine(String engineId) {
        stopEditing();
        int priority = 0;
        for (Row row : rows) {
            priority = Math.max(priority, row.priority + 1);
        }
        Row row = new Row(new BrowserSearchEngineSelection.Entry(engineId, true,
                BrowserSearchEngineSelection.Entry.DEFAULT_RESULT_PAGES,
                SearchEngineCatalog.defaultDelayMillis(engineId), priority,
                SearchEngineCatalog.defaultLanguages(engineId), false, Collections.<String>emptyList()));
        rows.add(row);
        resortKeeping(row);
    }

    void removeRow(int index) {
        stopEditing();
        if (index < 0 || index >= rows.size()) {
            return;
        }
        rows.remove(index);
        model.fireTableDataChanged();
    }

    /** Toggle one picked language of a row (the language menu's action). */
    void toggleLanguage(int index, String code) {
        if (index < 0 || index >= rows.size()) {
            return;
        }
        Row row = rows.get(index);
        List<String> languages = new ArrayList<String>(row.languages);
        if (!languages.remove(code)) {
            languages.add(code);
        }
        row.languages = orderedLikePicker(languages);
        model.fireTableRowsUpdated(index, index);
    }

    JTable table() {
        return table;
    }

    String statusText() {
        return status.getText();
    }

    // ------------------------------------------------------------------ internals

    private void buildAddMenu() {
        for (final SearchEngineCatalogEntry entry : SearchEngineCatalog.entries()) {
            JMenuItem item = new JMenuItem(entry.getDisplayName() + statusSuffix(entry.getStatus()));
            item.setEnabled(entry.isSearchable());
            String note = entry.getOrigin() + " · " + entry.getIndexSource()
                    + (entry.getStatusNote().isEmpty() ? "" : " — " + entry.getStatusNote());
            item.setToolTipText(note);
            item.addActionListener(e -> addEngine(entry.getId()));
            addMenu.add(item);
        }
    }

    private static String statusSuffix(SearchEngineImplementationStatus status) {
        switch (status) {
            case EXPERIMENTAL:
                return "  (experimental)";
            case NOT_IMPLEMENTED:
                return "  (not implemented)";
            case UNAVAILABLE:
                return "  (unavailable)";
            default:
                return "";
        }
    }

    private void showLanguageMenu(final int index, int x, int y) {
        stopEditing();
        JPopupMenu menu = new JPopupMenu();
        Row row = rows.get(index);
        for (final String code : SearchLanguages.pickerCodes()) {
            JCheckBoxMenuItem item = new JCheckBoxMenuItem(code + "  " + SearchLanguages.displayName(code),
                    row.languages.contains(code));
            item.addActionListener(e -> toggleLanguage(index, code));
            menu.add(item);
        }
        menu.show(table, x, y);
    }

    private void describeSelection() {
        int index = table.getSelectedRow();
        if (index < 0 || index >= rows.size()) {
            return;
        }
        SearchEngineCatalogEntry entry = SearchEngineCatalog.byId(rows.get(index).engineId);
        if (entry == null) {
            status.setText("Unknown engine (from a newer AskAI) — kept, never searched.");
        } else if (!entry.isSearchable()) {
            status.setText(entry.getDisplayName() + ": " + entry.getStatus().name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ')
                    + " — never searched.");
        } else if (!entry.hasLanguageFilter()) {
            status.setText(entry.getDisplayName() + ": language through the query words.");
        } else {
            status.setText(" ");
        }
    }

    private void resortKeeping(Row keep) {
        List<Row> sorted = new ArrayList<Row>(rows);
        Collections.sort(sorted, (a, b) -> {
            if (a.priority != b.priority) {
                return a.priority < b.priority ? -1 : 1;
            }
            return SearchEngineCatalog.displayName(a.engineId)
                    .compareToIgnoreCase(SearchEngineCatalog.displayName(b.engineId));
        });
        rows.clear();
        rows.addAll(sorted);
        model.fireTableDataChanged();
        int at = rows.indexOf(keep);
        if (at >= 0) {
            table.getSelectionModel().setSelectionInterval(at, at);
        }
    }

    private void stopEditing() {
        if (table.isEditing() && table.getCellEditor() != null) {
            table.getCellEditor().stopCellEditing();
        }
    }

    private static List<String> orderedLikePicker(List<String> codes) {
        List<String> ordered = new ArrayList<String>();
        for (String code : SearchLanguages.pickerCodes()) {
            if (codes.contains(code)) {
                ordered.add(code);
            }
        }
        for (String code : codes) {
            if (!ordered.contains(code)) {
                ordered.add(code);
            }
        }
        return ordered;
    }

    private static String engineLabel(String engineId) {
        SearchEngineCatalogEntry entry = SearchEngineCatalog.byId(engineId);
        if (entry == null) {
            return engineId + " (unknown)";
        }
        return entry.getDisplayName() + statusSuffix(entry.getStatus());
    }

    private static Integer parseNonNegative(Object value) {
        try {
            int parsed = Integer.parseInt(String.valueOf(value).trim());
            return parsed >= 0 ? parsed : null;
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    private final class Model extends AbstractTableModel {

        public int getRowCount() {
            return rows.size();
        }

        public int getColumnCount() {
            return COLUMNS.length;
        }

        @Override
        public String getColumnName(int column) {
            return COLUMNS[column];
        }

        @Override
        public Class<?> getColumnClass(int column) {
            return column == COL_ACTIVE || column == COL_MORE ? Boolean.class : String.class;
        }

        @Override
        public boolean isCellEditable(int row, int column) {
            return column != COL_ENGINE && column != COL_LANGUAGES;
        }

        public Object getValueAt(int index, int column) {
            Row row = rows.get(index);
            switch (column) {
                case COL_ACTIVE:
                    return row.enabled;
                case COL_ENGINE:
                    return engineLabel(row.engineId);
                case COL_PRIORITY:
                    return String.valueOf(row.priority);
                case COL_DELAY:
                    return BrowserSearchEngineSelection.formatDelaySeconds(row.delayMillis);
                case COL_PAGES:
                    return String.valueOf(row.resultPages);
                case COL_LANGUAGES:
                    return row.languages.isEmpty() ? "—" : SearchLanguages.join(row.languages);
                case COL_MORE:
                    return row.furtherOn;
                default:
                    return SearchLanguages.join(row.further);
            }
        }

        @Override
        public void setValueAt(Object value, int index, int column) {
            if (index < 0 || index >= rows.size()) {
                return;
            }
            Row row = rows.get(index);
            switch (column) {
                case COL_ACTIVE:
                    row.enabled = Boolean.TRUE.equals(value);
                    break;
                case COL_PRIORITY:
                    Integer priority = parseNonNegative(value);
                    if (priority == null) {
                        status.setText("Priority must be a whole number from 0.");
                        return;
                    }
                    row.priority = priority;
                    resortKeeping(row); // committed: now the row moves to its group
                    return;
                case COL_DELAY:
                    String text = String.valueOf(value).trim().replace(',', '.');
                    try {
                        if (Double.parseDouble(text) < 0) {
                            throw new NumberFormatException(text);
                        }
                    } catch (NumberFormatException invalid) {
                        status.setText("Delay must be seconds from 0, e.g. 1.5.");
                        return;
                    }
                    row.delayMillis = BrowserSearchEngineSelection.parseDelayMillis(text);
                    break;
                case COL_PAGES:
                    Integer pages = parseNonNegative(value);
                    if (pages == null || pages < 1) {
                        status.setText("Pages must be a whole number from 1.");
                        return;
                    }
                    row.resultPages = pages;
                    break;
                case COL_MORE:
                    row.furtherOn = Boolean.TRUE.equals(value);
                    break;
                case COL_FURTHER:
                    SearchLanguages.Parsed parsed = SearchLanguages.parse(String.valueOf(value));
                    row.further = new ArrayList<String>(parsed.getCodes());
                    if (!parsed.getCodes().isEmpty()) {
                        row.furtherOn = true; // typing codes means wanting them
                    }
                    status.setText(parsed.getRejected().isEmpty() ? " "
                            : "Not a language code, not stored: " + SearchLanguages.join(parsed.getRejected()));
                    break;
                default:
                    return;
            }
            fireTableRowsUpdated(index, index);
        }
    }

    private static Color listBackground() {
        Color color = UIManager.getColor("List.background");
        return color == null ? ThemeColor.of("comic.fieldBg") : color;
    }

    private static Color selectionBackground() {
        Color color = UIManager.getColor("List.selectionBackground");
        return color == null ? ThemeColor.of("research.engineListSelectionBackground") : color;
    }

    private static Color selectionForeground() {
        Color color = UIManager.getColor("List.selectionForeground");
        return color == null ? ThemeColor.of("research.engineListSelectionForeground") : color;
    }
}
