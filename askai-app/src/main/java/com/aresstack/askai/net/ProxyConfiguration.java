package com.aresstack.askai.net;

import com.aresstack.winproxy.ProxyDefaults;
import com.aresstack.winproxy.ProxyMode;
import com.aresstack.winproxy.ProxyResult;
import com.aresstack.winproxy.WindowsProxyResolver;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;

public final class ProxyConfiguration {
    public static final String DISABLED = "DISABLED";
    public static final String MANUAL_PROXY = "MANUAL_PROXY";
    public static final String WINDOWS_STATIC_PROXY = "WINDOWS_STATIC_PROXY";
    public static final String PAC_URL_MANUAL = "PAC_URL_MANUAL";
    public static final String PAC_URL_POWERSHELL = "PAC_URL_POWERSHELL";
    public static final String PAC_URL_WSCRIPT = "PAC_URL_WSCRIPT";
    public static final String PAC_URL_WINDOWS_SETTINGS = "PAC_URL_WINDOWS_SETTINGS";
    public static final String WINDOWS_NATIVE_PROXY_SETTINGS = "WINDOWS_NATIVE_PROXY_SETTINGS";
    public static final String WINDOWS_NATIVE_ROUTE_RESOLVER = "WINDOWS_NATIVE_ROUTE_RESOLVER";

    private final String mode;
    private final String testUrl;
    private final String pacUrlDiscoveryScript;
    private final String pacUrl;
    private final String manualProxyHost;
    private final int manualProxyPort;

    public ProxyConfiguration(boolean enabled, String host, int port) {
        this(enabled ? MANUAL_PROXY : DISABLED, defaults().getTestUrl(), defaults().getPacUrlDiscoveryScript(), "", host, port);
    }

    public ProxyConfiguration(String mode, String testUrl, String pacUrlDiscoveryScript, String pacUrl, String manualProxyHost, int manualProxyPort) {
        this.mode = normalizeMode(mode);
        this.testUrl = nonBlank(testUrl, com.aresstack.winproxy.ProxyConfiguration.defaults().getTestUrl());
        this.pacUrlDiscoveryScript = nonBlank(pacUrlDiscoveryScript, defaultDiscoveryScript(this.mode));
        this.pacUrl = empty(pacUrl);
        this.manualProxyHost = empty(manualProxyHost);
        this.manualProxyPort = manualProxyPort;
    }

    public ProxyConfiguration(ProxyMode mode, String testUrl, String pacUrlDiscoveryScript, String pacUrl, String manualProxyHost, int manualProxyPort) {
        this(mode == null ? null : mode.name(), testUrl, pacUrlDiscoveryScript, pacUrl, manualProxyHost, manualProxyPort);
    }

    public static ProxyConfiguration disabled() {
        return new ProxyConfiguration(DISABLED, com.aresstack.winproxy.ProxyConfiguration.defaults().getTestUrl(), ProxyDefaults.DEFAULT_PAC_URL_DISCOVERY_SCRIPT, "", "", 0);
    }

    public static ProxyConfiguration defaults() {
        com.aresstack.winproxy.ProxyConfiguration defaults = com.aresstack.winproxy.ProxyConfiguration.defaults();
        return new ProxyConfiguration(defaults.getMode().name(), defaults.getTestUrl(), defaultPacScript(defaults), defaults.getPacUrl(), defaults.getManualProxyHost(), defaults.getManualProxyPort());
    }

    public boolean isEnabled() {
        return !DISABLED.equals(mode);
    }

    public ProxyMode getMode() {
        return toWinProxyMode(mode);
    }

    public String getModeName() {
        return mode;
    }

    public String getTestUrl() {
        return testUrl;
    }

    public String getPacUrlDiscoveryScript() {
        return pacUrlDiscoveryScript;
    }

    public String getPacUrl() {
        return manualPacUrl();
    }

    public String getHost() {
        return manualProxyHost;
    }

    public String getManualProxyHost() {
        return manualProxyHost;
    }

