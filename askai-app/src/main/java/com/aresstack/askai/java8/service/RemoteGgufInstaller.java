package com.aresstack.askai.java8.service;

import com.aresstack.askai.java8.hf.GgufFile;
import com.aresstack.askai.java8.json.SimpleJson;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Install a local GGUF file into a remote Ollama by uploading it as a blob and calling
 * {@code /api/create}. Reports progress for each phase (hashing, uploading, creating) and can be
 * cancelled: {@link #cancel()} disconnects the live connection so the upload/create aborts.
 */
public final class RemoteGgufInstaller {

    /** Receive a progress update; {@code total <= 0} means the step is indeterminate. */
    public interface ProgressListener {
        void onProgress(String phase, long completed, long total);
    }

    private static final int BUFFER_SIZE = 1024 * 1024;

    private final String baseUrl;
    private volatile boolean cancelled;
    private volatile HttpURLConnection activeConnection;

    public RemoteGgufInstaller(String baseUrl) {
        this.baseUrl = normalizeBaseUrl(baseUrl);
    }

    /** Abort a running install: mark cancelled and drop the live connection. */
    public void cancel() {
        cancelled = true;
        HttpURLConnection connection = activeConnection;
        if (connection != null) {
            connection.disconnect();
        }
    }

    /** The capability tags Ollama understands on {@code /api/create.info.capabilities}. */
    private static final java.util.Set<String> OLLAMA_CAPABILITY_TAGS =
            new java.util.LinkedHashSet<String>(java.util.Arrays.asList(
                    "completion", "vision", "audio", "image", "tools", "thinking", "insert", "embedding"));

    public void install(String modelName, File file, ProgressListener listener) throws Exception {
        install(modelName, file, java.util.Collections.<File>emptyList(),
                com.aresstack.askai.java8.hf.meta.OllamaCreateMetadata.empty(), listener);
    }

    /** Manual GGUF import (no declared capabilities): no {@code info} is sent. */
    public void install(String modelName, File file, java.util.List<File> extraFiles,
                        ProgressListener listener) throws Exception {
        install(modelName, file, extraFiles,
                com.aresstack.askai.java8.hf.meta.OllamaCreateMetadata.empty(), listener);
    }

    /**
     * Hugging Face import declaring capabilities only. The list is normalized (real Ollama tags,
     * de-duplicated, stable order); an empty list omits {@code info} entirely (manual import).
     */
    public void install(String modelName, File file, java.util.List<File> extraFiles,
                        java.util.Collection<String> capabilities, ProgressListener listener) throws Exception {
        install(modelName, file, extraFiles,
                com.aresstack.askai.java8.hf.meta.OllamaCreateMetadata.ofCapabilities(
                        normalizeCapabilities(capabilities)), listener);
    }

    /**
     * Install the model GGUF plus optional companion files (e.g. the *mmproj* audio/vision encoder
     * a multimodal model needs). Every file is validated, hashed and uploaded (skipping blobs the
     * server already has), then referenced together in one {@code /api/create} call.
     *
     * <p>{@code metadata} carries the trusted values AskAI wants Ollama to record: capabilities plus,
     * when known, {@code info} fields (family, quantization, sizes, ...), {@code license} and
     * {@code parameters}. Only sufficiently trusted, non-empty values are sent, so an uncertain guess
     * never overwrites Ollama's own GGUF-derived detection. Empty metadata omits {@code info} entirely.</p>
     */
    public void install(String modelName, File file, java.util.List<File> extraFiles,
                        com.aresstack.askai.java8.hf.meta.OllamaCreateMetadata metadata,
                        ProgressListener listener) throws Exception {
        if (modelName == null || modelName.trim().length() == 0) {
            throw new IllegalArgumentException("Model name is required.");
        }
        if (file == null || !file.isFile()) {
            throw new IllegalArgumentException("GGUF file does not exist.");
        }
        if (metadata == null) {
            metadata = com.aresstack.askai.java8.hf.meta.OllamaCreateMetadata.empty();
        }
        java.util.List<File> allFiles = new java.util.ArrayList<File>();
        allFiles.add(file);
        if (extraFiles != null) {
            for (int i = 0; i < extraFiles.size(); i++) {
                File extra = extraFiles.get(i);
                if (extra == null || !extra.isFile()) {
                    throw new IllegalArgumentException("Companion file does not exist: " + extra);
                }
                allFiles.add(extra);
            }
        }

        Map<String, String> files = new LinkedHashMap<String, String>();
        for (int i = 0; i < allFiles.size(); i++) {
            File current = allFiles.get(i);
            String label = allFiles.size() > 1 ? " (" + current.getName() + ")" : "";
            // Reject a truncated/corrupt GGUF up front, so the failure is clear here instead of
            // surfacing as an opaque HTTP 500 from Ollama's import.
            report(listener, "Validating GGUF" + label, 0, 0);
            GgufFile.validate(current);
            String digest = "sha256:" + sha256(current, listener);
            uploadBlobWithRetry(digest, current, label, listener);
            files.put(current.getName(), digest);
        }

        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("model", modelName.trim());
        body.put("files", files);
        // Only trusted, non-empty values reach the wire; capabilities are never guessed from the name.
        Map<String, Object> info = metadata.toInfoMap();
        if (!info.isEmpty()) {
            body.put("info", info);
        }
        java.util.List<String> licenses = metadata.licenses();
        if (!licenses.isEmpty()) {
            body.put("license", licenses.size() == 1 ? licenses.get(0)
                    : new java.util.ArrayList<String>(licenses));
        }
        if (!metadata.parameters().isEmpty()) {
            body.put("parameters", new LinkedHashMap<String, Object>(metadata.parameters()));
        }
        // Top-level B3 fields — only ever populated with tested/safe values (see OllamaCreateProfile /
        // OllamaCreateMetadata); empty values are omitted so Ollama keeps its own detection.
        putIfNotEmpty(body, "template", metadata.template());
        putIfNotEmpty(body, "renderer", metadata.renderer());
        putIfNotEmpty(body, "parser", metadata.parser());
        putIfNotEmpty(body, "requires", metadata.requires());
        putIfNotEmpty(body, "system", metadata.system());
        if (!metadata.messages().isEmpty()) {
            body.put("messages", toMessageMaps(metadata.messages()));
        }
        body.put("stream", Boolean.TRUE);
        createModel("/api/create", SimpleJson.toJson(body), listener);
    }

    /**
     * Attaches a multimodal encoder (projector) to an <em>already-installed</em> model, without creating a
     * new one. The existing model stays the base via {@code from}, and the uploaded projector blob is
     * referenced under {@code adapters}:
     * {@code {"model":"m","from":"m","adapters":{"mmproj.gguf":"sha256:..."}}}.
     *
     * <p>No {@code info}/{@code capabilities} are sent — Ollama re-derives them from the attached encoder,
     * and the caller reloads {@code /api/show} afterwards (consistent with the stateless model: AskAI keeps
     * no per-model state). The file must prove itself a projector from its GGUF content before anything is
     * uploaded, so a plain model can never be attached as an encoder.</p>
     */
    public void attachAdapter(String existingModel, File projector, ProgressListener listener) throws Exception {
        if (existingModel == null || existingModel.trim().length() == 0) {
            throw new IllegalArgumentException("Existing model name is required.");
        }
        if (projector == null || !projector.isFile()) {
            throw new IllegalArgumentException("Projector GGUF file does not exist.");
        }
        report(listener, "Validating encoder (" + projector.getName() + ")", 0, 0);
        GgufFile.validate(projector);
        if (!GgufFile.inspect(projector).isProjector()) {
            throw new IllegalArgumentException(
                    "The chosen file is not a multimodal encoder (projector) GGUF, so it cannot be attached.");
        }
        String digest = "sha256:" + sha256(projector, listener);
        uploadBlobWithRetry(digest, projector, " (" + projector.getName() + ")", listener);

        Map<String, Object> adapters = new LinkedHashMap<String, Object>();
        adapters.put(projector.getName(), digest);
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("model", existingModel.trim());
        body.put("from", existingModel.trim());
        body.put("adapters", adapters);
        body.put("stream", Boolean.TRUE);
        createModel("/api/create", SimpleJson.toJson(body), listener);
    }

    private static void putIfNotEmpty(Map<String, Object> body, String key, String value) {
        if (value != null && value.trim().length() > 0) {
            body.put(key, value);
        }
    }

    private static java.util.List<Object> toMessageMaps(
            java.util.List<com.aresstack.askai.java8.hf.meta.OllamaCreateMessage> messages) {
        java.util.List<Object> result = new java.util.ArrayList<Object>();
        for (com.aresstack.askai.java8.hf.meta.OllamaCreateMessage message : messages) {
            Map<String, Object> map = new LinkedHashMap<String, Object>();
            map.put("role", message.getRole());
            map.put("content", message.getContent());
            result.add(map);
        }
        return result;
    }

    /**
     * @return the given capabilities reduced to real Ollama tags only ({@code completion}, {@code
     *         vision}, {@code audio}, {@code tools}, {@code thinking}, {@code embedding}),
     *         lower-cased, de-duplicated and in first-seen order. Enum names / unknown values are
     *         dropped, so no {@code "TEXT"}/{@code "AUDIO"} ever reaches Ollama.
     */
    public static java.util.List<String> normalizeCapabilities(java.util.Collection<String> capabilities) {
        java.util.List<String> result = new java.util.ArrayList<String>();
        if (capabilities == null) {
            return result;
        }
        for (String capability : capabilities) {
            if (capability == null) {
                continue;
            }
            String tag = capability.trim().toLowerCase(java.util.Locale.ROOT);
            if (OLLAMA_CAPABILITY_TAGS.contains(tag) && !result.contains(tag)) {
                result.add(tag);
            }
        }
        return result;
    }

    /** How often a blob upload is attempted before giving up (network drops mid-upload happen). */
    private static final int MAX_UPLOAD_ATTEMPTS = 3;

    /**
     * Upload the blob unless Ollama already has it, retrying on connection failures. The blob API
     * is atomic (no partial resume), but a retry re-checks via HEAD first — when only the response
     * was lost, the blob may in fact have arrived completely and the re-upload can be skipped.
     */
    private void uploadBlobWithRetry(String digest, File file, String label, ProgressListener listener)
            throws IOException {
        IOException last = null;
        for (int attempt = 1; attempt <= MAX_UPLOAD_ATTEMPTS; attempt++) {
            checkCancelled();
            try {
                if (blobExists(digest)) {
                    report(listener, "Blob already on server" + label, file.length(), file.length());
                    return;
                }
                if (attempt > 1) {
                    report(listener, "Upload attempt " + attempt + "/" + MAX_UPLOAD_ATTEMPTS
                            + label + " (restarting from 0%)", 0, 0);
                }
                uploadBlob("/api/blobs/" + digest, file, listener);
                return;
            } catch (InterruptedIOException ex) {
                throw ex; // user cancellation: stop immediately
            } catch (IOException ex) {
                last = ex;
                report(listener, "Upload failed" + label + ": " + ex.getMessage(), 0, 0);
            }
        }
        throw new IOException("Uploading to Ollama failed after " + MAX_UPLOAD_ATTEMPTS + " attempts: "
                + (last == null ? "unknown error" : last.getMessage())
                + " — the connection to " + baseUrl + " dropped mid-upload (unstable network or an"
                + " Ollama restart). The blob API cannot resume partial uploads, so each attempt"
                + " restarts from 0%.", last);
    }

    /** HEAD the blob: return true when Ollama already has it (200), false on 404. */
    private boolean blobExists(String digest) throws IOException {
        HttpURLConnection connection = open("/api/blobs/" + digest, "HEAD", null);
        try {
            int code = connection.getResponseCode();
            return code >= 200 && code < 300;
        } finally {
            activeConnection = null;
            connection.disconnect();
        }
    }

    private void uploadBlob(String path, File file, ProgressListener listener) throws IOException {
        long total = file.length();
        HttpURLConnection connection = open(path, "POST", "application/octet-stream");
        FileInputStream inputStream = null;
        OutputStream outputStream = null;
        try {
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(total);
            outputStream = connection.getOutputStream();
            inputStream = new FileInputStream(file);
            byte[] buffer = new byte[BUFFER_SIZE];
            long uploaded = 0;
            int read;
            while ((read = inputStream.read(buffer)) >= 0) {
                checkCancelled();
                outputStream.write(buffer, 0, read);
                uploaded += read;
                report(listener, "Uploading", uploaded, total);
            }
            outputStream.close();
            outputStream = null;
            requireSuccess(connection);
        } catch (IOException ex) {
            throw cancelAware(ex);
        } finally {
            activeConnection = null;
            closeQuietly(inputStream);
            closeQuietly(outputStream);
            connection.disconnect();
        }
    }

    /** POST /api/create with {@code stream:true} and report each status line. */
    private void createModel(String path, String json, ProgressListener listener) throws IOException {
        HttpURLConnection connection = open(path, "POST", "application/json; charset=UTF-8");
        OutputStream outputStream = null;
        BufferedReader reader = null;
        try {
            connection.setDoOutput(true);
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            outputStream = connection.getOutputStream();
            outputStream.write(bytes);
            outputStream.close();
            outputStream = null;

            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IOException("Remote Ollama returned HTTP " + code + ": "
                        + readBody(connection.getErrorStream()));
            }
            report(listener, "Creating model", 0, 0);
            reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8));
            String line;
            boolean succeeded = false;
            while ((line = reader.readLine()) != null) {
                checkCancelled();
                Map parsed = parseJsonObject(line);
                if (parsed == null) {
                    continue;
                }
                // A streamed {"error":...} object is a failure, not just a progress line: Ollama can
                // report e.g. an unsupported architecture mid-stream and then simply end the stream.
                Object error = parsed.get("error");
                if (error != null) {
                    throw new IOException("Ollama model creation failed: " + error);
                }
                Object status = parsed.get("status");
                if (status != null) {
                    String text = String.valueOf(status);
                    report(listener, text, 0, 0);
                    if ("success".equalsIgnoreCase(text)) {
                        succeeded = true;
                    }
                }
            }
            // A stream that ends without an explicit success (and without an error above) is not a
            // completed install — do not fall through to a silent "done".
            if (!succeeded) {
                throw new IOException("Create stream ended without success confirmation.");
            }
        } catch (IOException ex) {
            throw cancelAware(ex);
        } finally {
            activeConnection = null;
            closeQuietly(reader);
            closeQuietly(outputStream);
            connection.disconnect();
        }
    }

    private String sha256(File file, ProgressListener listener) throws Exception {
        long total = file.length();
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        FileInputStream inputStream = new FileInputStream(file);
        try {
            byte[] buffer = new byte[BUFFER_SIZE];
            long hashed = 0;
            int read;
            while ((read = inputStream.read(buffer)) >= 0) {
                checkCancelled();
                digest.update(buffer, 0, read);
                hashed += read;
                report(listener, "Hashing", hashed, total);
            }
        } finally {
            closeQuietly(inputStream);
        }
        byte[] bytes = digest.digest();
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (int i = 0; i < bytes.length; i++) {
            String hex = Integer.toHexString(bytes[i] & 0xff);
            if (hex.length() == 1) {
                builder.append('0');
            }
            builder.append(hex);
        }
        return builder.toString();
    }

    private HttpURLConnection open(String path, String method, String contentType) throws IOException {
        checkCancelled();
        HttpURLConnection connection = (HttpURLConnection) new URL(baseUrl + path).openConnection();
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(3600000);
        connection.setRequestMethod(method);
        connection.setRequestProperty("Accept", "application/json");
        if (contentType != null) {
            connection.setRequestProperty("Content-Type", contentType);
        }
        connection.setRequestProperty("User-Agent", "askai-java8");
        activeConnection = connection;
        return connection;
    }

    private void requireSuccess(HttpURLConnection connection) throws IOException {
        int code = connection.getResponseCode();
        if (code >= 200 && code < 300) {
            return;
        }
        throw new IOException("Remote Ollama returned HTTP " + code + ": " + readBody(connection.getErrorStream()));
    }

    /** Parse one create/pull NDJSON line into a JSON object, or {@code null} when it is not one. */
    private static Map parseJsonObject(String line) {
        if (line == null || line.trim().length() == 0) {
            return null;
        }
        try {
            Object parsed = SimpleJson.parse(line);
            if (parsed instanceof Map) {
                return (Map) parsed;
            }
        } catch (RuntimeException ignored) {
            // Not JSON: ignore this line.
        }
        return null;
    }

    private void checkCancelled() throws InterruptedIOException {
        if (cancelled) {
            throw new InterruptedIOException("Installation cancelled.");
        }
    }

    /** Turn an IOException caused by a cancel-disconnect into a clear cancellation error. */
    private IOException cancelAware(IOException ex) {
        if (cancelled) {
            return new InterruptedIOException("Installation cancelled.");
        }
        return ex;
    }

    private void report(ProgressListener listener, String phase, long completed, long total) {
        if (listener != null) {
            listener.onProgress(phase, completed, total);
        }
    }

    private String readBody(InputStream inputStream) throws IOException {
        if (inputStream == null) {
            return "";
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] bytes = new byte[8192];
        int read;
        try {
            while ((read = inputStream.read(bytes)) >= 0) {
                buffer.write(bytes, 0, read);
            }
        } finally {
            inputStream.close();
        }
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }

    private void closeQuietly(java.io.Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
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
