package com.aresstack.askai.config;

import com.aresstack.askai.net.CertificateTrustConfiguration;
import com.aresstack.askai.net.HttpClientConfiguration;
import com.aresstack.askai.net.ProxyConfiguration;
import com.aresstack.askai.stt.SpeechToTextConfiguration;
import com.aresstack.windirectml.catalog.LocalModelCatalog;
import com.aresstack.windirectml.catalog.LocalRuntimeModelDescriptor;

import java.io.File;

public final class AppConfiguration {

    private final String ollamaBaseUrl;
    private final String keepAlive;
    private final ProxyConfiguration proxyConfiguration;
    private final CertificateTrustConfiguration certificateTrustConfiguration;
    private final HttpClientConfiguration httpClientConfiguration;
    private final String defaultQuantization;
    private final String huggingFaceToken;
    private final File modelDownloadDirectory;
    private final SpeechToTextConfiguration speechToTextConfiguration;
    private final String huggingFaceSearchSuggestions;
    private final String huggingFaceSearchFilters;
    private final ChatColorSettings chatColors;
    private final AiModelSelections aiModelSelections;
    /** The chosen UI theme id ({@code arcane}/{@code classic}); empty = not chosen (start default applies). */
    private final String uiTheme;

    /**
     * Default HuggingFace search suggestions for the Install panel dropdown, curated for a 16 GB
     * VRAM card. Format per line: {@code <term> | <modality>,<modality>} — see
     * {@link HuggingFaceSearchSuggestion}.
     *
     * <p>An audio/vision tag here means a mainstream GGUF repository for that search actually ships
     * the model's encoder (mmproj), so installing it from HuggingFace yields a working multimodal
     * model. The audio entries are verified to include an mmproj: {@code voxtral-mini-3b}
     * (ggml-org/Voxtral-Mini-3B-2507-GGUF) and {@code ultravox}
     * (ggml-org/ultravox-v0_5-llama-3_1-8b-GGUF). Note: gemma-3n's common GGUF repos are
     * language-only (no mmproj), so it is tagged text here; its audio/vision works via
     * {@code ollama pull gemma3n:e4b}, not the single-file HuggingFace import.</p>
     */
    /**
     * The general Ollama/GGUF import suggestions (never marked local). The AskAI-local-engine group is NOT
     * duplicated here; it is generated deterministically from {@link LocalModelCatalog#runnable()} so there
     * is exactly one model list.
     */
    static final String GENERAL_HF_SEARCH_SUGGESTIONS =
            "openai/gpt-oss-20b | text\n"
                    + "LiquidAI/LFM2-24B-A2B | text\n"
                    + "deepseek-ai/DeepSeek-Coder-V2-Lite-Instruct | text\n"
                    + "unsloth/Qwen3-Coder-30B-A3B-Instruct-GGUF | text\n"
                    + "unsloth/GLM-4.7-Flash-GGUF | text\n"
                    + "mistralai/Devstral-Small-2-24B-Instruct-2512 | text\n"
                    + "llama-3.1-8b-instruct | text\n"
                    + "gemma-3-12b-it | text,vision\n"
                    + "qwen2.5-14b-instruct | text\n"
                    + "qwen2.5-coder-14b | text\n"
                    + "phi-4 | text\n"
                    + "mistral-nemo | text\n"
                    + "gemma-3n-e4b | text\n"
                    + "voxtral-mini-3b | text,audio\n"
                    + "ultravox | text,audio";

    /**
     * The shipped default: the AskAI-local-engine group (deterministically catalog-generated, in catalog
     * order) FIRST, then the general Ollama/GGUF imports. Only {@code RUNNABLE} catalog entries appear —
     * {@code UNVERIFIED} (e.g. the L-12 reranker) is never a local recommendation.
     */
    public static final String DEFAULT_HF_SEARCH_SUGGESTIONS = buildDefaultSearchSuggestions();

    /** Builds the shipped default from the neutral catalog + the general list. Deterministic (catalog order). */
    static String buildDefaultSearchSuggestions() {
        StringBuilder sb = new StringBuilder();
        for (LocalRuntimeModelDescriptor descriptor : LocalModelCatalog.runnable()) {
            sb.append(descriptor.huggingFaceRepositoryId()).append(" | text | local\n");
        }
        sb.append(GENERAL_HF_SEARCH_SUGGESTIONS);
        return sb.toString();
    }

