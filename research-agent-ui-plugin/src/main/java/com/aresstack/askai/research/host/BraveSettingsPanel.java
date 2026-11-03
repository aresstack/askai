package com.aresstack.askai.research.host;

import com.aresstack.askai.research.search.brave.BraveSearchConfiguration;
import com.aresstack.askai.research.search.config.ProviderConfigurationService;
import com.aresstack.comiccontrols.control.ComicControls;

import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import java.awt.BorderLayout;

import static com.aresstack.askai.research.host.ProviderForm.hint;
import static com.aresstack.askai.research.host.ProviderForm.row;

/**
 * Settings card for the Brave Search API ({@code brave.json}): the API key, written AES-GCM encrypted by
 * {@link ProviderConfigurationService#saveBrave(BraveSearchConfiguration, char[])}. The stored key is never shown;
 * an empty field keeps it. Typing a key switches the provider on.
 */
public final class BraveSettingsPanel extends JPanel {

    private final ProviderConfigurationService configurations;
    private final JCheckBox enabled = ComicControls.checkBox("Use the Brave Search API for the initial search");
    private final JPasswordField apiKey = ComicControls.passwordField(28);
    private final JLabel apiKeyHint = new JLabel(" ");
    private final JLabel status = new JLabel(" ");
    private BraveSearchConfiguration draft;
    private String lastError = "";

    public BraveSettingsPanel(ProviderConfigurationService configurations) {
        super(new BorderLayout());
        this.configurations = configurations;
        JPanel p = ProviderForm.column();
        p.add(row("", enabled));
        JPanel key = new JPanel(new BorderLayout(4, 0));
        key.add(apiKey, BorderLayout.CENTER);
        apiKeyHint.setEnabled(false);
        key.add(apiKeyHint, BorderLayout.SOUTH);
        p.add(row("API key:", key));
        p.add(hint("The stored key is never shown. Leave the field empty to keep it; type a new one to replace it "
                + "(encrypted on save). The key is sent as a header, never in the URL."));
        p.add(row("", status));
        add(p, BorderLayout.NORTH);
        ProviderForm.enableOnTyping(apiKey, enabled);
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
        enabled.setSelected(c.isEnabled());
        boolean stored = c.getApiKey() != null && c.getApiKey().isPresent();
        apiKeyHint.setText(stored ? "A key is stored." : "No key stored.");
    }

    /** Writes {@code brave.json}; a typed key replaces the stored one. Returns false on a validation error. */
    public boolean save() {
        try {
            draft.setEnabled(enabled.isSelected());
            char[] typed = apiKey.getPassword();
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
