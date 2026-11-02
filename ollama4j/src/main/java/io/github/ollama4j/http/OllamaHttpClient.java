package io.github.ollama4j.http;

import io.github.ollama4j.exceptions.OllamaException;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public final class OllamaHttpClient {

    private final String baseUrl;
    private int connectTimeoutMillis = 10000;
    private int readTimeoutMillis = 3600000;

    public OllamaHttpClient(String baseUrl) {
        this.baseUrl = normalizeBaseUrl(baseUrl);
    }

    public void setRequestTimeoutSeconds(long seconds) {
        long millis = Math.max(1L, seconds) * 1000L;
        this.readTimeoutMillis = millis > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) millis;
    }

    public OllamaHttpResponse get(String path) throws OllamaException {
        return request("GET", path, null);
    }

    public OllamaHttpResponse post(String path, String body) throws OllamaException {
        return request("POST", path, body);
    }

    public OllamaHttpResponse postBinary(String path, File file) throws OllamaException {
        HttpURLConnection connection = null;
        FileInputStream inputStream = null;
        OutputStream outputStream = null;
        try {
            connection = openConnection(path, "POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/octet-stream");
            connection.setRequestProperty("Content-Length", String.valueOf(file.length()));
            inputStream = new FileInputStream(file);
            outputStream = connection.getOutputStream();
            byte[] buffer = new byte[1024 * 1024];
            int read;
            while ((read = inputStream.read(buffer)) >= 0) {
                outputStream.write(buffer, 0, read);
            }
            outputStream.close();
            outputStream = null;
            int statusCode = connection.getResponseCode();
            String responseBody = readBody(statusCode >= 200 && statusCode < 300
                    ? connection.getInputStream()
                    : connection.getErrorStream());
            return new OllamaHttpResponse(statusCode, responseBody);
        } catch (IOException ex) {
            throw new OllamaException("Binary HTTP request failed: " + ex.getMessage(), ex);
        } finally {
            closeQuietly(inputStream);
            closeQuietly(outputStream);
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    public OllamaHttpResponse delete(String path, String body) throws OllamaException {
        return request("DELETE", path, body);
    }

    public void postLines(String path, String body, OllamaLineListener listener) throws OllamaException {
        HttpURLConnection connection = null;
        try {
            connection = openConnection(path, "POST");
            writeBody(connection, body);
            int statusCode = connection.getResponseCode();
            InputStream stream = statusCode >= 200 && statusCode < 300
                    ? connection.getInputStream()
                    : connection.getErrorStream();
            if (stream == null) {
                if (statusCode >= 200 && statusCode < 300) {
                    return;
                }
                throw new OllamaException("HTTP " + statusCode + " returned no response body.");
            }
            readLines(stream, listener);
            if (statusCode < 200 || statusCode >= 300) {
                throw new OllamaException("HTTP " + statusCode + " while reading streaming response.");
            }
        } catch (OllamaException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new OllamaException("HTTP streaming request failed: " + ex.getMessage(), ex);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private OllamaHttpResponse request(String method, String path, String body) throws OllamaException {
        HttpURLConnection connection = null;
        try {
            connection = openConnection(path, method);
            if (body != null) {
                writeBody(connection, body);
            }
            int statusCode = connection.getResponseCode();
            String responseBody = readBody(statusCode >= 200 && statusCode < 300
                    ? connection.getInputStream()
                    : connection.getErrorStream());
            return new OllamaHttpResponse(statusCode, responseBody);
        } catch (IOException ex) {
            throw new OllamaException("HTTP request failed: " + ex.getMessage(), ex);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private HttpURLConnection openConnection(String path, String method) throws IOException {
        URL url = new URL(baseUrl + path);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(connectTimeoutMillis);
        connection.setReadTimeout(readTimeoutMillis);
        connection.setRequestMethod(method);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        connection.setRequestProperty("User-Agent", "ollama4j");
        return connection;
    }

    private void writeBody(HttpURLConnection connection, String body) throws IOException {
        connection.setDoOutput(true);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        connection.setRequestProperty("Content-Length", String.valueOf(bytes.length));
        OutputStream outputStream = connection.getOutputStream();
        try {
            outputStream.write(bytes);
        } finally {
            outputStream.close();
        }
    }

    private String readBody(InputStream stream) throws IOException {
        if (stream == null) {
            return "";
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] bytes = new byte[8192];
        int read;
        try {
            while ((read = stream.read(bytes)) >= 0) {
                buffer.write(bytes, 0, read);
            }
        } finally {
            stream.close();
        }
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }

    private void readLines(InputStream stream, OllamaLineListener listener) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmedLine = line.trim();
                if (trimmedLine.length() > 0) {
                    listener.onLine(trimmedLine);
                }
            }
        } finally {
            reader.close();
        }
    }

    private void closeQuietly(InputStream inputStream) {
        if (inputStream == null) {
            return;
        }
        try {
            inputStream.close();
        } catch (IOException ignored) {
        }
    }

    private void closeQuietly(OutputStream outputStream) {
        if (outputStream == null) {
            return;
        }
        try {
            outputStream.close();
        } catch (IOException ignored) {
        }
    }

    private String normalizeBaseUrl(String value) {
        String normalized = value == null || value.trim().length() == 0
                ? "http://127.0.0.1:11434"
                : value.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}