    // Earlier default suggestion lists that shipped inaccurate audio tags (e.g. gemma-3n as audio).
    // A persisted list identical to one of these was never customized, so upgrade it to the current
    // default instead of freezing the old, misleading tags.
    private static final String[] LEGACY_HF_SEARCH_SUGGESTIONS = {
            // The default shipped right before the AskAI-local-engine group: the general list plus the
            // single local reranker suggestion as a plain two-column line. An unchanged persisted copy
            // upgrades to the current catalog-generated local-engine group (local suggestions first).
            "openai/gpt-oss-20b | text\nLiquidAI/LFM2-24B-A2B | text\n"
                    + "deepseek-ai/DeepSeek-Coder-V2-Lite-Instruct | text\n"
                    + "unsloth/Qwen3-Coder-30B-A3B-Instruct-GGUF | text\n"
                    + "unsloth/GLM-4.7-Flash-GGUF | text\n"
                    + "mistralai/Devstral-Small-2-24B-Instruct-2512 | text\n"
                    + "llama-3.1-8b-instruct | text\ngemma-3-12b-it | text,vision\n"
                    + "qwen2.5-14b-instruct | text\nqwen2.5-coder-14b | text\nphi-4 | text\n"
                    + "mistral-nemo | text\ngemma-3n-e4b | text\nvoxtral-mini-3b | text,audio\n"
                    + "ultravox | text,audio\ncross-encoder/ms-marco-MiniLM-L6-v2 | text",
            // The briefly-shipped R0 default with the TYPO reranker id (L-6 instead of L6):
            "openai/gpt-oss-20b | text\nLiquidAI/LFM2-24B-A2B | text\n"
                    + "deepseek-ai/DeepSeek-Coder-V2-Lite-Instruct | text\n"
                    + "unsloth/Qwen3-Coder-30B-A3B-Instruct-GGUF | text\n"
                    + "unsloth/GLM-4.7-Flash-GGUF | text\n"
                    + "mistralai/Devstral-Small-2-24B-Instruct-2512 | text\n"
                    + "llama-3.1-8b-instruct | text\ngemma-3-12b-it | text,vision\n"
                    + "qwen2.5-14b-instruct | text\nqwen2.5-coder-14b | text\nphi-4 | text\n"
                    + "mistral-nemo | text\ngemma-3n-e4b | text\nvoxtral-mini-3b | text,audio\n"
                    + "ultravox | text,audio\ncross-encoder/ms-marco-MiniLM-L-6-v2 | text",
            // The default just before the R0 local-reranker suggestion was added:
            "openai/gpt-oss-20b | text\nLiquidAI/LFM2-24B-A2B | text\n"
                    + "deepseek-ai/DeepSeek-Coder-V2-Lite-Instruct | text\n"
                    + "unsloth/Qwen3-Coder-30B-A3B-Instruct-GGUF | text\n"
                    + "unsloth/GLM-4.7-Flash-GGUF | text\n"
                    + "mistralai/Devstral-Small-2-24B-Instruct-2512 | text\n"
                    + "llama-3.1-8b-instruct | text\ngemma-3-12b-it | text,vision\n"
                    + "qwen2.5-14b-instruct | text\nqwen2.5-coder-14b | text\nphi-4 | text\n"
                    + "mistral-nemo | text\ngemma-3n-e4b | text\nvoxtral-mini-3b | text,audio\n"
                    + "ultravox | text,audio",
            "gpt-oss-20b\nllama-3.1-8b-instruct\ngemma-3-12b-it\nqwen2.5-14b-instruct\n"
                    + "qwen2.5-coder-14b\nphi-4\nmistral-nemo\ngemma-3n-e4b\nvoxtral-mini-3b\n"
                    + "qwen3-asr\nultravox",
            "gpt-oss-20b | text\nllama-3.1-8b-instruct | text\ngemma-3-12b-it | text,vision\n"
                    + "qwen2.5-14b-instruct | text\nqwen2.5-coder-14b | text\nphi-4 | text\n"
                    + "mistral-nemo | text\ngemma-3n-e4b | text,audio,vision\nvoxtral-mini-3b | text,audio\n"
                    + "qwen3-asr | audio\nultravox | text,audio",
            // The default list shipped just before the 16 GB additions (openai/gpt-oss-20b,
            // LiquidAI/LFM2-24B-A2B, DeepSeek-Coder-V2-Lite, Qwen3-Coder-30B-A3B, GLM-4.7-Flash).
            // An unchanged persisted copy of it should upgrade to the new defaults.
            "gpt-oss-20b | text\nllama-3.1-8b-instruct | text\ngemma-3-12b-it | text,vision\n"
                    + "qwen2.5-14b-instruct | text\nqwen2.5-coder-14b | text\nphi-4 | text\n"
                    + "mistral-nemo | text\ngemma-3n-e4b | text\nvoxtral-mini-3b | text,audio\n"
                    + "ultravox | text,audio",
            // The default list shipped with the 16 GB additions but before Devstral was added.
            "openai/gpt-oss-20b | text\nLiquidAI/LFM2-24B-A2B | text\n"
                    + "deepseek-ai/DeepSeek-Coder-V2-Lite-Instruct | text\n"
                    + "unsloth/Qwen3-Coder-30B-A3B-Instruct-GGUF | text\nunsloth/GLM-4.7-Flash-GGUF | text\n"
                    + "llama-3.1-8b-instruct | text\ngemma-3-12b-it | text,vision\n"
                    + "qwen2.5-14b-instruct | text\nqwen2.5-coder-14b | text\nphi-4 | text\n"
                    + "mistral-nemo | text\ngemma-3n-e4b | text\nvoxtral-mini-3b | text,audio\n"
                    + "ultravox | text,audio"
    };

