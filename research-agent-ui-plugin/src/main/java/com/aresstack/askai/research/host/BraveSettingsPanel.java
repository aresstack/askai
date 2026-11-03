package com.aresstack.askai.research.host;

import com.aresstack.askai.research.search.brave.BraveSearchConfiguration;
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
 * Settings for the Brave Search API ({@code brave.json}) in the shared {@link ProviderSettingsForm} frame.
 * The API key is written AES-GCM encrypted by
 * {@link ProviderConfigurationService#saveBrave(BraveSearchConfiguration, char[])}; the stored key is never
 * shown, an empty field keeps it. The provider is enabled exactly when a key is stored.
 */
public final class BraveSettingsPanel extends JPanel {

    private final ProviderConfigurationService configurations;
    private final JPasswordField apiKey = ProviderSettingsForm.secretField();
    private final JLabel apiKeyStatus = ProviderSettingsForm.status();
    private final JTextField country = ComicControls.textField(6);
    private final JTextField searchLanguage = ComicControls.textField(6);
    private final JTextField uiLanguage = ComicControls.textField(8);
    private final JSpinner count = ProviderSettingsForm.number(20, 1, 20, 3);
    private final JLabel status = ProviderSettingsForm.message();
    private BraveSearchConfiguration draft;
    private String lastError = "";

    public BraveSettingsPanel(ProviderConfigurationService configurations) {
        super(new BorderLayout());
        this.configurations = configurations;
        ProviderSettingsForm form = ProviderSettingsForm.create();
        ComicForm credentials = form.credentials();
        ProviderSettingsForm.secretRow(credentials, "API key:", apiKey, apiKeyStatus,
                "Leave empty to keep the stored key; a typed key replaces it (encrypted on save, sent as header).");
        credentials.row("", status);
        ComicForm options = form.searchOptions();
        options.row("Country:", country);
        country.setToolTipText("Two-letter country code, e.g. DE");
        options.row("Search language:", searchLanguage);
        searchLanguage.setToolTipText("Two-letter language code, e.g. de");
        options.row("UI language:", uiLanguage);
        uiLanguage.setToolTipText("Locale of the result texts, e.g. de-DE");
        options.row("Results per request:", count);
        count.setToolTipText("1 to 20");
        add(form.build(), BorderLayout.NORTH);
        load();
    }

    private void load() {
        BraveSearchConfiguration c;
        try {
            c = configurations.loadOrCreateBrave();
        } catch (RuntimeException invalid) {
            status.setText("brave.json is invalid: " + invalid.getMessage());
            c = new BraveSearchConfiguration();
        }
        draft = c;
        boolean stored = c.getApiKey() != null && c.getApiKey().isPresent();
        apiKeyStatus.setText(stored ? "A key is stored." : "No key stored.");
        country.setText(text(c.getCountry()));
        searchLanguage.setText(text(c.getSearchLanguage()));
        uiLanguage.setText(text(c.getUiLanguage()));
        count.setValue(Math.max(1, Math.min(20, c.getCount())));
    }

    /** Writes {@code brave.json}; a typed key replaces the stored one. Returns false on a validation error. */
    public boolean save() {
        try {
            boolean stored = draft.getApiKey() != null && draft.getApiKey().isPresent();
            char[] typed = apiKey.getPassword();
            draft.setEnabled(ProviderSettingsForm.enabled(stored, typed));
            draft.setCountry(trimToNull(country.getText()));
            draft.setSearchLanguage(trimToNull(searchLanguage.getText()));
            draft.setUiLanguage(trimToNull(uiLanguage.getText()));
            draft.setCount((Integer) count.getValue());
            if (typed.length > 0) {
                configurations.saveBrave(draft, typed); // clears the array
                apiKey.setText("");
            } else {
                configurations.saveBrave(draft);
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
