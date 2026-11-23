package com.aresstack.askai.localmodels;

import com.aresstack.askai.localruntime.onnx.OnnxRuntimeOptions;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Properties;

/**
 * Persists {@link LocalRuntimeSettings} in their own UTF-8 properties file next to the central configuration
 * ({@code local-runtime.properties}). Reads are cached by file timestamp; ONNX settings apply on the next runtime
 * start, the sentence-embedding model on the next lookup.
 */
public final class LocalRuntimeSettingsRepository {

    static final String FILE_NAME = "local-runtime.properties";

    static final String PROVIDER = "onnx.executionProvider";
    static final String INTRA_OP = "onnx.intraOpThreads";
    static final String INTER_OP = "onnx.interOpThreads";
    static final String QUANTIZED = "onnx.preferQuantized";
    static final String BATCH = "onnx.batchSize";
    static final String MAX_TOKENS = "onnx.maxTokens";
    static final String SENTENCE_MODEL = "embedding.sentence.model";

    private static LocalRuntimeSettingsRepository shared;

    private final File file;
    private long cachedStamp = Long.MIN_VALUE;
    private LocalRuntimeSettings cached;

    public LocalRuntimeSettingsRepository(File file) {
        if (file == null) {
            throw new IllegalArgumentException("file must not be null");
        }
        this.file = file;
    }

    /** The shared repository in the AskAI configuration directory. */
    public static synchronized LocalRuntimeSettingsRepository inConfigurationDirectory() {
        if (shared == null) {
            String appData = System.getenv("APPDATA");
            File directory = appData != null && appData.trim().length() > 0
                    ? new File(appData, ".askai")
                    : new File(System.getProperty("user.home"), ".askai");
            shared = new LocalRuntimeSettingsRepository(new File(directory, FILE_NAME));
        }
        return shared;
    }

    public File getFile() {
        return file;
    }

    /** The current settings; defaults when the file is absent or unreadable. Never throws. */
    public synchronized LocalRuntimeSettings load() {
        long stamp = file.isFile() ? file.lastModified() ^ file.length() : -1L;
        if (cached != null && stamp == cachedStamp) {
            return cached;
        }
        LocalRuntimeSettings loaded = stamp == -1L ? LocalRuntimeSettings.defaults() : read();
        cached = loaded;
        cachedStamp = stamp;
        return loaded;
    }

    public synchronized void save(LocalRuntimeSettings settings) throws IOException {
        OnnxRuntimeOptions onnx = settings.getOnnx();
        Properties properties = new Properties();
        properties.setProperty(PROVIDER, onnx.executionProvider().token());
        properties.setProperty(INTRA_OP, String.valueOf(onnx.intraOpThreads()));
        properties.setProperty(INTER_OP, String.valueOf(onnx.interOpThreads()));
        properties.setProperty(QUANTIZED, String.valueOf(onnx.preferQuantized()));
        properties.setProperty(BATCH, String.valueOf(onnx.batchSize()));
        properties.setProperty(MAX_TOKENS, String.valueOf(onnx.maxTokens()));
        properties.setProperty(SENTENCE_MODEL, settings.getSentenceEmbeddingModel());
        File directory = file.getAbsoluteFile().getParentFile();
        if (directory != null && !directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("could not create " + directory);
        }
        try (OutputStream out = Files.newOutputStream(file.toPath());
             Writer writer = new OutputStreamWriter(out, StandardCharsets.UTF_8)) {
            properties.store(writer, "AskAI local runtime (ONNX Runtime) and sentence-embedding slot");
        }
        cached = null;
    }

    private LocalRuntimeSettings read() {
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(file.toPath());
             Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            p.load(reader);
        } catch (IOException unreadable) {
            System.err.println("[local-runtime] settings unreadable, using defaults: " + unreadable.getMessage());
            return LocalRuntimeSettings.defaults();
        }
        OnnxRuntimeOptions onnx = OnnxRuntimeOptions.builder()
                .executionProvider(OnnxRuntimeOptions.ExecutionProvider.parse(p.getProperty(PROVIDER)))
                .intraOpThreads(intOr(p.getProperty(INTRA_OP), OnnxRuntimeOptions.DEFAULT_INTRA_OP_THREADS))
                .interOpThreads(intOr(p.getProperty(INTER_OP), OnnxRuntimeOptions.DEFAULT_INTER_OP_THREADS))
                .preferQuantized(boolOr(p.getProperty(QUANTIZED), OnnxRuntimeOptions.DEFAULT_PREFER_QUANTIZED))
                .batchSize(intOr(p.getProperty(BATCH), OnnxRuntimeOptions.DEFAULT_BATCH_SIZE))
                .maxTokens(intOr(p.getProperty(MAX_TOKENS), OnnxRuntimeOptions.DEFAULT_MAX_TOKENS))
                .build();
        return new LocalRuntimeSettings(onnx,
                p.getProperty(SENTENCE_MODEL, LocalRuntimeSettings.DEFAULT_SENTENCE_EMBEDDING_MODEL));
    }

    private static int intOr(String value, int fallback) {
        try {
            return value == null ? fallback : Integer.parseInt(value.trim());
        } catch (NumberFormatException invalid) {
            return fallback;
        }
    }

    private static boolean boolOr(String value, boolean fallback) {
        return value == null || value.trim().isEmpty() ? fallback : Boolean.parseBoolean(value.trim());
    }
}
