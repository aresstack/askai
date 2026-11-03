package com.aresstack.askai.research.host;

import com.aresstack.askai.research.search.brightdata.BrightDataSearchConfiguration;
import com.aresstack.askai.research.search.config.ProviderConfigurationService;
import com.aresstack.comiccontrols.control.ComicControls;
import com.aresstack.comiccontrols.control.ComicForm;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import java.awt.BorderLayout;

import static com.aresstack.askai.research.host.ProviderSettingsForm.text;
import static com.aresstack.askai.research.host.ProviderSettingsForm.trimToNull;

/**
 * Settings for Bright Data ({@code brightdata.json}) in the shared {@link ProviderSettingsForm} frame: the
 * API token (Bearer, written AES-GCM encrypted by
 * {@link ProviderConfigurationService#saveBrightData(BrightDataSearchConfiguration, char[])}) and the SERP
 * zone of the user's own account (no default). The stored token is never shown; an empty field keeps it.
 * The provider is enabled exactly when a token is stored.
 */
public final class BrightDataSettingsPanel extends JPanel {

    private final ProviderConfigurationService configurations;
    private final JPasswordField apiKey = ProviderSettingsForm.secretField();
    private final JLabel apiKeyStatus = ProviderSettingsForm.status();
    private final JTextField zone = ComicControls.textField(16);
    private final JTextField country = ComicControls.textField(6);
    private final JTextField language = ComicControls.textField(6);
    private final JSpinner resultsPerPage = ProviderSettingsForm.number(10, 1, 100, 3);
    private final JLabel status = ProviderSettingsForm.message();
    private BrightDataSearchConfiguration draft;
    private String lastError = "";

    public BrightDataSettingsPanel(ProviderConfigurationService configurations) {
        super(new BorderLayout());
        this.configurations = configurations;
        ProviderSettingsForm form = ProviderSettingsForm.create();
        ComicForm credentials = form.credentials();
        ProviderSettingsForm.secretRow(credentials, "API token:", apiKey, apiKeyStatus,
                "Leave empty to keep the stored token; a typed token replaces it (encrypted on save, sent as Bearer header).");
        credentials.row("Zone:", zone);
        zone.setToolTipText("Required: the name of the SERP API zone in your own Bright Data account.");
        credentials.row("", status);
        ComicForm options = form.searchOptions();
        options.row("Country:", country);
        country.setToolTipText("Two-letter country code, e.g. de");
        options.row("Language:", language);
        language.setToolTipText("Two-letter language code, e.g. de");
        options.row("Results per page:", resultsPerPage);
        resultsPerPage.setToolTipText("1 to 100");
        add(form.build(), BorderLayout.NORTH);
        load();
    }

    private void load() {
        BrightDataSearchConfiguration c;
        try {
            c = configurations.loadOrCreateBrightData();
        } catch (RuntimeException invalid) {
            status.setText("brightdata.json is invalid: " + invalid.getMessage());
            c = new BrightDataSearchConfiguration();
        }
        draft = c;
        zone.setText(text(c.getZone()));
        country.setText(text(c.getCountry()));
        language.setText(text(c.getLanguage()));
        resultsPerPage.setValue(Math.max(1, Math.min(100, c.getResultsPerPage())));
        boolean stored = c.getApiKey() != null && c.getApiKey().isPresent();
        apiKeyStatus.setText(stored ? "A token is stored." : "No token stored.");
    }

    /** Writes {@code brightdata.json}; a typed token replaces the stored one. False on a validation error. */
    public boolean save() {
        try {
            boolean stored = draft.getApiKey() != null && draft.getApiKey().isPresent();
            char[] typed = apiKey.getPassword();
            draft.setEnabled(ProviderSettingsForm.enabled(stored, typed));
            draft.setZone(zone.getText().trim()); // not a secret: what the field shows is what is saved
            draft.setCountry(trimToNull(country.getText()));
            draft.setLanguage(trimToNull(language.getText()));
            draft.setResultsPerPage((Integer) resultsPerPage.getValue());
            if (typed.length > 0) {
                configurations.saveBrightData(draft, typed); // clears the array
                apiKey.setText("");
            } else {
                configurations.saveBrightData(draft);
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

    public String getLastError() {
        return lastError;
    }
}
