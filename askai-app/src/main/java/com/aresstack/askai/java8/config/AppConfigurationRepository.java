package com.aresstack.askai.java8.config;

import com.aresstack.askai.java8.net.CertificateTrustConfiguration;
import com.aresstack.askai.java8.net.HttpClientConfiguration;
import com.aresstack.askai.java8.net.ProxyConfiguration;
import com.aresstack.askai.java8.stt.SpeechToTextConfiguration;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Properties;

public final class AppConfigurationRepository {

    private static final String OLLAMA_BASE_URL = "ollama.baseUrl";
    private static final String KEEP_ALIVE = "ollama.keepAlive";
    private static final String OLLAMA_QUANTIZATION = "ollama.quantization";
    private static final String PROXY_MODE = "proxy.mode";
    private static final String PROXY_TEST_URL = "proxy.testUrl";
    private static final String PROXY_PAC_SCRIPT = "proxy.pacUrlDiscoveryScript";
    private static final String PROXY_PAC_URL = "proxy.pacUrl";
    private static final String PROXY_HOST = "proxy.host";
    private static final String PROXY_PORT = "proxy.port";
    private static final String HF_TOKEN = "huggingface.token";
    private static final String DOWNLOAD_DIRECTORY = "huggingface.downloadDirectory";
    private static final String HF_SEARCH_SUGGESTIONS = "huggingface.searchSuggestions";
    private static final String HF_SEARCH_FILTERS = "huggingface.searchFilters";
    private static final String TRUST_JVM_DEFAULT = "trust.jvmDefault";
    private static final String TRUST_WINDOWS_ROOT = "trust.windowsRoot";
    private static final String TRUST_WINDOWS_CA_STORES = "trust.windowsCaStores";
    private static final String HTTP_USER_AGENT = "http.userAgent";
    private static final String HTTP_PREFER_IPV6 = "http.preferIpv6";
    private static final String PROXY_AUTH_MODE = "proxyauth.mode";
    private static final String PROXY_AUTH_USERNAME = "proxyauth.username";
    private static final String PROXY_AUTH_PASSWORD = "proxyauth.password";
    private static final String STT_ENABLED = "stt.enabled";
    private static final String STT_BACKEND = "stt.backend";
    private static final String STT_MODEL = "stt.model";
    private static final String STT_LANGUAGE = "stt.language";
    private static final String STT_PROMPT = "stt.prompt";
    private static final String STT_MAX_FILE_SIZE_MB = "stt.maxFileSizeMb";
    private static final String STT_TIMEOUT_SECONDS = "stt.timeoutSeconds";
    private static final String STT_MIC_DEVICE_ID = "stt.microphoneDeviceId";
    private static final String STT_AUDIO_MODEL_AUTOMATIC = "stt.audioModelAutomatic";
    private static final String STT_LAST_AUDIO_MODEL = "stt.lastAudioModel";
    private static final String STT_AUDIO_PROFILE = "stt.audioProcessingProfile";
    private static final String STT_AUTO_SEND = "stt.autoSendTranscription";
    private static final String STT_AUTO_STOP_SILENCE = "stt.autoStopOnSilence";
    private static final String STT_AUTO_STOP_SILENCE_SECONDS = "stt.autoStopSilenceSeconds";
    private static final String STT_SIGNAL_THRESHOLD_PERCENT = "stt.signalThresholdPercent";
    /** Scale marker for the threshold: absent = the OLD linear scale → converted to dB on load. */
    private static final String STT_SIGNAL_THRESHOLD_SCALE = "stt.signalThresholdScale";
    private static final String THRESHOLD_SCALE_DB = "db";

