package com.aresstack.askai.ui;

import com.aresstack.comiccontrols.control.WrapLayout;
import com.aresstack.comiccontrols.theme.ThemeColor;
import com.aresstack.comiccontrols.control.ComicControls;
import com.aresstack.comiccontrols.control.ComicButton;
import com.aresstack.askai.hf.SearchFilterState;
import com.aresstack.askai.hf.SearchFilterState.Group;
import com.aresstack.askai.hf.catalog.CatalogBundle;
import com.aresstack.askai.hf.catalog.CatalogEntry;
import com.aresstack.askai.hf.catalog.FilterCatalogs;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextField;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The faceted-search filter dialog: a Main quick-overview tab plus Tasks / Libraries / Languages /
 * Licenses / Other / Apps detail tabs, all reading and writing one shared {@link SearchFilterState} (a
 * selection in Main is reflected in the matching detail tab and vice-versa).
 *
 * <p>The catalogs come from live HuggingFace data (with cache/bundled fallbacks); a header shows the
 * origin + per-group counts and a Refresh button re-fetches live. Values selected but absent from the
 * current catalog are still shown (and kept selected) so refreshing never silently drops a choice.
 * The Languages tab (thousands of entries) renders lazily by the filter text to stay responsive.</p>
 */
public final class FilterDialog extends JDialog {

    /** Above this size a flat detail tab renders lazily (by filter) instead of all checkboxes at once. */
    private static final int LAZY_THRESHOLD = 400;
    /** How many entries a lazy tab shows before the user narrows with the filter field. */
    private static final int LAZY_INITIAL = 300;

    /** Lets the dialog ask the host to re-fetch the catalogs live, without depending on the service. */
    public interface CatalogRefresher {
        void refresh(RefreshCallback callback);
    }

    public interface RefreshCallback {
        void done(CatalogBundle bundle);

        void failed(String message);
    }

    private final SearchFilterState state;
    private final CatalogRefresher refresher;
    private final List<Runnable> syncers = new ArrayList<Runnable>();

    private FilterCatalogs catalogs;
    private CatalogBundle bundle;
    private JTabbedPane tabs;
    private JLabel originLabel;
    private JButton refreshButton;
    private boolean applied;

    public FilterDialog(Frame owner, SearchFilterState state, CatalogBundle bundle, CatalogRefresher refresher) {
        super(owner, "Search filters", true);
        this.state = state;
        this.bundle = bundle;
        this.catalogs = bundle.getCatalogs();
        this.refresher = refresher;
        buildUserInterface();
        setSize(580, 660);
        setLocationRelativeTo(owner);
    }

    /** @return true when the user pressed Apply (the shared state should be re-run/persisted). */
    public boolean isApplied() {
        return applied;
    }

    private void buildUserInterface() {
        setLayout(new BorderLayout(0, 0));
        add(buildHeader(), BorderLayout.NORTH);
        tabs = ComicControls.tabbedPane();
        // Re-sync tab checkboxes from the shared state on tab switch. Registered ONCE here — never in
        // rebuildTabs, which used to strip every ChangeListener (including BasicTabbedPaneUI's own
        // selection handler), leaving the tab header not repainting on click until the next rebuild.
        tabs.addChangeListener(event -> syncAll());
        rebuildTabs();
        add(tabs, BorderLayout.CENTER);
        add(buildFooter(), BorderLayout.SOUTH);
    }

    // ------------------------------------------------------------------ header (origin + refresh)

    private JComponent buildHeader() {
        originLabel = new JLabel();
        updateOriginLabel();
        refreshButton = new ComicButton("Refresh");
        refreshButton.setToolTipText("Reload the filter catalogs live from HuggingFace");
        refreshButton.addActionListener(event -> doRefresh());
        if (refresher == null) {
            refreshButton.setEnabled(false);
        }
        JPanel header = new JPanel(new BorderLayout(6, 0));
        header.setBorder(BorderFactory.createEmptyBorder(6, 8, 4, 8));
        header.add(originLabel, BorderLayout.CENTER);
        header.add(refreshButton, BorderLayout.EAST);
        return header;
    }

    /** The catalog error text color as an HTML hex string of its current theme value (built per message). */
    private static String errorTextHex() {
        return ThemeColor.toHtmlHex(ThemeColor.of("models.filterErrorText"));
    }

    private void updateOriginLabel() {
        String message = bundle.getMessage();
        originLabel.setText("<html>Quelle: <b>" + bundle.describe() + "</b>"
                + (message.length() > 0 ? " <span style='color:" + errorTextHex() + "'>(" + message + ")</span>" : "")
                + "</html>");
    }

