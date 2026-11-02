package com.aresstack.askai;

import com.aresstack.askai.config.AiModelSelections;
import com.aresstack.askai.config.AppConfiguration;
import com.aresstack.askai.config.AppConfigurationRepository;
import com.aresstack.askai.config.ChatColorSettings;
import com.aresstack.askai.stt.SpeechToTextConfiguration;

import java.io.File;
import java.nio.file.Path;

/**
 * Holds mutable application state shared by Swing panels, mirroring the original AskAI
 * {@code AskAiModel}. Java 8 port: persistence is delegated to the existing
 * {@link AppConfigurationRepository} (the same properties file the rest of the app uses), so
 * settings edited here and in the Network/Install panels never diverge.
 */
public final class AskAiModel {

    private final AppConfigurationRepository configurationRepository;
    private String ollamaBaseUrl;
    private File modelRoot;
    private String defaultQuantization;
    private String defaultKeepAlive;
    private SpeechToTextConfiguration speechToTextConfiguration;
    private ChatColorSettings chatColors;
    private AiModelSelections aiModelSelections;
    private String uiTheme;
    private final java.util.List<AiModelSelectionListener> modelSelectionListeners =
            new java.util.concurrent.CopyOnWriteArrayList<AiModelSelectionListener>();

    public AskAiModel(AppConfigurationRepository configurationRepository) {
        this.configurationRepository = configurationRepository;
        AppConfiguration configuration = configurationRepository.load();
        this.ollamaBaseUrl = configuration.getOllamaBaseUrl();
        this.modelRoot = configuration.getModelDownloadDirectory();
        this.defaultQuantization = configuration.getDefaultQuantization();
        this.defaultKeepAlive = configuration.getKeepAlive();
        this.speechToTextConfiguration = configuration.getSpeechToTextConfiguration();
        this.chatColors = configuration.getChatColors();
        this.aiModelSelections = configuration.getAiModelSelections();
        this.uiTheme = configuration.getUiTheme();
    }

    public String getOllamaBaseUrl() {
        return ollamaBaseUrl;
    }

    public void setOllamaBaseUrl(String ollamaBaseUrl) {
        this.ollamaBaseUrl = ollamaBaseUrl;
    }

    public Path getModelRoot() {
        return modelRoot.toPath();
    }

    public void setModelRoot(Path modelRoot) {
        this.modelRoot = modelRoot.toFile();
    }

    public String getDefaultQuantization() {
        return defaultQuantization;
    }

    public void setDefaultQuantization(String defaultQuantization) {
        this.defaultQuantization = defaultQuantization;
    }

    public String getDefaultKeepAlive() {
        return defaultKeepAlive;
    }

    public void setDefaultKeepAlive(String defaultKeepAlive) {
        this.defaultKeepAlive = defaultKeepAlive;
    }

    public SpeechToTextConfiguration getSpeechToTextConfiguration() {
        return speechToTextConfiguration;
    }

    public void setSpeechToTextConfiguration(SpeechToTextConfiguration speechToTextConfiguration) {
        this.speechToTextConfiguration = speechToTextConfiguration == null
                ? SpeechToTextConfiguration.defaults() : speechToTextConfiguration;
    }

    /** @return the chosen UI theme id ({@code arcane}/{@code classic}), "" when none was chosen yet. */
    public String getUiTheme() {
        return uiTheme;
    }

    public void setUiTheme(String uiTheme) {
        this.uiTheme = uiTheme == null ? "" : uiTheme.trim();
    }

    public ChatColorSettings getChatColors() {
        return chatColors;
    }

    public void setChatColors(ChatColorSettings chatColors) {
        this.chatColors = chatColors == null ? ChatColorSettings.defaults() : chatColors;
    }

    /** @return the centrally-managed AI model selections (main/chat, reranker, embeddings). */
    public AiModelSelections getAiModelSelections() {
        return aiModelSelections;
    }

    /** Subscribe to central model-selection changes (e.g. to refresh running research descriptors). */
    public void addAiModelSelectionListener(AiModelSelectionListener listener) {
        if (listener != null) {
            modelSelectionListeners.add(listener);
        }
    }