    /**
     * The gate threshold, migrated once: a value persisted WITHOUT the scale marker was stored
     * on the old LINEAR scale — it converts to the SAME physical level on the dB scale (e.g.
     * linear 8 → ≈63) instead of silently meaning something far quieter.
     */
    private static int signalThreshold(java.util.Properties properties, int dbDefault) {
        String raw = properties.getProperty(STT_SIGNAL_THRESHOLD_PERCENT);
        if (raw == null || raw.trim().isEmpty()) {
            return dbDefault;
        }
        int value;
        try {
            value = Integer.parseInt(raw.trim());
        } catch (NumberFormatException invalid) {
            return dbDefault;
        }
        if (THRESHOLD_SCALE_DB.equals(properties.getProperty(STT_SIGNAL_THRESHOLD_SCALE))) {
            return value;
        }
        return com.aresstack.askai.java8.stt.DecibelLevelScale.dbPercentFromLinearPercent(value);
    }
    private static final String UI_THEME = "ui.theme";
    private static final String CHAT_COLOR_TRANSCRIPT_BG = "chat.color.transcriptBackground";
    private static final String CHAT_COLOR_USER_BG = "chat.color.userBackground";
    private static final String CHAT_COLOR_USER_FG = "chat.color.userForeground";
    private static final String CHAT_COLOR_ASSISTANT_BG = "chat.color.assistantBackground";
    private static final String CHAT_COLOR_ASSISTANT_FG = "chat.color.assistantForeground";
    // Centrally-managed AI model selections (owned by AskAI, shared by all plugins). Model NAMES only —
    // never endpoints/secrets. The main model is also the chat-window selection.
    private static final String AI_MAIN_MODEL = "ai.mainModel";
    private static final String AI_MAIN_MODEL_TIMEOUT_SECONDS = "ai.mainModelTimeoutSeconds";
    private static final String AI_RERANKER_MODEL = "ai.rerankerModel";
    private static final String AI_EMBEDDINGS_MODEL = "ai.embeddingsModel";
    // Per-capability+language NLP model selections, e.g. nlp.sentence-detection.de / nlp.sentence-detection.en.
    private static final String NLP_MODEL_PREFIX = "nlp.";

    private final File configurationFile;

    /** Test/embedding seam: persist to an EXPLICIT file instead of the shared user config location. */
    public AppConfigurationRepository(File configurationFile) {
        this.configurationFile = configurationFile;
    }

    public AppConfigurationRepository() {
        this.configurationFile = new File(configurationDirectory(), "askai.properties");
    }

