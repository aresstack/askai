package com.aresstack.askai.plugin.host;

import com.aresstack.askai.plugin.api.WorkspacePluginDescriptor;
import com.aresstack.comiccontrols.control.ComicButton;
import com.aresstack.comiccontrols.control.ComicTableSupport;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.FlowLayout;
import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * The Configuration → Plugins screen: a read-only view of the real PF4J catalog (never a second agent
 * manager). All columns come from actual catalog data; Enable/Disable acts at plugin level, persisted by
 * stable id, and disabling drops a plugin from the Questing agent list. It never invents live JAR
 * replacement, and a broken plugin only shows as a Failed row — it does not break this view.
 */
public final class PluginManagementPanel extends JPanel {

    private static final String[] COLUMNS = {
        "Name", "ID", "Version", "Provider", "State", "Compatibility", "Enabled", "SHA-256", "Location"
    };

    private final WorkspacePluginService pluginService;
    private final PluginEnablementService enablement;
    private final File pluginsDirectory;

    private final DefaultTableModel tableModel = new DefaultTableModel(COLUMNS, 0) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };
    private final JTable table = new JTable(tableModel);
    private final JButton enableButton = new ComicButton("Enable");
    private final JButton disableButton = new ComicButton("Disable");
    private final JButton detailsButton = new ComicButton("Show details");
    private final JButton settingsButton = new ComicButton("Settings\u2026", ComicButton.Accent.ACTION);
    private SettingsOpener settingsOpener;
    private final javax.swing.JLabel globalStatusLabel = new javax.swing.JLabel(" ");
    private List<PluginCatalogEntry> entries = new ArrayList<PluginCatalogEntry>();

    /**
     * Opens a plugin's own settings (the categories it contributes to Settings). Set by the host; without
     * it the "Settings…" button stays hidden.
     */
    public interface SettingsOpener {
        /** Whether the plugin with this stable id contributes settings pages. */
        boolean hasSettings(String pluginId);

        /** Show the plugin's settings (the host activates whatever the pages need). */
        void openSettings(String pluginId);
    }

    public PluginManagementPanel(WorkspacePluginService pluginService, PluginEnablementService enablement,
                                 File pluginsDirectory) {
        super(new BorderLayout(8, 8));
        this.pluginService = pluginService;
        this.enablement = enablement;
        this.pluginsDirectory = pluginsDirectory;
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        ComicTableSupport.style(table);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getSelectionModel().addListSelectionListener(event -> updateButtons());
        globalStatusLabel.setBorder(BorderFactory.createEmptyBorder(0, 2, 6, 2));
        add(globalStatusLabel, BorderLayout.NORTH);
        add(new JScrollPane(table), BorderLayout.CENTER);
        add(buildToolbar(), BorderLayout.SOUTH);

        pluginService.addCatalogListener(new WorkspaceCatalogListener() {
            @Override
            public void onCatalogReady(List<PluginCatalogEntry> catalog, List<PluginLoadFailure> failures) {
                setEntries(catalog);
            }

            @Override
            public void onCatalogSnapshot(PluginCatalogSnapshot snapshot) {
                setEntries(snapshot.getEntries());
                showGlobalStatus(snapshot);
            }
        });
        setEntries(pluginService.getCatalog());
        showGlobalStatus(pluginService.getCatalogSnapshot());
        pluginService.refreshAsync();
    }

    /** Render global (non-plugin) discovery/start failures and the "previous generation kept" banner. */
    private void showGlobalStatus(PluginCatalogSnapshot snapshot) {
        if (snapshot == null || snapshot.getGlobalFailures().isEmpty()) {
            globalStatusLabel.setText(" ");
            globalStatusLabel.setForeground(java.awt.Color.DARK_GRAY);
            return;
        }
        StringBuilder text = new StringBuilder("<html>");
        if (snapshot.isGenerationFailed()) {
            text.append("<b>Refresh failed; previous plugin generation remains active.</b><br>");
        } else {
            text.append("<b>Plugin discovery reported problems.</b><br>");
        }
        for (PluginLoadFailure failure : snapshot.getGlobalFailures()) {
            text.append(escape(failure.getPublicMessage())).append("<br>");
        }
        text.append("</html>");
        globalStatusLabel.setText(text.toString());
        globalStatusLabel.setForeground(java.awt.Color.RED.darker());
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private JComponent buildToolbar() {
        JButton refresh = new ComicButton("Refresh catalog");
        refresh.addActionListener(event -> pluginService.refreshAsync());
        JButton openFolder = new ComicButton("Open plugin folder");
        openFolder.addActionListener(event -> openPluginFolder());
        enableButton.addActionListener(event -> setSelectedEnabled(true));
        disableButton.addActionListener(event -> setSelectedEnabled(false));
        detailsButton.addActionListener(event -> showDetails());
        settingsButton.addActionListener(event -> openSelectedSettings());
        settingsButton.setToolTipText("Open the selected plugin's own settings");
        // Double-click a row = open its settings (when it has any).
        table.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent event) {
                if (event.getClickCount() == 2 && settingsButton.isVisible() && settingsButton.isEnabled()) {
                    openSelectedSettings();
                }
            }
        });

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        left.setOpaque(false);
        left.add(enableButton);
        left.add(disableButton);
        left.add(detailsButton);
        left.add(openFolder);
        left.add(refresh);
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        right.setOpaque(false);
        right.add(settingsButton);
        JPanel bar = new JPanel(new BorderLayout(6, 0));
        bar.setOpaque(false);
        bar.add(left, BorderLayout.CENTER);
        bar.add(right, BorderLayout.EAST);
        updateButtons();
        return bar;
    }

    private void setEntries(List<PluginCatalogEntry> catalog) {
        this.entries = catalog == null ? new ArrayList<PluginCatalogEntry>() : catalog;
        String selectedId = selectedEntry() == null ? null : stableId(selectedEntry());
        tableModel.setRowCount(0);
        for (PluginCatalogEntry entry : entries) {
            WorkspacePluginDescriptor d = entry.getDescriptor();
            tableModel.addRow(new Object[] {
                d != null ? d.getDisplayName()
                        : (entry.getPluginId().isEmpty() ? "(unreadable)" : entry.getPluginId()),
                stableId(entry),
                d == null ? "" : d.getVersion(),
                d == null ? "" : d.getProvider(),
                entry.getPluginState(),
                String.valueOf(entry.getCompatibility()),
                entry.isEnabled() ? "Yes" : "No",
                shortHash(entry.getSha256()),
                entry.getLocation()
            });
        }
        // Keep the selection across catalog refreshes; with a single plugin, preselect it so its
        // buttons (Settings…) are usable right away.
        for (int i = 0; i < this.entries.size(); i++) {
            if (stableId(this.entries.get(i)).equals(selectedId)
                    || (selectedId == null && this.entries.size() == 1)) {
                table.getSelectionModel().setSelectionInterval(i, i);
                break;
            }
        }
        updateButtons();
    }

    private void updateButtons() {
        PluginCatalogEntry entry = selectedEntry();
        boolean has = entry != null;
        enableButton.setEnabled(has && !entry.isEnabled());
        disableButton.setEnabled(has && entry.isEnabled());
        detailsButton.setEnabled(has);
        settingsButton.setVisible(settingsOpener != null);
        settingsButton.setEnabled(has && settingsOpener != null && entry.isEnabled()
                && settingsOpener.hasSettings(stableId(entry)));
    }

    /** Wires the "Settings…" button (bottom right) to the host; null hides it. */
    public void setSettingsOpener(SettingsOpener opener) {
        this.settingsOpener = opener;
        updateButtons();
    }

    private void openSelectedSettings() {
        PluginCatalogEntry entry = selectedEntry();
        if (entry != null && settingsOpener != null) {
            settingsOpener.openSettings(stableId(entry));
        }
    }

    private void setSelectedEnabled(boolean enabled) {
        PluginCatalogEntry entry = selectedEntry();
        if (entry == null) {
            return;
        }
        enablement.setEnabled(stableId(entry), enabled);
        pluginService.refreshAsync(); // re-filters the selectable agents; the controller re-resolves centrally
    }

    private void openPluginFolder() {
        try {
            if (pluginsDirectory != null && Desktop.isDesktopSupported()) {
                if (!pluginsDirectory.exists()) {
                    pluginsDirectory.mkdirs();
                }
                Desktop.getDesktop().open(pluginsDirectory);
            }
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Could not open the plugin folder: " + ex.getMessage(),
                    "Plugins", JOptionPane.WARNING_MESSAGE);
        }
    }

    private void showDetails() {
        PluginCatalogEntry entry = selectedEntry();
        if (entry == null) {
            return;
        }
        StringBuilder text = new StringBuilder();
        text.append("Plugin id: ").append(stableId(entry)).append('\n');
        text.append("State: ").append(entry.getPluginState()).append('\n');
        text.append("Compatibility: ").append(entry.getCompatibility()).append('\n');
        text.append("Enabled: ").append(entry.isEnabled()).append('\n');
        text.append("Location: ").append(entry.getLocation()).append('\n');
        text.append("SHA-256: ").append(entry.getSha256()).append('\n');
        PluginLoadFailure failure = entry.getLastError();
        if (failure != null) {
            text.append("\nLast error (").append(failure.getPhase()).append("): ")
                    .append(failure.getPublicMessage()).append('\n');
            if (failure.getTechnicalCause() != null) {
                StringWriter sw = new StringWriter();
                failure.getTechnicalCause().printStackTrace(new PrintWriter(sw));
                text.append('\n').append(sw.toString());
            }
        }
        JTextArea area = new JTextArea(text.toString(), 20, 70);
        area.setEditable(false);
        area.setCaretPosition(0);
        JOptionPane.showMessageDialog(this, new JScrollPane(area), "Plugin details",
                JOptionPane.PLAIN_MESSAGE);
    }

    private PluginCatalogEntry selectedEntry() {
        int row = table.getSelectedRow();
        return row >= 0 && row < entries.size() ? entries.get(row) : null;
    }

    private static String stableId(PluginCatalogEntry entry) {
        return entry.getDescriptor() != null ? entry.getDescriptor().getId() : entry.getPluginId();
    }

    private static String shortHash(String sha256) {
        if (sha256 == null || sha256.length() < 12) {
            return sha256 == null ? "" : sha256;
        }
        return sha256.substring(0, 12) + "…";
    }
}
