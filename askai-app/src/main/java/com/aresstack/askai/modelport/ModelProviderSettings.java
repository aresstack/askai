package com.aresstack.askai.modelport;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Which model service fills the model slots, and the settings of the OpenAI-compatible API. Every limit is a
 * setting with a default, never a constant. Immutable. The API key is not part of this object's text: it is held
 * encrypted ({@link ApiKeyVault}) and {@link #toString()} never shows it.
 *
 * <ul>
 *   <li>{@code provider}: {@link ModelProviderKind#OLLAMA} (default) or {@link ModelProviderKind#OPENAI_COMPATIBLE};</li>
 *   <li>{@code apiBaseUrl}: the API's base URL, usually ending with {@code /v1};</li>
 *   <li>{@code encryptedApiKey}: the API key as {@link ApiKeyVault} stored it, or "" for none;</li>
 *   <li>{@code apiCapacity}: concurrent calls per model on the API when no per-model value is set (default
 *       {@value #DEFAULT_API_CAPACITY}; a per-model value of the model-port settings wins). With the API, different
 *       models also run at the same time;</li>
 *   <li>{@code apiSlotModels}: the model per slot (task) on the API, resolved like the Ollama slots (exact, parent,
 *       {@code default}); nothing is shipped, because only the user knows which models the service offers;</li>
 *   <li>{@code apiContextTokens} and {@code apiModelContextTokens}: the context the port plans an API call with
 *       (never sent; default {@value #DEFAULT_API_CONTEXT_TOKENS});</li>
 *   <li>{@code connectTimeoutSeconds}, {@code readTimeoutSeconds}: the connection's limits; the whole call keeps the
 *       caller's time limit;</li>
 *   <li>{@code retries}: how often a request the API rate-limits or cannot take (429, 502, 503, 504) is repeated.</li>
 * </ul>
 */
public final class ModelProviderSettings {

    public static final int DEFAULT_API_CAPACITY = 8;
    public static final int DEFAULT_API_CONTEXT_TOKENS = 131072;
    public static final int DEFAULT_CONNECT_TIMEOUT_SECONDS = 15;
    public static final int DEFAULT_READ_TIMEOUT_SECONDS = 600;
    public static final int DEFAULT_RETRIES = 2;
    /** The environment variable read when no API key is stored, so a test needs no key on disk. */
    public static final String API_KEY_ENVIRONMENT_VARIABLE = "ASKAI_OPENAI_API_KEY";

    private final ModelProviderKind provider;
    private final String apiBaseUrl;
    private final String encryptedApiKey;
    private final int apiCapacity;
    private final Map<String, String> apiSlotModels;
    private final int apiContextTokens;
    private final Map<String, Integer> apiModelContextTokens;
    private final int connectTimeoutSeconds;
    private final int readTimeoutSeconds;
    private final int retries;
    private final String keePassEntry;

    public ModelProviderSettings(ModelProviderKind provider, String apiBaseUrl, String encryptedApiKey,
                                 int apiCapacity, Map<String, String> apiSlotModels, int apiContextTokens,
                                 Map<String, Integer> apiModelContextTokens, int connectTimeoutSeconds,
                                 int readTimeoutSeconds, int retries) {
        this(provider, apiBaseUrl, encryptedApiKey, apiCapacity, apiSlotModels, apiContextTokens, apiModelContextTokens,
                connectTimeoutSeconds, readTimeoutSeconds, retries, "");
    }

    /** @param keePassEntry the KeePass entry whose password is the API key ("" = the key stored here) */
    public ModelProviderSettings(ModelProviderKind provider, String apiBaseUrl, String encryptedApiKey,
                                 int apiCapacity, Map<String, String> apiSlotModels, int apiContextTokens,
                                 Map<String, Integer> apiModelContextTokens, int connectTimeoutSeconds,
                                 int readTimeoutSeconds, int retries, String keePassEntry) {
        this.keePassEntry = keePassEntry == null ? "" : keePassEntry.trim();
        this.provider = provider == null ? ModelProviderKind.OLLAMA : provider;
        this.apiBaseUrl = apiBaseUrl == null ? "" : apiBaseUrl.trim();
        this.encryptedApiKey = encryptedApiKey == null ? "" : encryptedApiKey.trim();
        this.apiCapacity = apiCapacity < 1 ? DEFAULT_API_CAPACITY : apiCapacity;
        Map<String, String> models = new LinkedHashMap<String, String>();
        if (apiSlotModels != null) {
            for (Map.Entry<String, String> entry : apiSlotModels.entrySet()) {
                String slot = entry.getKey() == null ? "" : entry.getKey().trim();
                String model = entry.getValue() == null ? "" : entry.getValue().trim();
                if (!slot.isEmpty() && !model.isEmpty()) {
                    models.put(slot, model);
                }
            }
        }
        this.apiSlotModels = Collections.unmodifiableMap(models);
        this.apiContextTokens = apiContextTokens < 1 ? DEFAULT_API_CONTEXT_TOKENS : apiContextTokens;
        Map<String, Integer> contexts = new LinkedHashMap<String, Integer>();
        if (apiModelContextTokens != null) {
            for (Map.Entry<String, Integer> entry : apiModelContextTokens.entrySet()) {
                String model = entry.getKey() == null ? "" : entry.getKey().trim();
                if (!model.isEmpty() && entry.getValue() != null && entry.getValue() >= 1) {
                    contexts.put(model, entry.getValue());
                }
            }
        }
        this.apiModelContextTokens = Collections.unmodifiableMap(contexts);
        this.connectTimeoutSeconds = connectTimeoutSeconds < 1 ? DEFAULT_CONNECT_TIMEOUT_SECONDS
                : connectTimeoutSeconds;
        this.readTimeoutSeconds = readTimeoutSeconds < 1 ? DEFAULT_READ_TIMEOUT_SECONDS : readTimeoutSeconds;
        this.retries = retries < 0 ? DEFAULT_RETRIES : retries;
    }

    /** Ollama, no API configured. */
    public static ModelProviderSettings defaults() {
        return new ModelProviderSettings(ModelProviderKind.OLLAMA, "", "", DEFAULT_API_CAPACITY, null,
                DEFAULT_API_CONTEXT_TOKENS, null, DEFAULT_CONNECT_TIMEOUT_SECONDS, DEFAULT_READ_TIMEOUT_SECONDS,
                DEFAULT_RETRIES);
    }

    public ModelProviderKind getProvider() {
        return provider;
    }

    /** True when the OpenAI-compatible API fills the model slots. */
    public boolean usesApi() {
        return provider == ModelProviderKind.OPENAI_COMPATIBLE;
    }

    public String getApiBaseUrl() {
        return apiBaseUrl;
    }

    /** The stored (encrypted) API key, or "" when none is stored. */
    public String getEncryptedApiKey() {
        return encryptedApiKey;
    }

    public boolean hasStoredApiKey() {
        return !encryptedApiKey.isEmpty();
    }

    public int getApiCapacity() {
        return apiCapacity;
    }

    public Map<String, String> getApiSlotModels() {
        return apiSlotModels;
    }

    /** The API model configured for exactly this slot, or "" (see {@link #resolveApiModel}). */
    public String apiSlotModel(String slot) {
        String value = slot == null ? null : apiSlotModels.get(slot.trim());
        return value == null ? "" : value;
    }

    /**
     * The API model a slot resolves to: its own, else its parent's (the last {@code .segment} stripped,
     * repeatedly), else the {@code default} slot's; "" when none of them is set. Nothing is shipped.
     */
    public String resolveApiModel(String slot) {
        for (String name : ModelPortSettings.chain(slot)) {
            String value = apiSlotModels.get(name);
            if (value != null) {
                return value;
            }
        }
        return "";
    }

    /**
     * The model a slot resolves to with the chosen provider: the API's slot models when the API is chosen,
     * otherwise the Ollama slot models of the model-port settings (with their shipped defaults).
     */
    public String resolveModel(String slot, ModelPortSettings portSettings) {
        if (usesApi()) {
            return resolveApiModel(slot);
        }
        return (portSettings == null ? ModelPortSettings.defaults() : portSettings).resolveModel(slot);
    }

    public int getApiContextTokens() {
        return apiContextTokens;
    }

    public Map<String, Integer> getApiModelContextTokens() {
        return apiModelContextTokens;
    }

    /** The context the port plans a call of an API model with: its own value, else the API default. */
    public int apiContextOf(String model) {
        Integer value = model == null ? null : apiModelContextTokens.get(model.trim());
        return value == null ? apiContextTokens : value;
    }

    public int getConnectTimeoutSeconds() {
        return connectTimeoutSeconds;
    }

    public int getReadTimeoutSeconds() {
        return readTimeoutSeconds;
    }

    public int getRetries() {
        return retries;
    }

    /** A copy with another provider. */
    public ModelProviderSettings withProvider(ModelProviderKind kind) {
        return new ModelProviderSettings(kind, apiBaseUrl, encryptedApiKey, apiCapacity, apiSlotModels,
                apiContextTokens, apiModelContextTokens, connectTimeoutSeconds, readTimeoutSeconds, retries, keePassEntry);
    }

    /**
     * A copy with another base URL and model of the {@code default} slot ("" clears it); the other slots keep their
     * models (the setup wizard sets only these two).
     */
    public ModelProviderSettings withApi(String baseUrl, String defaultModel) {
        Map<String, String> models = new LinkedHashMap<String, String>(apiSlotModels);
        String model = defaultModel == null ? "" : defaultModel.trim();
        if (model.isEmpty()) {
            models.remove(ModelPortSettings.DEFAULT_SLOT);
        } else {
            models.put(ModelPortSettings.DEFAULT_SLOT, model);
        }
        return new ModelProviderSettings(provider, baseUrl, encryptedApiKey, apiCapacity, models, apiContextTokens,
                apiModelContextTokens, connectTimeoutSeconds, readTimeoutSeconds, retries, keePassEntry);
    }

    /** The KeePass entry whose password is the API key; "" when the key is stored here (or in the environment). */
    public String getKeePassEntry() {
        return keePassEntry;
    }

    public boolean usesKeePass() {
        return !keePassEntry.isEmpty();
    }

    /** A copy that reads the API key from the KeePass entry {@code entry} ("" = the key stored here). */
    public ModelProviderSettings withKeePassEntry(String entry) {
        return new ModelProviderSettings(provider, apiBaseUrl, encryptedApiKey, apiCapacity, apiSlotModels,
                apiContextTokens, apiModelContextTokens, connectTimeoutSeconds, readTimeoutSeconds, retries, entry);
    }

    /** A copy with another stored key ("" removes it). */
    public ModelProviderSettings withEncryptedApiKey(String encrypted) {
        return new ModelProviderSettings(provider, apiBaseUrl, encrypted, apiCapacity, apiSlotModels,
                apiContextTokens, apiModelContextTokens, connectTimeoutSeconds, readTimeoutSeconds, retries, keePassEntry);
    }

    @Override
    public String toString() {
        return "ModelProviderSettings{provider=" + provider.key() + ", apiBaseUrl=" + apiBaseUrl
                + ", apiKey=" + (usesKeePass() ? "keepass:" + keePassEntry : hasStoredApiKey() ? "stored" : "none") + ", apiCapacity=" + apiCapacity
                + ", apiSlotModels=" + apiSlotModels + "}";
    }
}
