package com.aresstack.askai.agent.model.port.resource;

import com.aresstack.askai.agent.model.port.ModelSlot;

/**
 * How the platform currently resolves one {@link ModelSlot}: to which endpoint and which model (as an
 * opaque {@link ModelHandle}), with which default context size (ADR 0001, 3.2 and 4.5). Slots with equal
 * handles on the same endpoint share one model and therefore one capacity.
 */
public final class SlotBinding {

    private final ModelSlot slot;
    private final String endpointId;
    private final ModelHandle model;
    private final int defaultContextTokens;

    /**
     * @param slot                 the slot; non-null
     * @param endpointId           the endpoint serving it; non-blank
     * @param model                the model filling it; non-null
     * @param defaultContextTokens the context size used when a call names none (a setting per slot); 0 =
     *                             the model's own default
     */
    public SlotBinding(ModelSlot slot, String endpointId, ModelHandle model, int defaultContextTokens) {
        if (slot == null || model == null) {
            throw new IllegalArgumentException("slot and model must not be null");
        }
        if (endpointId == null || endpointId.trim().isEmpty()) {
            throw new IllegalArgumentException("endpoint id must not be blank");
        }
        if (defaultContextTokens < 0) {
            throw new IllegalArgumentException("default context tokens must be >= 0");
        }
        this.slot = slot;
        this.endpointId = endpointId;
        this.model = model;
        this.defaultContextTokens = defaultContextTokens;
    }

    public ModelSlot getSlot() {
        return slot;
    }

    public String getEndpointId() {
        return endpointId;
    }

    public ModelHandle getModel() {
        return model;
    }

    /** Default context size in tokens; 0 = the model's own default. */
    public int getDefaultContextTokens() {
        return defaultContextTokens;
    }
}
