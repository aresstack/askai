package com.aresstack.askai.research.backend;

/**
 * Structured outcome of a UI-triggered research command. Rejections are DISTINGUISHABLE — the UI shows a
 * domain message; nothing is silently ignored and nothing falls back to a synthetic chat message.
 */
public final class ResearchCommandDispatchResult {

    public enum Status {
        ACCEPTED,
        /** No such command exists for this session (null/unknown). */
        COMMAND_NOT_AVAILABLE,
        /** The command exists but is not allowed in the current phase/run state (state machine says no). */
        INVALID_PHASE,
        /** The session has not been activated yet. */
        SESSION_NOT_ACTIVE,
        /** The session (or its generation's resources) is closed; the command reaches nothing. */
        SESSION_CLOSED,
        /** Refused before the state machine: actor policy, outdated revision, a hook, or a blocked project. */
        REFUSED,
        /** The state machine accepted responsibility but the dispatch itself failed. */
        DISPATCH_FAILED
    }

    private final Status status;
    private final String detail;

    private ResearchCommandDispatchResult(Status status, String detail) {
        this.status = status;
        this.detail = detail == null ? "" : detail;
    }

    public static ResearchCommandDispatchResult of(Status status, String detail) {
        return new ResearchCommandDispatchResult(status, detail);
    }

    public static ResearchCommandDispatchResult accepted() {
        return new ResearchCommandDispatchResult(Status.ACCEPTED, "");
    }

    public Status getStatus() {
        return status;
    }

    public String getDetail() {
        return detail;
    }

    public boolean isAccepted() {
        return status == Status.ACCEPTED;
    }
}