    public int getPort() {
        return manualProxyPort;
    }

    public int getManualProxyPort() {
        return manualProxyPort;
    }

    public com.aresstack.winproxy.ProxyConfiguration toWinProxyConfiguration() {
        return toWinProxyConfiguration(toWinProxyMode(mode), manualPacUrl());
    }

    private com.aresstack.winproxy.ProxyConfiguration toWinProxyConfiguration(ProxyMode configuredMode, String effectivePacUrl) {
        com.aresstack.winproxy.ProxyConfiguration.Builder builder = com.aresstack.winproxy.ProxyConfiguration.builder()
                .mode(configuredMode)
                .testUrl(testUrl)
                .pacUrlDiscoveryScript(pacUrlDiscoveryScript);

        if (trimToNull(effectivePacUrl) != null) {
            builder.pacUrl(effectivePacUrl);
        }
        if (trimToNull(manualProxyHost) != null) {
            builder.manualProxyHost(manualProxyHost);
        }
        if (manualProxyPort > 0) {
            builder.manualProxyPort(manualProxyPort);
        }

        return builder.build();
    }

    public Object resolve(String url) {
        try {
            return resolveByMode(nonBlank(url, testUrl));
        } catch (IOException ex) {
            return "ERROR (" + messageOf(ex) + ")";
        } catch (RuntimeException ex) {
            return "ERROR (" + messageOf(ex) + ")";
        }
    }

    /**
     * Produces a human readable diagnostic of the PAC script source for the current mode. This is
     * used by the UI to log whether the PAC/WPAD URL was discovered and whether the PAC script file
     * is actually reachable, which helps tell a discovery/download problem apart from an engine
     * evaluation problem (win-proxy-java only reports the opaque "pac-evaluation-failed" reason).
     *
     * @return a diagnostic message, or {@code null} when the current mode does not use a PAC script
     *         we can inspect here.
     */
    public String describePacSource() {
        String pacUrl;
        try {
            pacUrl = discoverPacUrl();
        } catch (IOException ex) {
            return "PAC URL discovery failed: " + messageOf(ex);
        }
        if (pacUrl == null) {
            return null;
        }
        if (trimToNull(pacUrl) == null) {
            return "No PAC/WPAD URL was discovered.";
        }
        StringBuilder message = new StringBuilder("PAC URL: ").append(pacUrl);
        try {
            int bytes = pacScriptLength(pacUrl);
            message.append(" (PAC script reachable, ").append(bytes).append(" bytes)");
        } catch (IOException ex) {
            message.append(" (PAC script NOT reachable: ").append(messageOf(ex)).append(")");
        }
        return message.toString();
    }

    private String discoverPacUrl() throws IOException {
        if (PAC_URL_WSCRIPT.equals(mode)) {
            return new PacUrlDiscoveryService().discoverWithScript(pacUrlDiscoveryScript);
        }
        if (PAC_URL_MANUAL.equals(mode)) {
            return pacUrlDiscoveryScript;
        }
        return null;
    }

