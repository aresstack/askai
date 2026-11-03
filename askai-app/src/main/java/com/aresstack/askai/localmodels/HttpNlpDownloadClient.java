package com.aresstack.askai.localmodels;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * The productive {@link NlpDownloadClient}: follows redirects MANUALLY (SourceForge {@code .../download} bounces
 * across hosts and http/https, which {@link HttpURLConnection} will not auto-follow across protocols) up to a
 * bounded number of hops, then returns the 200 body. The redirect loop is unit-testable via the {@link Connector}
 * seam; only the productive connector touches the network.
 */
public final class HttpNlpDownloadClient implements NlpDownloadClient {

    /** One HTTP exchange without auto-redirects: status, an optional {@code Location}, and the body (200 only). */
    interface Connector {
        Response open(String url) throws IOException;
    }

    static final class Response {
        final int status;
        final String location;
        final byte[] body;

        Response(int status, String location, byte[] body) {
            this.status = status;
            this.location = location;
            this.body = body;
        }
    }

    private static final int MAX_REDIRECTS = 8;

    private final Connector connector;

    public HttpNlpDownloadClient() {
        this(new UrlConnector(30_000));
    }

    HttpNlpDownloadClient(Connector connector) {
        this.connector = connector;
    }

    @Override
    public byte[] fetch(String url) throws IOException {
        String current = url;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            Response response = connector.open(current);
            if (response.status == 200) {
                if (response.body == null) {
                    throw new IOException("empty 200 response from " + current);
                }
                return response.body;
            }
            if (isRedirect(response.status)) {
                if (response.location == null || response.location.trim().isEmpty()) {
                    throw new IOException("redirect without a Location header from " + current);
                }
                current = resolve(current, response.location.trim());
                continue;
            }
            throw new IOException("download failed with HTTP " + response.status + " for " + current);
        }
        throw new IOException("too many redirects downloading " + url);
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private static String resolve(String base, String location) throws IOException {
        try {
            return new URL(new URL(base), location).toString();
        } catch (Exception ex) {
            throw new IOException("invalid redirect target '" + location + "'", ex);
        }
    }

    /** The productive connector over {@link HttpURLConnection} (no auto-redirects). */
    private static final class UrlConnector implements Connector {
        private final int timeoutMillis;

        UrlConnector(int timeoutMillis) {
            this.timeoutMillis = timeoutMillis;
        }

        public Response open(String url) throws IOException {
            // AskAI's proxy, PAC, certificates and proxy login (the setup wizard's unsaved ones while it runs)
            HttpURLConnection connection = com.aresstack.askai.plugin.api.service.HostNetwork.open(url);
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(timeoutMillis);
            connection.setReadTimeout(timeoutMillis);
            connection.setRequestProperty("User-Agent", "askai-nlp-installer");
            int status = connection.getResponseCode();
            if (isRedirect(status)) {
                return new Response(status, connection.getHeaderField("Location"), null);
            }
            if (status != 200) {
                return new Response(status, null, null);
            }
            InputStream in = connection.getInputStream();
            try {
                return new Response(200, null, readAll(in));
            } finally {
                in.close();
            }
        }

        private static byte[] readAll(InputStream in) throws IOException {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }
            return buffer.toByteArray();
        }
    }
}
