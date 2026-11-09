package com.aresstack.askai.modelport;

import com.aresstack.askai.agent.model.port.ThinkingMode;
import com.aresstack.askai.client.ModelContextSizes;

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
 *   <li>{@code slotModels}: the model filling a slot; absent or empty = inherited from the parent slot, then
 *       from {@code default} ({@link #resolveModel}), never from the chat model;</li>
 *   <li>{@code modelContextTokens}: the context ({@code num_ctx}) per MODEL (plan 4.1, rule 1); a model without
 *       a value gets its preset ({@link ModelContextSizes}). Every caller sends this value for the model, so
 *       Ollama never reloads the runner for a different context;</li>
 *   <li>{@code slotThinking}: the thinking mode per slot, filled into a request that leaves it at
 *       {@link ThinkingMode#DEFAULT}; absent = Auto (resolved per model, plan 4.1 rule 3);</li>
 *   <li>{@code slotAnswerTokens}: the answer budget per slot, filled into a request without one; absent or 0
 *       = none (the role's own budget, or the model's default);</li>
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
    /** The slot that closes every resolution chain (plan 5.1). */
    public static final String DEFAULT_SLOT = "default";
    /** The model of the {@link #DEFAULT_SLOT} while the user has not set one (plan 5.2). */
    public static final String DEFAULT_MODEL = "gemma4:e2b";
    /** The prefix of the research phase slots ({@code phase.<phase id>}, plan 5.1). */
    public static final String PHASE_PREFIX = "phase.";
    /**
     * The app-wide helper slots: a reasoning model would break their small answer budgets, so their model must
     * be able to turn thinking off (plan 5.1).
     */
    public static final java.util.List<String> HELPER_SLOTS = java.util.Collections.unmodifiableList(
            java.util.Arrays.asList("scope", "judge", "layout-repair"));
    /**
     * The slots the settings panel offers out of the box: the default slot, the four research phase slots and
     * their role sub-slots (plan 5.1), the helper slots and the slots of the opt-in orchestrator (lane F/G).
     * Every other slot name a plugin uses resolves the same way (exact, parent, default); this list only
     * decides what is shown.
     */
    public static final java.util.List<String> DEFAULT_SLOTS = java.util.Collections.unmodifiableList(
            java.util.Arrays.asList(DEFAULT_SLOT, "phase.scoping", "phase.research", "phase.research.planner",
                    "phase.outline", "phase.outline.labels", "phase.outline.briefs", "phase.draft",
                    "phase.draft.writer", "scope", "judge", "layout-repair", "orchestrator", "planner",
                    "verifier", "synthesis"));
    public static final String DEFAULT_KEEP_ALIVE = "5m";
    public static final int DEFAULT_IMPLICIT_WAIT_SECONDS = 600;
    public static final int DEFAULT_RESIDENCY_REFRESH_SECONDS = 5;

    private final int defaultCapacity;
    private final Map<String, Integer> modelCapacities;
    private final Map<String, Long> endpointMemoryBudgetsMb;
    private final Map<String, String> slotModels;
    private final Map<String, Integer> modelContextTokens;
    private final Map<String, ThinkingMode> slotThinking;
    private final Map<String, Integer> slotAnswerTokens;
    private final String keepAlive;
    private final int implicitWaitSeconds;
    private final int residencyRefreshSeconds;

    public ModelPortSettings(int defaultCapacity, Map<String, Integer> modelCapacities,
                             Map<String, Long> endpointMemoryBudgetsMb, Map<String, String> slotModels,
                             Map<String, Integer> modelContextTokens, Map<String, ThinkingMode> slotThinking,
                             Map<String, Integer> slotAnswerTokens, String keepAlive, int implicitWaitSeconds,
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
        this.modelContextTokens = positive(modelContextTokens);
        Map<String, ThinkingMode> thinking = new LinkedHashMap<String, ThinkingMode>();
        if (slotThinking != null) {
            for (Map.Entry<String, ThinkingMode> entry : slotThinking.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null
                        && entry.getValue() != ThinkingMode.DEFAULT) {
                    thinking.put(entry.getKey(), entry.getValue());
                }
            }
        }
        this.slotThinking = Collections.unmodifiableMap(thinking);
        this.slotAnswerTokens = positive(slotAnswerTokens);
        this.keepAlive = keepAlive == null || keepAlive.trim().isEmpty() ? DEFAULT_KEEP_ALIVE : keepAlive.trim();
        this.implicitWaitSeconds = implicitWaitSeconds < 0 ? DEFAULT_IMPLICIT_WAIT_SECONDS : implicitWaitSeconds;
        this.residencyRefreshSeconds = residencyRefreshSeconds < 1
                ? DEFAULT_RESIDENCY_REFRESH_SECONDS : residencyRefreshSeconds;
    }

    public static ModelPortSettings defaults() {
        return new ModelPortSettings(DEFAULT_CAPACITY, null, null, null, null, null, null, DEFAULT_KEEP_ALIVE,
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

    /**
     * The model a slot resolves to (plan 5.1): the slot's own model, else its parent's (the last
     * {@code .segment} stripped, repeatedly), else the {@link #DEFAULT_SLOT}'s, else {@link #DEFAULT_MODEL}.
     * Never the chat model.
     */
    public String resolveModel(String slot) {
        for (String name : chain(slot)) {
            String value = slotModels.get(name);
            if (value != null) {
                return value;
            }
        }
        return DEFAULT_MODEL;
    }

    /** The thinking mode a slot inherits along the same chain; {@link ThinkingMode#DEFAULT} = Auto. */
    public ThinkingMode resolveThinking(String slot) {
        for (String name : chain(slot)) {
            ThinkingMode value = slotThinking.get(name);
            if (value != null) {
                return value;
            }
        }
        return ThinkingMode.DEFAULT;
    }

    /** True for an app-wide helper slot whose model must be able to turn thinking off. */
    public static boolean isHelperSlot(String slot) {
        return slot != null && HELPER_SLOTS.contains(slot);
    }

    /**
     * The user-facing name of what a slot serves, for messages: "Document phase", "page judge", ... Text only;
     * no code branches on it.
     */
    public static String describe(String slot) {
        String name = slot == null ? "" : slot;
        if (name.startsWith(PHASE_PREFIX)) {
            String rest = name.substring(PHASE_PREFIX.length());
            int dot = rest.indexOf('.');
            String phase = phaseLabel(dot < 0 ? rest : rest.substring(0, dot));
            return dot < 0 ? phase + " phase" : phase + " phase (" + rest.substring(dot + 1) + ")";
        }
        if ("scope".equals(name)) {
            return "scope check";
        }
        if ("judge".equals(name)) {
            return "page judge";
        }
        if ("layout-repair".equals(name)) {
            return "SERP repair";
        }
        return name + " role";
    }

    /** The product name of a phase id (Concept, Sources, Outline, Document). */
    public static String phaseLabel(String phaseId) {
        String id = phaseId == null ? "" : phaseId.trim().toLowerCase(java.util.Locale.ROOT);
        if ("scoping".equals(id)) {
            return "Concept";
        }
        if ("research".equals(id)) {
            return "Sources";
        }
        if ("outline".equals(id)) {
            return "Outline";
        }
        if ("draft".equals(id) || "finalization".equals(id)) {
            return "Document";
        }
        return id.isEmpty() ? "research" : id;
    }

    /**
     * The writer settings of the Document slot, preset by its model (plan 4.x, D8): about 1,100 words per call
     * and 8 passages of 300 tokens for {@code llama3.1:8b} and {@code gpt-oss:20b}; about 450 words and 6 x 250
     * for {@code gemma4:e2b} and every other model. The answer budget follows the word target (3,072 / 1,200),
     * plus about 1,000 reasoning tokens on {@code gpt-oss}.
     */
    public static WriterPreset writerPreset(String model) {
        String name = model == null ? "" : model.trim().toLowerCase(java.util.Locale.ROOT);
        boolean gptOss = name.startsWith("gpt-oss");
        if (gptOss || name.startsWith("llama3.1:8b")) {
            return new WriterPreset(1100, 8, 300, gptOss ? 4072 : 3072);
        }
        return new WriterPreset(450, 6, 250, 1200);
    }

    /** The writer preset of the model the Document writer slot resolves to. */
    public WriterPreset documentWriterPreset() {
        return writerPreset(resolveModel("phase.draft.writer"));
    }

    /** Word target per call, evidence pack size and the writer's answer budget. Immutable. */
    public static final class WriterPreset {
        private final int wordsPerCall;
        private final int evidencePassages;
        private final int passageTokens;
        private final int answerTokens;

        WriterPreset(int wordsPerCall, int evidencePassages, int passageTokens, int answerTokens) {
            this.wordsPerCall = wordsPerCall;
            this.evidencePassages = evidencePassages;
            this.passageTokens = passageTokens;
            this.answerTokens = answerTokens;
        }

        public int getWordsPerCall() {
            return wordsPerCall;
        }

        public int getEvidencePassages() {
            return evidencePassages;
        }

        public int getPassageTokens() {
            return passageTokens;
        }

        public int getAnswerTokens() {
            return answerTokens;
        }
    }

    /** "Model X for the Document phase is not installed. Install it under Models -> Setup." */
    public static String notInstalledMessage(String model, String slot) {
        return "Model " + model + " for the " + describe(slot) + " is not installed. Install it under "
                + "Models -> Setup.";
    }

    /**
     * "The page judge needs a model that can turn thinking off; gpt-oss:20b cannot. Set a model for it under
     * Research settings -> Models."
     */
    public static String thinkingOffRequiredMessage(String slot, String model) {
        String role = describe(slot);
        return "The " + role + " needs a model that can turn thinking off; " + model.trim()
                + " cannot. Set a model for it under Research settings -> Models.";
    }

    /**
     * True when {@code model} belongs to a family that accepts only thinking levels, never "off" (plan 4.1,
     * rule 3), so it can never serve a {@linkplain #isHelperSlot helper slot}.
     */
    public static boolean isLevelOnlyModel(String model, String family) {
        String name = model == null ? "" : model.trim().toLowerCase(java.util.Locale.ROOT);
        String fam = family == null ? "" : family.toLowerCase(java.util.Locale.ROOT).replace("-", "");
        return name.startsWith("gpt-oss") || fam.equals("gptoss");
    }

    /** The exact slot, its parents and the default slot, in resolution order. */
    static java.util.List<String> chain(String slot) {
        java.util.List<String> names = new java.util.ArrayList<String>();
        String name = slot == null ? "" : slot.trim();
        while (!name.isEmpty()) {
            names.add(name);
            int dot = name.lastIndexOf('.');
            name = dot < 0 ? "" : name.substring(0, dot);
            if (name.equals("phase")) {
                break;
            }
        }
        if (!names.contains(DEFAULT_SLOT)) {
            names.add(DEFAULT_SLOT);
        }
        return names;
    }

    /** The model configured for exactly this slot, or "" when none is set (see {@link #resolveModel}). */
    public String slotModel(String slot) {
        String value = slot == null ? null : slotModels.get(slot);
        return value == null ? "" : value;
    }

    public Map<String, String> getSlotModels() {
        return slotModels;
    }

    /** The context ({@code num_ctx}) of a model: its setting, else its preset. Always positive. */
    public int contextOf(String model) {
        Integer value = model == null ? null : modelContextTokens.get(model.trim());
        return value == null ? ModelContextSizes.preset(model) : value;
    }

    /** The configured contexts per model (presets are not listed unless saved). */
    public Map<String, Integer> getModelContextTokens() {
        return modelContextTokens;
    }

    /** The thinking mode of a slot; {@link ThinkingMode#DEFAULT} means Auto. */
    public ThinkingMode slotThinking(String slot) {
        ThinkingMode value = slot == null ? null : slotThinking.get(slot);
        return value == null ? ThinkingMode.DEFAULT : value;
    }

    public Map<String, ThinkingMode> getSlotThinking() {
        return slotThinking;
    }

    /** The answer budget of a slot in tokens, 0 = none. */
    public int slotAnswerTokens(String slot) {
        Integer value = slot == null ? null : slotAnswerTokens.get(slot);
        return value == null ? 0 : value;
    }

    public Map<String, Integer> getSlotAnswerTokens() {
        return slotAnswerTokens;
    }

    /** The default slots plus every slot with a configured model, thinking mode or budget, in a stable order. */
    public java.util.List<String> knownSlotNames() {
        java.util.List<String> names = new java.util.ArrayList<String>(DEFAULT_SLOTS);
        for (String name : slotModels.keySet()) {
            if (!names.contains(name)) {
                names.add(name);
            }
        }
        for (String name : slotThinking.keySet()) {
            if (!names.contains(name)) {
                names.add(name);
            }
        }
        for (String name : slotAnswerTokens.keySet()) {
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
