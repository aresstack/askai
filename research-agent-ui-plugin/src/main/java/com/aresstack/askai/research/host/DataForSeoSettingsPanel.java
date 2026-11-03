package com.aresstack.askai.research.host;

import com.aresstack.comiccontrols.control.ComicControls;
import com.aresstack.comiccontrols.control.ComicButton;
import com.aresstack.askai.research.search.config.DataForSeoSettingsDraft;
import com.aresstack.askai.research.search.dataforseo.DataForSeoDevice;
import com.aresstack.askai.research.search.dataforseo.DataForSeoOperatingSystem;
import com.aresstack.askai.research.search.dataforseo.DataForSeoPlayground;
import com.aresstack.askai.research.search.dataforseo.DataForSeoSearchConfiguration;
import com.aresstack.askai.research.search.dataforseo.DataForSeoSearchEngine;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.List;

/**
 * The provider-specific DataForSEO editor (its own card — the top provider combo only SELECTS it, it never
 * reduces DataForSEO to shared fields). Bound to ONE {@link DataForSeoSettingsDraft}: edits mutate the
 * draft's working config directly, so switching provider cards never reloads or discards them; the dialog
 * persists the draft on Save. The stored secret is never shown — the password field stays empty over a
 * "Saved credential" hint, and only a freshly typed password is (re)encrypted on save.
 *
 * <p>Four tabs: Account, SERP, Advanced, Playground. The Playground runs the SAME productive adapter off
 * the EDT against the CURRENT unsaved draft (a depth or credential can be tried before saving).</p>
 */
public final class DataForSeoSettingsPanel extends JPanel {

    private static final DataForSeoSearchEngine[] ENGINES = DataForSeoSearchEngine.values();

    private final DataForSeoSettingsDraft draft;
    private final DataForSeoPlayground playground;
    private final com.aresstack.askai.research.search.security.SecretValueService secrets;

    private final JCheckBox enabled = ComicControls.checkBox("Use DataForSEO for the initial search");
    private final JTextField username = ComicControls.textField(28);
    private final JPasswordField password = ComicControls.passwordField(28);
    private final JLabel passwordHint = new JLabel(" ");
    private final JTextField endpointBase = ComicControls.textField(28);

