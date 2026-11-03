package com.aresstack.askai.research.host;

import com.aresstack.comiccontrols.control.ComicControls;
import com.aresstack.comiccontrols.control.ComicButton;
import com.aresstack.comiccontrols.control.ComicForm;
import com.aresstack.askai.research.search.config.DataForSeoSettingsDraft;
import com.aresstack.askai.research.search.dataforseo.DataForSeoDevice;
import com.aresstack.askai.research.search.dataforseo.DataForSeoOperatingSystem;
import com.aresstack.askai.research.search.dataforseo.DataForSeoPlayground;
import com.aresstack.askai.research.search.dataforseo.DataForSeoSearchConfiguration;
import com.aresstack.askai.research.search.dataforseo.DataForSeoSearchEngine;

import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JSpinner;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

/**
 * The provider-specific DataForSEO editor (its own card — the top provider combo only SELECTS it, it never
 * reduces DataForSEO to shared fields). Bound to ONE {@link DataForSeoSettingsDraft}: edits mutate the
 * draft's working config directly, so switching provider cards never reloads or discards them; the dialog
 * persists the draft on Save. The stored secret is never shown — the API key field stays empty over a
 * "Saved credential" hint, and only a freshly typed API key is (re)encrypted on save.
 *
 * <p>Built on the shared {@link ProviderSettingsForm} frame (Credentials, Search options, Advanced) plus a
 * Playground tab that runs the SAME productive adapter off the EDT against the CURRENT unsaved draft (a
 * depth or credential can be tried before saving). The provider is enabled exactly when a key is stored.</p>
 */
public final class DataForSeoSettingsPanel extends JPanel {

    private static final DataForSeoSearchEngine[] ENGINES = DataForSeoSearchEngine.values();

    private final DataForSeoSettingsDraft draft;
    private final DataForSeoPlayground playground;
    private final com.aresstack.askai.research.search.security.SecretValueService secrets;

    private final JPasswordField apiKey = ProviderSettingsForm.secretField();
    private final JLabel apiKeyHint = ProviderSettingsForm.status();
    private final JTextField endpointBase = ComicControls.textField(28);

    private final JComboBox<DataForSeoSearchEngine> engine =
            ComicControls.<DataForSeoSearchEngine>comboBox(ENGINES);
    private final JComboBox<RegionChoices.Choice> location = RegionChoices.dataForSeoLocations();
    private final JComboBox<RegionChoices.Choice> language = RegionChoices.languages();
    private final JComboBox<String> device = ComicControls.<String>comboBox(new String[]{"Desktop", "Mobile"});
    private final JComboBox<String> operatingSystem = ComicControls.<String>comboBox();
    private final JSpinner depth = ProviderSettingsForm.number(10, 1, 200, 4);
    private final JCheckBox groupOrganic = ComicControls.checkBox(
            "Group organic results from the same domain");
    private final DefaultTableModel removeFromUrl = new DefaultTableModel(
            new Object[]{"URL parameter to remove"}, 0);

    private final JTextField playgroundTerm = ComicControls.textField("wearables", 24);
    private final JButton runPlayground = new ComicButton("Run search");
    private final JLabel playgroundStatus = new JLabel(" ");
    private final DefaultTableModel playgroundResults = new DefaultTableModel(
            new Object[]{"Rank", "Title", "Domain", "URL"}, 0);
    private final JTextArea requestPreview = ComicControls.textArea(8, 40);
    private final JTextArea rawResponse = ComicControls.textArea(8, 40);

    public DataForSeoSettingsPanel(DataForSeoSettingsDraft draft, DataForSeoPlayground playground,
                                   com.aresstack.askai.research.search.security.SecretValueService secrets) {
        super(new BorderLayout());
        this.draft = draft;
        this.playground = playground;
        this.secrets = secrets;
        ProviderSettingsForm form = ProviderSettingsForm.create();
        buildCredentials(form.credentials());
        buildSearchOptions(form.searchOptions());
        buildAdvanced(form.advanced());
        form.playground(buildPlayground());
        add(form.build(), BorderLayout.CENTER);
        loadFromDraft();
        wireListeners();
    }

