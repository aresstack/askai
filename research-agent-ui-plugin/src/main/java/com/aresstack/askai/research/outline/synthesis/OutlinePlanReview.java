package com.aresstack.askai.research.outline.synthesis;

import java.util.List;

/** Phase 3c: the deterministic review of a plan against its brief (the analysis and the concept requirements). */
public interface OutlinePlanReview {

    /** The findings, blocking ones first; empty for a plan without problems. */
    List<PlanFinding> review(OutlinePlan plan, SynthesisBrief brief);
}
