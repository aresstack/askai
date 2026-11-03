package com.aresstack.askai.research.host;

import com.aresstack.comiccontrols.control.ComicControls;
import com.aresstack.comiccontrols.control.ComicButton;
import com.aresstack.comiccontrols.theme.ThemeColor;
import com.aresstack.askai.browser.search.engine.BrowserSearchEngine;
import com.aresstack.askai.browser.search.engine.BrowserSearchEngineCatalog;
import com.aresstack.askai.browser.search.engine.BrowserSearchEngineSelection;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * The search-engine list: which engines take part, and in which order they are tried. The order in this
 * list IS the execution order — moving DuckDuckGo above Bing means DuckDuckGo is asked first, not that a
 * hint was recorded somewhere.
 * <p>
 * The per-engine settings live IN the row: an unselected row reads as plain text
 * ("Bing — 3 pages, delay 1.5 s"), the SELECTED row shows the two spinners (result pages, request
 * delay in seconds) inline — no separate editor column beside the list.
 * <p>
 * Engines the catalog knows but the stored configuration does not mention (a newly shipped one) appear at
 * the end, switched OFF: gaining a search engine should be the user's decision, not a side effect of an
 * update.
 */
final class SearchEngineOrderEditor extends JPanel {

    private static final class Row {
        final String engineId;
        final String displayName;
        boolean enabled;
        /** Result pages one search fetches from this engine (the user's per-engine setting). */
        int resultPages;
        /** Pause before every further request to this engine, in milliseconds (0 = off). */
        int delayMillis;

        Row(String engineId, String displayName, boolean enabled, int resultPages, int delayMillis) {
            this.engineId = engineId;
            this.displayName = displayName;
            this.enabled = enabled;
            this.resultPages = resultPages;
            this.delayMillis = delayMillis;
        }
    }

    private final List<Row> rows = new ArrayList<Row>();
    private int selectedIndex = -1;
    private final JPanel rowsPanel = new JPanel();

    /** Result pages of the SELECTED engine — shown inline in its row. */
    private final javax.swing.JSpinner pagesSpinner = ComicControls.spinner(
            new javax.swing.SpinnerNumberModel(
                    BrowserSearchEngineSelection.Entry.DEFAULT_RESULT_PAGES, 1, 10, 1));

    /** Request delay of the SELECTED engine, in seconds (0 = off, no upper bound imposed here). */
    private final javax.swing.JSpinner delaySpinner = ComicControls.spinner(
            new javax.swing.SpinnerNumberModel(Double.valueOf(0), Double.valueOf(0), null,
                    Double.valueOf(0.1)));

    /** True while the spinners are being synced FROM the model — their change events are then not edits. */
    private boolean syncingSpinners;