    public AppConfiguration load() {
        if (!configurationFile.isFile()) {
            return AppConfiguration.defaults();
        }
        Properties properties = new Properties();
        FileInputStream inputStream = null;
        try {
            inputStream = new FileInputStream(configurationFile);
            properties.load(inputStream);
            AppConfiguration defaults = AppConfiguration.defaults();
            ProxyConfiguration defaultProxy = defaults.getProxyConfiguration();
            CertificateTrustConfiguration defaultTrust = defaults.getCertificateTrustConfiguration();
            HttpClientConfiguration defaultHttp = defaults.getHttpClientConfiguration();
            SpeechToTextConfiguration defaultStt = defaults.getSpeechToTextConfiguration();
            AiModelSelections defaultModels = defaults.getAiModelSelections();
            AiModelSelections aiModels = new AiModelSelections(
                    properties.getProperty(AI_MAIN_MODEL, defaultModels.getMainModel()),
                    properties.getProperty(AI_RERANKER_MODEL, defaultModels.getRerankerModel()),
                    properties.getProperty(AI_EMBEDDINGS_MODEL, defaultModels.getEmbeddingsModel()),
                    parseInt(properties.getProperty(AI_MAIN_MODEL_TIMEOUT_SECONDS,
                            String.valueOf(defaultModels.getMainModelTimeoutSeconds()))),
                    NlpModelSelections.fromEntries(nlpModelEntries(properties)));
            String mode = properties.getProperty(PROXY_MODE, defaultProxy.getModeName());
            SpeechToTextConfiguration stt = new SpeechToTextConfiguration(
                    parseBoolean(properties.getProperty(STT_ENABLED), defaultStt.isEnabled()),
                    SpeechToTextConfiguration.parseBackend(
                            properties.getProperty(STT_BACKEND, defaultStt.getBackend().name())),
                    properties.getProperty(STT_MODEL, defaultStt.getModelName()),
                    properties.getProperty(STT_LANGUAGE, defaultStt.getLanguage()),
                    properties.getProperty(STT_PROMPT, defaultStt.getPrompt()),
                    parseInt(properties.getProperty(STT_MAX_FILE_SIZE_MB,
                            String.valueOf(defaultStt.getMaxFileSizeMb()))),
                    parseInt(properties.getProperty(STT_TIMEOUT_SECONDS,
                            String.valueOf(defaultStt.getTimeoutSeconds()))),
                    properties.getProperty(STT_MIC_DEVICE_ID, defaultStt.getMicrophoneDeviceId()),
                    parseBoolean(properties.getProperty(STT_AUDIO_MODEL_AUTOMATIC),
                            defaultStt.isAudioModelAutomatic()),
                    properties.getProperty(STT_LAST_AUDIO_MODEL, defaultStt.getLastAudioModel()),
                    properties.getProperty(STT_AUDIO_PROFILE, defaultStt.getAudioProcessingProfileId()),
                    parseBoolean(properties.getProperty(STT_AUTO_SEND),
                            defaultStt.isAutoSendTranscription()),
                    parseBoolean(properties.getProperty(STT_AUTO_STOP_SILENCE),
                            defaultStt.isAutoStopOnSilence()),
                    parseInt(properties.getProperty(STT_AUTO_STOP_SILENCE_SECONDS,
                            String.valueOf(defaultStt.getAutoStopSilenceSeconds()))),
                    signalThreshold(properties, defaultStt.getSignalThresholdPercent()));
            ChatColorSettings chatColors = new ChatColorSettings(
                    ChatColorSettings.fromStored(properties.getProperty(CHAT_COLOR_TRANSCRIPT_BG),
                            "transcriptBackground"),
                    ChatColorSettings.fromStored(properties.getProperty(CHAT_COLOR_USER_BG), "userBackground"),
                    ChatColorSettings.fromStored(properties.getProperty(CHAT_COLOR_USER_FG), "userForeground"),
                    ChatColorSettings.fromStored(properties.getProperty(CHAT_COLOR_ASSISTANT_BG),
                            "assistantBackground"),
                    ChatColorSettings.fromStored(properties.getProperty(CHAT_COLOR_ASSISTANT_FG),
                            "assistantForeground"));
            return new AppConfiguration(
                    properties.getProperty(OLLAMA_BASE_URL, defaults.getOllamaBaseUrl()),
                    properties.getProperty(KEEP_ALIVE, defaults.getKeepAlive()),
                    new ProxyConfiguration(
                            mode,
                            properties.getProperty(PROXY_TEST_URL, defaultProxy.getTestUrl()),
                            properties.getProperty(PROXY_PAC_SCRIPT, defaultProxy.getPacUrlDiscoveryScript()),
                            properties.getProperty(PROXY_PAC_URL, defaultProxy.getPacUrl()),
                            properties.getProperty(PROXY_HOST, defaultProxy.getManualProxyHost()),
                            parseInt(properties.getProperty(PROXY_PORT, String.valueOf(defaultProxy.getManualProxyPort())))),
                    new CertificateTrustConfiguration(
                            parseBoolean(properties.getProperty(TRUST_JVM_DEFAULT), defaultTrust.isUseJvmDefault()),
                            parseBoolean(properties.getProperty(TRUST_WINDOWS_ROOT), defaultTrust.isUseWindowsRoot()),
                            parseBoolean(properties.getProperty(TRUST_WINDOWS_CA_STORES), defaultTrust.isUseWindowsCaStores())),
                    new HttpClientConfiguration(
                            properties.getProperty(HTTP_USER_AGENT, defaultHttp.getUserAgent()),
                            HttpClientConfiguration.parseProxyAuthMode(
                                    properties.getProperty(PROXY_AUTH_MODE, defaultHttp.getProxyAuthMode().name())),
                            properties.getProperty(PROXY_AUTH_USERNAME, defaultHttp.getProxyAuthUsername()),
                            properties.getProperty(PROXY_AUTH_PASSWORD, defaultHttp.getProxyAuthPassword()),
                            parseBoolean(properties.getProperty(HTTP_PREFER_IPV6), defaultHttp.isPreferIpv6())),
                    properties.getProperty(OLLAMA_QUANTIZATION, defaults.getDefaultQuantization()),
                    properties.getProperty(HF_TOKEN, ""),
                    new File(properties.getProperty(DOWNLOAD_DIRECTORY, defaults.getModelDownloadDirectory().getAbsolutePath())))
                    .withSpeechToTextConfiguration(stt)
                    .withHuggingFaceSearchSuggestions(AppConfiguration.migrateSearchSuggestions(
                            properties.getProperty(HF_SEARCH_SUGGESTIONS,
                                    AppConfiguration.DEFAULT_HF_SEARCH_SUGGESTIONS)))
                    .withHuggingFaceSearchFilters(properties.getProperty(HF_SEARCH_FILTERS, ""))
                    .withChatColors(chatColors)
                    .withAiModelSelections(aiModels)
                    .withUiTheme(properties.getProperty(UI_THEME, ""));
        } catch (IOException ex) {
            return AppConfiguration.defaults();
        } finally {
            closeQuietly(inputStream);
        }
    }

