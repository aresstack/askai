package com.aresstack.askai.net;

import javax.net.ssl.HttpsURLConnection;
import java.io.IOException;
import java.net.Authenticator;
import java.net.HttpURLConnection;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Opens HTTP(S) connections the way AskAI's network settings say: the configured proxy (including PAC), the
 * configured certificate trust (Windows stores for TLS-inspecting company proxies), the configured User-Agent and
 * BASIC proxy credentials. Downloads that should prove the network settings (for example the setup wizard's voice
 * download) go through here instead of a plain {@link URL#openConnection()}.
 *
 * <p>BASIC proxy credentials go to the proxy through a per-connection {@link Authenticator}, so they also reach the
 * CONNECT tunnel of HTTPS URLs (a plain request header does not). The JDK refuses BASIC for tunnels unless
 * {@link #TUNNELING_DISABLED_SCHEMES} allows it; {@link #allowBasicProxyTunneling()} must run before the first HTTP
 * connection of the process, which {@code AskAiApp.main} does.</p>
 */
public final class ConfiguredConnections {

    /** JDK property listing the auth schemes refused for HTTPS tunnels (default: {@code Basic}). */
    public static final String TUNNELING_DISABLED_SCHEMES = "jdk.http.auth.tunneling.disabledSchemes";

    static {
        allowBasicProxyTunneling();
    }

    /**
     * Lets the JDK answer a proxy's BASIC challenge for HTTPS tunnels, unless the user configured the property
     * explicitly. The JDK reads it once, when the HTTP handler class initialises.
     */
    public static void allowBasicProxyTunneling() {
        if (System.getProperty(TUNNELING_DISABLED_SCHEMES) == null) {
            System.setProperty(TUNNELING_DISABLED_SCHEMES, "");
        }
    }

    private final ProxyConfiguration proxy;
    private final CertificateTrustConfiguration trust;
    private final HttpClientConfiguration http;

    public ConfiguredConnections(ProxyConfiguration proxy, CertificateTrustConfiguration trust,
                                 HttpClientConfiguration http) {
        if (proxy == null || trust == null) {
            throw new IllegalArgumentException("proxy and trust must not be null");
        }
        this.proxy = proxy;
        this.trust = trust;
        this.http = http == null ? HttpClientConfiguration.defaults() : http;
    }

    /** An unconnected connection to {@code url}; the caller sets timeouts and reads it. */
    public HttpURLConnection open(String url) throws IOException {
        proxy.validateForUse();
        Proxy resolved = proxy.resolveJavaProxy(url);
        HttpURLConnection connection = (HttpURLConnection) (resolved == Proxy.NO_PROXY
                ? new URL(url).openConnection()
                : new URL(url).openConnection(resolved));
        if (connection instanceof HttpsURLConnection) {
            ((HttpsURLConnection) connection).setSSLSocketFactory(
                    SystemTrustSslSocketFactory.build(trust).getSocketFactory());
        }
        connection.setRequestProperty("User-Agent", http.getUserAgent());
        if (http.hasBasicCredentials()) {
            connection.setAuthenticator(new ProxyCredentials(http.getProxyAuthUsername(),
                    http.getProxyAuthPassword()));
            if (!(connection instanceof HttpsURLConnection)) {
                // Plain HTTP through the proxy: send them right away instead of waiting for the 407.
                String raw = http.getProxyAuthUsername() + ":" + http.getProxyAuthPassword();
                connection.setRequestProperty("Proxy-Authorization",
                        "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8)));
            }
        }
        return connection;
    }

    /** Answers only the proxy's challenge; a server asking for credentials gets none. */
    private static final class ProxyCredentials extends Authenticator {
        private final String user;
        private final String password;

        ProxyCredentials(String user, String password) {
            this.user = user;
            this.password = password;
        }

        @Override
        protected PasswordAuthentication getPasswordAuthentication() {
            return getRequestorType() == RequestorType.PROXY
                    ? new PasswordAuthentication(user, password.toCharArray())
                    : null;
        }
    }
}
