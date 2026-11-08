package com.aresstack.askai.research.agent;

/**
 * The typed result of the explicit "Fragestellung freigeben & weiter" action. It exists so a rejected click is
 * NEVER a silent no-op: every non-{@link #SUCCESS} value carries a concrete reason the UI can surface and the
 * diagnostics can log. The order of evaluation mirrors the gate in
 * {@link ResearchAgentSession#approveScopingBriefAndContinue()}.
 */
public enum ScopingApprovalOutcome {

    /** The brief was approved and exactly one {@code SUBMIT_SCOPE} advanced SCOPING → OUTLINE. */
    SUCCESS,

    /** No non-blank research brief exists yet, so there is nothing to approve. */
    MISSING_BRIEF,

    /** The concept has no first-level card or the mission is empty (PR 07); the reason names which. */
    CONCEPT_INCOMPLETE,

    /** A foreground agent turn is in flight; the transition must wait until it terminates. */
    BUSY,

    /** The active phase is not SCOPING (or the state machine does not allow {@code SUBMIT_SCOPE}). */
    WRONG_PHASE,

    /** The artifact approval (persisting the immutable revision) failed; the phase stayed untouched. */
    APPROVAL_FAILED,

    /** The brief was approved, but the state machine rejected the {@code SUBMIT_SCOPE} transition. */
    TRANSITION_REJECTED,

    /** The session is disposed, closed or not started — no live command authority to reach. */
    SESSION_INACTIVE;

    public boolean isSuccess() {
        return this == SUCCESS;
    }
}
