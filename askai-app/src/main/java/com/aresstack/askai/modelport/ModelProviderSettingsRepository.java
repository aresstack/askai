package com.aresstack.askai.modelport;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

/**
 * Persists {@link ModelProviderSettings} in their own properties file next to the model-port settings
 * ({@code model-provider.properties}); the API key is stored encrypted ({@link ApiKeyVault}, key file
 * {@code model-provider.key}). Reads are cached by file timestamp, so the port asks on every call and a save in the
 * settings panel applies to the next call. The API key is read only when a request needs it and never logged.
 */
public final class ModelProviderSettingsRepository {

    static final String FILE_NAME = "model-provider.properties";
    static final String KEY_FILE_NAME = "model-provider.key";

    private static final String PROVIDER = "provider";
    private static final String API_BASE_URL = "openai.baseUrl";
    private static final String API_KEY = "openai.apiKey";
    private static final String API_CAPACITY = "openai.capacity.default";
    private static final String API_SLOT = "openai.slot.";
    private static final String API_SLOT_MODEL_SUFFIX = ".model";
    private static final String API_CONTEXT = "openai.contextTokens.default";
    private static final String API_MODEL = "openai.model.";
    private static final String API_MODEL_CONTEXT_SUFFIX = ".contextTokens";
    private static final String CONNECT_TIMEOUT = "openai.connectTimeoutSeconds";
    private static final String READ_TIMEOUT = "openai.readTimeoutSeconds";
    private static final String RETRIES = "openai.retries";
    private static final String KEEPASS_ENTRY = "openai.keePassEntry";

    private static ModelProviderSettingsRepository shared;

    private final File file;
    private final ApiKeyVault vault;
    private final java.util.function.Function<String, String> environment;
    private long cachedStamp = Long.MIN_VALUE;
    private ModelProviderSettings cached;

    public ModelProviderSettingsRepository(File file) {
        this(file, new ApiKeyVault(new File(file.getAbsoluteFile().getParentFile(), KEY_FILE_NAME)),
                System::getenv);
    }

    /** @param environment reads an environment variable (a seam for tests) */
    ModelProviderSettingsRepository(File file, ApiKeyVault vault,
                                    java.util.function.Function<String, String> environment) {
        if (file == null || vault == null) {
            throw new IllegalArgumentException("file and vault must not be null");
        }
        this.file = file;
        this.vault = vault;
        this.environment = environment == null ? name -> null : environment;
    }

    /** The shared repository in the AskAI configuration directory, next to {@code model-port.properties}. */
    public static synchronized ModelProviderSettingsRepository inConfigurationDirectory() {
        if (shared == null) {
            String appData = System.getenv("APPDATA");
            File directory = appData != null && appData.trim().length() > 0
                    ? new File(appData, ".askai")
                    : new File(System.getProperty("user.home"), ".askai");
            shared = new ModelProviderSettingsRepository(new File(directory, FILE_NAME));
        }
        return shared;
    }

    public File getFile() {
        return file;
    }

    /** The current settings; defaults (Ollama) when the file is absent or unreadable. Never throws. */
    public synchronized ModelProviderSettings load() {
        long stamp = file.isFile() ? file.lastModified() ^ file.length() : -1L;
        if (cached != null && stamp == cachedStamp) {
            return cached;
        }
        ModelProviderSettings loaded = stamp == -1L ? ModelProviderSettings.defaults() : read();
        cached = loaded;
        cachedStamp = stamp;
        return loaded;
    }

