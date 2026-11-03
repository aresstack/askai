package com.aresstack.askai.tts;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Installs the Piper speech-output engine and curated voices, fail-closed and self-verifying:
 * the ENGINE zip is pinned by URL + SHA-256 (a fixed release), and each VOICE model's SHA-256 is
 * taken from the HuggingFace tree API's LFS metadata at install time and verified against the
 * downloaded bytes — a mismatch aborts before anything reaches its final directory. Both installs
 * download into a staging directory and only MOVE into place when complete, so a crashed download
 * never counts as installed (see {@link PiperTtsStore}'s both-files rule).
 */
public final class PiperInstaller {

    /** The pinned Piper Windows release (CPU engine; bundles espeak-ng data + onnxruntime DLLs). */
    static final String ENGINE_ZIP_URL =
            "https://github.com/rhasspy/piper/releases/download/2023.11.14-2/piper_windows_amd64.zip";
    static final String ENGINE_ZIP_SHA256 =
            "f3c58906402b24f3a96d92145f58acba6d86c9b5db896d207f78dc80811efcea";
    static final long ENGINE_ZIP_SIZE_BYTES = 22477236L;

    /** Progress sink; called from the install thread (never assume the EDT). */
    public interface Progress {
        void onProgress(String stage, long bytesDone, long bytesTotal);
    }

    /** Opens the download connections; lets the caller route them through AskAI's network settings. */
    public interface Connector {
        HttpURLConnection open(String url) throws IOException;
    }

    private final PiperTtsStore store;
    private final TtsSettingsStore settings;
    private final Connector connector;

    public PiperInstaller(PiperTtsStore store, TtsSettingsStore settings) {
        this(store, settings, url -> (HttpURLConnection) new URL(url).openConnection());
    }

    /** @param connector opens each download, e.g. with the configured proxy and certificate trust */
    public PiperInstaller(PiperTtsStore store, TtsSettingsStore settings, Connector connector) {
        if (connector == null) {
            throw new IllegalArgumentException("connector must not be null");
        }
        this.store = store;
        this.settings = settings;
        this.connector = connector;
    }

    /**
     * Install engine (if missing) and voice; idempotent — an installed part is skipped.
     * Blocking; run OFF the EDT.
     */
    public void install(PiperVoice voice, Progress progress) throws IOException {
        if (!store.isEngineInstalled()) {
            installEngine(progress);
        }
        if (!store.isVoiceInstalled(voice)) {
            installVoice(voice, progress);
        }
    }

    // ------------------------------------------------------------------ engine

    void installEngine(Progress progress) throws IOException {
        Path staging = stagingDirectory("engine");
        try {
            Path zip = staging.resolve("piper.zip");
            download(ENGINE_ZIP_URL, zip, "Downloading engine", ENGINE_ZIP_SIZE_BYTES, progress);
            String actual = sha256(zip);
            if (!ENGINE_ZIP_SHA256.equals(actual)) {
                throw new IOException("engine download corrupt: sha256 " + actual
                        + " does not match the pinned release");
            }
            progress.onProgress("Unpacking engine", 0, -1);
            Path extracted = staging.resolve("extracted");
            extractZip(zip, extracted);
            // The release zip wraps everything in a single top-level "piper/" directory.
            Path engineSource = Files.isRegularFile(extracted.resolve("piper.exe"))
                    ? extracted : extracted.resolve("piper");
            if (!Files.isRegularFile(engineSource.resolve("piper.exe"))) {
                throw new IOException("engine zip layout unexpected: piper.exe not found");
            }
            Files.createDirectories(store.engineDirectory().getParent());
            deleteRecursively(store.engineDirectory()); // replace a broken partial engine
            Files.move(engineSource, store.engineDirectory());
        } finally {
            deleteRecursively(staging);
        }
    }

    /** Extract with a zip-slip guard: every entry must stay under {@code target}. */
    static void extractZip(Path zip, Path target) throws IOException {
        Path root = target.toAbsolutePath().normalize();
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                Path resolved = root.resolve(entry.getName()).normalize();
                if (!resolved.startsWith(root)) {
                    throw new IOException("zip entry escapes target directory: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(resolved);
                } else {
                    Files.createDirectories(resolved.getParent());
                    Files.copy(in, resolved, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    // ------------------------------------------------------------------ voice

    void installVoice(PiperVoice voice, Progress progress) throws IOException {
        VoiceFileMetadata metadata = fetchVoiceMetadata(voice);
        Path staging = stagingDirectory(voice.getId());
        try {
            Path onnx = staging.resolve(voice.onnxFileName());
            Path config = staging.resolve(voice.configFileName());
            download(resolveUrl(voice, voice.configFileName()), config,
                    "Downloading voice config", -1, progress);
            download(resolveUrl(voice, voice.onnxFileName()), onnx,
                    "Downloading voice", metadata.sizeBytes, progress);
            String actual = sha256(onnx);
            if (!metadata.sha256.equals(actual)) {
                throw new IOException("voice download corrupt: sha256 " + actual
                        + " does not match HuggingFace's published hash");
            }
            Files.createDirectories(store.voiceDirectory(voice).getParent());
            deleteRecursively(store.voiceDirectory(voice));
            Files.move(staging, store.voiceDirectory(voice));
        } finally {
            deleteRecursively(staging);
        }
    }

    private static String resolveUrl(PiperVoice voice, String fileName) {
        return "https://huggingface.co/" + PiperVoiceCatalog.VOICES_REPOSITORY
                + "/resolve/main/" + voice.getHfPath() + "/" + fileName;
    }

    /** The HF tree API's LFS entry for the voice model: published SHA-256 + exact size. */
    private VoiceFileMetadata fetchVoiceMetadata(PiperVoice voice) throws IOException {
        String url = "https://huggingface.co/api/models/" + PiperVoiceCatalog.VOICES_REPOSITORY
                + "/tree/main/" + voice.getHfPath();
        String json = readText(url);
        // One JSON object per file; the model's object carries "lfs":{"oid":"<sha256>","size":N}.
        for (String chunk : json.split("\\{\"type\"")) {
            if (!chunk.contains("\"" + voice.getHfPath() + "/" + voice.onnxFileName() + "\"")) {
                continue;
            }
            Matcher hash = Pattern.compile(
                    "\"lfs\"\\s*:\\s*\\{\\s*\"oid\"\\s*:\\s*\"([0-9a-f]{64})\"\\s*,\\s*\"size\"\\s*:\\s*(\\d+)")
                    .matcher(chunk);
            if (hash.find()) {
                return new VoiceFileMetadata(hash.group(1), Long.parseLong(hash.group(2)));
            }
        }
        throw new IOException("HuggingFace metadata for " + voice.getId()
                + " has no LFS hash — refusing an unverifiable download");
    }

    private static final class VoiceFileMetadata {
        final String sha256;
        final long sizeBytes;

        VoiceFileMetadata(String sha256, long sizeBytes) {
            this.sha256 = sha256;
            this.sizeBytes = sizeBytes;
        }
    }

    // ------------------------------------------------------------------ plumbing

    private void download(String url, Path target, String stage, long expectedBytes,
                          Progress progress) throws IOException {
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
        int timeoutMillis = settings.load().getNetworkTimeoutSeconds() * 1000;
        HttpURLConnection connection = connector.open(url);
        connection.setConnectTimeout(timeoutMillis);
        connection.setReadTimeout(timeoutMillis);
        connection.setInstanceFollowRedirects(true);
        int status = connection.getResponseCode();
        if (status < 200 || status >= 300) {
            connection.disconnect();
            throw new IOException("HTTP " + status + " for " + url);
        }
        return connection;
    }

    static String sha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IOException("SHA-256 unavailable", impossible);
        }
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest()) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }

    private Path stagingDirectory(String name) throws IOException {
        Path staging = store.engineDirectory().getParent()
                .resolve(".staging-" + name + "-" + System.nanoTime());
        Files.createDirectories(staging);
        return staging;
    }

    private static void deleteRecursively(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try {
            if (Files.isDirectory(path)) {
                try (DirectoryStream<Path> children = Files.newDirectoryStream(path)) {
                    for (Path child : children) {
                        deleteRecursively(child);
                    }
                }
            }
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // best-effort cleanup; a leftover .staging dir is invisible to the store
        }
    }
}
