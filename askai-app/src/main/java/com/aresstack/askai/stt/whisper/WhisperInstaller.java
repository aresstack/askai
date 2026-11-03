package com.aresstack.askai.stt.whisper;

import com.aresstack.askai.tts.PiperInstaller;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Installs the whisper.cpp dictation engine and curated models, fail-closed and self-verifying like the Piper
 * installer: the ENGINE zip is pinned by URL + SHA-256 (a fixed release), each MODEL's SHA-256 comes from the
 * HuggingFace tree API's LFS metadata and is checked against the downloaded bytes. Everything downloads into a
 * staging directory and only moves into place when complete. All connections go through {@link Connector} —
 * productively {@code HostNetwork::open}, i.e. AskAI's proxy and certificate settings.
 */
public final class WhisperInstaller {

    /** The pinned whisper.cpp Windows CPU build (whisper-cli.exe + ggml/whisper DLLs under "Release/"). */
    static final String ENGINE_ZIP_URL =
            "https://github.com/ggml-org/whisper.cpp/releases/download/v1.9.2/whisper-bin-x64.zip";
    static final String ENGINE_ZIP_SHA256 =
            "49dcc16de826f20bd53d44f947a1ae49dfa81f86cad67a64d80820cb192d674a";
    static final long ENGINE_ZIP_SIZE_BYTES = 8194445L;

    /** Per network operation (connect / read), not per file. */
    private static final int NETWORK_TIMEOUT_MILLIS = 60_000;

    /** Opens the download connections; productively AskAI's network settings. */
    public interface Connector {
        HttpURLConnection open(String url) throws IOException;
    }

    private final WhisperStore store;
    private final Connector connector;

    /** Productive wiring: downloads follow AskAI's network settings (proxy, PAC, Windows certificates). */
    public WhisperInstaller(WhisperStore store) {
        this(store, com.aresstack.askai.plugin.api.service.HostNetwork::open);
    }

    public WhisperInstaller(WhisperStore store, Connector connector) {
        if (store == null || connector == null) {
            throw new IllegalArgumentException("store and connector must not be null");
        }
        this.store = store;
        this.connector = connector;
    }

    /** Install engine (if missing) and model (if missing). Idempotent; blocking — run OFF the EDT. */
    public void install(WhisperModel model, PiperInstaller.Progress progress) throws IOException {
        if (!store.isEngineInstalled()) {
            installEngine(progress);
        }
        if (!store.isModelInstalled(model)) {
            installModel(model, progress);
        }
    }

    /**
     * Installs the engine if missing and downloads the model again even when it is installed; the old file is
     * replaced only after the new download passed its hash check. Blocking — run OFF the EDT.
     */
    public void reinstall(WhisperModel model, PiperInstaller.Progress progress) throws IOException {
        if (!store.isEngineInstalled()) {
            installEngine(progress);
        }
        installModel(model, progress);
    }

    void installEngine(PiperInstaller.Progress progress) throws IOException {
        Path staging = stagingDirectory("engine");
        try {
            Path zip = staging.resolve("whisper.zip");
            download(ENGINE_ZIP_URL, zip, "Downloading engine", ENGINE_ZIP_SIZE_BYTES, progress);
            String actual = PiperInstaller.sha256(zip);
            if (!ENGINE_ZIP_SHA256.equals(actual)) {
                throw new IOException("engine download corrupt: sha256 " + actual
                        + " does not match the pinned release");
            }
            progress.onProgress("Unpacking engine", 0, -1);
            Path extracted = staging.resolve("extracted");
            PiperInstaller.extractZip(zip, extracted);
            Path engineSource = Files.isRegularFile(extracted.resolve("whisper-cli.exe"))
                    ? extracted : extracted.resolve("Release");
            if (!Files.isRegularFile(engineSource.resolve("whisper-cli.exe"))) {
                throw new IOException("engine zip layout unexpected: whisper-cli.exe not found");
            }
            Files.createDirectories(store.engineDirectory().getParent());
            WhisperStore.deleteRecursively(store.engineDirectory()); // replace a broken partial engine
            Files.move(engineSource, store.engineDirectory());
        } finally {
            WhisperStore.deleteRecursively(staging);
        }
    }

