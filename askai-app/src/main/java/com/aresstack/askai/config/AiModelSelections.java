package com.aresstack.askai.config;

/**
 * The centrally-managed AI model selections that AskAI owns for ALL plugins. Three sekret-free model
 * identifiers:
 * <ul>
 *   <li>the <b>main</b> (chat/generation) model — the one the user picks in the chat window, shared by
 *       every plugin as its generation/inference model,</li>
 *   <li>the <b>reranker</b> model (cross-encoder used for relevance ranking), and</li>
 *   <li>the <b>embeddings</b> model.</li>
 * </ul>
 *
 * <p>Only model NAMES live here — never endpoints or secrets. The host resolves the actual endpoint
 * (local runtime port or remote Ollama base URL) when it publishes a descriptor to a plugin. An empty
 * value means "not explicitly selected"; consumers fall back to their existing default behaviour.</p>
 */
public final class AiModelSelections {

    /**
     * Per-call read timeout of the MAIN model in seconds. A SETTING, not a code constant: with a
     * user-configurable answer budget, long legitimate answers (a 4096-token review on a small local
     * model) must not silently die on a hardcoded cap.
     */
    public static final int DEFAULT_MAIN_MODEL_TIMEOUT_SECONDS = 300;

    /**
     * Per-call read timeout of the RERANKER in seconds. A setting like the main model's: live run 3
     * (2026-11-10) scored every visited page through the local cross-encoder, and on the CPU backend each
     * call outlived the former 15 s code constant, so every search fell back to keyword ranking. Two
     * minutes covers a full page on a CPU; the research loop still never hangs on a wedged runtime.
     */
    public static final int DEFAULT_RERANKER_TIMEOUT_SECONDS = 120;

    private final String mainModel;
    private final String rerankerModel;
    private final String embeddingsModel;
    private final int mainModelTimeoutSeconds;
    private final int rerankerTimeoutSeconds;
    /** Per-capability+language NLP model selections (sentence detection etc.); never null. */
    private final NlpModelSelections nlp;

    public AiModelSelections(String mainModel, String rerankerModel, String embeddingsModel) {
        this(mainModel, rerankerModel, embeddingsModel, NlpModelSelections.defaults());
    }

    public AiModelSelections(String mainModel, String rerankerModel, String embeddingsModel,
                             NlpModelSelections nlp) {
        this(mainModel, rerankerModel, embeddingsModel, DEFAULT_MAIN_MODEL_TIMEOUT_SECONDS, nlp);
    }

    public AiModelSelections(String mainModel, String rerankerModel, String embeddingsModel,
                             int mainModelTimeoutSeconds, NlpModelSelections nlp) {
        this(mainModel, rerankerModel, embeddingsModel, mainModelTimeoutSeconds,
                DEFAULT_RERANKER_TIMEOUT_SECONDS, nlp);
    }

    public AiModelSelections(String mainModel, String rerankerModel, String embeddingsModel,
                             int mainModelTimeoutSeconds, int rerankerTimeoutSeconds, NlpModelSelections nlp) {
        this.mainModel = normalize(mainModel);
        this.rerankerModel = normalize(rerankerModel);
        this.embeddingsModel = normalize(embeddingsModel);
        this.mainModelTimeoutSeconds = mainModelTimeoutSeconds > 0
                ? mainModelTimeoutSeconds : DEFAULT_MAIN_MODEL_TIMEOUT_SECONDS;
        this.rerankerTimeoutSeconds = rerankerTimeoutSeconds > 0
                ? rerankerTimeoutSeconds : DEFAULT_RERANKER_TIMEOUT_SECONDS;
        this.nlp = nlp == null ? NlpModelSelections.defaults() : nlp;
    }

    /** All-empty selections: nothing explicitly chosen yet. */
    public static AiModelSelections defaults() {
        return new AiModelSelections("", "", "");
    }

    public String getMainModel() {
        return mainModel;
    }

    public String getRerankerModel() {
        return rerankerModel;
    }

    public String getEmbeddingsModel() {
        return embeddingsModel;
    }

    /** Per-call read timeout of the main model in seconds (always positive). */
    public int getMainModelTimeoutSeconds() {
        return mainModelTimeoutSeconds;
    }

    /** Per-call read timeout of the reranker in seconds (always positive). */
    public int getRerankerTimeoutSeconds() {
        return rerankerTimeoutSeconds;
    }

    /** The per-capability+language NLP model selections (never null). */
    public NlpModelSelections getNlp() {
        return nlp;
    }

    public AiModelSelections withMainModel(String value) {
        return new AiModelSelections(value, rerankerModel, embeddingsModel, mainModelTimeoutSeconds,
                rerankerTimeoutSeconds, nlp);
    }

    public AiModelSelections withRerankerModel(String value) {
        return new AiModelSelections(mainModel, value, embeddingsModel, mainModelTimeoutSeconds,
                rerankerTimeoutSeconds, nlp);
    }

    public AiModelSelections withEmbeddingsModel(String value) {
        return new AiModelSelections(mainModel, rerankerModel, value, mainModelTimeoutSeconds,
                rerankerTimeoutSeconds, nlp);
    }

    public AiModelSelections withMainModelTimeoutSeconds(int value) {
        return new AiModelSelections(mainModel, rerankerModel, embeddingsModel, value, rerankerTimeoutSeconds,
                nlp);
    }

    public AiModelSelections withRerankerTimeoutSeconds(int value) {
        return new AiModelSelections(mainModel, rerankerModel, embeddingsModel, mainModelTimeoutSeconds, value,
                nlp);
    }

    public AiModelSelections withNlp(NlpModelSelections value) {
        return new AiModelSelections(mainModel, rerankerModel, embeddingsModel, mainModelTimeoutSeconds,
                rerankerTimeoutSeconds, value == null ? NlpModelSelections.defaults() : value);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof AiModelSelections)) {
            return false;
        }
        AiModelSelections that = (AiModelSelections) other;
        return mainModel.equals(that.mainModel)
                && rerankerModel.equals(that.rerankerModel)
                && embeddingsModel.equals(that.embeddingsModel)
                && mainModelTimeoutSeconds == that.mainModelTimeoutSeconds
                && rerankerTimeoutSeconds == that.rerankerTimeoutSeconds
                && nlp.equals(that.nlp);
    }

    @Override
    public int hashCode() {
        int result = mainModel.hashCode();
        result = 31 * result + rerankerModel.hashCode();
        result = 31 * result + embeddingsModel.hashCode();
        result = 31 * result + mainModelTimeoutSeconds;
        result = 31 * result + rerankerTimeoutSeconds;
        result = 31 * result + nlp.hashCode();
        return result;
    }

    @Override
    public String toString() {
        return "AiModelSelections{main='" + mainModel + "', reranker='" + rerankerModel
                + "', embeddings='" + embeddingsModel + "', mainTimeoutSeconds="
                + mainModelTimeoutSeconds + ", rerankerTimeoutSeconds=" + rerankerTimeoutSeconds
                + ", nlp=" + nlp + "}";
    }
}
