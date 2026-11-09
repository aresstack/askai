package com.aresstack.askai.research.outline.brief;

/** One label or brief call as telemetry sees it: slot, kind, the budget it ran with and whether it passed. */
public final class BriefCallTelemetry {

    /** What a call produced. */
    public enum Kind { LABEL, BRIEF }

    private final String slot;
    private final Kind kind;
    private final String target;
    private final int maxOutputTokens;
    private final int attempt;
    private final boolean accepted;

    public BriefCallTelemetry(String slot, Kind kind, String target, int maxOutputTokens, int attempt,
                              boolean accepted) {
        this.slot = slot;
        this.kind = kind;
        this.target = target;
        this.maxOutputTokens = maxOutputTokens;
        this.attempt = attempt;
        this.accepted = accepted;
    }

    public String getSlot() {
        return slot;
    }

    public Kind getKind() {
        return kind;
    }

    /** The chapter or section id (or the candidate cluster id) the call was for. */
    public String getTarget() {
        return target;
    }

    public int getMaxOutputTokens() {
        return maxOutputTokens;
    }

    /** 0 for the first call, 1 for the repair. */
    public int getAttempt() {
        return attempt;
    }

    public boolean isAccepted() {
        return accepted;
    }

    @Override
    public String toString() {
        return kind + " " + target + " on " + slot + " budget " + maxOutputTokens + " attempt " + attempt
                + (accepted ? " accepted" : " refused");
    }
}
