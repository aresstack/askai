package com.aresstack.askai.hf.meta;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * A curated, tested mapping from a Hugging Face {@code config.json} {@code model_type} to the family name
 * Ollama uses. Only known, unambiguous types map; anything unrecognised returns {@code null} so AskAI
 * never writes a guessed family (which could override Ollama's own detection). This is deliberately a
 * small allow-list, not a heuristic over arbitrary class names like {@code Qwen2ForCausalLM}.
 */
public final class OllamaModelFamilyRegistry {

    private static final Map<String, String> MODEL_TYPE_TO_FAMILY = buildMap();

    private OllamaModelFamilyRegistry() {
    }

    /**
     * @param modelType the {@code config.json} {@code model_type} (e.g. {@code "qwen3"}); case-insensitive.
     * @return the matching Ollama family, or {@code null} when the type is unknown/blank.
     */
    public static String familyFor(String modelType) {
        if (modelType == null) {
            return null;
        }
        return MODEL_TYPE_TO_FAMILY.get(modelType.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * Maps a {@code model_type} to a HIGH-confidence family value. The registry is a transformation, so
     * the value keeps the {@code source} of the input {@code model_type} (e.g. {@link MetadataSource#CONFIG_JSON}
     * when it came from config.json), not a synthetic "registry" source.
     *
     * @return the family value, or {@code null} when the type is unknown/blank.
     */
    public static MetadataValue<String> familyValue(String modelType, MetadataSource source) {
        String family = familyFor(modelType);
        return family == null ? null : MetadataValue.high(family, source);
    }

    private static Map<String, String> buildMap() {
        Map<String, String> map = new LinkedHashMap<String, String>();
        // Llama lineage
        put(map, "llama");
        put(map, "mistral");
        put(map, "mixtral");
        // Qwen
        put(map, "qwen");
        put(map, "qwen2");
        put(map, "qwen2_moe");
        map.put("qwen2_moe", "qwen2");
        put(map, "qwen2_vl");
        map.put("qwen2_vl", "qwen2");
        put(map, "qwen3");
        put(map, "qwen3_moe");
        map.put("qwen3_moe", "qwen3");
        // Gemma
        put(map, "gemma");
        put(map, "gemma2");
        put(map, "gemma3");
        put(map, "gemma3_text");
        map.put("gemma3_text", "gemma3");
        put(map, "gemma4");
        // Phi
        put(map, "phi");
        put(map, "phi3");
        put(map, "phi3_5");
        map.put("phi3_5", "phi3");
        // Others with a stable Ollama family name
        put(map, "starcoder2");
        put(map, "stablelm");
        put(map, "falcon");
        put(map, "gptneox");
        put(map, "gpt2");
        put(map, "bert");
        put(map, "nomic_bert");
        map.put("nomic_bert", "nomic-bert");
        return Collections.unmodifiableMap(map);
    }

    private static void put(Map<String, String> map, String type) {
        map.put(type, type);
    }
}