    public void removeAiModelSelectionListener(AiModelSelectionListener listener) {
        modelSelectionListeners.remove(listener);
    }

    public void setAiModelSelections(AiModelSelections aiModelSelections) {
        this.aiModelSelections = aiModelSelections == null ? AiModelSelections.defaults() : aiModelSelections;
    }

    /**
     * Persists ONLY the centrally-managed main (chat) model selection, leaving every other setting on
     * disk untouched. The chat window uses this: the model the user picks there is the global main model
     * shared by all plugins. Surgical load-modify-save so it never clobbers unrelated in-memory edits.
     */
    public void persistMainModel(String modelName) {
        String value = modelName == null ? "" : modelName.trim();
        this.aiModelSelections = this.aiModelSelections.withMainModel(value);
        AppConfiguration current = configurationRepository.load();
        configurationRepository.save(current.withAiModelSelections(
                current.getAiModelSelections().withMainModel(value)));
        for (AiModelSelectionListener listener : modelSelectionListeners) {
            listener.onMainModelChanged();
        }
    }

    /**
     * Persists ONLY the centrally-managed reranker + embeddings model selections, leaving every other
     * setting on disk untouched. Used by the central AI-models settings panel. Surgical load-modify-save
     * so it never clobbers unrelated in-memory edits (and never touches the chat-window main model).
     */
    public void persistRerankerAndEmbeddingsModels(String rerankerModel, String embeddingsModel) {
        String reranker = rerankerModel == null ? "" : rerankerModel.trim();
        String embeddings = embeddingsModel == null ? "" : embeddingsModel.trim();
        this.aiModelSelections = this.aiModelSelections
                .withRerankerModel(reranker).withEmbeddingsModel(embeddings);
        AppConfiguration current = configurationRepository.load();
        configurationRepository.save(current.withAiModelSelections(current.getAiModelSelections()
                .withRerankerModel(reranker).withEmbeddingsModel(embeddings)));
        for (AiModelSelectionListener listener : modelSelectionListeners) {
            listener.onRerankerOrEmbeddingsChanged();
        }
    }

    /**
     * Persists ONLY the centrally-managed NLP sentence-detection model selections (German + English),
     * independently and leaving every other setting on disk untouched. An empty value CLEARS that language's
     * selection (later a regex fallback). Surgical load-modify-save, like the reranker/embeddings persist.
     */
    public void persistNlpSentenceModels(String germanModel, String englishModel) {
        String de = germanModel == null ? "" : germanModel.trim();
        String en = englishModel == null ? "" : englishModel.trim();
        com.aresstack.askai.agent.model.nlp.NlpCapability sentence =
                com.aresstack.askai.agent.model.nlp.NlpCapability.SENTENCE_DETECTION;
        this.aiModelSelections = this.aiModelSelections.withNlp(this.aiModelSelections.getNlp()
                .withModelId(sentence, "de", de).withModelId(sentence, "en", en));
        AppConfiguration current = configurationRepository.load();
        configurationRepository.save(current.withAiModelSelections(current.getAiModelSelections()
                .withNlp(current.getAiModelSelections().getNlp()
                        .withModelId(sentence, "de", de).withModelId(sentence, "en", en))));
    }

    /**
     * Persists the buffered values, preserving every other setting (proxy, TLS trust, HTTP client,
     * HuggingFace token) exactly as currently stored.
     */
    public void saveSettings() {
        AppConfiguration current = configurationRepository.load();
        configurationRepository.save(new AppConfiguration(
                ollamaBaseUrl,
                defaultKeepAlive,
                current.getProxyConfiguration(),
                current.getCertificateTrustConfiguration(),
                current.getHttpClientConfiguration(),
                defaultQuantization,
                current.getHuggingFaceToken(),
                modelRoot)
                .withSpeechToTextConfiguration(speechToTextConfiguration)
                .withHuggingFaceSearchSuggestions(current.getHuggingFaceSearchSuggestionsRaw())
                .withHuggingFaceSearchFilters(current.getHuggingFaceSearchFilters())
                .withChatColors(chatColors)
                .withAiModelSelections(aiModelSelections)
                .withUiTheme(uiTheme));
        this.ollamaBaseUrl = configurationRepository.load().getOllamaBaseUrl();
    }
}
