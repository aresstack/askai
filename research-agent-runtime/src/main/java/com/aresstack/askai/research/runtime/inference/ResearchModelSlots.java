package com.aresstack.askai.research.runtime.inference;

import com.aresstack.askai.agent.model.port.ModelSlot;

/**
 * The model slots the research runtime's model roles call (ADR 0001, 3.2). A slot is a role-level name;
 * the platform resolves it to a model on an endpoint. Out of the box every slot resolves to the central main
 * model, which is exactly the behaviour before the model port: one model for every role. A user who wants a
 * small judge and a large conversation model sets the slot in AskAI's model settings, without touching the
 * plugin.
 */
public final class ResearchModelSlots {

    /** The TeamAgent conversation (scoping, outline dialogue, concept moves). */
    public static final ModelSlot CONVERSATION = ModelSlot.of("conversation");
    /** The scope sweep: probe generator and advice chooser. */
    public static final ModelSlot SCOPE = ModelSlot.of("scope");
    /** The page readiness judge of the acquisition runner. */
    public static final ModelSlot JUDGE = ModelSlot.of("judge");
    /** The model-backed SERP layout repair. */
    public static final ModelSlot LAYOUT_REPAIR = ModelSlot.of("layout-repair");
    /** The research orchestrator: plans, evaluates and re-plans an orchestrated run (ADR 0001, 4.2). */
    public static final ModelSlot ORCHESTRATOR = ModelSlot.of("orchestrator");
    /** The search planner: turns one aspect into distinct search queries (ADR 0001, 4.1, lane G). */
    public static final ModelSlot PLANNER = ModelSlot.of("planner");
    /** The source verifier: judges accepted sources against the brief, finds contradictions (lane G). */
    public static final ModelSlot VERIFIER = ModelSlot.of("verifier");
    /** The writer: drafts cited section text from verified sources (ADR 3.2 names it "synthesis"; lane G). */
    public static final ModelSlot SYNTHESIS = ModelSlot.of("synthesis");

    private ResearchModelSlots() {
    }
}
