package com.aresstack.askai.ollamalib;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * One installable tag of an Ollama library model, e.g. {@code devstral-small-2:24b}, with the
 * details ollama.com shows for it. Immutable. Input types are modelled as a list so AskAI can later
 * filter reliably by Text / Image / Audio / Embedding instead of re-parsing a joined string.
 */
public final class OllamaModelVariant {

    private final String tag;
    private final String size;
    private final String contextWindow;
    private final List<String> inputTypes;
    private final String updatedText;
    private final boolean latest;

    public OllamaModelVariant(String tag, String size, String contextWindow, List<String> inputTypes,
                              String updatedText, boolean latest) {
        this.tag = tag == null ? "" : tag;
        this.size = size == null ? "" : size;
        this.contextWindow = contextWindow == null ? "" : contextWindow;
        this.inputTypes = inputTypes == null
                ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new ArrayList<String>(inputTypes));
        this.updatedText = updatedText == null ? "" : updatedText;
        this.latest = latest;
    }

    /** @return the full model tag passed to {@code /api/pull}, e.g. "devstral-small-2:24b". */
    public String getTag() {
        return tag;
    }

    /** @return the short tag after the colon, e.g. "24b", or the whole tag when there is no colon. */
    public String getShortTag() {
        int colon = tag.indexOf(':');
        return colon >= 0 ? tag.substring(colon + 1) : tag;
    }

    /** @return the on-disk size (e.g. "15GB"), or "" for a cloud tag that has no local size. */
    public String getSize() {
        return size;
    }

    public String getContextWindow() {
        return contextWindow;
    }

    /** @return the input types as a list, e.g. ["Text", "Image"]. */
    public List<String> getInputTypes() {
        return inputTypes;
    }

    /** @return the input types joined for display, e.g. "Text, Image". */
    public String getInputTypesText() {
        return String.join(", ", inputTypes);
    }

    public String getUpdatedText() {
        return updatedText;
    }

    /** @return true when ollama.com marks this tag as the current "latest". */
    public boolean isLatest() {
        return latest;
    }

    /** @return true for a cloud-only tag ("…-cloud"), which runs on Ollama's cloud, not a local pull. */
    public boolean isCloud() {
        String shortTag = getShortTag().toLowerCase(Locale.ROOT);
        return shortTag.endsWith("-cloud") || shortTag.equals("cloud");
    }

    @Override
    public String toString() {
        return tag;
    }
}
