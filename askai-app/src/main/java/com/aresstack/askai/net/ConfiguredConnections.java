package com.aresstack.askai.net;

import javax.net.ssl.HttpsURLConnection;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Opens HTTP(S) connections the way AskAI's network settings say: the configured proxy (including PAC), the
 * configured certificate trust (Windows stores for TLS-inspecting company proxies), the configured User-Agent and
 * BASIC proxy credentials. Downloads that should prove the network settings (for example the setup wizard's voice
 * download) go through here instead of a plain {@link URL#openConnection()}.
 */
public final class ConfiguredConnections {

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
            String raw = http.getProxyAuthUsername() + ":" + http.getProxyAuthPassword();
            connection.setRequestProperty("Proxy-Authorization",
                    "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8)));
        }
        return connection;
    }
}
