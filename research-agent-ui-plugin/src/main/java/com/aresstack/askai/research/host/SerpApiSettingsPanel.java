package com.aresstack.askai.research.host;

import com.aresstack.askai.research.search.config.ProviderConfigurationService;
import com.aresstack.askai.research.search.serpapi.SerpApiSearchConfiguration;
import com.aresstack.askai.research.search.serpapi.SerpApiSearchEngine;
import com.aresstack.comiccontrols.control.ComicControls;
import com.aresstack.comiccontrols.control.ComicForm;

import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import java.awt.BorderLayout;

import static com.aresstack.askai.research.host.ProviderSettingsForm.text;
import static com.aresstack.askai.research.host.ProviderSettingsForm.trimToNull;

/**
 * Settings for SerpApi ({@code serpapi.json}) in the shared {@link ProviderSettingsForm} frame. The API key
 * is written AES-GCM encrypted by
 * {@link ProviderConfigurationService#saveSerpApi(SerpApiSearchConfiguration, char[])}; the stored key is
 * never shown, an empty field keeps it. The provider is enabled exactly when a key is stored. The search
 * itself runs in Serp4J.
 */
public final class SerpApiSettingsPanel extends JPanel {

    private final ProviderConfigurationService configurations;
    private final JPasswordField apiKey = ProviderSettingsForm.secretField();
    private final JLabel apiKeyStatus = ProviderSettingsForm.status();
    private final JComboBox<SerpApiSearchEngine> engine =
            ComicControls.<SerpApiSearchEngine>comboBox(SerpApiSearchEngine.values());
    private final JComboBox<RegionChoices.Choice> country = RegionChoices.countries(false);
    private final JComboBox<RegionChoices.Choice> language = RegionChoices.languages();
    private final JTextField location = ComicControls.textField(24);
    private final JTextField googleDomain = ComicControls.textField(16);
    private final JCheckBox resolveGoto = ComicControls.checkBox("Resolve Google /goto links to the target URL");
    private final JLabel status = ProviderSettingsForm.message();
    /** Draft the edits are applied to; an invalid file falls back to defaults so the UI can repair it. */
    private SerpApiSearchConfiguration draft;
    private String lastError = "";

    public SerpApiSettingsPanel(ProviderConfigurationService configurations) {
        super(new BorderLayout());
        this.configurations = configurations;
        ProviderSettingsForm form = ProviderSettingsForm.create();
        ComicForm credentials = form.credentials();
        ProviderSettingsForm.secretRow(credentials, "API key:", apiKey, apiKeyStatus,
                "Leave empty to keep the stored key; a typed key replaces it (encrypted on save). "
                        + "SerpApi takes the key as URL parameter; Serp4J masks it in logs and errors.");
        credentials.row("", status);
        ComicForm options = form.searchOptions();
        options.row("Search engine:", engine);
        options.row("Country:", country);
        country.setToolTipText("Country the results are localised for");
        options.row("Language:", language);
        language.setToolTipText("Language of the results");
        options.row("City / region:", location);
        location.setToolTipText("Optional city or region, e.g. Berlin, Germany");
        options.row("Google domain:", googleDomain);
        googleDomain.setToolTipText("Optional, e.g. google.de");
        form.advanced().check(resolveGoto);
        add(form.build(), BorderLayout.NORTH);
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
        boolean stored = c.getApiKey() != null && c.getApiKey().isPresent();
        apiKeyStatus.setText(stored ? "A key is stored." : "No key stored.");
        engine.setSelectedItem(c.getSearchEngine() == null ? SerpApiSearchEngine.GOOGLE : c.getSearchEngine());
        RegionChoices.select(country, c.getCountry());
        RegionChoices.select(language, c.getLanguage());
        location.setText(text(c.getLocation()));
        googleDomain.setText(text(c.getGoogleDomain()));
        resolveGoto.setSelected(c.isResolveGoogleGotoUrls());
    }

    /** Writes {@code serpapi.json}; a typed key replaces the stored one. Returns false on a validation error. */
    public boolean save() {
        try {
            SerpApiSearchConfiguration c = draft;
            char[] typed = apiKey.getPassword();
            apply(c, typed);
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

    private void apply(SerpApiSearchConfiguration c, char[] typed) {
        boolean stored = c.getApiKey() != null && c.getApiKey().isPresent();
        c.setEnabled(ProviderSettingsForm.enabled(stored, typed));
        c.setSearchEngine((SerpApiSearchEngine) engine.getSelectedItem());
        c.setCountry(RegionChoices.selected(country));
        c.setLanguage(RegionChoices.selected(language));
        c.setLocation(trimToNull(location.getText()));
        c.setGoogleDomain(trimToNull(googleDomain.getText()));
        c.setResolveGoogleGotoUrls(resolveGoto.isSelected());
    }
}
