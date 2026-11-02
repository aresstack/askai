package com.aresstack.askai.ui;

import com.aresstack.comiccontrols.control.ComicControls;
import com.aresstack.comiccontrols.control.ComicButton;
import com.aresstack.askai.config.AppConfiguration;
import com.aresstack.askai.config.AppConfigurationRepository;
import com.aresstack.askai.hf.HuggingFaceClient;
import com.aresstack.askai.hf.HuggingFaceConnectionTestResult;
import com.aresstack.askai.net.CertificateTrustConfiguration;
import com.aresstack.askai.net.HttpClientConfiguration;
import com.aresstack.askai.net.PacUrlDiscoveryService;
import com.aresstack.askai.net.ProxyConfiguration;
import com.aresstack.winproxy.ProxyDefaults;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.JTextComponent;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridLayout;

public final class ProxyPanel extends JPanel {

    private static final String[] UI_MODES = {
            ProxyConfiguration.DISABLED,
            ProxyConfiguration.MANUAL_PROXY,
            ProxyConfiguration.WINDOWS_STATIC_PROXY,
            ProxyConfiguration.PAC_URL_MANUAL,
            ProxyConfiguration.PAC_URL_POWERSHELL,
            ProxyConfiguration.PAC_URL_WSCRIPT,
            ProxyConfiguration.PAC_URL_WINDOWS_SETTINGS,
            ProxyConfiguration.WINDOWS_NATIVE_PROXY_SETTINGS,
            ProxyConfiguration.WINDOWS_NATIVE_ROUTE_RESOLVER
    };

    private final AppConfigurationRepository configurationRepository;
    private final JComboBox<String> mode;
    private final JTextField testUrl;
    private final JTextField pacUrlDiscoveryScript;
    private final JTextField manualHost;
    private final JTextField manualPort;
    private final JCheckBox trustJvmDefault;
    private final JCheckBox trustWindowsRoot;
    private final JCheckBox trustWindowsCaStores;
    private final JTextField userAgentField;
    private final JComboBox<String> proxyAuthMode;
    private final JTextField proxyAuthUser;
    private final JPasswordField proxyAuthPassword;
    private final JCheckBox preferIpv6;
    private final JTextArea log;

    private static final String[] PROXY_AUTH_MODES = {"NONE", "BASIC", "WINDOWS_INTEGRATED"};

    private String activeScriptMode;
    private String manualPacUrlText;
    private String powerShellScriptText;
    private String wScriptText;
    private String otherScriptText;
    private boolean updatingScriptText;

