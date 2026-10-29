package com.aresstack.askai.java8.localmodels;

import com.aresstack.askai.java8.json.SimpleJson;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.Map;

/** Minimal loopback HTTP/JSON helper for talking to the local model runtime sidecar. */
public final class LocalRuntimeHttp {

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final int TIMEOUT_MILLIS = 10 * 60 * 1000; // package compile can take a while

    private LocalRuntimeHttp() {
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> postJson(String baseUrl, String path, Object body)
            throws IOException {
        HttpURLConnection connection =
                (HttpURLConnection) new URL(baseUrl + path).openConnection();
        connection.setConnectTimeout(TIMEOUT_MILLIS);
        connection.setReadTimeout(TIMEOUT_MILLIS);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        OutputStream out = connection.getOutputStream();
        try {
            out.write(SimpleJson.toJson(body).getBytes(UTF8));
        } finally {
            out.close();
        }
        String response = readBody(connection);
        Object parsed = SimpleJson.parse(response);
        if (!(parsed instanceof Map)) {
            throw new IOException("Unexpected local runtime response: " + response);
        }
        return (Map<String, Object>) parsed;
    }

    private static String readBody(HttpURLConnection connection) throws IOException {
        InputStream in = connection.getResponseCode() >= 400
                ? connection.getErrorStream() : connection.getInputStream();
        if (in == null) {
            return "{}";
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = in.read(chunk)) >= 0) {
            buffer.write(chunk, 0, read);
        }
        in.close();
        return new String(buffer.toByteArray(), UTF8);
    }
}