    SearchEngineOrderEditor(String encodedValue) {
        super(new BorderLayout(6, 0));
        setAlignmentX(Component.LEFT_ALIGNMENT);
        rowsPanel.setLayout(new BoxLayout(rowsPanel, BoxLayout.Y_AXIS));
        rowsPanel.setBackground(listBackground());
        rowsPanel.setOpaque(true);

        pagesSpinner.setToolTipText("How many result pages of this engine one search fetches "
                + "(sequentially, evaluated between fetches)");
        pagesSpinner.setMaximumSize(new Dimension(56, 24));
        pagesSpinner.addChangeListener(new javax.swing.event.ChangeListener() {
            public void stateChanged(javax.swing.event.ChangeEvent event) {
                if (!syncingSpinners && selectedIndex >= 0) {
                    rows.get(selectedIndex).resultPages = (Integer) pagesSpinner.getValue();
                }
            }
        });
        delaySpinner.setToolTipText("Delay in seconds before each further request to this engine "
                + "(0 = off; up to three decimals)");
        delaySpinner.setEditor(new javax.swing.JSpinner.NumberEditor(delaySpinner, "0.###"));
        delaySpinner.setMaximumSize(new Dimension(64, 24));
        delaySpinner.addChangeListener(new javax.swing.event.ChangeListener() {
            public void stateChanged(javax.swing.event.ChangeEvent event) {
                if (!syncingSpinners && selectedIndex >= 0) {
                    rows.get(selectedIndex).delayMillis = (int) Math.round(
                            ((Number) delaySpinner.getValue()).doubleValue() * 1000.0);
                }
            }
        });

        JScrollPane scroll = new JScrollPane(rowsPanel);
        scroll.setPreferredSize(new Dimension(380, 96));
        scroll.setMaximumSize(new Dimension(480, 128));
        scroll.getViewport().setBackground(listBackground());
        add(scroll, BorderLayout.CENTER);

        JPanel buttons = new JPanel();
        buttons.setLayout(new BoxLayout(buttons, BoxLayout.Y_AXIS));
        buttons.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 0));
        buttons.add(moveButton("↑", -1));
        buttons.add(Box.createVerticalStrut(4));
        buttons.add(moveButton("↓", 1));
        buttons.add(Box.createVerticalGlue());
        add(buttons, BorderLayout.EAST);

        set(encodedValue);
    }

    private JButton moveButton(String label, final int delta) {
        JButton button = new ComicButton(label);
        button.setMargin(new java.awt.Insets(1, 6, 1, 6));
        button.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent event) {
                move(delta);
            }
        });
        return button;
    }

    private void move(int delta) {
        int target = selectedIndex + delta;
        if (selectedIndex < 0 || target < 0 || target >= rows.size()) {
            return;
        }
        rows.add(target, rows.remove(selectedIndex));
        selectedIndex = target;
        rebuildRows();
    }

    /** Select a row: its text collapses to the name and the two inline spinners appear. */
    private void select(int index) {
        if (index == selectedIndex || index < 0 || index >= rows.size()) {
            return;
        }
        selectedIndex = index;
        rebuildRows();
    }

    private void rebuildRows() {
        rowsPanel.removeAll();
        for (int i = 0; i < rows.size(); i++) {
            rowsPanel.add(rowPanel(i, rows.get(i)));
        }
        rowsPanel.add(Box.createVerticalGlue());
        rowsPanel.revalidate();
        rowsPanel.repaint();
    }

    private JPanel rowPanel(final int index, final Row row) {
        boolean selected = index == selectedIndex;
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.X_AXIS));
        panel.setOpaque(true);
        panel.setBackground(selected ? selectionBackground() : listBackground());
        panel.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        Color foreground = selected ? selectionForeground() : UIManager.getColor("List.foreground");

        final JCheckBox box = ComicControls.checkBox(
                selected ? row.displayName : summaryFor(row));
        box.setSelected(row.enabled);
        box.setOpaque(false);
        box.setForeground(foreground);
        box.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent event) {
                row.enabled = box.isSelected();
                select(index);
            }
        });
        panel.add(box);

        if (selected) {
            // The spinners live IN the selected row — the shared instances are synced to it first.
            syncingSpinners = true;
            pagesSpinner.setValue(row.resultPages);
            delaySpinner.setValue(row.delayMillis / 1000.0);
            syncingSpinners = false;
            panel.add(Box.createHorizontalStrut(10));
            panel.add(inlineLabel("Pages:", foreground));
            panel.add(Box.createHorizontalStrut(3));
            panel.add(pagesSpinner);
            panel.add(Box.createHorizontalStrut(10));
            panel.add(inlineLabel("Delay (s):", foreground));
            panel.add(Box.createHorizontalStrut(3));
            panel.add(delaySpinner);
        }
        panel.add(Box.createHorizontalGlue());
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, panel.getPreferredSize().height));
        panel.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent event) {
                select(index);
            }
        });
        return panel;
    }

    private static JLabel inlineLabel(String text, Color foreground) {
        JLabel label = new JLabel(text);
        label.setForeground(foreground);
        return label;
    }

    /** {@code "Bing  — 3 pages, delay 1.5 s"} — the unselected row is pure text, delay only when on. */
    private static String summaryFor(Row row) {
        String delay = row.delayMillis > 0
                ? ", delay " + BrowserSearchEngineSelection
                        .formatDelaySeconds(row.delayMillis) + " s"
                : "";
        String where = BrowserSearchEngineCatalog.runsInSearchBrowser(row.engineId) ? "search browser, " : "";
        return row.displayName + "  — " + where + row.resultPages
                + (row.resultPages == 1 ? " page" : " pages") + delay;
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

    /** The flat form the settings codec stores: {@code "duckduckgo:on:3,bing:off:3"}, order significant. */
    String get() {
        List<BrowserSearchEngineSelection.Entry> entries =
                new ArrayList<BrowserSearchEngineSelection.Entry>();
        for (Row row : rows) {
            entries.add(new BrowserSearchEngineSelection.Entry(row.engineId, row.enabled,
                    row.resultPages, row.delayMillis));
        }
        return new BrowserSearchEngineSelection(entries, null).encodeEntries();
    }

    void set(String encodedValue) {
        rows.clear();
        selectedIndex = -1;
        List<String> placed = new ArrayList<String>();
        for (BrowserSearchEngineSelection.Entry entry
                : BrowserSearchEngineSelection.parseEntries(encodedValue)) {
            BrowserSearchEngine engine = BrowserSearchEngineCatalog.byId(entry.getEngineId());
            if (engine == null) {
                continue; // an engine this build does not know: keep it out of the user's way
            }
            rows.add(new Row(engine.getId(), engine.getDisplayName(), entry.isEnabled(),
                    entry.getResultPages(), entry.getDelayMillis()));
            placed.add(engine.getId());
        }
        for (BrowserSearchEngine engine : BrowserSearchEngineCatalog.engines()) {
            if (!placed.contains(engine.getId())) {
                rows.add(new Row(engine.getId(), engine.getDisplayName(), false,
                        BrowserSearchEngineSelection.Entry.DEFAULT_RESULT_PAGES,
                        BrowserSearchEngineSelection.Entry.DEFAULT_DELAY_MILLIS));
            }
        }
        rebuildRows();
    }
}