    public synchronized void save(ModelProviderSettings settings) throws IOException {
        Properties properties = new Properties();
        properties.setProperty(PROVIDER, settings.getProvider().key());
        properties.setProperty(API_BASE_URL, settings.getApiBaseUrl());
        if (settings.hasStoredApiKey()) {
            properties.setProperty(API_KEY, settings.getEncryptedApiKey());
        }
        properties.setProperty(API_CAPACITY, String.valueOf(settings.getApiCapacity()));
        for (Map.Entry<String, String> entry : settings.getApiSlotModels().entrySet()) {
            properties.setProperty(API_SLOT + entry.getKey() + API_SLOT_MODEL_SUFFIX, entry.getValue());
        }
        properties.setProperty(API_CONTEXT, String.valueOf(settings.getApiContextTokens()));
        for (Map.Entry<String, Integer> entry : settings.getApiModelContextTokens().entrySet()) {
            properties.setProperty(API_MODEL + entry.getKey() + API_MODEL_CONTEXT_SUFFIX,
                    String.valueOf(entry.getValue()));
        }
        properties.setProperty(CONNECT_TIMEOUT, String.valueOf(settings.getConnectTimeoutSeconds()));
        properties.setProperty(READ_TIMEOUT, String.valueOf(settings.getReadTimeoutSeconds()));
        properties.setProperty(RETRIES, String.valueOf(settings.getRetries()));
        if (settings.usesKeePass()) {
            properties.setProperty(KEEPASS_ENTRY, settings.getKeePassEntry());
        }
        File directory = file.getAbsoluteFile().getParentFile();
        if (directory != null && !directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("could not create " + directory);
        }
        Writer out = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8);
        try {
            properties.store(out, "AskAI model provider: Ollama or an OpenAI-compatible API (the API key is "
                    + "encrypted)");
        } finally {
            out.close();
        }
        cached = null;
    }

    /** Reads the API key of a KeePass entry (its password); the caller clears the array. */
    public interface KeePassKeys {
        /** @throws IOException when KeePass cannot give the key (the message names no part of it) */
        char[] apiKey(String entry) throws IOException;
    }

    private volatile KeePassKeys keePass;

    /** Lets {@link #apiKey()} read the key from KeePass when the settings name an entry; null = no KeePass. */
    public void useKeePass(KeePassKeys keys) {
        this.keePass = keys;
    }

    /** Encrypts and stores a typed API key with the current settings. The caller clears {@code key}. */
    public synchronized void storeApiKey(char[] key) throws IOException {
        save(load().withEncryptedApiKey(vault.encrypt(key)));
    }

    /** Removes the stored API key. */
    public synchronized void removeApiKey() throws IOException {
        save(load().withEncryptedApiKey(""));
    }

    /**
     * The API key for one request: the stored one, else the environment variable
     * {@value ModelProviderSettings#API_KEY_ENVIRONMENT_VARIABLE}; null when neither is set. The caller clears the
     * array after use.
     *
     * @throws IOException when a stored key cannot be decrypted (the message names no part of it)
     */
    public char[] apiKey() throws IOException {
        ModelProviderSettings settings = load();
        if (settings.usesKeePass()) {
            KeePassKeys keys = keePass;
            if (keys == null) {
                throw new IOException("the API key is to come from KeePass, but KeePass is not available here");
            }
            return keys.apiKey(settings.getKeePassEntry());
        }
        if (settings.hasStoredApiKey()) {
            return vault.decrypt(settings.getEncryptedApiKey());
        }
        String fromEnvironment = environment.apply(ModelProviderSettings.API_KEY_ENVIRONMENT_VARIABLE);
        return fromEnvironment == null || fromEnvironment.trim().isEmpty() ? null
                : fromEnvironment.trim().toCharArray();
    }

    private ModelProviderSettings read() {
        Properties properties = new Properties();
        try {
            Reader in = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8);
            try {
                properties.load(in);
            } finally {
                in.close();
            }
        } catch (IOException unreadable) {
            System.err.println("[model-port] provider settings unreadable, using Ollama: " + unreadable.getMessage());
            return ModelProviderSettings.defaults();
        }
        Map<String, String> slotModels = new LinkedHashMap<String, String>();
        Map<String, Integer> contexts = new LinkedHashMap<String, Integer>();
        for (String key : new TreeMap<Object, Object>(properties).keySet().toArray(new String[0])) {
            String value = properties.getProperty(key, "").trim();
            if (key.startsWith(API_SLOT) && key.endsWith(API_SLOT_MODEL_SUFFIX)) {
                slotModels.put(key.substring(API_SLOT.length(), key.length() - API_SLOT_MODEL_SUFFIX.length()),
                        value);
            } else if (key.startsWith(API_MODEL) && key.endsWith(API_MODEL_CONTEXT_SUFFIX)) {
                contexts.put(key.substring(API_MODEL.length(), key.length() - API_MODEL_CONTEXT_SUFFIX.length()),
                        intOr(value, 0));
            }
        }
        return new ModelProviderSettings(ModelProviderKind.fromKey(properties.getProperty(PROVIDER)),
                properties.getProperty(API_BASE_URL, ""), properties.getProperty(API_KEY, ""),
                intOr(properties.getProperty(API_CAPACITY), ModelProviderSettings.DEFAULT_API_CAPACITY),
                slotModels,
                intOr(properties.getProperty(API_CONTEXT), ModelProviderSettings.DEFAULT_API_CONTEXT_TOKENS),
                contexts,
                intOr(properties.getProperty(CONNECT_TIMEOUT), ModelProviderSettings.DEFAULT_CONNECT_TIMEOUT_SECONDS),
                intOr(properties.getProperty(READ_TIMEOUT), ModelProviderSettings.DEFAULT_READ_TIMEOUT_SECONDS),
                intOr(properties.getProperty(RETRIES), ModelProviderSettings.DEFAULT_RETRIES),
                properties.getProperty(KEEPASS_ENTRY, ""));
    }

    private static int intOr(String value, int fallback) {
        try {
            return value == null ? fallback : Integer.parseInt(value.trim());
        } catch (NumberFormatException invalid) {
            return fallback;
        }
    }
}
