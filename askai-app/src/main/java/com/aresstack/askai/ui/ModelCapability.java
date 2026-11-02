package com.aresstack.askai.ui;

import com.aresstack.askai.config.HuggingFaceSearchSuggestion.Modality;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A model capability, named after ollama.com's capability tags and extended with the HuggingFace
 * input modalities, so both search sources share one vocabulary and one icon set ({@link
 * CapabilityIcons}). The enum order is also the icon display order.
 */
public enum ModelCapability {

    TEXT("Text", "Accepts text input", "completion"),
    VISION("Vision", "Accepts image input", "vision"),
    AUDIO("Audio", "Accepts audio input", "audio"),
    IMAGE("Image generation", "Generates images", "image"),
    TOOLS("Tools", "Supports tool calls", "tools"),
    THINKING("Thinking", "Supports reasoning output", "thinking"),
    INSERT("Insert", "Supports fill-in-the-middle completion", "insert"),
    EMBEDDING("Embedding", "Creates vector embeddings", "embedding"),
    CLOUD("Cloud", "Remote Ollama model", "");

    private final String displayName;
    private final String description;
    private final String ollamaTag;

    ModelCapability(String displayName, String description, String ollamaTag) {
        this.displayName = displayName;
        this.description = description;
        this.ollamaTag = ollamaTag;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }

    /** @return the canonical {@code /api/show} capability tag this maps to, or "" when not local (CLOUD). */
    public String getOllamaCapabilityTag() {
        return ollamaTag;
    }

    /** @return "Text — accepts text input" style one-liner for tooltips. */
    public String tooltipLine() {
        return displayName + " — " + Character.toLowerCase(description.charAt(0)) + description.substring(1);
    }

    /** @return an HTML tooltip listing each present capability with its description (shared by the UIs). */
    public static String tooltipHtml(Set<ModelCapability> capabilities) {
        StringBuilder builder = new StringBuilder("<html>Model capabilities:");
        for (ModelCapability capability : values()) {
            if (capabilities != null && capabilities.contains(capability)) {
                builder.append("<br>").append(capability.tooltipLine());
            }
        }
        return builder.append("</html>").toString();
    }

    /**
     * @return the canonical Ollama capability tags a set of capabilities must yield after install
     *         (CLOUD dropped: it is not a locally installable capability), in enum order.
     */
    public static java.util.List<String> requiredOllamaTags(Set<ModelCapability> capabilities) {
        java.util.List<String> tags = new java.util.ArrayList<String>();
        if (capabilities == null) {
            return tags;
        }
        for (ModelCapability capability : values()) {
            if (capabilities.contains(capability) && capability.ollamaTag.length() > 0) {
                tags.add(capability.ollamaTag);
            }
        }
        return tags;
    }

    /** @return the capability for an ollama.com tag (vision/tools/thinking/embedding/cloud/...), or null. */
    public static ModelCapability fromOllamaTag(String tag) {
        if (tag == null) {
            return null;
        }
        String value = tag.trim().toLowerCase(Locale.ROOT);
        if (value.equals("vision")) {
            return VISION;
        }
        if (value.equals("tools")) {
            return TOOLS;
        }
        if (value.equals("thinking")) {
            return THINKING;
        }
        if (value.equals("embedding")) {
            return EMBEDDING;
        }
        if (value.equals("insert")) {
            return INSERT;
        }
        if (value.equals("image")) {
            return IMAGE;
        }
        if (value.equals("cloud")) {
            return CLOUD;
        }
        if (value.equals("audio")) {
            return AUDIO;
        }
        // Ollama reports the text/generation capability as "completion"; "text" is the HF spelling.
        if (value.equals("completion") || value.equals("text")) {
            return TEXT;
        }
        return null;
    }

    /** @return the capabilities for a list of ollama.com capability tags (unknown tags skipped). */
    public static Set<ModelCapability> fromOllamaTags(List<String> tags) {
        EnumSet<ModelCapability> capabilities = EnumSet.noneOf(ModelCapability.class);
        if (tags != null) {
            for (int i = 0; i < tags.size(); i++) {
                ModelCapability capability = fromOllamaTag(tags.get(i));
                if (capability != null) {
                    capabilities.add(capability);
                }
            }
        }
        return capabilities;
    }

    /** @return the capabilities for a set of HuggingFace suggestion modalities (text/audio/vision). */
    public static Set<ModelCapability> fromModalities(Set<Modality> modalities) {
        EnumSet<ModelCapability> capabilities = EnumSet.noneOf(ModelCapability.class);
        if (modalities != null) {
            for (Modality modality : modalities) {
                capabilities.add(fromModality(modality));
            }
        }
        return capabilities;
    }

    private static ModelCapability fromModality(Modality modality) {
        switch (modality) {
            case AUDIO:
                return AUDIO;
            case VISION:
                return VISION;
            case TEXT:
            default:
                return TEXT;
        }
    }
}
