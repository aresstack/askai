package com.aresstack.askai.ui;

import com.aresstack.askai.config.AiModelSelections;

/**
 * What an installed model can be used for, as offered by the "Use" menu of a model card. Each purpose maps to
 * exactly one slot of the centrally managed {@link AiModelSelections}, so only one model can hold a purpose at a
 * time: choosing another model for it replaces (and thereby un-checks) the previous one.
 */
enum ModelUsePurpose {
    /** The main (chat/generation) model shared by the chat window and every plugin. */
    CHAT("Chat"),
    /** The embeddings model. */
    EMBEDDINGS("Embeddings"),
    /** The cross-encoder reranker (served by the local model runtime). */
    RERANKER("Reranker");

    private final String label;

    ModelUsePurpose(String label) {
        this.label = label;
    }

    String label() {
        return label;
    }

    /** @return the model currently selected for this purpose ("" = none). */
    String selectedModel(AiModelSelections selections) {
        switch (this) {
            case CHAT:
                return selections.getMainModel();
            case EMBEDDINGS:
                return selections.getEmbeddingsModel();
            default:
                return selections.getRerankerModel();
        }
    }

    /** @return whether {@code modelName} currently holds this purpose. */
    boolean isSelected(AiModelSelections selections, String modelName) {
        return modelName != null && !modelName.isEmpty() && modelName.equals(selectedModel(selections));
    }

    /**
     * @return the selections after (un)assigning {@code modelName} to this purpose. Assigning replaces the
     *         previous holder; un-assigning clears the slot only if this model still holds it.
     */
    AiModelSelections apply(AiModelSelections selections, String modelName, boolean use) {
        String value = use ? modelName : (isSelected(selections, modelName) ? "" : selectedModel(selections));
        switch (this) {
            case CHAT:
                return selections.withMainModel(value);
            case EMBEDDINGS:
                return selections.withEmbeddingsModel(value);
            default:
                return selections.withRerankerModel(value);
        }
    }
}
