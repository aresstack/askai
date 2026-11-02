package com.aresstack.askai.research.host;

import com.aresstack.askai.research.search.config.ProviderConfigurationService;
import com.aresstack.askai.research.search.serpapi.SerpApiSearchConfiguration;
import com.aresstack.askai.research.search.serpapi.SerpApiSearchEngine;
import com.aresstack.comiccontrols.control.ComicButton;
import com.aresstack.comiccontrols.control.ComicControls;

import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import java.awt.BorderLayout;

import static com.aresstack.askai.research.host.ProviderForm.hint;
import static com.aresstack.askai.research.host.ProviderForm.row;
import static com.aresstack.askai.research.host.ProviderForm.text;
import static com.aresstack.askai.research.host.ProviderForm.trimToNull;

/**
 * Settings card for SerpApi ({@code serpapi.json}). The API key is written AES-GCM encrypted by
 * {@link ProviderConfigurationService#saveSerpApi(SerpApiSearchConfiguration, char[])}; the stored key is never shown.
 * An empty key field keeps the saved key. The search itself runs in Serp4J.
 */
public final class SerpApiSettingsPanel extends JPanel {

    private final ProviderConfigurationService configurations;
    private final JCheckBox enabled = ComicControls.checkBox("Use SerpApi for the initial search");
    private final JPasswordField apiKey = ComicControls.passwordField(28);
    private final JLabel apiKeyHint = new JLabel(" ");
    private final JComboBox<SerpApiSearchEngine> engine =
            ComicControls.<SerpApiSearchEngine>comboBox(SerpApiSearchEngine.values());
    private final JTextField country = ComicControls.textField(6);
    private final JTextField language = ComicControls.textField(6);
    private final JTextField location = ComicControls.textField(24);
    private final JTextField googleDomain = ComicControls.textField(16);
    private final JCheckBox resolveGoto = ComicControls.checkBox("Resolve Google /goto links to the target URL");
    private final JLabel status = new JLabel(" ");
    /** Draft the edits are applied to; an invalid file falls back to defaults so the UI can repair it. */
    private SerpApiSearchConfiguration draft;
    private String lastError = "";

    public SerpApiSettingsPanel(ProviderConfigurationService configurations) {
        super(new BorderLayout());
        this.configurations = configurations;
        JPanel p = ProviderForm.column();
        p.add(row("", enabled));
        JPanel key = new JPanel(new BorderLayout(4, 0));
        key.add(apiKey, BorderLayout.CENTER);
        apiKeyHint.setEnabled(false);
        key.add(apiKeyHint, BorderLayout.SOUTH);
        p.add(row("API key:", key));
        p.add(hint("The stored key is never shown. Leave the field empty to keep it; type a new one to replace "
                + "it (encrypted on save). SerpApi only accepts the key as URL parameter; Serp4J masks it in every "
                + "log and error text."));
        p.add(row("Search engine:", engine));
        p.add(row("Country:", country));
        p.add(row("Language:", language));
        p.add(row("Location:", location));
        p.add(row("Google domain:", googleDomain));
        p.add(row("", resolveGoto));
        javax.swing.JButton saveNow = new ComicButton("Save SerpApi settings");
        saveNow.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent e) {
                save();
            }
        });
        p.add(row("", saveNow));
        p.add(row("", status));
        add(p, BorderLayout.NORTH);
        load();
    }

    private void load() {
        SerpApiSearchConfiguration c;
        try {
            c = configurations.loadOrCreateSerpApi();
        } catch (RuntimeException invalid) {
            status.setText("serpapi.json is invalid: " + invalid.getMessage());
            c = new SerpApiSearchConfiguration();
        }
        draft = c;
        enabled.setSelected(c.isEnabled());
        boolean stored = c.getApiKey() != null && c.getApiKey().isPresent();
        apiKeyHint.setText(stored ? "A key is stored." : "No key stored.");
        engine.setSelectedItem(c.getSearchEngine() == null ? SerpApiSearchEngine.GOOGLE : c.getSearchEngine());
        country.setText(text(c.getCountry()));
        language.setText(text(c.getLanguage()));
        location.setText(text(c.getLocation()));
        googleDomain.setText(text(c.getGoogleDomain()));
        resolveGoto.setSelected(c.isResolveGoogleGotoUrls());
    }

    /** Writes {@code serpapi.json}; a typed key replaces the stored one. Returns false on a validation error. */
    public boolean save() {
        try {
            SerpApiSearchConfiguration c = draft;
            apply(c);
            char[] typed = apiKey.getPassword();
            if (typed.length > 0) {
                configurations.saveSerpApi(c, typed); // clears the array
                apiKey.setText("");
            } else {
                configurations.saveSerpApi(c);
            }
            lastError = "";
            status.setText("Saved.");
            load();
            return true;
        } catch (RuntimeException invalid) {
            lastError = String.valueOf(invalid.getMessage());
            status.setText("Not saved: " + lastError);
            return false;
        }
    }

    /** Reason of the last failed {@link #save()}, empty after a successful one. */
    public String getLastError() {
        return lastError;
    }

    private void apply(SerpApiSearchConfiguration c) {
        c.setEnabled(enabled.isSelected());
        c.setSearchEngine((SerpApiSearchEngine) engine.getSelectedItem());
        c.setCountry(trimToNull(country.getText()));
        c.setLanguage(trimToNull(language.getText()));
        c.setLocation(trimToNull(location.getText()));
        c.setGoogleDomain(trimToNull(googleDomain.getText()));
        c.setResolveGoogleGotoUrls(resolveGoto.isSelected());
    }
}