    void installModel(WhisperModel model, PiperInstaller.Progress progress) throws IOException {
        FileMetadata metadata = fetchMetadata(model);
        Path staging = stagingDirectory(model.getId());
        try {
            Path file = staging.resolve(model.fileName());
            download("https://huggingface.co/" + WhisperModelCatalog.REPOSITORY + "/resolve/main/"
                    + model.fileName(), file, "Downloading model", metadata.sizeBytes, progress);
            progress.onProgress("Verifying model", 0, -1);
            String actual = PiperInstaller.sha256(file);
            if (!metadata.sha256.equals(actual)) {
                throw new IOException("model download corrupt: sha256 " + actual
                        + " does not match HuggingFace's published hash");
            }
            Path target = store.modelFile(model);
            Files.createDirectories(target.getParent());
            Files.deleteIfExists(target);
            Files.move(file, target);
        } finally {
            WhisperStore.deleteRecursively(staging);
        }
    }

    /** The HF tree API's LFS entry for the model file: published SHA-256 + exact size. */
    private FileMetadata fetchMetadata(WhisperModel model) throws IOException {
        String json = readText("https://huggingface.co/api/models/" + WhisperModelCatalog.REPOSITORY + "/tree/main");
        FileMetadata metadata = parseMetadata(json, model.fileName());
        if (metadata == null) {
            throw new IOException("HuggingFace metadata for " + model.fileName()
                    + " has no LFS hash — refusing an unverifiable download");
        }
        return metadata;
    }

    /** Visible for tests: finds {@code "lfs":{"oid":"<sha256>","size":N}} of {@code fileName} in the tree JSON. */
    static FileMetadata parseMetadata(String json, String fileName) {
        for (String chunk : json.split("\\{\"type\"")) {
            if (!chunk.contains("\"path\":\"" + fileName + "\"")) {
                continue;
            }
            Matcher hash = Pattern.compile(
                    "\"lfs\"\\s*:\\s*\\{\\s*\"oid\"\\s*:\\s*\"([0-9a-f]{64})\"\\s*,\\s*\"size\"\\s*:\\s*(\\d+)")
                    .matcher(chunk);
            if (hash.find()) {
                return new FileMetadata(hash.group(1), Long.parseLong(hash.group(2)));
            }
        }
        return null;
    }

    static final class FileMetadata {
        final String sha256;
        final long sizeBytes;

        FileMetadata(String sha256, long sizeBytes) {
            this.sha256 = sha256;
            this.sizeBytes = sizeBytes;
        }
    }

    private void download(String url, Path target, String stage, long expectedBytes,
                          PiperInstaller.Progress progress) throws IOException {
        HttpURLConnection connection = open(url);
        long total = expectedBytes > 0 ? expectedBytes : connection.getContentLengthLong();
        try (InputStream in = connection.getInputStream();
             OutputStream out = Files.newOutputStream(target)) {
            byte[] buffer = new byte[64 * 1024];
            long done = 0;
            int read;
            while ((read = in.read(buffer)) >= 0) {
                out.write(buffer, 0, read);
                done += read;
                progress.onProgress(stage, done, total);
            }
        } finally {
            connection.disconnect();
        }
    }

    private String readText(String url) throws IOException {
        HttpURLConnection connection = open(url);
        try (InputStream in = connection.getInputStream()) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8 * 1024];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                out.write(buffer, 0, read);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            connection.disconnect();
        }
    }

    private HttpURLConnection open(String url) throws IOException {
        HttpURLConnection connection = connector.open(url);
        connection.setConnectTimeout(NETWORK_TIMEOUT_MILLIS);
        connection.setReadTimeout(NETWORK_TIMEOUT_MILLIS);
        connection.setInstanceFollowRedirects(true);
        int status = connection.getResponseCode();
        if (status < 200 || status >= 300) {
            connection.disconnect();
            throw new IOException("HTTP " + status + " for " + url);
        }
        return connection;
    }

    private Path stagingDirectory(String name) throws IOException {
        Path staging = store.root().resolve(".staging-" + name + "-" + System.nanoTime());
        Files.createDirectories(staging);
        return staging;
    }
}