    private int pacScriptLength(String pacUrl) throws IOException {
        java.net.HttpURLConnection connection = null;
        try {
            java.net.URLConnection raw = new java.net.URL(pacUrl).openConnection();
            raw.setConnectTimeout(10000);
            raw.setReadTimeout(10000);
            if (raw instanceof java.net.HttpURLConnection) {
                connection = (java.net.HttpURLConnection) raw;
            }
            java.io.InputStream input = raw.getInputStream();
            try {
                byte[] buffer = new byte[8192];
                int total = 0;
                int read;
                while ((read = input.read(buffer)) != -1) {
                    total += read;
                }
                return total;
            } finally {
                input.close();
            }
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    public Proxy toProxy() {
        return toProxyFor(testUrl);
    }

    public Proxy toProxyFor(String url) {
        try {
            return resolveJavaProxy(url);
        } catch (IOException ex) {
            return Proxy.NO_PROXY;
        }
    }

    public Proxy resolveJavaProxy(String url) throws IOException {
        validateForUse();

        if (DISABLED.equals(mode)) {
            return Proxy.NO_PROXY;
        }
        if (MANUAL_PROXY.equals(mode)) {
            return manualProxy();
        }

        try {
            ProxyResult result = resolveByMode(nonBlank(url, testUrl));
            return result.toJavaProxyOrNoProxy();
        } catch (RuntimeException ex) {
            throw new IOException("Proxy resolution failed: " + messageOf(ex), ex);
        }
    }

    private Proxy manualProxy() throws IOException {
        if (trimToNull(manualProxyHost) == null || manualProxyPort <= 0) {
            throw new IOException("MANUAL_PROXY requires manual host and port.");
        }

        return new Proxy(Proxy.Type.HTTP, new InetSocketAddress(manualProxyHost, manualProxyPort));
    }

    private ProxyResult resolveByMode(String url) throws IOException {
        if (PAC_URL_WSCRIPT.equals(mode)) {
            String discoveredPacUrl = new PacUrlDiscoveryService().discoverWithScript(pacUrlDiscoveryScript);
            return resolveWithConfiguration(toWinProxyConfiguration(ProxyMode.PAC_URL_MANUAL, discoveredPacUrl), url);
        }

        return resolveWithConfiguration(toWinProxyConfiguration(), url);
    }

    private ProxyResult resolveWithConfiguration(com.aresstack.winproxy.ProxyConfiguration configuration, String url) {
        return new WindowsProxyResolver(configuration).resolve(url);
    }

    public void validateForUse() throws IOException {
        if (DISABLED.equals(mode)) {
            return;
        }

        if (MANUAL_PROXY.equals(mode)) {
            if (trimToNull(manualProxyHost) == null || manualProxyPort <= 0) {
                throw new IOException("MANUAL_PROXY requires manual host and port.");
            }
            return;
        }

        if (PAC_URL_MANUAL.equals(mode) && trimToNull(pacUrlDiscoveryScript) == null) {
            throw new IOException("PAC_URL_MANUAL requires the PAC/WPAD URL in the PAC URL discovery script field.");
        }

        if (PAC_URL_WSCRIPT.equals(mode) && trimToNull(pacUrlDiscoveryScript) == null) {
            throw new IOException("PAC_URL_WSCRIPT requires a script in the PAC URL discovery script field.");
        }
    }

    private String manualPacUrl() {
        return PAC_URL_MANUAL.equals(mode) ? pacUrlDiscoveryScript : pacUrl;
    }

    private ProxyMode toWinProxyMode(String configuredMode) {
        if (PAC_URL_WSCRIPT.equals(configuredMode)) {
            return ProxyMode.PAC_URL_MANUAL;
        }

        return ProxyMode.valueOf(configuredMode);
    }

    private static String normalizeMode(String value) {
        String candidate = value == null || value.trim().length() == 0 ? PAC_URL_POWERSHELL : value.trim();

        if (PAC_URL_WSCRIPT.equals(candidate)) {
            return candidate;
        }

        try {
            return ProxyMode.valueOf(candidate).name();
        } catch (Exception ex) {
            return PAC_URL_POWERSHELL;
        }
    }

    private static String defaultDiscoveryScript(String configuredMode) {
        if (PAC_URL_WSCRIPT.equals(configuredMode)) {
            return PacUrlDiscoveryService.defaultScript();
        }

        return ProxyDefaults.DEFAULT_PAC_URL_DISCOVERY_SCRIPT;
    }

    private static String defaultPacScript(com.aresstack.winproxy.ProxyConfiguration cfg) {
        return nonBlank(cfg.getPacUrlDiscoveryScript(), ProxyDefaults.DEFAULT_PAC_URL_DISCOVERY_SCRIPT);
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

    private static String messageOf(Throwable throwable) {
        if (throwable == null) {
            return "unknown error";
        }

        String message = throwable.getMessage();
        if (message != null && message.trim().length() > 0) {
            return message;
        }

        return throwable.getClass().getName();
    }
}
