package com.aresstack.askai.research.search.googlebrowser;

import com.aresstack.askai.research.search.config.HttpTransportConfiguration;

/**
 * AskAI configuration file {@code google-browser.json} for the Google browser search (Quick Web Search, askai #31).
 * The search itself runs completely in Serp4J ({@code serp4j-engine-google-browser}): Serp4J builds the Google URL,
 * drives its own search browser over the Serp4J browser port and parses the result page. AskAI only chooses the
 * local browser for that search browser. It is a separate browser process with an ephemeral profile per search,
 * independent of the research browser (MCP sidecar) that opens and checks the found pages.
 *
 * <p>No secrets. Google {@code /goto} result links are resolved by Serp4J over HTTP (cookie-free, redirects not
 * followed automatically) when {@link #isResolveGotoUrls()} is set; unresolved links stay marked as unresolved.</p>
 */
public final class GoogleBrowserSearchConfiguration {

    private int formatVersion = 1;
    private boolean enabled;
    /** Playwright browser family: {@code chromium} (default) or {@code firefox}. */
    private String browserType = "chromium";
    /**
     * Installed browser channel for Chromium, e.g. {@code msedge} or {@code chrome}; empty uses
     * {@link #getExecutablePath()} or Playwright's own Chromium.
     */
    private String channel = "msedge";
    /** Optional absolute path to a browser executable; overrides {@link #getChannel()}. */
    private String executablePath;
    private boolean headless = true;
    private String language = "de";
    private String country = "de";
    /** Navigation budget per search; 0 keeps the Serp4J default (30 s). */
    private int navigationTimeoutMillis;
    /** Maximum parallel searches in the search browser; 0 keeps the Serp4J default. */
    private int maxConcurrentSessions;
    private boolean resolveGotoUrls = true;
    /** HTTP settings for the {@code /goto} resolution. */
    private HttpTransportConfiguration transport = new HttpTransportConfiguration();

    public GoogleBrowserSearchConfiguration() {
    }

    public int getFormatVersion() {
        return formatVersion;
    }

    public void setFormatVersion(int formatVersion) {
        this.formatVersion = formatVersion;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBrowserType() {
        return browserType;
    }

    public void setBrowserType(String browserType) {
        this.browserType = browserType;
    }

    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }

    public String getExecutablePath() {
        return executablePath;
    }

    public void setExecutablePath(String executablePath) {
        this.executablePath = executablePath;
    }

    public boolean isHeadless() {
        return headless;
    }

    public void setHeadless(boolean headless) {
        this.headless = headless;
    }

    public String getLanguage() {
        return language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    public String getCountry() {
        return country;
    }

    public void setCountry(String country) {
        this.country = country;
    }

    public int getNavigationTimeoutMillis() {
        return navigationTimeoutMillis;
    }

    public void setNavigationTimeoutMillis(int navigationTimeoutMillis) {
        this.navigationTimeoutMillis = navigationTimeoutMillis;
    }

    public int getMaxConcurrentSessions() {
        return maxConcurrentSessions;
    }

    public void setMaxConcurrentSessions(int maxConcurrentSessions) {
        this.maxConcurrentSessions = maxConcurrentSessions;
    }

    public boolean isResolveGotoUrls() {
        return resolveGotoUrls;
    }

    public void setResolveGotoUrls(boolean resolveGotoUrls) {
        this.resolveGotoUrls = resolveGotoUrls;
    }

    public HttpTransportConfiguration getTransport() {
        return transport;
    }

    public void setTransport(HttpTransportConfiguration transport) {
        this.transport = transport;
    }
}