    /**
     * @return the current default when {@code raw} equals a superseded default list (silent
     *         migration of the inaccurate audio/vision tags), otherwise {@code raw} unchanged.
     */
    public static String migrateSearchSuggestions(String raw) {
        if (raw == null) {
            return DEFAULT_HF_SEARCH_SUGGESTIONS;
        }
        String trimmed = raw.trim();
        for (int i = 0; i < LEGACY_HF_SEARCH_SUGGESTIONS.length; i++) {
            if (trimmed.equals(LEGACY_HF_SEARCH_SUGGESTIONS[i].trim())) {
                return DEFAULT_HF_SEARCH_SUGGESTIONS;
            }
        }
        return raw;
    }

    public AppConfiguration(String ollamaBaseUrl, String keepAlive) {
        this(ollamaBaseUrl, keepAlive, ProxyConfiguration.defaults(),
                CertificateTrustConfiguration.defaults(), "", defaultDownloadDirectory());
    }

    public AppConfiguration(String ollamaBaseUrl, String keepAlive, ProxyConfiguration proxyConfiguration,
                            String huggingFaceToken, File modelDownloadDirectory) {
        this(ollamaBaseUrl, keepAlive, proxyConfiguration, CertificateTrustConfiguration.defaults(),
                huggingFaceToken, modelDownloadDirectory);
    }

    public AppConfiguration(String ollamaBaseUrl, String keepAlive, ProxyConfiguration proxyConfiguration,
                            CertificateTrustConfiguration certificateTrustConfiguration,
                            String huggingFaceToken, File modelDownloadDirectory) {
        this(ollamaBaseUrl, keepAlive, proxyConfiguration, certificateTrustConfiguration,
                HttpClientConfiguration.defaults(), huggingFaceToken, modelDownloadDirectory);
    }

    public AppConfiguration(String ollamaBaseUrl, String keepAlive, ProxyConfiguration proxyConfiguration,
                            CertificateTrustConfiguration certificateTrustConfiguration,
                            HttpClientConfiguration httpClientConfiguration,
                            String huggingFaceToken, File modelDownloadDirectory) {
        this(ollamaBaseUrl, keepAlive, proxyConfiguration, certificateTrustConfiguration,
                httpClientConfiguration, "Q4_K_M", huggingFaceToken, modelDownloadDirectory);
    }

    public AppConfiguration(String ollamaBaseUrl, String keepAlive, ProxyConfiguration proxyConfiguration,
                            CertificateTrustConfiguration certificateTrustConfiguration,
                            HttpClientConfiguration httpClientConfiguration, String defaultQuantization,
                            String huggingFaceToken, File modelDownloadDirectory) {
        this(ollamaBaseUrl, keepAlive, proxyConfiguration, certificateTrustConfiguration,
                httpClientConfiguration, defaultQuantization, huggingFaceToken, modelDownloadDirectory,
                SpeechToTextConfiguration.defaults(), DEFAULT_HF_SEARCH_SUGGESTIONS, "",
                ChatColorSettings.defaults(), AiModelSelections.defaults(), "");
    }

