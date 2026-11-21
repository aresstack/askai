package com.aresstack.askai.ui.setup;

/**
 * The wizard's working copy of the AskAI settings it edits — the neutral draft that replaces the Enterprise AI
 * Client's {@code SettingsForm}. Immutable; every {@code with…} returns a new draft. It carries no secrets and no
 * property keys: the host ({@link SetupActions}) maps it onto AskAI's configuration when the wizard finishes.
 *
 * <p>Model roles are kept apart as AskAI knows them: the main (chat) model, the embedding model, the optional
 * reranker, the OpenNLP sentence models per language and the optional read-aloud voice per language.</p>
 *
 * <p>Model provider: with {@link #useApi()} the research's model slots run on an OpenAI-compatible API (base URL and
 * the model of the {@code default} slot here). The API key is no part of the draft; the wizard hands a typed key to
 * {@link SetupActions#finish(SetupDraft, char[], java.util.function.Consumer)} only.</p>
 */
public final class SetupDraft {

    private final String ollamaBaseUrl;
    private final String proxyMode;
    private final String proxyHost;
    private final int proxyPort;
    private final boolean trustWindowsStores;
    private final String chatModel;
    private final String embeddingModel;
    private final String rerankerModel;
    private final String nlpSentenceGerman;
    private final String nlpSentenceEnglish;
    private final String voiceGerman;
    private final String voiceEnglish;
    private final String microphone;
    private final String audioModel;
    private final boolean dictationWithOllama;
    private final String whisperModel;
    private final boolean useApi;
    private final String apiBaseUrl;
    private final String apiDefaultModel;
    private final boolean apiKeyStored;