    // ------------------------------------------------------------------ sections

    private void buildCredentials(ComicForm p) {
        ProviderSettingsForm.secretRow(p, "API key:", apiKey, apiKeyHint,
                "Base64(login:password), sent as HTTP Basic. Leave empty to keep the saved key; "
                        + "a typed key replaces it (encrypted on save).");
    }

    private void buildSearchOptions(ComicForm p) {
        p.row("Search engine:", engine);
        p.row("Location:", location);
        location.setToolTipText("Country the results are localised for");
        p.row("Language:", language);
        language.setToolTipText("Language of the results");
        p.row("Device:", device);
        p.row("Operating system:", operatingSystem);
        p.row("Depth:", depth);
        depth.setToolTipText("Results per request, 1 to 200. DataForSEO bills in blocks of 10; "
                + "a depth above 10 may cost more.");
    }

    private void buildAdvanced(ComicForm p) {
        p.row("API endpoint:", endpointBase);
        endpointBase.setToolTipText("Leave empty for the default DataForSEO endpoint.");
        p.row("Request:", readOnly("Organic Search · Live · Advanced"));
        p.check(groupOrganic);
        groupOrganic.setToolTipText("Off (default): pages from the same domain stay independent URL candidates.");
        removeFromUrlRow(p);
        p.note("Further DataForSEO options (crawl targets, People-Also-Ask depth) are kept from the file "
                + "as they are; some options may incur additional charges.");
    }

    private JComponent buildPlayground() {
        JPanel p = new JPanel(new BorderLayout(6, 6));
        p.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        ComicForm top = new ComicForm();
        top.row("Search term:", playgroundTerm, runPlayground);
        top.row("", playgroundStatus);
        top.note("Uses the CURRENT unsaved settings and may incur DataForSEO charges. "
                + "Endpoint: " + playground.endpoint(draft.configuration()));
        p.add(top, BorderLayout.NORTH);

        JTable results = new JTable(playgroundResults);
        results.setFillsViewportHeight(true);
        JTabbedPane detail = ComicControls.tabbedPane();
        requestPreview.setEditable(false);
        rawResponse.setEditable(false);
        detail.addTab("Organic results", new JScrollPane(results));
        detail.addTab("Effective request", new JScrollPane(requestPreview));
        detail.addTab("Raw response", new JScrollPane(rawResponse));
        detail.setPreferredSize(new Dimension(ProviderSettingsForm.DIALOG_WIDTH - 16, 260));
        p.add(detail, BorderLayout.CENTER);
        return p;
    }

