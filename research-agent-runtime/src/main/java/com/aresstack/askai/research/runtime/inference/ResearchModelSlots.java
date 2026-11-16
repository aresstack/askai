package com.aresstack.askai.research.runtime.inference;

import com.aresstack.askai.agent.model.port.ModelSlot;

/**
 * The model slots the research runtime's model roles call (ADR 0001, 3.2; plan 5.1). A slot is a role-level
 * name; the platform resolves it to a model on an endpoint: the exact slot, then its parent (the last
 * {@code .segment} stripped), then {@link #DEFAULT}. The research runtime never falls back to the chat model.
 *
 * <p>Each research phase has its own slot ({@code phase.<phase id>}), shown as Concept, Sources, Outline and
 * Document; role sub-slots ({@code phase.research.planner}, {@code phase.draft.writer}, ...) inherit the model
 * and the thinking mode of their phase. The helper slots {@link #SCOPE}, {@link #JUDGE} and
 * {@link #LAYOUT_REPAIR} stay app-wide. The plugin passes slot ids only, never model names.</p>
 */
public final class ResearchModelSlots {

    /** Closes every resolution chain (preset {@code gemma4:e2b} on the host). */
    public static final ModelSlot DEFAULT = ModelSlot.of("default");
    /** Concept (phase id {@code scoping}): the chat turns, the opening greeting and the concept moves. */
    public static final ModelSlot PHASE_SCOPING = ModelSlot.of("phase.scoping");
    /** Sources (phase id {@code research}). */
    public static final ModelSlot PHASE_RESEARCH = ModelSlot.of("phase.research");
    /** Outline (phase id {@code outline}). */
    public static final ModelSlot PHASE_OUTLINE = ModelSlot.of("phase.outline");
    /** Document (phase id {@code draft}). */
    public static final ModelSlot PHASE_DRAFT = ModelSlot.of("phase.draft");
    /** The book pipeline's search planner in the Sources phase. */
    public static final ModelSlot PHASE_RESEARCH_PLANNER = ModelSlot.of("phase.research.planner");
    /**
     * The research team's page workers in the Sources phase (plan T4): many parallel calls, one per opened page.
     * Inherits the Sources model until the user gives it its own.
     */
    public static final ModelSlot PHASE_RESEARCH_WORKER = ModelSlot.of("phase.research.worker");
    /** The outline's chapter labels. */
    public static final ModelSlot PHASE_OUTLINE_LABELS = ModelSlot.of("phase.outline.labels");
    /** The outline's chapter briefs. */
    public static final ModelSlot PHASE_OUTLINE_BRIEFS = ModelSlot.of("phase.outline.briefs");
    /** The book writer in the Document phase. */
    public static final ModelSlot PHASE_DRAFT_WRITER = ModelSlot.of("phase.draft.writer");

    /**
     * The slot of a live phase id: {@code phase.<id>}; an empty id (before the host reports one, the opening
     * greeting) is Concept, and the legacy {@code finalization} phase works on the Document.
     */
    public static ModelSlot phase(String phaseId) {
        String id = phaseId == null ? "" : phaseId.trim().toLowerCase(java.util.Locale.ROOT);
        if (id.isEmpty()) {
            return PHASE_SCOPING;
        }
        if ("finalization".equals(id)) {
            return PHASE_DRAFT;
        }
        return ModelSlot.of("phase." + id);
    }

    /** The summary sub-slot of a phase ({@code phase.<id>.summary}). */
    public static ModelSlot summary(String phaseId) {
        return ModelSlot.of(phase(phaseId).getName() + ".summary");
    }

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
