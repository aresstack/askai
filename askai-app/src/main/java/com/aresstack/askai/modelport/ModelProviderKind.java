package com.aresstack.askai.modelport;

import java.util.Locale;

/**
 * Which kind of model service fills the model slots (the research roles): Ollama on this or another machine (the
 * default), or an OpenAI-compatible API. AskAI's local runtime ({@code local/...} models) serves its models with
 * either choice.
 */
public enum ModelProviderKind {

    /** Ollama: one machine, one GPU; models are loaded, kept warm and switched as a deliberate step. */
    OLLAMA("ollama", "Ollama"),
    /**
     * An OpenAI-compatible API ({@code POST /chat/completions}): base URL, API key and a model per task; many
     * models side by side, several calls per model, nothing is loaded or unloaded by AskAI.
     */
    OPENAI_COMPATIBLE("openai-compatible", "OpenAI-compatible API");

    private final String key;
    private final String label;

    ModelProviderKind(String key, String label) {
        this.key = key;
        this.label = label;
    }

    /** The value in the settings file. */
    public String key() {
        return key;
    }

    /** The name in the user interface. */
    public String label() {
        return label;
    }

    /** The kind of a settings value; blank or unknown values are Ollama, the default. */
    public static ModelProviderKind fromKey(String value) {
        String wanted = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        for (ModelProviderKind kind : values()) {
            if (kind.key.equals(wanted)) {
                return kind;
            }
        }
        return OLLAMA;
    }

    @Override
    public String toString() {
        return label;
    }
}