    public ProxyPanel(AppConfigurationRepository configurationRepository) {
        this.configurationRepository = configurationRepository;
        ProxyConfiguration cfg = configurationRepository.load().getProxyConfiguration();
        this.manualPacUrlText = "";
        this.powerShellScriptText = ProxyDefaults.DEFAULT_PAC_URL_DISCOVERY_SCRIPT;
        this.wScriptText = PacUrlDiscoveryService.defaultScript();
        this.otherScriptText = "";
        rememberScriptText(cfg.getModeName(), cfg.getPacUrlDiscoveryScript());
        this.activeScriptMode = cfg.getModeName();

        setLayout(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        CertificateTrustConfiguration trust = configurationRepository.load().getCertificateTrustConfiguration();
        HttpClientConfiguration http = configurationRepository.load().getHttpClientConfiguration();

        this.mode = ComicControls.<String>comboBox(UI_MODES);
        this.mode.setSelectedItem(cfg.getModeName());
        this.testUrl = ComicControls.textField(cfg.getTestUrl());
        this.pacUrlDiscoveryScript = ComicControls.textField(scriptTextFor(cfg.getModeName()));
        this.manualHost = ComicControls.textField(empty(cfg.getManualProxyHost()));
        this.manualPort = ComicControls.textField(cfg.getManualProxyPort() > 0 ? String.valueOf(cfg.getManualProxyPort()) : "");
        this.trustJvmDefault = ComicControls.checkBox("Use JVM default cacerts", trust.isUseJvmDefault());
        this.trustWindowsRoot = ComicControls.checkBox("Use Windows-ROOT store", trust.isUseWindowsRoot());
        this.trustWindowsCaStores = ComicControls.checkBox("Use Windows Root/Intermediate stores", trust.isUseWindowsCaStores());
        this.userAgentField = ComicControls.textField(http.getUserAgent(), 28);
        this.proxyAuthMode = ComicControls.<String>comboBox(PROXY_AUTH_MODES);
        this.proxyAuthMode.setSelectedItem(http.getProxyAuthMode().name());
        this.proxyAuthUser = ComicControls.textField(empty(http.getProxyAuthUsername()), 16);
        this.proxyAuthPassword = ComicControls.passwordField(empty(http.getProxyAuthPassword()), 16);
        this.preferIpv6 = ComicControls.checkBox("Prefer IPv6 (needs restart; use when IPv4 egress is broken)", http.isPreferIpv6());

        JPanel form = new JPanel(new GridLayout(0, 2, 6, 6));
        form.setBorder(ComicControls.titledBorder("Proxy resolution"));
        form.add(new JLabel("Mode"));
        form.add(mode);
        form.add(new JLabel("Test URL"));
        form.add(testUrl);
        form.add(new JLabel("PAC URL discovery script"));
        form.add(pacUrlDiscoveryScript);
        form.add(new JLabel("Manual host"));
        form.add(manualHost);
        form.add(new JLabel("Manual port"));
        form.add(manualPort);

        JPanel trustPanel = new JPanel(new GridLayout(0, 1, 4, 4));
        trustPanel.setBorder(ComicControls.titledBorder("TLS certificate trust"));
        trustPanel.add(trustJvmDefault);
        trustPanel.add(trustWindowsRoot);
        trustPanel.add(trustWindowsCaStores);

        JButton browserUserAgent = new ComicButton("Use browser UA");
        browserUserAgent.addActionListener(e -> userAgentField.setText(HttpClientConfiguration.BROWSER_USER_AGENT));
        JPanel userAgentRow = new JPanel(new BorderLayout(6, 0));
        userAgentRow.add(userAgentField, BorderLayout.CENTER);
        userAgentRow.add(browserUserAgent, BorderLayout.EAST);

        JPanel httpPanel = new JPanel(new GridLayout(0, 2, 6, 6));
        httpPanel.setBorder(ComicControls.titledBorder("HTTP client & proxy authentication"));
        httpPanel.add(new JLabel("User-Agent"));
        httpPanel.add(userAgentRow);
        httpPanel.add(new JLabel("Proxy auth mode"));
        httpPanel.add(proxyAuthMode);
        httpPanel.add(new JLabel("Proxy username (BASIC)"));
        httpPanel.add(proxyAuthUser);
        httpPanel.add(new JLabel("Proxy password (BASIC)"));
        httpPanel.add(proxyAuthPassword);
        httpPanel.add(new JLabel("Network"));
        httpPanel.add(preferIpv6);

        JPanel groups = new JPanel();
        groups.setLayout(new BoxLayout(groups, BoxLayout.Y_AXIS));
        groups.add(form);
        groups.add(trustPanel);
        groups.add(httpPanel);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton test = new ComicButton("Resolve test URL");
        test.addActionListener(e -> resolveTestUrl());
        JButton testHuggingFace = new ComicButton("Test HuggingFace HTTPS");
        testHuggingFace.addActionListener(e -> testHuggingFaceHttps());
        JButton defaults = new ComicButton("Reset default");
        defaults.addActionListener(e -> resetDefault());
        buttons.add(test);
        buttons.add(testHuggingFace);
        buttons.add(defaults);

        JPanel top = new JPanel(new BorderLayout(8, 8));
        top.add(groups, BorderLayout.CENTER);
        top.add(buttons, BorderLayout.SOUTH);
        add(top, BorderLayout.NORTH);

        log = ComicControls.textArea(12, 60);
        log.setEditable(false);
        add(new JScrollPane(log), BorderLayout.CENTER);
        append("Proxy changes apply immediately to all Hugging Face operations.");
        append("PAC_URL_POWERSHELL: field contains a PowerShell discovery script.");
        append("PAC_URL_WSCRIPT: field contains a VBScript/WScript discovery script.");
        append("PAC_URL_MANUAL: field contains the PAC/WPAD URL directly.");
        append("Resolve test URL checks only proxy/PAC resolution (no TLS, no certificate check).");
        append("Test HuggingFace HTTPS performs a real request and validates the certificate chain.");
        installLiveBindings();
        appendModeHelp(selectedMode());
    }

    private void installLiveBindings() {
        mode.addActionListener(e -> switchScriptTextForSelectedMode());
        DocumentListener onEdit = new DocumentListener() {
            public void insertUpdate(DocumentEvent event) { onFieldEdited(); }
            public void removeUpdate(DocumentEvent event) { onFieldEdited(); }
            public void changedUpdate(DocumentEvent event) { onFieldEdited(); }
        };
        JTextComponent[] fields = new JTextComponent[]{
                manualHost, manualPort, testUrl, pacUrlDiscoveryScript,
                userAgentField, proxyAuthUser, proxyAuthPassword};
        for (int i = 0; i < fields.length; i++) {
            fields[i].getDocument().addDocumentListener(onEdit);
        }
        JCheckBox[] trustBoxes = new JCheckBox[]{trustJvmDefault, trustWindowsRoot, trustWindowsCaStores};
        for (int i = 0; i < trustBoxes.length; i++) {
            trustBoxes[i].addActionListener(e -> writeConfiguration());
        }
        proxyAuthMode.addActionListener(e -> {
            updateProxyAuthEnabled();
            writeConfiguration();
        });
        preferIpv6.addActionListener(e -> {
            writeConfiguration();
            append("Prefer IPv6 = " + preferIpv6.isSelected() + " (takes effect after an app restart).");
        });
        updateProxyAuthEnabled();
    }

    private void updateProxyAuthEnabled() {
        boolean basic = "BASIC".equals(String.valueOf(proxyAuthMode.getSelectedItem()));
        proxyAuthUser.setEnabled(basic);
        proxyAuthPassword.setEnabled(basic);
    }

    private void switchScriptTextForSelectedMode() {
        if (!updatingScriptText) {
            rememberScriptText(activeScriptMode, pacUrlDiscoveryScript.getText());
        }
        activeScriptMode = selectedMode();
        updatingScriptText = true;
        try {
            pacUrlDiscoveryScript.setText(scriptTextFor(activeScriptMode));
        } finally {
            updatingScriptText = false;
        }
        writeConfiguration();
        append("Proxy mode: " + activeScriptMode);
        appendModeHelp(activeScriptMode);
    }

    private void onFieldEdited() {
        if (!updatingScriptText) {
            rememberScriptText(activeScriptMode, pacUrlDiscoveryScript.getText());
        }
        writeConfiguration();
    }

    private void rememberScriptText(String modeName, String value) {
        String text = value == null ? "" : value;
        if (ProxyConfiguration.PAC_URL_MANUAL.equals(modeName)) {
            manualPacUrlText = text;
        } else if (ProxyConfiguration.PAC_URL_WSCRIPT.equals(modeName)) {
            wScriptText = text.length() == 0 ? PacUrlDiscoveryService.defaultScript() : text;
        } else if (ProxyConfiguration.PAC_URL_POWERSHELL.equals(modeName)) {
            powerShellScriptText = text.length() == 0 ? ProxyDefaults.DEFAULT_PAC_URL_DISCOVERY_SCRIPT : text;
        } else {
            otherScriptText = text;
        }
    }

    private String scriptTextFor(String modeName) {
        if (ProxyConfiguration.PAC_URL_MANUAL.equals(modeName)) {
            return manualPacUrlText;
        }
        if (ProxyConfiguration.PAC_URL_WSCRIPT.equals(modeName)) {
            return wScriptText == null || wScriptText.length() == 0 ? PacUrlDiscoveryService.defaultScript() : wScriptText;
        }
        if (ProxyConfiguration.PAC_URL_POWERSHELL.equals(modeName)) {
            return powerShellScriptText == null || powerShellScriptText.length() == 0 ? ProxyDefaults.DEFAULT_PAC_URL_DISCOVERY_SCRIPT : powerShellScriptText;
        }
        return otherScriptText == null ? "" : otherScriptText;
    }

    private void writeConfiguration() {
        AppConfiguration current = configurationRepository.load();
        configurationRepository.save(current.withNetworkConfiguration(
                buildConfiguration(), buildTrustConfiguration(), buildHttpClientConfiguration()));
    }

    private CertificateTrustConfiguration buildTrustConfiguration() {
        return new CertificateTrustConfiguration(
                trustJvmDefault.isSelected(),
                trustWindowsRoot.isSelected(),
                trustWindowsCaStores.isSelected());
    }

    private HttpClientConfiguration buildHttpClientConfiguration() {
        return new HttpClientConfiguration(
                userAgentField.getText(),
                HttpClientConfiguration.parseProxyAuthMode(String.valueOf(proxyAuthMode.getSelectedItem())),
                proxyAuthUser.getText(),
                new String(proxyAuthPassword.getPassword()),
                preferIpv6.isSelected());
    }

    private void resetDefault() {
        ProxyConfiguration cfg = ProxyConfiguration.defaults();
        manualPacUrlText = "";
        powerShellScriptText = ProxyDefaults.DEFAULT_PAC_URL_DISCOVERY_SCRIPT;
        wScriptText = PacUrlDiscoveryService.defaultScript();
        otherScriptText = "";
        activeScriptMode = cfg.getModeName();
        mode.setSelectedItem(cfg.getModeName());
        testUrl.setText(cfg.getTestUrl());
        updatingScriptText = true;
        try {
            pacUrlDiscoveryScript.setText(scriptTextFor(activeScriptMode));
        } finally {
            updatingScriptText = false;
        }
        manualHost.setText(empty(cfg.getManualProxyHost()));
        manualPort.setText(cfg.getManualProxyPort() > 0 ? String.valueOf(cfg.getManualProxyPort()) : "");
        CertificateTrustConfiguration trustDefaults = CertificateTrustConfiguration.defaults();
        trustJvmDefault.setSelected(trustDefaults.isUseJvmDefault());
        trustWindowsRoot.setSelected(trustDefaults.isUseWindowsRoot());
        trustWindowsCaStores.setSelected(trustDefaults.isUseWindowsCaStores());
        HttpClientConfiguration httpDefaults = HttpClientConfiguration.defaults();
        userAgentField.setText(httpDefaults.getUserAgent());
        proxyAuthMode.setSelectedItem(httpDefaults.getProxyAuthMode().name());
        proxyAuthUser.setText(empty(httpDefaults.getProxyAuthUsername()));
        proxyAuthPassword.setText(empty(httpDefaults.getProxyAuthPassword()));
        preferIpv6.setSelected(httpDefaults.isPreferIpv6());
        updateProxyAuthEnabled();
        writeConfiguration();
        append("Reset to win-proxy-java defaults: " + cfg.getModeName());
        appendModeHelp(cfg.getModeName());
    }

    private void resolveTestUrl() {
        final ProxyConfiguration cfg = buildConfiguration();
        writeConfiguration();
        final String url = cfg.getTestUrl();
        try {
            cfg.validateForUse();
        } catch (Exception ex) {
            append("ERROR: " + ex.getMessage());
            JOptionPane.showMessageDialog(this, ex.getMessage(),
                    "Proxy configuration incomplete", JOptionPane.WARNING_MESSAGE);
            return;
        }
        append("Resolve test URL checks only proxy/PAC resolution. Use 'Test HuggingFace HTTPS' for TLS.");
        append("Resolving " + url + " ...");
        new SwingWorker<Object, Void>() {
            protected Object doInBackground() {
                String diagnostic = cfg.describePacSource();
                if (diagnostic != null) {
                    append(diagnostic);
                }
                return cfg.resolve(url);
            }

            protected void done() {
                try {
                    Object result = get();
                    String detail = String.valueOf(result);
                    append("Result: " + detail);
                    JOptionPane.showMessageDialog(ProxyPanel.this, detail,
                            "Proxy Test - " + cfg.getModeName(), JOptionPane.INFORMATION_MESSAGE);
                } catch (Exception ex) {
                    append("ERROR: " + rootMessage(ex));
                }
            }
        }.execute();
    }

    private void testHuggingFaceHttps() {
        writeConfiguration();
        final ProxyConfiguration proxy = buildConfiguration();
        try {
            proxy.validateForUse();
        } catch (Exception ex) {
            append("ERROR: " + ex.getMessage());
            JOptionPane.showMessageDialog(this, ex.getMessage(),
                    "Proxy configuration incomplete", JOptionPane.WARNING_MESSAGE);
            return;
        }
        final CertificateTrustConfiguration trust = buildTrustConfiguration();
        final HttpClientConfiguration http = buildHttpClientConfiguration();
        final String token = configurationRepository.load().getHuggingFaceToken();
        append("Testing HuggingFace HTTPS (real request; probes default + browser User-Agent) ...");
        new SwingWorker<HuggingFaceConnectionTestResult, Void>() {
            protected HuggingFaceConnectionTestResult doInBackground() {
                return new HuggingFaceClient(proxy, trust, http, token).testConnection();
            }

            protected void done() {
                try {
                    HuggingFaceConnectionTestResult result = get();
                    append(result.describe());
                    JOptionPane.showMessageDialog(ProxyPanel.this, result.shortSummary(),
                            "HuggingFace HTTPS - " + (result.isSuccess() ? "OK" : "Problem"),
                            result.isSuccess() ? JOptionPane.INFORMATION_MESSAGE : JOptionPane.WARNING_MESSAGE);
                } catch (Exception ex) {
                    append("ERROR: " + rootMessage(ex));
                }
            }
        }.execute();
    }

    private ProxyConfiguration buildConfiguration() {
        return new ProxyConfiguration(
                selectedMode(),
                nonBlank(testUrl.getText(), ProxyConfiguration.defaults().getTestUrl()),
                scriptTextFor(selectedMode()),
                "",
                empty(manualHost.getText()),
                parsePort(manualPort.getText()));
    }

    private String selectedMode() {
        Object selected = mode.getSelectedItem();
        return selected == null ? ProxyConfiguration.defaults().getModeName() : String.valueOf(selected);
    }

    private void appendModeHelp(String selectedMode) {
        if (ProxyConfiguration.PAC_URL_MANUAL.equals(selectedMode)) {
            append("PAC_URL_MANUAL: paste the PAC/WPAD URL into 'PAC URL discovery script'.");
        } else if (ProxyConfiguration.PAC_URL_WSCRIPT.equals(selectedMode)) {
            append("PAC_URL_WSCRIPT: the field contains VBScript that prints the PAC/WPAD URL.");
        } else if (ProxyConfiguration.PAC_URL_WINDOWS_SETTINGS.equals(selectedMode)) {
            append("PAC_URL_WINDOWS_SETTINGS: the PAC URL is taken from Windows proxy settings.");
        } else if (ProxyConfiguration.PAC_URL_POWERSHELL.equals(selectedMode)) {
            append("PAC_URL_POWERSHELL: the field contains the PowerShell discovery script.");
        } else if (ProxyConfiguration.MANUAL_PROXY.equals(selectedMode)) {
            append("MANUAL_PROXY: enter proxy host and port; PAC fields are ignored.");
        }
    }

    private void append(String message) {
        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                log.append(message + System.lineSeparator());
            }
        });
    }

    private String rootMessage(Exception ex) {
        Throwable current = ex;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? String.valueOf(current) : current.getMessage();
    }

    private static String nonBlank(String value, String fallback) {
        String trimmed = trimToNull(value);
        return trimmed == null ? fallback : trimmed;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() == 0 ? null : trimmed;
    }

    private static String empty(String value) {
        return value == null ? "" : value;
    }

    private static int parsePort(String value) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            return 0;
        }
        try {
            return Integer.parseInt(trimmed);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }
}
