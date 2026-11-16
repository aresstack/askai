package com.aresstack.askai.research.document;

import com.aresstack.askai.research.manuscript.ManuscriptSection;
import com.aresstack.askai.research.manuscript.ManuscriptWorkspace;
import com.aresstack.askai.research.outline.Outline;
import com.aresstack.askai.research.tabs.RowSignal;
import com.aresstack.askai.research.tabs.TabLists;
import com.aresstack.comiccontrols.control.ComicButton;
import com.aresstack.comiccontrols.control.ComicControls;
import com.aresstack.comiccontrols.control.ComicSearchBar;
import com.aresstack.comiccontrols.control.ComicTableSupport;
import com.aresstack.comiccontrols.control.WrapLayout;
import com.aresstack.comiccontrols.paint.ComposerIcons;
import com.aresstack.comiccontrols.theme.ComicPalette;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableColumnModel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * The Document tab of the sidebar: the book's table of contents with the writing state of every section.
 * The search bar is the topmost element (as in the Chats tab), below it the list at full height: grey = not
 * written yet, green = written, yellow = stale, red = failed. Selecting a section opens it in the main area
 * ({@link DocumentWorkView}), where the book itself is shown and edited. The icon buttons at the bottom act
 * on the whole book: write the remaining sections, stop, rewrite the stale ones, export. Nothing sits above
 * the search bar. EDT only.
 */
public final class DocumentSectionsView extends JPanel {

    private static final long serialVersionUID = 1L;

    private static final int NUMBER_COLUMN_WIDTH = 44;
    private static final int TITLE_COLUMN_WIDTH = 240;
    private static final int STATUS_COLUMN_WIDTH = 90;
    private static final int STATUS_COLUMN_MIN_WIDTH = 64;
    /** How far a section's title steps in under its chapter. */
    private static final int SECTION_INDENT = 12;
    private static final int BUTTON_GAP = 4;
    private static final int FOOTER_GAP = 4;

    private final ComicPalette palette = ComicPalette.defaultPalette();
    private final TabLists lists = new TabLists(palette);
    private final RowsModel rowsModel = new RowsModel();
    private final JTable table = new JTable(rowsModel) {
        private static final long serialVersionUID = 1L;

        @Override
        public String getToolTipText(java.awt.event.MouseEvent event) {
            int row = rowAtPoint(event.getPoint());
            if (row < 0) {
                return null;
            }
            DocumentRow line = rowsModel.rowAt(row);
            return "<html><b>" + TabLists.escape(line.number() + "  " + line.title()) + "</b>"
                    + (line.note().isEmpty() ? "" : "<br>" + TabLists.escape(line.note())) + "</html>";
        }

        @Override
        public Component prepareRenderer(TableCellRenderer renderer, int row, int column) {
            Component cell = super.prepareRenderer(renderer, row, column);
            DocumentRow line = rowsModel.rowAt(row);
            cell.setFont(getFont().deriveFont(line.isChapter() ? Font.BOLD : Font.PLAIN));
            if (!line.isChapter() && cell instanceof JComponent
                    && convertColumnIndexToModel(column) == RowsModel.COLUMN_TITLE) {
                JComponent label = (JComponent) cell;
                label.setBorder(BorderFactory.createCompoundBorder(label.getBorder(),
                        BorderFactory.createEmptyBorder(0, SECTION_INDENT, 0, 0)));
            }
            return lists.dress(this, cell, isRowSelected(row), line.signal(), line.muted(), false);
        }
    };
    private final ComicSearchBar filterField = new ComicSearchBar("Filter sections…",
            "Filter chapters and sections by title (Enter applies)");
    private final ComicButton writeButton =
            ComicControls.iconButton(ComposerIcons.play(), "Write the remaining sections");
    private final ComicButton stopButton = ComicControls.iconButton(ComposerIcons.stop(), "Stop writing");
    private final ComicButton rewriteStaleButton =
            ComicControls.iconButton(ComposerIcons.refresh(), "Rewrite the stale sections");
    private final ComicButton exportButton = ComicControls.iconButton(ComposerIcons.export(), "Export the book");
    /** What the user has to know right now: why the book is read-only, requested changes, the writing job. */
    private final JTextArea notes = ComicControls.textArea();
    private final JLabel summary = lists.summaryLabel();

    private DocumentSession session;
    private Consumer<SectionEditor.Item> selectionListener;
    private Runnable exportAction;
    private String message = "";
    private boolean selecting;

