package com.aresstack.askai.agent.model.port.resource;

import com.aresstack.askai.agent.model.port.ModelSlot;

import java.time.Duration;

/**
 * A request to reserve one place of a slot's model capacity before calling it (ADR 0001, 4.5).
 *
 * <ul>
 *   <li>{@code contextTokens}: the context size the call(s) under this reservation will use; 0 = the
 *       slot's configured default. The platform may need it to plan memory (required memory scales with
 *       parallel slots times context length).</li>
 *   <li>{@code maxWait}: how long the request may wait in the scheduler's queue for a free place; a
 *       setting of the caller, never a constant. {@link Duration#ZERO} means "only if free now".</li>
 *   <li>{@code label}: a diagnostic label naming the requester (for example a task id); free text, may be
 *       "".</li>
 * </ul>
 */
public final class SlotRequest {

    private final ModelSlot slot;
    private final int contextTokens;
    private final Duration maxWait;
    private final String label;

    public SlotRequest(ModelSlot slot, int contextTokens, Duration maxWait, String label) {
        if (slot == null) {
            throw new IllegalArgumentException("slot must not be null");
        }
        if (contextTokens < 0) {
            throw new IllegalArgumentException("context tokens must be >= 0");
        }
        if (maxWait == null || maxWait.isNegative()) {
            throw new IllegalArgumentException("maxWait must be non-null and not negative");
        }
        this.slot = slot;
        this.contextTokens = contextTokens;
        this.maxWait = maxWait;
        this.label = label == null ? "" : label;
    }

    public ModelSlot getSlot() {
        return slot;
    }

    /** Requested context size in tokens; 0 = slot default. */
    public int getContextTokens() {
        return contextTokens;
    }

    public Duration getMaxWait() {
        return maxWait;
    }

    public String getLabel() {
        return label;
    }
}