    private SetupDraft(Builder b) {
        this.ollamaBaseUrl = clean(b.ollamaBaseUrl);
        this.proxyMode = clean(b.proxyMode);
        this.proxyHost = clean(b.proxyHost);
        this.proxyPort = Math.max(0, b.proxyPort);
        this.trustWindowsStores = b.trustWindowsStores;
        this.chatModel = clean(b.chatModel);
        this.embeddingModel = clean(b.embeddingModel);
        this.rerankerModel = clean(b.rerankerModel);
        this.nlpSentenceGerman = clean(b.nlpSentenceGerman);
        this.nlpSentenceEnglish = clean(b.nlpSentenceEnglish);
        this.voiceGerman = clean(b.voiceGerman);
        this.voiceEnglish = clean(b.voiceEnglish);
        this.microphone = clean(b.microphone);
        this.audioModel = clean(b.audioModel);
        this.dictationWithOllama = b.dictationWithOllama;
        this.whisperModel = clean(b.whisperModel);
        this.useApi = b.useApi;
        this.apiBaseUrl = clean(b.apiBaseUrl);
        this.apiDefaultModel = clean(b.apiDefaultModel);
        this.apiKeyStored = b.apiKeyStored;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** A builder pre-filled with this draft. */
    public Builder toBuilder() {
        Builder b = new Builder();
        b.ollamaBaseUrl = ollamaBaseUrl;
        b.proxyMode = proxyMode;
        b.proxyHost = proxyHost;
        b.proxyPort = proxyPort;
        b.trustWindowsStores = trustWindowsStores;
        b.chatModel = chatModel;
        b.embeddingModel = embeddingModel;
        b.rerankerModel = rerankerModel;
        b.nlpSentenceGerman = nlpSentenceGerman;
        b.nlpSentenceEnglish = nlpSentenceEnglish;
        b.voiceGerman = voiceGerman;
        b.voiceEnglish = voiceEnglish;
        b.microphone = microphone;
        b.audioModel = audioModel;
        b.dictationWithOllama = dictationWithOllama;
        b.whisperModel = whisperModel;
        b.useApi = useApi;
        b.apiBaseUrl = apiBaseUrl;
        b.apiDefaultModel = apiDefaultModel;
        b.apiKeyStored = apiKeyStored;
        return b;
    }

    /** Base URL of the Ollama server (remote or local); empty = AskAI's default. */
    public String ollamaBaseUrl() {
        return ollamaBaseUrl;
    }

    /** AskAI's proxy mode name ({@code ProxyConfiguration} constants such as {@code DISABLED}). */
    public String proxyMode() {
        return proxyMode;
    }

    public String proxyHost() {
        return proxyHost;
    }

    public int proxyPort() {
        return proxyPort;
    }

    /** Whether certificates of the Windows certificate stores are trusted (corporate TLS inspection). */
    public boolean trustWindowsStores() {
        return trustWindowsStores;
    }

    /** The main (chat/generation) model shared by all plugins. */
    public String chatModel() {
        return chatModel;
    }

    public String embeddingModel() {
        return embeddingModel;
    }

    /** Optional cross-encoder for relevance ranking; empty = none. */
    public String rerankerModel() {
        return rerankerModel;
    }

    /** OpenNLP sentence-detection model for German; empty = regex fallback. */
    public String nlpSentenceGerman() {
        return nlpSentenceGerman;
    }

    /** OpenNLP sentence-detection model for English; empty = regex fallback. */
    public String nlpSentenceEnglish() {
        return nlpSentenceEnglish;
    }

    /** Read-aloud voice for German: a Piper voice id, empty = the Windows default voice. */
    public String voiceGerman() {
        return voiceGerman;
    }

    /** Read-aloud voice for English: a Piper voice id, empty = the Windows default voice. */
    public String voiceEnglish() {
        return voiceEnglish;
    }

    /** The capture device for dictation; empty: the system default microphone. */
    public String microphone() {
        return microphone;
    }

    /** The audio model for dictation; empty: AskAI picks an audio-capable model automatically. */
    public String audioModel() {
        return audioModel;
    }

    /** Dictation runs on the Ollama audio model instead of Whisper (Whisper on this computer is the default). */
    public boolean dictationWithOllama() {
        return dictationWithOllama;
    }

    /** The Whisper model id for dictation; empty: the first installed one. */
    public String whisperModel() {
        return whisperModel;
    }

    /** The research's model slots run on an OpenAI-compatible API instead of Ollama. */
    public boolean useApi() {
        return useApi;
    }

    /** The API's base URL (usually ending with {@code /v1}); kept while Ollama is chosen. */
    public String apiBaseUrl() {
        return apiBaseUrl;
    }

    /** The API model of the {@code default} slot, which every task inherits unless it has its own. */
    public String apiDefaultModel() {
        return apiDefaultModel;
    }

    /** Whether an API key is stored already (an empty key field then keeps it). */
    public boolean apiKeyStored() {
        return apiKeyStored;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    @Override
    public String toString() {
        // No secrets in the draft, so the whole state may be logged.
        return "SetupDraft{ollama='" + ollamaBaseUrl + "', proxy=" + proxyMode + ' ' + proxyHost + ':' + proxyPort
                + ", windowsTrust=" + trustWindowsStores + ", chat='" + chatModel + "', embedding='" + embeddingModel
                + "', reranker='" + rerankerModel + "', nlp=" + nlpSentenceGerman + '/' + nlpSentenceEnglish
                + ", voices=" + voiceGerman + '/' + voiceEnglish + ", microphone='" + microphone + "', audio='"
                + audioModel + "', dictationEngine=" + (dictationWithOllama ? "ollama" : "whisper") + ", whisper='"
                + whisperModel + "', provider=" + (useApi ? "api" : "ollama") + ", apiBaseUrl='" + apiBaseUrl
                + "', apiDefaultModel='" + apiDefaultModel + "', apiKey=" + (apiKeyStored ? "stored" : "none") + "}";
    }

    /** Mutable builder; {@link #build()} trims every text. */
    public static final class Builder {
        private String ollamaBaseUrl;
        private String proxyMode;
        private String proxyHost;
        private int proxyPort;
        private boolean trustWindowsStores;
        private String chatModel;
        private String embeddingModel;
        private String rerankerModel;
        private String nlpSentenceGerman;
        private String nlpSentenceEnglish;
        private String voiceGerman;
        private String voiceEnglish;
        private String microphone;
        private String audioModel;
        private boolean dictationWithOllama;
        private String whisperModel;
        private boolean useApi;
        private String apiBaseUrl;
        private String apiDefaultModel;
        private boolean apiKeyStored;

        private Builder() {
        }

        public Builder ollamaBaseUrl(String value) {
            this.ollamaBaseUrl = value;
            return this;
        }

        public Builder proxy(String mode, String host, int port) {
            this.proxyMode = mode;
            this.proxyHost = host;
            this.proxyPort = port;
            return this;
        }

        public Builder trustWindowsStores(boolean value) {
            this.trustWindowsStores = value;
            return this;
        }

        public Builder chatModel(String value) {
            this.chatModel = value;
            return this;
        }

        public Builder embeddingModel(String value) {
            this.embeddingModel = value;
            return this;
        }

        public Builder rerankerModel(String value) {
            this.rerankerModel = value;
            return this;
        }

        public Builder nlpSentence(String german, String english) {
            this.nlpSentenceGerman = german;
            this.nlpSentenceEnglish = english;
            return this;
        }

        public Builder voices(String german, String english) {
            this.voiceGerman = german;
            this.voiceEnglish = english;
            return this;
        }

        public Builder dictation(String microphone, String audioModel) {
            this.microphone = microphone;
            this.audioModel = audioModel;
            return this;
        }

        /** @param withOllama transcribe with the Ollama audio model; else with Whisper ({@code model}, "" = auto) */
        public Builder dictationEngine(boolean withOllama, String model) {
            this.dictationWithOllama = withOllama;
            this.whisperModel = model;
            return this;
        }

        /** @param useApi the research runs on the OpenAI-compatible API ({@code baseUrl}, {@code defaultModel}) */
        public Builder api(boolean useApi, String baseUrl, String defaultModel) {
            this.useApi = useApi;
            this.apiBaseUrl = baseUrl;
            this.apiDefaultModel = defaultModel;
            return this;
        }

        public Builder apiKeyStored(boolean value) {
            this.apiKeyStored = value;
            return this;
        }

        public SetupDraft build() {
            return new SetupDraft(this);
        }
    }
}