    private AppConfiguration(String ollamaBaseUrl, String keepAlive, ProxyConfiguration proxyConfiguration,
                             CertificateTrustConfiguration certificateTrustConfiguration,
                             HttpClientConfiguration httpClientConfiguration, String defaultQuantization,
                             String huggingFaceToken, File modelDownloadDirectory,
                             SpeechToTextConfiguration speechToTextConfiguration,
                             String huggingFaceSearchSuggestions, String huggingFaceSearchFilters,
                             ChatColorSettings chatColors, AiModelSelections aiModelSelections,
                             String uiTheme) {
        this.ollamaBaseUrl = normalizeBaseUrl(ollamaBaseUrl);
        this.keepAlive = keepAlive == null || keepAlive.trim().length() == 0 ? "5m" : keepAlive.trim();
        this.proxyConfiguration = proxyConfiguration == null ? ProxyConfiguration.defaults() : proxyConfiguration;
        this.certificateTrustConfiguration = certificateTrustConfiguration == null
                ? CertificateTrustConfiguration.defaults() : certificateTrustConfiguration;
        this.httpClientConfiguration = httpClientConfiguration == null
                ? HttpClientConfiguration.defaults() : httpClientConfiguration;
        this.defaultQuantization = defaultQuantization == null || defaultQuantization.trim().length() == 0
                ? "Q4_K_M" : defaultQuantization.trim();
        this.huggingFaceToken = huggingFaceToken == null ? "" : huggingFaceToken.trim();
        this.modelDownloadDirectory = modelDownloadDirectory == null ? defaultDownloadDirectory() : modelDownloadDirectory;
        this.speechToTextConfiguration = speechToTextConfiguration == null
                ? SpeechToTextConfiguration.defaults() : speechToTextConfiguration;
        this.huggingFaceSearchSuggestions = huggingFaceSearchSuggestions == null
                || huggingFaceSearchSuggestions.trim().length() == 0
                ? DEFAULT_HF_SEARCH_SUGGESTIONS : huggingFaceSearchSuggestions;
        // Empty means "use the first-run defaults" — SearchFilterState.deserialize("") yields them;
        // stored opaquely here to keep the config package free of a dependency on the hf package.
        this.huggingFaceSearchFilters = huggingFaceSearchFilters == null ? "" : huggingFaceSearchFilters;
        this.chatColors = chatColors == null ? ChatColorSettings.defaults() : chatColors;
        this.aiModelSelections = aiModelSelections == null ? AiModelSelections.defaults() : aiModelSelections;
        this.uiTheme = uiTheme == null ? "" : uiTheme.trim();
    }

    /**
     * @return a copy of this configuration with the given speech-to-text settings. Save sites that
     *         rebuild an {@code AppConfiguration} use this to carry the STT settings over.
     */
    public AppConfiguration withSpeechToTextConfiguration(SpeechToTextConfiguration configuration) {
        return new AppConfiguration(ollamaBaseUrl, keepAlive, proxyConfiguration,
                certificateTrustConfiguration, httpClientConfiguration, defaultQuantization,
                huggingFaceToken, modelDownloadDirectory, configuration, huggingFaceSearchSuggestions,
                huggingFaceSearchFilters, chatColors, aiModelSelections, uiTheme);
    }

    /**
     * @return a copy of this configuration with the given newline-separated HuggingFace search
     *         suggestions (the Install panel dropdown entries).
     */
    public AppConfiguration withHuggingFaceSearchSuggestions(String suggestions) {
        return new AppConfiguration(ollamaBaseUrl, keepAlive, proxyConfiguration,
                certificateTrustConfiguration, httpClientConfiguration, defaultQuantization,
                huggingFaceToken, modelDownloadDirectory, speechToTextConfiguration, suggestions,
                huggingFaceSearchFilters, chatColors, aiModelSelections, uiTheme);
    }

    /**
     * @return a copy of this configuration with the given serialized HuggingFace search-filter
     *         selection (see {@code SearchFilterState}); empty means the first-run defaults.
     */
    public AppConfiguration withHuggingFaceSearchFilters(String filters) {
        return new AppConfiguration(ollamaBaseUrl, keepAlive, proxyConfiguration,
                certificateTrustConfiguration, httpClientConfiguration, defaultQuantization,
                huggingFaceToken, modelDownloadDirectory, speechToTextConfiguration,
                huggingFaceSearchSuggestions, filters, chatColors, aiModelSelections, uiTheme);
    }

    /** @return a copy of this configuration with the given chat bubble colors. */
    public AppConfiguration withChatColors(ChatColorSettings colors) {
        return new AppConfiguration(ollamaBaseUrl, keepAlive, proxyConfiguration,
                certificateTrustConfiguration, httpClientConfiguration, defaultQuantization,
                huggingFaceToken, modelDownloadDirectory, speechToTextConfiguration,
                huggingFaceSearchSuggestions, huggingFaceSearchFilters, colors, aiModelSelections, uiTheme);
    }

