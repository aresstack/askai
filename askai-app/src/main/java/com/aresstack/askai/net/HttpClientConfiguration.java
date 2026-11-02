package com.aresstack.askai.net;

/**
 * Request-level HTTP settings that are independent of both proxy <em>resolution</em>
 * ({@link ProxyConfiguration}) and TLS <em>trust</em> ({@link CertificateTrustConfiguration}):
 * the outgoing {@code User-Agent} and how (if at all) the client authenticates to the proxy.
 *
 * <p>These exist because a corporate proxy can return HTTP 403 <em>after</em> a perfectly valid TLS
 * handshake &mdash; e.g. it evaluates the User-Agent differently, or it expects the request to be
 * authenticated (SSO/NTLM/Kerberos/Basic) the way a browser session is. None of that is a certificate
 * or PAC problem.</p>
 */
public final class HttpClientConfiguration {

    /** How the client authenticates to the proxy. */
    public enum ProxyAuthMode {
        /** No proxy credentials are sent. */
        NONE,
        /** Send {@code Proxy-Authorization: Basic base64(user:pass)}. */
        BASIC,
        /**
         * Windows integrated auth (NTLM/Kerberos/SSO). Prepared and diagnosed only &mdash; it is
         * <em>not</em> wired into request negotiation, so for real requests it behaves like
         * {@link #NONE}. Selecting it surfaces guidance in the HuggingFace test.
         */
        WINDOWS_INTEGRATED
    }

    public static final String DEFAULT_USER_AGENT = "askai";

    /** A Firefox-like User-Agent, useful to check whether the proxy treats browsers differently. */
    public static final String BROWSER_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:125.0) Gecko/20100101 Firefox/125.0";

    private final String userAgent;
    private final ProxyAuthMode proxyAuthMode;
    private final String proxyAuthUsername;
    private final String proxyAuthPassword;
    private final boolean preferIpv6;

    public HttpClientConfiguration(String userAgent, ProxyAuthMode proxyAuthMode,
                                   String proxyAuthUsername, String proxyAuthPassword, boolean preferIpv6) {
        this.userAgent = userAgent == null || userAgent.trim().length() == 0
                ? DEFAULT_USER_AGENT : userAgent.trim();
        this.proxyAuthMode = proxyAuthMode == null ? ProxyAuthMode.NONE : proxyAuthMode;
        this.proxyAuthUsername = proxyAuthUsername == null ? "" : proxyAuthUsername;
        this.proxyAuthPassword = proxyAuthPassword == null ? "" : proxyAuthPassword;
        this.preferIpv6 = preferIpv6;
    }

    public static HttpClientConfiguration defaults() {
        return new HttpClientConfiguration(DEFAULT_USER_AGENT, ProxyAuthMode.NONE, "", "", false);
    }

    public String getUserAgent() {
        return userAgent;
    }

    public ProxyAuthMode getProxyAuthMode() {
        return proxyAuthMode;
    }

    public String getProxyAuthUsername() {
        return proxyAuthUsername;
    }

    public String getProxyAuthPassword() {
        return proxyAuthPassword;
    }

    /**
     * @return whether Java should prefer IPv6 addresses. Useful when the machine's IPv4 egress is
     *         broken (e.g. a LAN cable steals the default route) but IPv6 works, as browsers do.
     *         Applied at startup via {@code java.net.preferIPv6Addresses}; changing it needs a restart.
     */
    public boolean isPreferIpv6() {
        return preferIpv6;
    }

    /** @return {@code true} when BASIC auth is selected and a username is present. */
    public boolean hasBasicCredentials() {
        return proxyAuthMode == ProxyAuthMode.BASIC && proxyAuthUsername.length() > 0;
    }

    public static ProxyAuthMode parseProxyAuthMode(String value) {
        if (value == null) {
            return ProxyAuthMode.NONE;
        }
        try {
            return ProxyAuthMode.valueOf(value.trim());
        } catch (IllegalArgumentException ex) {
            return ProxyAuthMode.NONE;
        }
    }
}