    public DocumentSectionsView() {
        super(new BorderLayout(0, TabLists.TAB_MARGIN));
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(TabLists.TAB_MARGIN, TabLists.TAB_MARGIN, TabLists.TAB_MARGIN,
                TabLists.TAB_MARGIN));
        JPanel top = new JPanel(new BorderLayout());
        top.setOpaque(false);
        top.add(filterField, BorderLayout.CENTER);
        filterField.addSearchAction(e -> refresh());
        configureTable();
        add(lists.plate(top, lists.scroll(table)), BorderLayout.CENTER);
        add(buildFooter(), BorderLayout.SOUTH);
        wire();
        refresh();
    }

    private void configureTable() {
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        ComicTableSupport.style(table, palette);
        TableColumnModel columns = table.getColumnModel();
        columns.getColumn(RowsModel.COLUMN_NUMBER).setPreferredWidth(NUMBER_COLUMN_WIDTH);
        columns.getColumn(RowsModel.COLUMN_NUMBER).setMaxWidth(NUMBER_COLUMN_WIDTH);
        columns.getColumn(RowsModel.COLUMN_TITLE).setPreferredWidth(TITLE_COLUMN_WIDTH);
        columns.getColumn(RowsModel.COLUMN_STATUS).setPreferredWidth(STATUS_COLUMN_WIDTH);
        columns.getColumn(RowsModel.COLUMN_STATUS).setMinWidth(STATUS_COLUMN_MIN_WIDTH);
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && !selecting) {
                publishSelection();
            }
        });
    }

    /** The bottom of the tab: the icon buttons, what the user has to know, one line of summary. */
    private JPanel buildFooter() {
        JPanel buttons = new JPanel(new WrapLayout(FlowLayout.LEFT, BUTTON_GAP, BUTTON_GAP));
        buttons.setOpaque(false);
        for (JButton button : allButtons()) {
            buttons.add(button);
        }
        notes.setEditable(false);
        notes.setLineWrap(true);
        notes.setWrapStyleWord(true);
        notes.setOpaque(false);
        notes.setBorder(BorderFactory.createEmptyBorder());
        notes.setFont(summary.getFont());
        notes.setForeground(lists.mutedInk());
        JPanel lines = new JPanel(new BorderLayout(0, FOOTER_GAP));
        lines.setOpaque(false);
        lines.add(notes, BorderLayout.CENTER);
        lines.add(summary, BorderLayout.SOUTH);
        JPanel footer = new JPanel(new BorderLayout(0, FOOTER_GAP));
        footer.setOpaque(false);
        footer.add(buttons, BorderLayout.CENTER);
        footer.add(lines, BorderLayout.SOUTH);
        return footer;
    }

    private void wire() {
        writeButton.addActionListener(e -> {
            String refusal = session.writeRemaining();
            say(refusal == null ? "Writing..." : refusal);
        });
        stopButton.addActionListener(e -> {
            session.stopWriting();
            refresh();
        });
        rewriteStaleButton.addActionListener(e -> {
            String refusal = session.rewriteStale();
            say(refusal == null ? "Rewriting the stale sections..." : refusal);
        });
        exportButton.addActionListener(e -> {
            if (exportAction != null) {
                exportAction.run();
            }
        });
    }

    /** Every button of the view (tests: icon-only, each with a tooltip). */
    JButton[] allButtons() {
        return new JButton[] {writeButton, stopButton, rewriteStaleButton, exportButton};
    }

    // ------------------------------------------------------------------ wiring

    public void setSession(DocumentSession session) {
        this.session = session;
        refresh();
    }

    /** Called with the section the user selected; {@code null} when a chapter heading or nothing is selected. */
    public void setSelectionListener(Consumer<SectionEditor.Item> listener) {
        this.selectionListener = listener;
    }

    /** What "Export the book" does (it needs the host's artifact store). */
    public void setExportAction(Runnable action) {
        this.exportAction = action;
        updateButtons();
    }

    /** Show an outcome line ("Exported to ...", why an action did not start). */
    public void say(String line) {
        message = line == null ? "" : line.trim();
        refresh();
    }

    /** Re-read the book from the session; the selection stays on its section. */
    public void refresh() {
        String selected = selectedSectionId();
        Outline approved = session == null ? null : session.approvedOutline();
        List<ManuscriptSection> stored = storedSections();
        selecting = true;
        try {
            rowsModel.setRows(DocumentRow.rows(approved, stored, filterField.getText()));
            select(selected);
        } finally {
            selecting = false;
        }
        summary.setText(summaryLine(DocumentRow.rows(approved, stored, ""),
                session == null ? "" : session.bookStatus()));
        String report = session == null ? "" : session.bookReport();
        summary.setToolTipText(report.isEmpty() ? null
                : "<html><pre>" + TabLists.escape(report) + "</pre></html>");
        updateNotes();
        updateButtons();
    }

    private List<ManuscriptSection> storedSections() {
        ManuscriptWorkspace manuscript = session == null ? null : session.manuscript();
        if (manuscript == null) {
            return Collections.emptyList();
        }
        try {
            return manuscript.sections();
        } catch (RuntimeException unreadable) {
            return Collections.emptyList();
        }
    }

    static String summaryLine(List<DocumentRow> rows, String bookStatus) {
        String sections = DocumentRow.summary(rows);
        return bookStatus == null || bookStatus.isEmpty() ? sections : sections + " · book: " + bookStatus;
    }

    private void updateNotes() {
        List<String> lines = new ArrayList<String>();
        if (session != null) {
            String refusal = session.writeRefusal();
            if (refusal != null && !refusal.isEmpty()) {
                lines.add(refusal);
            }
            String request = session.changeRequest();
            if (request != null && !request.isEmpty()) {
                lines.add("Requested changes: " + request);
            }
            String writing = session.writingStatus();
            if (writing != null && !writing.isEmpty()) {
                lines.add(writing);
            }
        }
        if (!message.isEmpty() && !lines.contains(message)) {
            lines.add(message);
        }
        notes.setText(String.join("\n", lines));
        notes.setVisible(!lines.isEmpty());
        revalidate();
        repaint();
    }

    private void updateButtons() {
        boolean running = session != null && session.writingRunning();
        boolean outline = session != null && session.approvedOutline() != null
                && session.manuscript() != null;
        writeButton.setEnabled(outline && !running);
        stopButton.setVisible(running);
        rewriteStaleButton.setEnabled(outline && !running && session.hasStaleSections());
        exportButton.setEnabled(exportAction != null);
    }

    private void publishSelection() {
        if (selectionListener == null) {
            return;
        }
        int row = table.getSelectedRow();
        selectionListener.accept(row < 0 ? null : rowsModel.rowAt(row).item());
    }

    private String selectedSectionId() {
        int row = table.getSelectedRow();
        if (row < 0 || row >= rowsModel.getRowCount()) {
            return null;
        }
        SectionEditor.Item item = rowsModel.rowAt(row).item();
        return item == null ? null : item.sectionId;
    }

    /** Put the selection on a section without telling the listener (it already shows it). */
    private void select(String sectionId) {
        int row = sectionId == null ? -1 : rowsModel.indexOf(sectionId);
        if (row >= 0) {
            table.getSelectionModel().setSelectionInterval(row, row);
        } else {
            table.clearSelection();
        }
    }

    // ------------------------------------------------------------------ for tests

    int rowCount() {
        return rowsModel.getRowCount();
    }

    Object cellAt(int row, int column) {
        return rowsModel.getValueAt(row, column);
    }

    RowSignal signalAt(int row) {
        return rowsModel.rowAt(row).signal();
    }

    /** Select a row as the user does. */
    void selectRow(int row) {
        table.getSelectionModel().setSelectionInterval(row, row);
    }

    void typeFilter(String text) {
        filterField.setText(text);
        refresh();
    }

    String summaryText() {
        return summary.getText();
    }

    String notesText() {
        return notes.isVisible() ? notes.getText() : "";
    }

    JButton button(String name) {
        for (JButton button : allButtons()) {
            if (name.equals(button.getAccessibleContext().getAccessibleName())) {
                return button;
            }
        }
        return null;
    }

    /** The list: number in the book, title, writing state. */
    private static final class RowsModel extends AbstractTableModel {
        private static final long serialVersionUID = 1L;
        static final int COLUMN_NUMBER = 0;
        static final int COLUMN_TITLE = 1;
        static final int COLUMN_STATUS = 2;

        private final String[] columns = {"#", "Section", "Status"};
        private List<DocumentRow> rows = new ArrayList<DocumentRow>();

        void setRows(List<DocumentRow> next) {
            rows = new ArrayList<DocumentRow>(next);
            fireTableDataChanged();
        }

        DocumentRow rowAt(int row) {
            return rows.get(row);
        }

        int indexOf(String sectionId) {
            for (int i = 0; i < rows.size(); i++) {
                SectionEditor.Item item = rows.get(i).item();
                if (item != null && item.sectionId.equals(sectionId)) {
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
            DocumentRow row = rows.get(rowIndex);
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
