package com.aresstack.askai.research.knowledge.processing.embedding;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;

/** The productive {@link EmbeddingHttpTransport} over {@code HttpURLConnection}. No JSON, no descriptor. */
public final class UrlConnectionEmbeddingHttpTransport implements EmbeddingHttpTransport {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    private final int timeoutMillis;

    public UrlConnectionEmbeddingHttpTransport(int timeoutMillis) {
        this.timeoutMillis = timeoutMillis <= 0 ? 30_000 : timeoutMillis;
    }

    @Override
    public String post(String url, String jsonBody) throws IOException {
        // Through the host's network settings (proxy, certificate trust), as the chat provider's requests.
        HttpURLConnection connection = com.aresstack.askai.agent.model.EndpointAuthorization.open(url);
        connection.setConnectTimeout(timeoutMillis);
        connection.setReadTimeout(timeoutMillis);
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json");
        // An OpenAI-compatible API gets its key from the host (per request, never stored); other URLs get none.
        boolean authorized = com.aresstack.askai.agent.model.EndpointAuthorization.authorize(connection,
                com.aresstack.askai.agent.model.EndpointAuthorization.bearerFor(url));
        connection.setInstanceFollowRedirects(!authorized); // the key never follows a redirect elsewhere
        connection.setDoOutput(true);
        OutputStream out = connection.getOutputStream();
        try {
            out.write(jsonBody.getBytes(UTF8));
        } finally {
            out.close();
        }
        int status = connection.getResponseCode();
        String body = readAll(status >= 400 ? connection.getErrorStream() : connection.getInputStream());
        if (status != 200) {
            if (authorized) { // an API's error text may echo the input texts: status and error code only
                throw new IOException(OpenAiEmbeddingWire.failure(new URL(url).getPath(), status, body));
            }
            throw new IOException("embedding endpoint returned HTTP " + status + ": " + body);
        }
        return body;
    }

    private static String readAll(InputStream in) throws IOException {
        if (in == null) {
            return "";
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int read;
        try {
            while ((read = in.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }
        } finally {
            in.close();
        }
        return new String(buffer.toByteArray(), UTF8);
    }
}
