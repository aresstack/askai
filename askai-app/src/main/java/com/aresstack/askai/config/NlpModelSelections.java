package com.aresstack.askai.config;

import com.aresstack.askai.agent.model.nlp.NlpCapability;

import java.util.Collections;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * The user's EXPLICIT NLP model selections, keyed per (capability, language) — the NLP counterpart of the
 * reranker/embedding model names, but typed and multi-key because sentence detection needs a German AND an
 * English model (a single global slot would be too coarse). Only model NAMES live here; the host resolves the
 * artifact. Immutable; an empty value means "not selected" (consumers fall back to the regex segmenter).
 *
 * <p>Storage is a stable map keyed by {@code "<capabilityTag>.<language>"} so more capabilities/languages need
 * no new fields; persistence writes each entry under an {@code nlp.<key>} property.</p>
 */
public final class NlpModelSelections {

    private final SortedMap<String, String> byKey;

    public NlpModelSelections() {
        this(new TreeMap<String, String>());
    }

    private NlpModelSelections(SortedMap<String, String> byKey) {
        this.byKey = Collections.unmodifiableSortedMap(new TreeMap<String, String>(byKey));
    }

    /** No NLP model selected yet. */
    public static NlpModelSelections defaults() {
        return new NlpModelSelections();
    }

    /** Rebuild from persisted entries (keys WITHOUT the {@code nlp.} prefix); empty values are dropped. */
    public static NlpModelSelections fromEntries(Map<String, String> entries) {
        TreeMap<String, String> map = new TreeMap<String, String>();
        if (entries != null) {
            for (Map.Entry<String, String> e : entries.entrySet()) {
                String value = e.getValue() == null ? "" : e.getValue().trim();
                if (e.getKey() != null && !e.getKey().trim().isEmpty() && !value.isEmpty()) {
                    map.put(e.getKey().trim(), value);
                }
            }
        }
        return new NlpModelSelections(map);
    }

    static String key(NlpCapability capability, String languageCode) {
        return capability.getTag() + "." + (languageCode == null ? "" : languageCode.trim().toLowerCase());
    }

    /** The selected model id for the (capability, language), or "" when none is selected. */
    public String getModelId(NlpCapability capability, String languageCode) {
        String value = byKey.get(key(capability, languageCode));
        return value == null ? "" : value;
    }

    /** A copy with the (capability, language) selection set (empty clears it). */
    public NlpModelSelections withModelId(NlpCapability capability, String languageCode, String modelId) {
        TreeMap<String, String> map = new TreeMap<String, String>(byKey);
        String k = key(capability, languageCode);
        String value = modelId == null ? "" : modelId.trim();
        if (value.isEmpty()) {
            map.remove(k);
        } else {
            map.put(k, value);
        }
        return new NlpModelSelections(map);
    }

    /** The raw entries (key WITHOUT the {@code nlp.} prefix), for persistence. Stable order. */
    public Map<String, String> entries() {
        return byKey;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof NlpModelSelections && byKey.equals(((NlpModelSelections) other).byKey);
    }

    @Override
    public int hashCode() {
        return byKey.hashCode();
    }

    @Override
    public String toString() {
        return "NlpModelSelections" + byKey;
    }
}