    private static void putUserColor(Properties properties, String key, java.awt.Color color) {
        if (!ChatColorSettings.isThemeDefault(color)) {
            properties.setProperty(key, ChatColorSettings.toHex(color));
        }
    }

    /** The theme id currently stored in the configuration file ("" when none). */
    private String storedUiTheme() {
        if (!configurationFile.isFile()) {
            return "";
        }
        Properties stored = new Properties();
        FileInputStream inputStream = null;
        try {
            inputStream = new FileInputStream(configurationFile);
            stored.load(inputStream);
            return stored.getProperty(UI_THEME, "").trim();
        } catch (IOException ex) {
            return "";
        } finally {
            closeQuietly(inputStream);
        }
    }

    public void save(AppConfiguration configuration) {
        File directory = configurationFile.getParentFile();
        if (!directory.isDirectory() && !directory.mkdirs()) {
            return;
        }
        ProxyConfiguration proxy = configuration.getProxyConfiguration();
        CertificateTrustConfiguration trust = configuration.getCertificateTrustConfiguration();
        HttpClientConfiguration http = configuration.getHttpClientConfiguration();
        Properties properties = new Properties();
        properties.setProperty(OLLAMA_BASE_URL, configuration.getOllamaBaseUrl());
        properties.setProperty(KEEP_ALIVE, configuration.getKeepAlive());
        properties.setProperty(OLLAMA_QUANTIZATION, configuration.getDefaultQuantization());
        properties.setProperty(PROXY_MODE, proxy.getModeName());
        properties.setProperty(PROXY_TEST_URL, proxy.getTestUrl());
        properties.setProperty(PROXY_PAC_SCRIPT, proxy.getPacUrlDiscoveryScript());
        properties.setProperty(PROXY_PAC_URL, proxy.getPacUrl());
        properties.setProperty(PROXY_HOST, proxy.getManualProxyHost());
        properties.setProperty(PROXY_PORT, String.valueOf(proxy.getManualProxyPort()));
        properties.setProperty(TRUST_JVM_DEFAULT, String.valueOf(trust.isUseJvmDefault()));
        properties.setProperty(TRUST_WINDOWS_ROOT, String.valueOf(trust.isUseWindowsRoot()));
        properties.setProperty(TRUST_WINDOWS_CA_STORES, String.valueOf(trust.isUseWindowsCaStores()));
        properties.setProperty(HTTP_USER_AGENT, http.getUserAgent());
        properties.setProperty(HTTP_PREFER_IPV6, String.valueOf(http.isPreferIpv6()));
        properties.setProperty(PROXY_AUTH_MODE, http.getProxyAuthMode().name());
        properties.setProperty(PROXY_AUTH_USERNAME, http.getProxyAuthUsername());
        properties.setProperty(PROXY_AUTH_PASSWORD, http.getProxyAuthPassword());
        SpeechToTextConfiguration stt = configuration.getSpeechToTextConfiguration();
        properties.setProperty(STT_ENABLED, String.valueOf(stt.isEnabled()));
        properties.setProperty(STT_BACKEND, stt.getBackend().name());
        properties.setProperty(STT_MODEL, stt.getModelName());
        properties.setProperty(STT_LANGUAGE, stt.getLanguage());
        properties.setProperty(STT_PROMPT, stt.getPrompt());
        properties.setProperty(STT_MAX_FILE_SIZE_MB, String.valueOf(stt.getMaxFileSizeMb()));
        properties.setProperty(STT_TIMEOUT_SECONDS, String.valueOf(stt.getTimeoutSeconds()));
        properties.setProperty(STT_MIC_DEVICE_ID, stt.getMicrophoneDeviceId());
        properties.setProperty(STT_AUDIO_MODEL_AUTOMATIC, String.valueOf(stt.isAudioModelAutomatic()));
        properties.setProperty(STT_LAST_AUDIO_MODEL, stt.getLastAudioModel());
        properties.setProperty(STT_AUDIO_PROFILE, stt.getAudioProcessingProfileId());
        properties.setProperty(STT_AUTO_SEND, String.valueOf(stt.isAutoSendTranscription()));
        properties.setProperty(STT_AUTO_STOP_SILENCE, String.valueOf(stt.isAutoStopOnSilence()));
        properties.setProperty(STT_AUTO_STOP_SILENCE_SECONDS,
                String.valueOf(stt.getAutoStopSilenceSeconds()));
        properties.setProperty(STT_SIGNAL_THRESHOLD_PERCENT,
                String.valueOf(stt.getSignalThresholdPercent()));
        properties.setProperty(STT_SIGNAL_THRESHOLD_SCALE, THRESHOLD_SCALE_DB);
        properties.setProperty(HF_TOKEN, configuration.getHuggingFaceToken());
        properties.setProperty(DOWNLOAD_DIRECTORY, configuration.getModelDownloadDirectory().getAbsolutePath());
        properties.setProperty(HF_SEARCH_SUGGESTIONS, configuration.getHuggingFaceSearchSuggestionsRaw());
        properties.setProperty(HF_SEARCH_FILTERS, configuration.getHuggingFaceSearchFilters());
        // Only real user choices are stored; theme defaults stay absent so they keep following the theme.
        ChatColorSettings colors = configuration.getChatColors();
        putUserColor(properties, CHAT_COLOR_TRANSCRIPT_BG, colors.getTranscriptBackground());
        putUserColor(properties, CHAT_COLOR_USER_BG, colors.getUserBackground());
        putUserColor(properties, CHAT_COLOR_USER_FG, colors.getUserForeground());
        putUserColor(properties, CHAT_COLOR_ASSISTANT_BG, colors.getAssistantBackground());
        putUserColor(properties, CHAT_COLOR_ASSISTANT_FG, colors.getAssistantForeground());
        // A configuration rebuilt without a theme (e.g. a save site using the plain constructor) keeps
        // the stored choice instead of wiping it.
        String uiTheme = configuration.getUiTheme().isEmpty() ? storedUiTheme() : configuration.getUiTheme();
        if (!uiTheme.isEmpty()) {
            properties.setProperty(UI_THEME, uiTheme);
        }
        AiModelSelections aiModels = configuration.getAiModelSelections();
        properties.setProperty(AI_MAIN_MODEL, aiModels.getMainModel());
        properties.setProperty(AI_MAIN_MODEL_TIMEOUT_SECONDS,
                String.valueOf(aiModels.getMainModelTimeoutSeconds()));
        properties.setProperty(AI_RERANKER_MODEL, aiModels.getRerankerModel());
        properties.setProperty(AI_EMBEDDINGS_MODEL, aiModels.getEmbeddingsModel());
        for (java.util.Map.Entry<String, String> nlp : aiModels.getNlp().entries().entrySet()) {
            properties.setProperty(NLP_MODEL_PREFIX + nlp.getKey(), nlp.getValue());
        }
        FileOutputStream outputStream = null;
        try {
            outputStream = new FileOutputStream(configurationFile);
            properties.store(outputStream, "AskAI Java 8 configuration");
        } catch (IOException ignored) {
        } finally {
            closeQuietly(outputStream);
        }
    }

