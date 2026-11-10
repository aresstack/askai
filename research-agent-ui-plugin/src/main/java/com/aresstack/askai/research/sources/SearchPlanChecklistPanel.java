package com.aresstack.askai.research.sources;

import com.aresstack.askai.research.plan.SearchPlanEntry;
import com.aresstack.comiccontrols.control.ComicButton;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The search plan checklist in the Sources tab (plan PR 14): every entry with its status, node, query and accepted
 * sources; "Run search plan" and "Stop search plan"; drop, edit and re-run of a selected entry; "Search everything
 * again"; and the nodes still uncovered. The comment of "Request changes" at the gate shows as its priority entry.
 */
public final class SearchPlanChecklistPanel extends JPanel {

    /** What the checklist reads and does; the session implements it. */
    public interface Plan {
        List<SearchPlanEntry> entries();

        boolean running();

        /** Whether the plan may run now (research/running). */
        boolean runnable();

        List<String> uncovered();

        void run();

        void stop();

        void drop(String entryId);

        void edit(String entryId, String query);

        void rerun(String entryId);

        void searchAgain();
    }

    static final String[] COLUMNS = {"Status", "Node", "Query", "Sources"};

    private final Plan plan;
    private final EntryModel model = new EntryModel();
    private final JTable table = new JTable(model);
    // Icon buttons with tooltips (Angelo 10.11.): text buttons do not fit the sidebar width.
    final JButton run = iconButton("▶", "Run search plan");
    final JButton stop = iconButton("■", "Stop search plan");
    final JButton again = iconButton("⟲", "Search everything again");
    final JButton edit = iconButton("✎", "Edit the selected planned query");
    final JButton drop = iconButton("✕", "Drop the selected planned query");
    final JButton rerun = iconButton("↻", "Re-run the selected query");
    final JLabel summary = new JLabel(" ");

    public SearchPlanChecklistPanel(Plan plan) {
        super(new BorderLayout(0, 4));
        this.plan = plan;
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));

        JPanel header = new JPanel(new BorderLayout(6, 0));
        header.setOpaque(false);
        header.add(new JLabel("Search plan"), BorderLayout.WEST);
        header.add(summary, BorderLayout.CENTER);

        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getSelectionModel().addListSelectionListener(e -> updateButtons());
        JScrollPane scroll = new JScrollPane(table, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setPreferredSize(new Dimension(200, 120));

        // All buttons live in ONE row at the bottom — nothing sits above the panel.
        JPanel entryButtons = new JPanel(new com.aresstack.comiccontrols.control.WrapLayout(FlowLayout.LEFT, 4, 0));
        entryButtons.setOpaque(false);
        entryButtons.add(run);
        entryButtons.add(stop);
        entryButtons.add(again);
        entryButtons.add(edit);
        entryButtons.add(drop);
        entryButtons.add(rerun);

        add(header, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
        add(entryButtons, BorderLayout.SOUTH);

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
        drop.addActionListener(e -> {
            SearchPlanEntry entry = selected();
            if (entry != null) {
                plan.drop(entry.getId());
                refresh();
            }
        });
        rerun.addActionListener(e -> {
            SearchPlanEntry entry = selected();
            if (entry != null) {
                plan.rerun(entry.getId());
                refresh();
            }
        });
        edit.addActionListener(e -> {
            SearchPlanEntry entry = selected();
            if (entry == null) {
                return;
            }
            Object query = JOptionPane.showInputDialog(this, "Query", "Edit planned query",
                    JOptionPane.PLAIN_MESSAGE, null, null, entry.getQuery());
            if (query != null && !query.toString().trim().isEmpty()) {
                plan.edit(entry.getId(), query.toString().trim());
                refresh();
            }
        });
        refresh();
    }

    private static JButton iconButton(String glyph, String tooltip) {
        ComicButton button = new ComicButton(glyph);
        button.setToolTipText(tooltip);
        button.getAccessibleContext().setAccessibleName(tooltip);
        button.setFocusable(false);
        return button;
    }

    /** Re-read the plan (on every session state change). */
    public void refresh() {
        String keep = selected() == null ? null : selected().getId();
        List<SearchPlanEntry> entries;
        try {
            entries = plan.entries();
        } catch (RuntimeException unreadable) {
            entries = Collections.emptyList();
        }
        model.set(entries);
        if (keep != null) {
            for (int row = 0; row < entries.size(); row++) {
                if (entries.get(row).getId().equals(keep)) {
                    table.getSelectionModel().setSelectionInterval(row, row);
                }
            }
        }
        summary.setText(summaryText(entries, plan.uncovered()));
        setVisible(!entries.isEmpty());
        updateButtons();
    }

    static String summaryText(List<SearchPlanEntry> entries, List<String> uncovered) {
        int done = 0;
        for (SearchPlanEntry entry : entries) {
            if (entry.getStatus() == SearchPlanEntry.Status.DONE) {
                done++;
            }
        }
        StringBuilder text = new StringBuilder(done + " of " + entries.size() + " queries done");
        if (uncovered != null && !uncovered.isEmpty()) {
            text.append(" · uncovered: ");
            for (int i = 0; i < uncovered.size(); i++) {
                text.append(i > 0 ? ", " : "").append(uncovered.get(i));
            }
        }
        return text.toString();
    }

    private void updateButtons() {
        boolean running = plan.running();
        boolean runnable = plan.runnable();
        run.setEnabled(runnable && !running);
        stop.setEnabled(running);
        again.setEnabled(runnable);
        SearchPlanEntry entry = selected();
        boolean pending = entry != null && entry.getStatus() == SearchPlanEntry.Status.PENDING;
        boolean ended = entry != null && entry.getStatus() != SearchPlanEntry.Status.PENDING
                && entry.getStatus() != SearchPlanEntry.Status.RUNNING;
        edit.setEnabled(pending);
        drop.setEnabled(pending);
        rerun.setEnabled(ended && runnable);
    }

    private SearchPlanEntry selected() {
        int view = table.getSelectedRow();
        return view < 0 ? null : model.at(table.convertRowIndexToModel(view));
    }

    JTable table() {
        return table;
    }

    static final class EntryModel extends AbstractTableModel {
        private List<SearchPlanEntry> rows = new ArrayList<SearchPlanEntry>();

        void set(List<SearchPlanEntry> entries) {
            rows = new ArrayList<SearchPlanEntry>(entries);
            fireTableDataChanged();
        }

        SearchPlanEntry at(int row) {
            return row >= 0 && row < rows.size() ? rows.get(row) : null;
        }

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

        public Object getValueAt(int row, int column) {
            SearchPlanEntry entry = rows.get(row);
            switch (column) {
                case 0:
                    String note = entry.getNote();
                    String status = entry.getStatus().name().toLowerCase(java.util.Locale.ROOT);
                    return note == null || note.isEmpty() ? status : status + " (" + note + ")";
                case 1:
                    return entry.getNodeLabel();
                case 2:
                    return entry.getQuery();
                default:
                    return Integer.toString(entry.getAcceptedSourceIds().size());
            }
        }
    }
}