    /** The parameter table with its Add/Remove buttons on the same line, right beside it. */
    private void removeFromUrlRow(ComicForm p) {
        final JTable table = new JTable(removeFromUrl);
        table.setPreferredScrollableViewportSize(new Dimension(240, 60));
        JScrollPane scroll = new JScrollPane(table);
        JButton add = new ComicButton("Add");
        JButton remove = new ComicButton("Remove");
        add.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                if (removeFromUrl.getRowCount() < 10) {
                    removeFromUrl.addRow(new Object[]{""});
                }
            }
        });
        remove.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                int r = table.getSelectedRow();
                if (r >= 0) {
                    removeFromUrl.removeRow(r);
                }
            }
        });
        p.row("Remove URL parameters:", scroll, add, remove);
        scroll.setToolTipText("Query parameters stripped from result URLs (max. 10)");
    }

    // ------------------------------------------------------------------ draft <-> ui

    private void loadFromDraft() {
        DataForSeoSearchConfiguration c = draft.configuration();
        apiKey.setText("");
        apiKeyHint.setText(draft.hasStoredApiKey() ? "A key is stored." : "No key stored.");
        endpointBase.setText(nullToEmpty(c.getEndpointBase()));
        engine.setSelectedItem(c.getSearchEngine());
        RegionChoices.select(location, c.getLocationCode() != null ? String.valueOf(c.getLocationCode())
                : c.getLocationName() != null ? c.getLocationName() : "2276");
        RegionChoices.select(language, c.getLanguageCode() != null ? c.getLanguageCode() : c.getLanguageName());
        device.setSelectedItem(c.getDevice() == DataForSeoDevice.MOBILE ? "Mobile" : "Desktop");
        refreshOsChoices();
        operatingSystem.setSelectedItem(osLabel(c.getOperatingSystem()));
        depth.setValue(Math.max(1, Math.min(200, c.getDepth())));
        groupOrganic.setSelected(c.isGroupOrganicResults());
        removeFromUrl.setRowCount(0);
        for (String param : c.getRemoveFromUrl()) {
            removeFromUrl.addRow(new Object[]{param});
        }
        requestPreview.setText(playground.requestPreview(c, playgroundTerm.getText()));
    }

    /** Writes the current UI into the draft's config — called before save and before a playground run. */
    public void applyToDraft() {
        DataForSeoSearchConfiguration c = draft.configuration();
        char[] typed = apiKey.getPassword();
        c.setEnabled(ProviderSettingsForm.enabled(draft.hasStoredApiKey(), typed));
        draft.setNewApiKey(typed != null && typed.length > 0 ? typed : null);
        c.setEndpointBase(emptyToNull(endpointBase.getText().trim()));
        c.setSearchEngine((DataForSeoSearchEngine) engine.getSelectedItem());
        applyLocation(c, RegionChoices.selected(location));
        applyLanguage(c, RegionChoices.selected(language));
        c.setDevice("Mobile".equals(device.getSelectedItem())
                ? DataForSeoDevice.MOBILE : DataForSeoDevice.DESKTOP);
        c.setOperatingSystem(osFromLabel((String) operatingSystem.getSelectedItem()));
        c.setDepth((Integer) depth.getValue());
        c.setGroupOrganicResults(groupOrganic.isSelected());
        java.util.List<String> params = new java.util.ArrayList<String>();
        for (int i = 0; i < removeFromUrl.getRowCount(); i++) {
            Object value = removeFromUrl.getValueAt(i, 0);
            String text = value == null ? "" : value.toString().trim();
            if (!text.isEmpty()) {
                params.add(text);
            }
        }
        c.setRemoveFromUrl(params);
    }

    /** Persist the draft (called by the hosting dialog's Save). */
    public void save() {
        applyToDraft();
        draft.save();
    }

    // ------------------------------------------------------------------ listeners

    private void wireListeners() {
        device.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                refreshOsChoices();
            }
        });
        runPlayground.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                runPlaygroundSearch();
            }
        });
    }

    /** The OS combo is COUPLED to the device: Desktop → Windows/macOS, Mobile → Android/iOS. */
    private void refreshOsChoices() {
        boolean mobile = "Mobile".equals(device.getSelectedItem());
        Object previous = operatingSystem.getSelectedItem();
        DefaultComboBoxModel<String> model = new DefaultComboBoxModel<String>(
                mobile ? new String[]{"Android", "iOS"} : new String[]{"Windows", "macOS"});
        operatingSystem.setModel(model);
        if (previous != null && model.getIndexOf(previous) >= 0) {
            operatingSystem.setSelectedItem(previous);
        }
    }

    private void runPlaygroundSearch() {
        applyToDraft();
        // The playground uses the freshly typed API key too: encrypt it into the config for THIS run
        // only (not persisted; save re-encrypts from the same pending API key).
        draft.applyPendingApiKeyForRun(secrets);
        final DataForSeoSearchConfiguration config = draft.configuration();
        requestPreview.setText(playground.requestPreview(config, playgroundTerm.getText()));
        runPlayground.setEnabled(false);
        playgroundStatus.setText("Running …");
        final String term = playgroundTerm.getText();
        Thread worker = new Thread(new Runnable() {
            public void run() {
                try {
                    final DataForSeoPlayground.Result result = playground.run(config, term);
                    SwingUtilities.invokeLater(new Runnable() {
                        public void run() {
                            showResult(result);
                            runPlayground.setEnabled(true);
                        }
                    });
                } catch (final Exception ex) {
                    SwingUtilities.invokeLater(new Runnable() {
                        public void run() {
                            playgroundStatus.setText("Failed: " + ex.getMessage());
                            runPlayground.setEnabled(true);
                        }
                    });
                }
            }
        }, "dataforseo-playground");
        worker.setDaemon(true);
        worker.start();
    }

    private void showResult(DataForSeoPlayground.Result result) {
        StringBuilder status = new StringBuilder();
        if (result.getStatusCode() != null) {
            status.append("Status ").append(result.getStatusCode());
        }
        if (result.getStatusMessage() != null) {
            status.append(' ').append(result.getStatusMessage());
        }
        if (result.getTimeSeconds() != null) {
            status.append("  ·  ").append(result.getTimeSeconds()).append(" s");
        }
        if (result.getCost() != null) {
            status.append("  ·  $").append(result.getCost());
        }
        status.append("  ·  ").append(result.getOrganicHits().size()).append(" organic results");
        playgroundStatus.setText(status.toString());
        rawResponse.setText(result.getRawResponse());
        rawResponse.setCaretPosition(0);
        requestPreview.setText(result.getRequestPreview());
        requestPreview.setCaretPosition(0);
        playgroundResults.setRowCount(0);
        for (com.aresstack.askai.research.search.api.WebSearchHit hit : result.getOrganicHits()) {
            playgroundResults.addRow(new Object[]{hit.getRank(), hit.getTitle(),
                    hostOf(hit.getUrl()), hit.getUrl()});
        }
    }

    // ------------------------------------------------------------------ helpers

    private static String osLabel(DataForSeoOperatingSystem os) {
        if (os == null) {
            return "Windows";
        }
        switch (os) {
            case MACOS: return "macOS";
            case ANDROID: return "Android";
            case IOS: return "iOS";
            default: return "Windows";
        }
    }

    private static DataForSeoOperatingSystem osFromLabel(String label) {
        if ("macOS".equals(label)) {
            return DataForSeoOperatingSystem.MACOS;
        }
        if ("Android".equals(label)) {
            return DataForSeoOperatingSystem.ANDROID;
        }
        if ("iOS".equals(label)) {
            return DataForSeoOperatingSystem.IOS;
        }
        return DataForSeoOperatingSystem.WINDOWS;
    }

    private static String hostOf(String url) {
        try {
            return new java.net.URI(url).getHost();
        } catch (Exception invalid) {
            return "";
        }
    }

    private static JComponent readOnly(String value) {
        JTextField field = ComicControls.textField(value);
        field.setEditable(false);
        return field;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    /** Stores the chosen location as code (numeric) or, for an unknown saved name, as name; never both. */
    private static void applyLocation(DataForSeoSearchConfiguration c, String value) {
        c.setLocationCode(null);
        c.setLocationName(null);
        if (value == null) {
            return;
        }
        c.setLocationCoordinate(null);
        if (value.matches("\\d{1,9}")) {
            c.setLocationCode(Integer.valueOf(value));
        } else {
            c.setLocationName(value);
        }
    }

    /** Stores the chosen language as its code; an unknown saved name stays a name. */
    private static void applyLanguage(DataForSeoSearchConfiguration c, String value) {
        c.setLanguageCode(null);
        c.setLanguageName(null);
        if (value == null) {
            return;
        }
        if (value.matches("[A-Za-z]{2,3}(-[A-Za-z0-9]+)?")) {
            c.setLanguageCode(value);
        } else {
            c.setLanguageName(value);
        }
    }
}
