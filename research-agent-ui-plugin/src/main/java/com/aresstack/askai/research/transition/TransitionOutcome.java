package com.aresstack.askai.research.transition;

import com.aresstack.askai.research.state.oo.ResearchStateMemento;

/** The result of one {@link TransitionEngine#dispatch}: the committed transition, or why nothing changed. */
public final class TransitionOutcome {

    public enum Kind {
        COMMITTED,
        /** The session is blocked by an unreadable project file. */
        BLOCKED,
        /** The actor policy refused the command (D2, Q13). */
        ACTOR_REFUSED,
        /** The sender saw an outdated revision (D3). */
        OUTDATED,
        /** The state machine has no edge for the command in the current state. */
        INVALID_STATE,
        /** A transition hook refused, or its record could not be written. */
        HOOK_REFUSED,
        /** The journal or the memento could not be written; the previous state stays active. */
        NOT_PERSISTED
    }

    private final Kind kind;
    private final String reason;
    private final PhaseTransition transition;
    private final ResearchStateMemento state;

    private TransitionOutcome(Kind kind, String reason, PhaseTransition transition,
                              ResearchStateMemento state) {
        this.kind = kind;
        this.reason = reason == null ? "" : reason;
        this.transition = transition;
        this.state = state;
    }

    static TransitionOutcome committed(PhaseTransition transition) {
        return new TransitionOutcome(Kind.COMMITTED, "", transition, transition.getTo());
    }

    static TransitionOutcome refused(Kind kind, String reason, ResearchStateMemento unchanged) {
        return new TransitionOutcome(kind, reason, null, unchanged);
    }

    public boolean isCommitted() {
        return kind == Kind.COMMITTED;
    }

    public Kind getKind() {
        return kind;
    }

    public String getReason() {
        return reason;
    }

    /** The committed transition, or {@code null}. */
    public PhaseTransition getTransition() {
        return transition;
    }

    /** The state after the call (the new state, or the unchanged one). */
    public ResearchStateMemento getState() {
        return state;
    }
}
