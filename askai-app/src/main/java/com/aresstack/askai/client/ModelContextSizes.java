package com.aresstack.askai.client;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.ToIntFunction;

/**
 * The context size ({@code num_ctx}) of a model: a property of the MODEL, not of the caller (plan section 4.1,
 * rule 1). Every caller of {@link AskAiOllamaClient} sends the same value for the same model, because a
 * different {@code num_ctx} makes Ollama reload the runner.
 *
 * <p>The app installs its settings-backed lookup at start ({@link #install}); until then, and in tests, the
 * presets apply: {@code gemma4:e2b} 32,768, {@code llama3.1:8b} 16,384, {@code gpt-oss:20b} 16,384, every
 * other model 8,192.</p>
 */
public final class ModelContextSizes {

    /** The context of a model without a preset. */
    public static final int OTHER_MODELS = 8192;

    /** The presets of plan section 4.1, by model name. */
    public static final Map<String, Integer> PRESETS;

    static {
        Map<String, Integer> presets = new LinkedHashMap<String, Integer>();
        presets.put("gemma4:e2b", 32768);
        presets.put("llama3.1:8b", 16384);
        presets.put("gpt-oss:20b", 16384);
        PRESETS = Collections.unmodifiableMap(presets);
    }

    private static final ToIntFunction<String> PRESET_LOOKUP = ModelContextSizes::preset;

    private static volatile ToIntFunction<String> lookup = PRESET_LOOKUP;

    private ModelContextSizes() {
    }

    /** The preset context of a model; {@link #OTHER_MODELS} without a preset. */
    public static int preset(String model) {
        Integer value = model == null ? null : PRESETS.get(model.trim());
        return value == null ? OTHER_MODELS : value;
    }

    /** Installs the lookup every client call uses (the app's model settings); null restores the presets. */
    public static void install(ToIntFunction<String> contextOfModel) {
        lookup = contextOfModel == null ? PRESET_LOOKUP : contextOfModel;
    }

    /** The context the host sends for a model; always positive. */
    public static int of(String model) {
        int value;
        try {
            value = lookup.applyAsInt(model);
        } catch (RuntimeException broken) {
            value = preset(model);
        }
        return value > 0 ? value : preset(model);
    }
}