    private void doRefresh() {
        if (refresher == null) {
            return;
        }
        refreshButton.setEnabled(false);
        originLabel.setText("Aktualisiere Katalog …");
        refresher.refresh(new RefreshCallback() {
            public void done(CatalogBundle refreshed) {
                bundle = refreshed;
                catalogs = refreshed.getCatalogs();
                rebuildTabs();
                updateOriginLabel();
                refreshButton.setEnabled(true);
            }

            public void failed(String errorMessage) {
                originLabel.setText("<html>Aktualisierung fehlgeschlagen: <span style='color:" + errorTextHex() + "'>"
                        + errorMessage + "</span> — vorherige Daten bleiben.</html>");
                refreshButton.setEnabled(true);
            }
        });
    }

    /** Rebuilds all tabs from the current {@link #catalogs} (used initially and after a refresh). */
    private void rebuildTabs() {
        syncers.clear();
        int selected = tabs.getTabCount() > 0 ? tabs.getSelectedIndex() : 0;
        tabs.removeAll();
        tabs.addTab("Main", buildMainTab());
        tabs.addTab("Tasks", buildCatalogTab(Group.TASKS, catalogs.getTasks(), true));
        tabs.addTab("Libraries", buildCatalogTab(Group.LIBRARIES, catalogs.getLibraries(), false));
        tabs.addTab("Languages", buildCatalogTab(Group.LANGUAGES, catalogs.getLanguages(), false));
        tabs.addTab("Licenses", buildCatalogTab(Group.LICENSES, catalogs.getLicenses(), false));
        tabs.addTab("Other", buildCatalogTab(Group.OTHER, catalogs.getOther(), true));
        tabs.addTab("Apps", buildCatalogTab(Group.APPS, catalogs.getApps(), false));
        if (selected >= 0 && selected < tabs.getTabCount()) {
            tabs.setSelectedIndex(selected);
        }
    }

    // ------------------------------------------------------------------ footer

    private JComponent buildFooter() {
        JButton resetAll = new ComicButton("Reset all");
        resetAll.addActionListener(event -> {
            state.resetAll();
            rebuildTabs();
        });
        JButton apply = new ComicButton("Apply");
        apply.addActionListener(event -> {
            applied = true;
            dispose();
        });
        JButton close = new ComicButton("Close");
        close.addActionListener(event -> dispose());
        JPanel footer = new JPanel(new BorderLayout());
        footer.setBorder(BorderFactory.createEmptyBorder(6, 8, 8, 8));
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        left.add(resetAll);
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        right.add(apply);
        right.add(close);
        footer.add(left, BorderLayout.WEST);
        footer.add(right, BorderLayout.EAST);
        return footer;
    }

    // ------------------------------------------------------------------ Main tab

    private JComponent buildMainTab() {
        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        content.add(sectionLabel("Common tasks"));
        content.add(quickPickRow(Group.TASKS, new String[]{
                "text-generation", "image-text-to-text", "audio-text-to-text",
                "automatic-speech-recognition", "text-to-image", "feature-extraction"}));
        content.add(Box.createVerticalStrut(8));

        content.add(sectionLabel("Common libraries"));
        content.add(quickPickRow(Group.LIBRARIES, new String[]{
                "gguf", "safetensors", "transformers", "pytorch", "onnx", "mlx", "diffusers"}));
        content.add(Box.createVerticalStrut(8));

        content.add(sectionLabel("Apps (HuggingFace compatibility hint, not proof of local install)"));
        content.add(quickPickRow(Group.APPS, new String[]{
                "ollama", "llama.cpp", "vllm", "lmstudio", "mlx-lm", "jan"}));
        content.add(Box.createVerticalStrut(8));

        content.add(sectionLabel("Repository"));
        content.add(gatedRow());
        content.add(inferenceRow());
        content.add(Box.createVerticalStrut(8));

        content.add(comingSoonSection("Parameters",
                "The public HuggingFace API has no working server-side parameter filter, so a range "
                        + "slider here would only sort a partial page. Deferred until AskAI can compute "
                        + "it locally from repository metadata."));
        content.add(comingSoonSection("Inference Providers",
                "Groq, Novita, Cerebras, Together AI, Fireworks, ... — filtering by hosted provider "
                        + "is planned; no active filter in this build."));
        content.add(comingSoonSection("Hardware",
                "Local GPU / VRAM-aware \"fits fully in VRAM\" marking is a planned AskAI feature; not "
                        + "available yet."));
        content.add(Box.createVerticalGlue());

        JScrollPane scroll = new JScrollPane(content);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(null);
        return scroll;
    }