    /** All persisted {@code nlp.*} selections as a map keyed WITHOUT the prefix (e.g. {@code sentence-detection.de}). */
    private static java.util.Map<String, String> nlpModelEntries(Properties properties) {
        java.util.Map<String, String> entries = new java.util.TreeMap<String, String>();
        for (String name : properties.stringPropertyNames()) {
            if (name.startsWith(NLP_MODEL_PREFIX) && name.length() > NLP_MODEL_PREFIX.length()) {
                entries.put(name.substring(NLP_MODEL_PREFIX.length()), properties.getProperty(name));
            }
        }
        return entries;
    }

    private File configurationDirectory() {
        return com.aresstack.askai.java8.settings.AskAiPaths.appDirectory().toFile();
    }

    private boolean parseBoolean(String value, boolean fallback) {
        if (value == null || value.trim().length() == 0) {
            return fallback;
        }
        String trimmed = value.trim();
        if ("true".equalsIgnoreCase(trimmed)) {
            return true;
        }
        if ("false".equalsIgnoreCase(trimmed)) {
            return false;
        }
        return fallback;
    }

    private int parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private void closeQuietly(FileInputStream inputStream) {
        if (inputStream == null) {
            return;
        }
        try {
            inputStream.close();
        } catch (IOException ignored) {
        }
    }

    private void closeQuietly(FileOutputStream outputStream) {
        if (outputStream == null) {
            return;
        }
        try {
            outputStream.close();
        } catch (IOException ignored) {
        }
    }
}
