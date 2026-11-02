package com.aresstack.askai.ollamalib;

import com.aresstack.askai.net.CertificateTrustConfiguration;
import com.aresstack.askai.net.HttpClientConfiguration;
import com.aresstack.askai.net.ProxyConfiguration;
import com.aresstack.askai.net.SystemTrustSslSocketFactory;

import javax.net.ssl.HttpsURLConnection;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Encapsulates all ollama.com HTTP + HTML-parsing behind a small API: {@link #search(String)} and
 * {@link #loadVariants(String)}. The HTTP fetch goes through the same proxy resolution and Windows
 * certificate trust the rest of AskAI uses (so it works behind a corporate PAC proxy / MITM), while
 * Jerry/Lagarto parses the returned HTML (see {@link OllamaLibraryHtmlParser}). A short in-memory TTL
 * cache avoids re-fetching the same page on repeated opens.
 */
public final class OllamaLibraryClient {

    private static final String BASE = "https://ollama.com";
    private static final String BROWSER_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/124.0 Safari/537.36";
    private static final Pattern BASE_NAME = Pattern.compile("[a-z0-9][a-z0-9._-]*");
    private static final int CONNECT_TIMEOUT_MILLIS = 15000;
    private static final int READ_TIMEOUT_MILLIS = 20000;
    private static final long CACHE_TTL_MILLIS = 5L * 60L * 1000L;

    private final ProxyConfiguration proxyConfiguration;
    private final CertificateTrustConfiguration trustConfiguration;
    private final HttpClientConfiguration httpClientConfiguration;
    private final OllamaLibraryHtmlParser parser = new OllamaLibraryHtmlParser();
    private final Map<String, CachedPage> cache = new HashMap<String, CachedPage>();

    public OllamaLibraryClient(ProxyConfiguration proxyConfiguration,
                               CertificateTrustConfiguration trustConfiguration,
                               HttpClientConfiguration httpClientConfiguration) {
        this.proxyConfiguration = proxyConfiguration == null ? ProxyConfiguration.defaults() : proxyConfiguration;
        this.trustConfiguration = trustConfiguration == null
                ? CertificateTrustConfiguration.defaults() : trustConfiguration;
        this.httpClientConfiguration = httpClientConfiguration == null
                ? HttpClientConfiguration.defaults() : httpClientConfiguration;
    }

    /** Searches the Ollama library for {@code query} and returns the parsed result models. */
    public List<OllamaLibraryModel> search(String query) throws IOException {
        String url = BASE + "/search?q=" + encode(query == null ? "" : query.trim());
        return parser.parseSearchResults(getHtml(url));
    }

    /** Loads the installable tag variants of the given library base name (e.g. "devstral-small-2"). */
    public List<OllamaModelVariant> loadVariants(String baseName) throws IOException {
        String name = baseName == null ? "" : baseName.trim();
        if (!BASE_NAME.matcher(name).matches()) {
            throw new IOException("Ungültiger Ollama-Modellname: " + baseName);
        }
        return parser.parseModelVariants(name, getHtml(BASE + "/library/" + name));
    }

    private String getHtml(String url) throws IOException {
        synchronized (cache) {
            CachedPage cached = cache.get(url);
            if (cached != null && System.currentTimeMillis() - cached.timestamp < CACHE_TTL_MILLIS) {
                return cached.html;
            }
        }
        HttpURLConnection connection = open(url);
        InputStream inputStream = null;
        try {
            int status = connection.getResponseCode();
            inputStream = status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream();
            String body = readText(inputStream);
            if (status < 200 || status >= 300) {
                throw new IOException("ollama.com antwortete mit HTTP " + status);
            }
            synchronized (cache) {
                cache.put(url, new CachedPage(body, System.currentTimeMillis()));
            }
            return body;
        } finally {
            closeQuietly(inputStream);
            connection.disconnect();
        }
    }

    /**
     * Opens the connection with the same proxy resolution + TLS trust as the rest of AskAI, but with
     * a browser User-Agent and an HTML Accept header (no HuggingFace token). Mirrors the HuggingFace
     * client's {@code open} so a corporate PAC proxy / private-CA MITM is handled identically.
     */
    private HttpURLConnection open(String url) throws IOException {
        Proxy proxy = proxyConfiguration.resolveJavaProxy(url);
        HttpURLConnection connection = (HttpURLConnection) (proxy == Proxy.NO_PROXY
                ? new URL(url).openConnection()
                : new URL(url).openConnection(proxy));
        if (connection instanceof HttpsURLConnection) {
            ((HttpsURLConnection) connection).setSSLSocketFactory(
                    SystemTrustSslSocketFactory.build(trustConfiguration).getSocketFactory());
        }
        connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
        connection.setReadTimeout(READ_TIMEOUT_MILLIS);
        connection.setRequestProperty("User-Agent", BROWSER_USER_AGENT);
        connection.setRequestProperty("Accept", "text/html,application/xhtml+xml");
        connection.setInstanceFollowRedirects(true);
        applyProxyAuthorization(connection);
        return connection;
    }

    private void applyProxyAuthorization(HttpURLConnection connection) {
        if (!httpClientConfiguration.hasBasicCredentials()) {
            return;
        }
        String raw = httpClientConfiguration.getProxyAuthUsername() + ":"
                + httpClientConfiguration.getProxyAuthPassword();
        String encoded = Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        connection.setRequestProperty("Proxy-Authorization", "Basic " + encoded);
    }

    private static String readText(InputStream inputStream) throws IOException {
        if (inputStream == null) {
            return "";
        }
        byte[] buffer = new byte[8192];
        StringBuilder builder = new StringBuilder();
        int read;
        while ((read = inputStream.read(buffer)) >= 0) {
            builder.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
        }
        return builder.toString();
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (UnsupportedEncodingException ex) {
            return "";
        }
    }

    private static void closeQuietly(InputStream inputStream) {
        if (inputStream != null) {
            try {
                inputStream.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static final class CachedPage {
        private final String html;
        private final long timestamp;

        private CachedPage(String html, long timestamp) {
            this.html = html;
            this.timestamp = timestamp;
        }
    }
}
