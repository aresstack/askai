package com.aresstack.askai.ollamalib;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One model from an ollama.com library search: the installable base name (e.g. {@code
 * devstral-small-2}) plus the descriptive data ollama.com renders server-side. Immutable.
 */
public final class OllamaLibraryModel {

    private final String baseName;
    private final String description;
    private final List<String> capabilities;
    private final List<String> parameterSizes;
    private final String pullsText;
    private final int tagCount;
    private final String updatedText;

    public OllamaLibraryModel(String baseName, String description, List<String> capabilities,
                              List<String> parameterSizes, String pullsText, int tagCount, String updatedText) {
        this.baseName = baseName == null ? "" : baseName;
        this.description = description == null ? "" : description;
        this.capabilities = immutable(capabilities);
        this.parameterSizes = immutable(parameterSizes);
        this.pullsText = pullsText == null ? "" : pullsText;
        this.tagCount = tagCount;
        this.updatedText = updatedText == null ? "" : updatedText;
    }

    private static List<String> immutable(List<String> values) {
        return values == null
                ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new ArrayList<String>(values));
    }

    /** @return the installable base name, e.g. "devstral-small-2" (the part after /library/). */
    public String getBaseName() {
        return baseName;
    }

    public String getDescription() {
        return description;
    }

    /** @return capability flags such as "vision", "tools", "thinking". */
    public List<String> getCapabilities() {
        return capabilities;
    }

    /** @return the offered parameter sizes, e.g. "24b", "8x7b". */
    public List<String> getParameterSizes() {
        return parameterSizes;
    }

    /** @return the pull count as displayed, e.g. "914.9K". */
    public String getPullsText() {
        return pullsText;
    }

    public int getTagCount() {
        return tagCount;
    }

    /** @return the "updated" text as displayed, e.g. "7 months ago". */
    public String getUpdatedText() {
        return updatedText;
    }

    @Override
    public String toString() {
        return baseName;
    }
}