    private final JComboBox<DataForSeoSearchEngine> engine =
            ComicControls.<DataForSeoSearchEngine>comboBox(ENGINES);
    private final JTextField locationName = ComicControls.textField(20);
    private final JSpinner locationCode =
            ComicControls.spinner(new SpinnerNumberModel(2276, 1, 9_999_999, 1));
    private final JTextField languageName = ComicControls.textField(12);
    private final JTextField languageCode = ComicControls.textField(6);
    private final JComboBox<String> device = ComicControls.<String>comboBox(new String[]{"Desktop", "Mobile"});
    private final JComboBox<String> operatingSystem = ComicControls.<String>comboBox();
    private final JSpinner depth = ComicControls.spinner(new SpinnerNumberModel(10, 1, 200, 1));
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
        JTabbedPane tabs = ComicControls.tabbedPane();
        tabs.addTab("Account", scroll(buildAccount()));
        tabs.addTab("SERP", scroll(buildSerp()));
        tabs.addTab("Advanced", scroll(buildAdvanced()));
        tabs.addTab("Playground", buildPlayground());
        add(tabs, BorderLayout.CENTER);
        loadFromDraft();
        wireListeners();
    }

    // ------------------------------------------------------------------ tabs

    private JComponent buildAccount() {
        JPanel p = column();
        p.add(row("", enabled));
        p.add(row("API login:", username));
        JPanel pw = new JPanel(new BorderLayout(4, 0));
        pw.add(password, BorderLayout.CENTER);
        passwordHint.setEnabled(false);
        pw.add(passwordHint, BorderLayout.SOUTH);
        p.add(row("API password:", pw));
        p.add(row("API endpoint:", endpointBase));
        p.add(hint("The stored password is never shown. Leave it empty to keep the saved credential; "
                + "type a new one to replace it (encrypted on save)."));
        return p;
    }

    private JComponent buildSerp() {
        JPanel p = column();
        p.add(row("Search engine:", engine));
        p.add(row("Search type:", readOnly("Organic Search")));
        p.add(row("Execution mode:", readOnly("Live")));
        p.add(row("Result format:", readOnly("Advanced")));
        JPanel loc = new JPanel(new BorderLayout(4, 0));
        loc.add(locationName, BorderLayout.CENTER);
        loc.add(labeled("code", locationCode), BorderLayout.EAST);
        p.add(row("Location:", loc));
        JPanel lang = new JPanel(new BorderLayout(4, 0));
        lang.add(languageName, BorderLayout.CENTER);
        lang.add(labeled("code", languageCode), BorderLayout.EAST);
        p.add(row("Language:", lang));
        p.add(row("Device:", device));
        p.add(row("Operating system:", operatingSystem));
        p.add(row("Depth:", depth));
        p.add(hint("DataForSEO bills organic SERPs in blocks of up to 10; a depth above 10 may increase "
                + "the request cost. Default 10, maximum 200."));
        p.add(row("", groupOrganic));
        p.add(hint("AskAI default: Off, so related pages from the same domain stay independent URL "
                + "candidates for the discovery pipeline."));
        p.add(row("Remove URL parameters:", removeFromUrlEditor()));
        return p;
    }

    private JComponent buildAdvanced() {
        JPanel p = column();
        p.add(hint("Additional DataForSEO charges may apply for some options here."));
        p.add(hint("The full advanced grid (crawl targets, People-Also-Ask depth, rectangles) is not yet "
                + "editable in this slice — the underlying settings are preserved untouched."));
        return p;
    }

    private JComponent buildPlayground() {
        JPanel p = new JPanel(new BorderLayout(6, 6));
        p.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.add(row("Search term:", playgroundTerm));
        JPanel actions = new JPanel(new BorderLayout(6, 0));
        actions.add(runPlayground, BorderLayout.WEST);
        actions.add(playgroundStatus, BorderLayout.CENTER);
        top.add(actions);
        top.add(hint("This request uses the CURRENT unsaved settings and may incur DataForSEO charges. "
                + "Endpoint: " + playground.endpoint(draft.configuration())));
        p.add(top, BorderLayout.NORTH);

        JTable results = new JTable(playgroundResults);
        results.setFillsViewportHeight(true);
        JTabbedPane detail = ComicControls.tabbedPane();
        requestPreview.setEditable(false);
        rawResponse.setEditable(false);
        detail.addTab("Organic results", new JScrollPane(results));
        detail.addTab("Effective request", new JScrollPane(requestPreview));
        detail.addTab("Raw response", new JScrollPane(rawResponse));
        detail.setPreferredSize(new Dimension(560, 260));
        p.add(detail, BorderLayout.CENTER);
        return p;
    }

    private JComponent removeFromUrlEditor() {
        JPanel p = new JPanel(new BorderLayout(4, 4));
        final JTable table = new JTable(removeFromUrl);
        table.setPreferredScrollableViewportSize(new Dimension(240, 60));
        JScrollPane scroll = new JScrollPane(table);
        p.add(scroll, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new BorderLayout(4, 0));
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
        buttons.add(add, BorderLayout.WEST);
        buttons.add(remove, BorderLayout.EAST);
        p.add(buttons, BorderLayout.SOUTH);
        return p;
    }

    // ------------------------------------------------------------------ draft <-> ui

    private void loadFromDraft() {
        DataForSeoSearchConfiguration c = draft.configuration();
        enabled.setSelected(c.isEnabled());
        username.setText(nullToEmpty(c.getUsername()));
        password.setText("");
        passwordHint.setText(draft.hasStoredPassword() ? "Saved credential" : "No credential stored");
        endpointBase.setText(nullToEmpty(c.getEndpointBase()));
        engine.setSelectedItem(c.getSearchEngine());
        locationName.setText(nullToEmpty(c.getLocationName()));
        locationCode.setValue(c.getLocationCode() == null ? 2276 : c.getLocationCode());
        languageName.setText(nullToEmpty(c.getLanguageName()));
        languageCode.setText(nullToEmpty(c.getLanguageCode()));
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
        c.setEnabled(enabled.isSelected());
        c.setUsername(emptyToNull(username.getText().trim()));
        char[] typed = password.getPassword();
        draft.setNewPassword(typed != null && typed.length > 0 ? typed : null);
        c.setEndpointBase(emptyToNull(endpointBase.getText().trim()));
        c.setSearchEngine((DataForSeoSearchEngine) engine.getSelectedItem());
        c.setLocationName(emptyToNull(locationName.getText().trim()));
        c.setLocationCode((Integer) locationCode.getValue());
        c.setLanguageName(emptyToNull(languageName.getText().trim()));
        c.setLanguageCode(emptyToNull(languageCode.getText().trim()));
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
        // The playground uses the freshly typed password too: encrypt it into the config for THIS run
        // only (not persisted; save re-encrypts from the same pending password).
        draft.applyPendingPasswordForRun(secrets);
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

    private static JPanel column() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        return p;
    }

    private static JComponent scroll(JComponent content) {
        JScrollPane scroll = new JScrollPane(content);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getVerticalScrollBar().setUnitIncrement(14);
        return scroll;
    }

    private static JPanel row(String label, JComponent field) {
        JPanel p = new JPanel(new BorderLayout(8, 0));
        p.setBorder(BorderFactory.createEmptyBorder(3, 0, 3, 0));
        JLabel l = new JLabel(label);
        l.setPreferredSize(new Dimension(150, 22));
        p.add(l, BorderLayout.WEST);
        p.add(field, BorderLayout.CENTER);
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, field.getPreferredSize().height + 6));
        return p;
    }

    private static JComponent labeled(String label, JComponent field) {
        JPanel p = new JPanel(new BorderLayout(3, 0));
        p.add(new JLabel(label), BorderLayout.WEST);
        p.add(field, BorderLayout.CENTER);
        return p;
    }

    private static JComponent readOnly(String value) {
        JTextField field = ComicControls.textField(value);
        field.setEditable(false);
        return field;
    }

    private static JComponent hint(String text) {
        JLabel l = new JLabel("<html><body style='width:420px'>" + text + "</body></html>");
        l.setEnabled(false);
        l.setBorder(BorderFactory.createEmptyBorder(2, 0, 6, 0));
        return l;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }
}
