package com.aresstack.askai.hf.meta;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The central, tested map from Ollama model family to its {@link OllamaCreateProfile}. Only families with
 * a verified profile are present; anything else returns {@code null} so no renderer/parser/requires is
 * guessed.
 *
 * <p>Verified values are conservative: unless a value has been confirmed against a real Ollama, the
 * profile leaves the field empty so {@code /api/create} is never given a wrong renderer/parser and Ollama
 * keeps its own detection.</p>
 */
public final class OllamaCreateProfileRegistry {

    private static final Map<String, OllamaCreateProfile> PROFILES = build();

    private OllamaCreateProfileRegistry() {
    }

    /** @return the profile for {@code family} (case-insensitive), or {@code null} when none is registered. */
    public static OllamaCreateProfile profileFor(String family) {
        if (family == null) {
            return null;
        }
        return PROFILES.get(family.trim().toLowerCase(Locale.ROOT));
    }

    private static Map<String, OllamaCreateProfile> build() {
        Map<String, OllamaCreateProfile> map = new LinkedHashMap<String, OllamaCreateProfile>();
        register(map, new Gemma4CreateProfile());
        register(map, new Qwen3CreateProfile());
        register(map, new MistralCreateProfile());
        register(map, new LlamaCreateProfile());
        return map;
    }

    private static void register(Map<String, OllamaCreateProfile> map, OllamaCreateProfile profile) {
        map.put(profile.family().toLowerCase(Locale.ROOT), profile);
    }

    /** A profile that omits every field (safe default: let Ollama detect renderer/parser). */
    abstract static class BaseProfile implements OllamaCreateProfile {
        public String renderer() {
            return "";
        }

        public String parser() {
            return "";
        }

        public String requires() {
            return "";
        }
    }
}
