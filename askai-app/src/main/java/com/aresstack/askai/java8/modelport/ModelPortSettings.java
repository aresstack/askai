package com.aresstack.askai.java8.modelport;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The user's settings of the model port's resource scheduling (ADR 0001, 4.5). Every limit is a setting
 * with a default, never a constant. Immutable.
 *
 * <ul>
 *   <li>{@code defaultCapacity}: concurrent calls per model and endpoint when no per-model value is set
 *       (default 1; it mirrors {@code OLLAMA_NUM_PARALLEL} and stays 1 until the measurement protocol of
 *       ADR 4.5 has run on a machine);</li>
 *   <li>{@code modelCapacities}: per-model capacity overrides, keyed by the model name;</li>
 *   <li>{@code endpointMemoryBudgetsMb}: the GPU memory budget per endpoint id in MiB; absent or negative =
 *       not configured, which disables the memory planning for that endpoint (models then never share
 *       it; a model switch is a deliberate step);</li>
 *   <li>{@code slotModels}: the model filling a slot; absent or empty = the central main model;</li>
 *   <li>{@code slotContextTokens}: the default context size per slot (0 = the model's own default);</li>
 *   <li>{@code keepAlive}: Ollama's {@code keep_alive} sent with every call, keeping the active model
 *       warm (default {@code 5m}, the value AskAI has always used);</li>
 *   <li>{@code implicitWaitSeconds}: how long a call without an explicit reservation may wait for a free
 *       place;</li>
 *   <li>{@code residencyRefreshSeconds}: how old the cached {@code /api/ps} view may get before it is
 *       refreshed in the background.</li>
 * </ul>
 */
public final class ModelPortSettings {

    public static final int DEFAULT_CAPACITY = 1;
    /**
     * The slots the settings panel offers out of the box: the model roles of the research plugin today
     * (ADR 0001, 1.3), the orchestrator (lane F) and the worker roles of lane G (planner, verifier, synthesis).
     * Every other slot name a plugin uses resolves the same way (to the main model unless configured); this list
     * only decides what is shown.
     */
    public static final java.util.List<String> DEFAULT_SLOTS = java.util.Collections.unmodifiableList(
            java.util.Arrays.asList("conversation", "scope", "judge", "layout-repair", "orchestrator", "planner",
                    "verifier", "synthesis"));
    public static final String DEFAULT_KEEP_ALIVE = "5m";
    public static final int DEFAULT_IMPLICIT_WAIT_SECONDS = 600;
    public static final int DEFAULT_RESIDENCY_REFRESH_SECONDS = 5;

    private final int defaultCapacity;
    private final Map<String, Integer> modelCapacities;
    private final Map<String, Long> endpointMemoryBudgetsMb;
    private final Map<String, String> slotModels;
    private final Map<String, Integer> slotContextTokens;
    private final String keepAlive;
    private final int implicitWaitSeconds;
    private final int residencyRefreshSeconds;

    public ModelPortSettings(int defaultCapacity, Map<String, Integer> modelCapacities,
                             Map<String, Long> endpointMemoryBudgetsMb, Map<String, String> slotModels,
                             Map<String, Integer> slotContextTokens, String keepAlive, int implicitWaitSeconds,
                             int residencyRefreshSeconds) {
        this.defaultCapacity = defaultCapacity < 1 ? DEFAULT_CAPACITY : defaultCapacity;
        this.modelCapacities = positive(modelCapacities);
        this.endpointMemoryBudgetsMb = endpointMemoryBudgetsMb == null
                ? Collections.<String, Long>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, Long>(endpointMemoryBudgetsMb));
        Map<String, String> models = new LinkedHashMap<String, String>();
        if (slotModels != null) {
            for (Map.Entry<String, String> entry : slotModels.entrySet()) {
                String value = entry.getValue() == null ? "" : entry.getValue().trim();
                if (!value.isEmpty()) {
                    models.put(entry.getKey(), value);
                }
            }
        }
        this.slotModels = Collections.unmodifiableMap(models);
        Map<String, Integer> contexts = new LinkedHashMap<String, Integer>();
        if (slotContextTokens != null) {
            for (Map.Entry<String, Integer> entry : slotContextTokens.entrySet()) {
                if (entry.getValue() != null && entry.getValue() > 0) {
                    contexts.put(entry.getKey(), entry.getValue());
                }
            }
        }
        this.slotContextTokens = Collections.unmodifiableMap(contexts);
        this.keepAlive = keepAlive == null || keepAlive.trim().isEmpty() ? DEFAULT_KEEP_ALIVE : keepAlive.trim();
        this.implicitWaitSeconds = implicitWaitSeconds < 0 ? DEFAULT_IMPLICIT_WAIT_SECONDS : implicitWaitSeconds;
        this.residencyRefreshSeconds = residencyRefreshSeconds < 1
                ? DEFAULT_RESIDENCY_REFRESH_SECONDS : residencyRefreshSeconds;
    }

    public static ModelPortSettings defaults() {
        return new ModelPortSettings(DEFAULT_CAPACITY, null, null, null, null, DEFAULT_KEEP_ALIVE,
                DEFAULT_IMPLICIT_WAIT_SECONDS, DEFAULT_RESIDENCY_REFRESH_SECONDS);
    }

    public int getDefaultCapacity() {
        return defaultCapacity;
    }

    /** The capacity of a model: its override, else the default (always >= 1). */
    public int capacityOf(String model) {
        Integer value = model == null ? null : modelCapacities.get(model);
        return value == null ? defaultCapacity : value;
    }

    public Map<String, Integer> getModelCapacities() {
        return modelCapacities;
    }

    /** The GPU memory budget of an endpoint in bytes, or -1 when not configured. */
    public long memoryBudgetBytes(String endpointId) {
        Long mb = endpointId == null ? null : endpointMemoryBudgetsMb.get(endpointId);
        return mb == null || mb < 0 ? -1L : mb * 1024L * 1024L;
    }

    public Map<String, Long> getEndpointMemoryBudgetsMb() {
        return endpointMemoryBudgetsMb;
    }

    /** The model configured for a slot, or "" for "the central main model". */
    public String slotModel(String slot) {
        String value = slot == null ? null : slotModels.get(slot);
        return value == null ? "" : value;
    }

    public Map<String, String> getSlotModels() {
        return slotModels;
    }

    /** The default context size of a slot in tokens, 0 = the model's default. */
    public int slotContextTokens(String slot) {
        Integer value = slot == null ? null : slotContextTokens.get(slot);
        return value == null ? 0 : value;
    }

    public Map<String, Integer> getSlotContextTokens() {
        return slotContextTokens;
    }

    /** The default slots plus every slot with a configured model or context, in a stable order. */
    public java.util.List<String> knownSlotNames() {
        java.util.List<String> names = new java.util.ArrayList<String>(DEFAULT_SLOTS);
        for (String name : slotModels.keySet()) {
            if (!names.contains(name)) {
                names.add(name);
            }
        }
        for (String name : slotContextTokens.keySet()) {
            if (!names.contains(name)) {
                names.add(name);
            }
        }
        return names;
    }

    public String getKeepAlive() {
        return keepAlive;
    }

    public int getImplicitWaitSeconds() {
        return implicitWaitSeconds;
    }

    public int getResidencyRefreshSeconds() {
        return residencyRefreshSeconds;
    }

    private static Map<String, Integer> positive(Map<String, Integer> values) {
        Map<String, Integer> result = new LinkedHashMap<String, Integer>();
        if (values != null) {
            for (Map.Entry<String, Integer> entry : values.entrySet()) {
                if (entry.getKey() != null && !entry.getKey().trim().isEmpty()
                        && entry.getValue() != null && entry.getValue() >= 1) {
                    result.put(entry.getKey().trim(), entry.getValue());
                }
            }
        }
        return Collections.unmodifiableMap(result);
    }
}
