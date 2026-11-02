package com.aresstack.askai.research.host;

import com.aresstack.askai.research.search.config.ProviderConfigurationService;
import com.aresstack.askai.research.search.googlebrowser.GoogleBrowserSearchConfiguration;
import com.aresstack.comiccontrols.control.ComicControls;

import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import java.awt.BorderLayout;

import static com.aresstack.askai.research.host.ProviderForm.hint;
import static com.aresstack.askai.research.host.ProviderForm.row;
import static com.aresstack.askai.research.host.ProviderForm.text;
import static com.aresstack.askai.research.host.ProviderForm.trimToNull;

/**
 * Settings card for the Google search browser ({@code google-browser.json}, askai #31). Only the local browser is
 * chosen here; the Google search itself (URL, page parsing, {@code /goto} resolution) runs in Serp4J. The search
 * browser is its own process with an ephemeral profile per search, separate from the research browser.
 */
public final class GoogleBrowserSettingsPanel extends JPanel {

    private final ProviderConfigurationService configurations;
    private final JCheckBox enabled = ComicControls.checkBox("Use the Google search browser for the initial search");
    private final JComboBox<String> browserType =
            ComicControls.<String>comboBox(new String[]{"chromium", "firefox"});
    private final JTextField channel = ComicControls.textField(12);
    private final JTextField executablePath = ComicControls.textField(28);
    private final JCheckBox headless = ComicControls.checkBox("Headless");
    private final JTextField country = ComicControls.textField(6);
    private final JTextField language = ComicControls.textField(6);
    private final JCheckBox resolveGoto = ComicControls.checkBox("Resolve Google /goto links over HTTP");
    private final JLabel status = new JLabel(" ");

    public GoogleBrowserSettingsPanel(ProviderConfigurationService configurations) {
        super(new BorderLayout());
        this.configurations = configurations;
        JPanel p = ProviderForm.column();
        p.add(row("", enabled));
        p.add(hint("Google results are fetched by Serp4J in a separate search browser (Quick Web Search). It "
                + "never shares a session with the research browser that opens the found pages. Consent or "
                + "bot-check pages are not clicked away; the search then fails visibly."));
        p.add(row("Browser:", browserType));
        p.add(row("Channel:", channel));
        p.add(hint("Chromium channel of an installed browser, e.g. msedge or chrome. Empty uses the executable "
                + "below or Playwright's own Chromium."));
        p.add(row("Executable:", executablePath));
        p.add(row("", headless));
        p.add(row("Country:", country));
        p.add(row("Language:", language));
        p.add(row("", resolveGoto));
        p.add(hint("Unresolved /goto links are never used as sources; they are listed as unresolved hits."));
        p.add(row("", status));
        add(p, BorderLayout.NORTH);
        load();
    }

    private void load() {
        GoogleBrowserSearchConfiguration c;
        try {
            c = configurations.loadOrCreateGoogleBrowser();
        } catch (RuntimeException invalid) {
            status.setText("google-browser.json is invalid: " + invalid.getMessage());
            c = new GoogleBrowserSearchConfiguration();
        }
        enabled.setSelected(c.isEnabled());
        browserType.setSelectedItem("firefox".equalsIgnoreCase(text(c.getBrowserType()).trim())
                ? "firefox" : "chromium");
        channel.setText(text(c.getChannel()));
        executablePath.setText(text(c.getExecutablePath()));
        headless.setSelected(c.isHeadless());
        country.setText(text(c.getCountry()));
        language.setText(text(c.getLanguage()));
        resolveGoto.setSelected(c.isResolveGotoUrls());
    }

    /** Writes {@code google-browser.json}. Returns false on a validation error. */
    public boolean save() {
        try {
            GoogleBrowserSearchConfiguration c = configurations.loadOrCreateGoogleBrowser();
            c.setEnabled(enabled.isSelected());
            c.setBrowserType((String) browserType.getSelectedItem());
            c.setChannel(trimToNull(channel.getText()));
            c.setExecutablePath(trimToNull(executablePath.getText()));
            c.setHeadless(headless.isSelected());
            c.setCountry(trimToNull(country.getText()));
            c.setLanguage(trimToNull(language.getText()));
            c.setResolveGotoUrls(resolveGoto.isSelected());
            configurations.saveGoogleBrowser(c);
            status.setText("Saved.");
            return true;
        } catch (RuntimeException invalid) {
            status.setText("Not saved: " + invalid.getMessage());
            return false;
        }
    }
}