    private JComponent quickPickRow(Group group, String[] ids) {
        JPanel row = new JPanel(new WrapLayout(FlowLayout.LEFT, 4, 2));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        for (int i = 0; i < ids.length; i++) {
            final String id = ids[i];
            final JCheckBox box = ComicControls.checkBox(displayNameFor(group, id), state.isSelected(group, id));
            box.addActionListener(event -> state.setSelected(group, id, box.isSelected()));
            syncers.add(new Runnable() {
                public void run() {
                    box.setSelected(state.isSelected(group, id));
                }
            });
            row.add(box);
        }
        return row;
    }

    private JComponent gatedRow() {
        JPanel row = new JPanel(new WrapLayout(FlowLayout.LEFT, 4, 2));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        final JCheckBox gated = ComicControls.checkBox("Gated only", state.isGated());
        gated.setToolTipText("Only repositories that require accepting terms / access (HuggingFace gated=true)");
        gated.addActionListener(event -> state.setGated(gated.isSelected()));
        syncers.add(new Runnable() {
            public void run() {
                gated.setSelected(state.isGated());
            }
        });
        row.add(gated);
        return row;
    }

    private JComponent inferenceRow() {
        JPanel row = new JPanel(new WrapLayout(FlowLayout.LEFT, 4, 2));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        final JCheckBox inference = ComicControls.checkBox("Warm inference only", state.isInference());
        inference.setToolTipText("Only models served by at least one warm inference provider "
                + "(HuggingFace inference=warm)");
        inference.addActionListener(event -> state.setInference(inference.isSelected()));
        syncers.add(new Runnable() {
            public void run() {
                inference.setSelected(state.isInference());
            }
        });
        row.add(inference);
        return row;
    }