    /**
     * @return a copy of this configuration with the given centrally-managed AI model selections
     *         (main/chat, reranker, embeddings). Save sites that rebuild an {@code AppConfiguration}
     *         use this to carry the selections over.
     */
    public AppConfiguration withAiModelSelections(AiModelSelections selections) {
        return new AppConfiguration(ollamaBaseUrl, keepAlive, proxyConfiguration,
                certificateTrustConfiguration, httpClientConfiguration, defaultQuantization,
                huggingFaceToken, modelDownloadDirectory, speechToTextConfiguration,
                huggingFaceSearchSuggestions, huggingFaceSearchFilters, chatColors, selections, uiTheme);
    }

    /**
     * @return a copy of this configuration with the given network settings (proxy, TLS trust, HTTP
     *         client). Every other setting is carried over — the network panel must never reset the
     *         chat colors, model selections or search filters.
     */
    public AppConfiguration withNetworkConfiguration(ProxyConfiguration proxy,
                                                     CertificateTrustConfiguration trust,
                                                     HttpClientConfiguration httpClient) {
        return new AppConfiguration(ollamaBaseUrl, keepAlive, proxy, trust, httpClient, defaultQuantization,
                huggingFaceToken, modelDownloadDirectory, speechToTextConfiguration,
                huggingFaceSearchSuggestions, huggingFaceSearchFilters, chatColors, aiModelSelections, uiTheme);
    }

    /** @return a copy of this configuration with the given Hugging Face token; everything else is kept. */
    public AppConfiguration withHuggingFaceToken(String token) {
        return new AppConfiguration(ollamaBaseUrl, keepAlive, proxyConfiguration,
                certificateTrustConfiguration, httpClientConfiguration, defaultQuantization,
                token, modelDownloadDirectory, speechToTextConfiguration,
                huggingFaceSearchSuggestions, huggingFaceSearchFilters, chatColors, aiModelSelections, uiTheme);
    }

    /** @return a copy of this configuration with the given UI theme id ({@code arcane}/{@code classic}). */
    public AppConfiguration withUiTheme(String theme) {
        return new AppConfiguration(ollamaBaseUrl, keepAlive, proxyConfiguration,
                certificateTrustConfiguration, httpClientConfiguration, defaultQuantization,
                huggingFaceToken, modelDownloadDirectory, speechToTextConfiguration,
                huggingFaceSearchSuggestions, huggingFaceSearchFilters, chatColors, aiModelSelections, theme);
    }

    public static AppConfiguration defaults() {
        return new AppConfiguration("http://127.0.0.1:11434", "5m");
    }

    public String getOllamaBaseUrl() {
        return ollamaBaseUrl;
    }

    public String getKeepAlive() {
        return keepAlive;
    }

    public ProxyConfiguration getProxyConfiguration() {
        return proxyConfiguration;
    }

    public CertificateTrustConfiguration getCertificateTrustConfiguration() {
        return certificateTrustConfiguration;
    }

    public HttpClientConfiguration getHttpClientConfiguration() {
        return httpClientConfiguration;
    }

    public String getDefaultQuantization() {
        return defaultQuantization;
    }

    public SpeechToTextConfiguration getSpeechToTextConfiguration() {
        return speechToTextConfiguration;
    }

    /** @return the chosen UI theme id, or an empty string when none was chosen yet. */
    public String getUiTheme() {
        return uiTheme;
    }

    public ChatColorSettings getChatColors() {
        return chatColors;
    }

    /** @return the centrally-managed AI model selections (main/chat, reranker, embeddings). */
    public AiModelSelections getAiModelSelections() {
        return aiModelSelections;
    }

    /** @return the raw newline-separated suggestion list, as persisted. */
    public String getHuggingFaceSearchSuggestionsRaw() {
        return huggingFaceSearchSuggestions;
    }

    /** @return the serialized HuggingFace search-filter selection, or "" for first-run defaults. */
    public String getHuggingFaceSearchFilters() {
        return huggingFaceSearchFilters;
    }

    /** @return the parsed HuggingFace search suggestions for the Install panel dropdown, in order. */
    public java.util.List<HuggingFaceSearchSuggestion> getHuggingFaceSearchSuggestions() {
        return HuggingFaceSearchSuggestion.parseList(huggingFaceSearchSuggestions);
    }

    public String getHuggingFaceToken() {
        return huggingFaceToken;
    }

    public File getModelDownloadDirectory() {
        return modelDownloadDirectory;
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

    private static File defaultDownloadDirectory() {
        return new File(com.aresstack.askai.settings.AskAiPaths.appDirectory().toFile(), "models");
    }
}
