package com.aresstack.askai.java8.localmodels;

import com.aresstack.windirectml.catalog.ModelCapability;

import java.util.ArrayList;
import java.util.List;

/**
 * Capabilities the LOCAL model runtime exposes, mirroring the neutral catalog's {@link ModelCapability}
 * so the host and the local runtime route strictly by capability (never by a model-name heuristic). The Ollama
 * tag is the token that appears in the virtual container's {@code /api/show} capability list.
 */
public enum LocalRuntimeCapability {

    EMBEDDING("embedding"),
    RERANK("rerank"),
    COMPLETION("completion"),
    CHAT("chat"),
    SEQ2SEQ("seq2seq"),
    SUMMARIZE("summarize");

    private final String ollamaTag;

    LocalRuntimeCapability(String ollamaTag) {
        this.ollamaTag = ollamaTag;
    }

    /** The tag as it appears in the virtual container's {@code /api/show} capabilities. */
    public String getOllamaTag() {
        return ollamaTag;
    }

    /** Maps a neutral catalog capability to its local-runtime counterpart. */
    public static LocalRuntimeCapability fromCatalog(ModelCapability capability) {
        switch (capability) {
            case EMBEDDING: return EMBEDDING;
            case RERANK: return RERANK;
            case COMPLETION: return COMPLETION;
            case CHAT: return CHAT;
            case SEQ2SEQ: return SEQ2SEQ;
            case SUMMARIZE: return SUMMARIZE;
            default: throw new IllegalArgumentException("Unmapped capability: " + capability);
        }
    }

    /** The Ollama tags for a catalog capability set, in the enum's declared order. */
    public static List<String> tags(java.util.Set<ModelCapability> capabilities) {
        List<String> tags = new ArrayList<String>();
        for (LocalRuntimeCapability c : values()) {
            for (ModelCapability catalog : capabilities) {
                if (fromCatalog(catalog) == c) {
                    tags.add(c.getOllamaTag());
                    break;
                }
            }
        }
        return tags;
    }
}