    private JComponent comingSoonSection(String title, String explanation) {
        JPanel panel = new JPanel(new BorderLayout(0, 2));
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, ThemeColor.of("models.sectionDivider")),
                BorderFactory.createEmptyBorder(6, 0, 6, 0)));
        JPanel header = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        JLabel titleLabel = new JLabel(title);
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD));
        JLabel badge = new JLabel("Coming Soon");
        badge.setOpaque(true);
        badge.setBackground(ThemeColor.of("models.unavailableBadge"));
        badge.setForeground(ThemeColor.of("bubble.userForeground"));
        badge.setBorder(BorderFactory.createEmptyBorder(1, 6, 1, 6));
        header.add(titleLabel);
        header.add(badge);
        // The text color rides on the label's living foreground (no CSS color), so it follows theme switches.
        JLabel body = new JLabel("<html><body style='width:500px'>" + explanation + "</body></html>");
        body.setForeground(ThemeColor.of("models.comingSoonText"));
        panel.add(header, BorderLayout.NORTH);
        panel.add(body, BorderLayout.CENTER);
        return panel;
    }

    private JComponent sectionLabel(String text) {
        JLabel label = new JLabel(text);
        label.setFont(label.getFont().deriveFont(Font.BOLD));
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        label.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
        return label;
    }

    // ------------------------------------------------------------------ detail tabs

    /**
     * A detail tab: a "Filter … by name" field, a scroll pane of checkboxes, a live count and a
     * per-group Reset. Small grouped tabs (Tasks/Other) render all checkboxes under category headers;
     * large flat tabs (Languages) render lazily by the filter to stay responsive. Any selected id not
     * present in the current catalog is shown at the top as a checked "(nicht im aktuellen Katalog)"
     * entry so a refresh never silently drops a choice.
     */
    private JComponent buildCatalogTab(final Group group, final List<CatalogEntry> entries, boolean grouped) {
        final JPanel list = new JPanel();
        list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
        list.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));

        final List<JCheckBox> boxes = new ArrayList<JCheckBox>();
        final boolean lazy = !grouped && entries.size() > LAZY_THRESHOLD;
        final JLabel countLabel = new JLabel();
        final JLabel shownLabel = new JLabel();

        // Selected ids that are not in the current catalog — rendered at the top so they stay visible.
        final List<CatalogEntry> orphans = orphanEntries(group, entries);

        Runnable updateCount = new Runnable() {
            public void run() {
                countLabel.setText(state.count(group) + " selected");
            }
        };

        if (lazy) {
            final JTextField searchField = ComicControls.textField();
            Runnable render = new Runnable() {
                public void run() {
                    renderLazy(list, boxes, group, orphans, entries, searchField.getText(), shownLabel, updateCount);
                }
            };
            render.run();
            searchField.getDocument().addDocumentListener(new DocumentListener() {
                public void insertUpdate(DocumentEvent e) { render.run(); }
                public void removeUpdate(DocumentEvent e) { render.run(); }
                public void changedUpdate(DocumentEvent e) { render.run(); }
            });
            syncers.add(new Runnable() {
                public void run() {
                    render.run();
                }
            });
            return assembleTab(group, list, searchField, countLabel, shownLabel, updateCount);
        }

        // Small tab: render everything (orphans first, then grouped/flat).
        final List<Component> categoryHeaders = new ArrayList<Component>();
        addOrphanBoxes(list, boxes, group, orphans, updateCount);
        if (grouped) {
            Map<String, List<CatalogEntry>> byGroup = groupEntries(group, entries);
            for (Map.Entry<String, List<CatalogEntry>> bucket : byGroup.entrySet()) {
                JLabel header = new JLabel(bucket.getKey());
                header.setFont(header.getFont().deriveFont(Font.BOLD));
                header.setAlignmentX(Component.LEFT_ALIGNMENT);
                header.setBorder(BorderFactory.createEmptyBorder(6, 0, 2, 0));
                list.add(header);
                categoryHeaders.add(header);
                for (CatalogEntry entry : bucket.getValue()) {
                    JCheckBox box = catalogCheckbox(group, entry, updateCount);
                    boxes.add(box);
                    list.add(box);
                }
            }
        } else {
            for (CatalogEntry entry : entries) {
                JCheckBox box = catalogCheckbox(group, entry, updateCount);
                boxes.add(box);
                list.add(box);
            }
        }

        syncers.add(new Runnable() {
            public void run() {
                for (int i = 0; i < boxes.size(); i++) {
                    JCheckBox box = boxes.get(i);
                    box.setSelected(state.isSelected(group, (String) box.getClientProperty("filterId")));
                }
                countLabel.setText(state.count(group) + " selected");
            }
        });
        updateCount.run();

        final JTextField searchField = ComicControls.textField();
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { filter(); }
            public void removeUpdate(DocumentEvent e) { filter(); }
            public void changedUpdate(DocumentEvent e) { filter(); }
            private void filter() {
                String needle = searchField.getText().trim().toLowerCase(Locale.ROOT);
                for (int i = 0; i < boxes.size(); i++) {
                    JCheckBox box = boxes.get(i);
                    boolean match = needle.length() == 0 || box.getText().toLowerCase(Locale.ROOT).contains(needle)
                            || String.valueOf(box.getClientProperty("filterId")).toLowerCase(Locale.ROOT).contains(needle);
                    box.setVisible(match);
                }
                for (int i = 0; i < categoryHeaders.size(); i++) {
                    categoryHeaders.get(i).setVisible(needle.length() == 0);
                }
                list.revalidate();
                list.repaint();
            }
        });
        return assembleTab(group, list, searchField, countLabel, shownLabel, updateCount);
    }

    /** Clears and repopulates a lazy tab's list for the current filter text. */
    private void renderLazy(JPanel list, List<JCheckBox> boxes, Group group, List<CatalogEntry> orphans,
                            List<CatalogEntry> entries, String filterText, JLabel shownLabel, Runnable updateCount) {
        list.removeAll();
        boxes.clear();
        String needle = filterText.trim().toLowerCase(Locale.ROOT);
        addOrphanBoxes(list, boxes, group, orphans, updateCount);

        List<CatalogEntry> matches = new ArrayList<CatalogEntry>();
        for (int i = 0; i < entries.size(); i++) {
            CatalogEntry entry = entries.get(i);
            boolean match = needle.length() == 0
                    || entry.getDisplayName().toLowerCase(Locale.ROOT).contains(needle)
                    || entry.getId().toLowerCase(Locale.ROOT).contains(needle);
            if (match) {
                matches.add(entry);
            }
        }
        int cap = needle.length() == 0 ? LAZY_INITIAL : matches.size();
        int shown = Math.min(cap, matches.size());
        for (int i = 0; i < shown; i++) {
            JCheckBox box = catalogCheckbox(group, matches.get(i), updateCount);
            boxes.add(box);
            list.add(box);
        }
        if (shown < matches.size()) {
            JLabel more = new JLabel("… und " + (matches.size() - shown) + " weitere — zum Filtern tippen");
            more.setForeground(ThemeColor.of("models.mutedText"));
            more.setAlignmentX(Component.LEFT_ALIGNMENT);
            list.add(more);
        }
        shownLabel.setText(matches.size() + " / " + entries.size() + " Werte");
        updateCount.run();
        list.revalidate();
        list.repaint();
    }

    private JComponent assembleTab(final Group group, JPanel list, JTextField searchField,
                                   JLabel countLabel, JLabel shownLabel, final Runnable updateCount) {
        JButton reset = new ComicButton("Reset");
        reset.addActionListener(event -> {
            state.resetGroup(group);
            rebuildTabs();
        });

        JPanel top = new JPanel(new BorderLayout(6, 0));
        top.setBorder(BorderFactory.createEmptyBorder(6, 6, 4, 6));
        top.add(new JLabel("Filter by name:"), BorderLayout.WEST);
        top.add(searchField, BorderLayout.CENTER);

        JPanel bottom = new JPanel(new BorderLayout());
        bottom.setBorder(BorderFactory.createEmptyBorder(4, 6, 6, 6));
        JPanel counts = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        counts.add(countLabel);
        counts.add(shownLabel);
        bottom.add(counts, BorderLayout.WEST);
        JPanel resetWrap = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        resetWrap.add(reset);
        bottom.add(resetWrap, BorderLayout.EAST);

        JScrollPane scroll = new JScrollPane(list);
        scroll.getVerticalScrollBar().setUnitIncrement(16);

        JPanel tab = new JPanel(new BorderLayout());
        tab.add(top, BorderLayout.NORTH);
        tab.add(scroll, BorderLayout.CENTER);
        tab.add(bottom, BorderLayout.SOUTH);
        return tab;
    }

    /** @return selected ids in the group that are not present in the given catalog entries. */
    private List<CatalogEntry> orphanEntries(Group group, List<CatalogEntry> entries) {
        java.util.Set<String> known = new java.util.HashSet<String>();
        for (int i = 0; i < entries.size(); i++) {
            known.add(entries.get(i).getId());
        }
        List<CatalogEntry> orphans = new ArrayList<CatalogEntry>();
        for (String id : state.values(group)) {
            if (!known.contains(id)) {
                orphans.add(new CatalogEntry(id, id + " (nicht im aktuellen Katalog)", ""));
            }
        }
        return orphans;
    }

    private void addOrphanBoxes(JPanel list, List<JCheckBox> boxes, Group group, List<CatalogEntry> orphans,
                                Runnable updateCount) {
        for (int i = 0; i < orphans.size(); i++) {
            JCheckBox box = catalogCheckbox(group, orphans.get(i), updateCount);
            box.setForeground(ThemeColor.of("models.statusWarningText"));
            boxes.add(box);
            list.add(box);
        }
    }

    private JCheckBox catalogCheckbox(final Group group, CatalogEntry entry, final Runnable updateCount) {
        final String id = entry.getId();
        JCheckBox box = ComicControls.checkBox(entry.getDisplayName(), state.isSelected(group, id));
        box.setAlignmentX(Component.LEFT_ALIGNMENT);
        box.putClientProperty("filterId", id);
        box.setToolTipText(id);
        box.addActionListener(event -> {
            state.setSelected(group, id, box.isSelected());
            updateCount.run();
        });
        return box;
    }

    private Map<String, List<CatalogEntry>> groupEntries(Group group, List<CatalogEntry> entries) {
        if (group == Group.TASKS) {
            return catalogs.getTasksByCategory();
        }
        if (group == Group.OTHER) {
            return catalogs.getOtherBySubgroup();
        }
        LinkedHashMap<String, List<CatalogEntry>> single = new LinkedHashMap<String, List<CatalogEntry>>();
        single.put("", entries);
        return single;
    }

    private String displayNameFor(Group group, String id) {
        List<CatalogEntry> entries = catalogForGroup(group);
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).getId().equals(id)) {
                return entries.get(i).getDisplayName();
            }
        }
        return id;
    }

    private List<CatalogEntry> catalogForGroup(Group group) {
        switch (group) {
            case TASKS: return catalogs.getTasks();
            case LIBRARIES: return catalogs.getLibraries();
            case LANGUAGES: return catalogs.getLanguages();
            case LICENSES: return catalogs.getLicenses();
            case OTHER: return catalogs.getOther();
            case APPS: return catalogs.getApps();
            default: return new ArrayList<CatalogEntry>();
        }
    }

    private void syncAll() {
        for (int i = 0; i < syncers.size(); i++) {
            syncers.get(i).run();
        }
    }
}
