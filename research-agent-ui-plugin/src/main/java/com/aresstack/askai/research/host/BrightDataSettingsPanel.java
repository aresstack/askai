package com.aresstack.askai.research.host;

import com.aresstack.askai.research.search.brightdata.BrightDataSearchConfiguration;
import com.aresstack.askai.research.search.config.ProviderConfigurationService;
import com.aresstack.comiccontrols.control.ComicControls;

import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import java.awt.BorderLayout;

import static com.aresstack.askai.research.host.ProviderForm.hint;
import static com.aresstack.askai.research.host.ProviderForm.row;
import static com.aresstack.askai.research.host.ProviderForm.text;

/**
 * Settings card for Bright Data ({@code brightdata.json}): the API token (Bearer, written AES-GCM encrypted by
 * {@link ProviderConfigurationService#saveBrightData(BrightDataSearchConfiguration, char[])}) and the SERP zone.
 * The stored token is never shown; an empty field keeps it. Typing a token switches the provider on.
 */
public final class BrightDataSettingsPanel extends JPanel {

    private final ProviderConfigurationService configurations;
    private final JCheckBox enabled = ComicControls.checkBox("Use Bright Data for the initial search");
    private final JPasswordField apiKey = ComicControls.passwordField(28);
    private final JLabel apiKeyHint = new JLabel(" ");
    private final JTextField zone = ComicControls.textField(16);
    private final JLabel status = new JLabel(" ");
    private BrightDataSearchConfiguration draft;
    private String lastError = "";

    public BrightDataSettingsPanel(ProviderConfigurationService configurations) {
        super(new BorderLayout());
        this.configurations = configurations;
        JPanel p = ProviderForm.column();
        p.add(row("", enabled));
        JPanel key = new JPanel(new BorderLayout(4, 0));
        key.add(apiKey, BorderLayout.CENTER);
        apiKeyHint.setEnabled(false);
        key.add(apiKeyHint, BorderLayout.SOUTH);
        p.add(row("API token:", key));
        p.add(row("Zone:", zone));
        p.add(hint("Zone (required): the name of the SERP API zone in your own Bright Data account; AskAI has no "
                + "default. The stored token is never shown; leave the token field empty to keep it, type a new one "
                + "to replace it (encrypted on save, sent as Bearer header)."));
        p.add(row("", status));
        add(p, BorderLayout.NORTH);
        ProviderForm.enableOnTyping(apiKey, enabled);
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
        enabled.setSelected(c.isEnabled());
        zone.setText(text(c.getZone()));
        boolean stored = c.getApiKey() != null && c.getApiKey().isPresent();
        apiKeyHint.setText(stored ? "A token is stored." : "No token stored.");
    }

    /** Writes {@code brightdata.json}; a typed token replaces the stored one. False on a validation error. */
    public boolean save() {
        try {
            draft.setEnabled(enabled.isSelected());
            draft.setZone(zone.getText().trim()); // not a secret: what the field shows is what is saved
            char[] typed = apiKey.getPassword();
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
