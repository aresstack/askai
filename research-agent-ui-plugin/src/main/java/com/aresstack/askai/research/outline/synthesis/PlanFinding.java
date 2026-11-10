package com.aresstack.askai.research.outline.synthesis;

/**
 * Phase 3c: one finding of the outline review. A BLOCKING finding sends the plan back to the model while revision
 * rounds remain; a WARNING is only reported. The message is one English sentence the model and the user can read.
 * Immutable.
 */
public final class PlanFinding {

    public enum Severity { BLOCKING, WARNING }

    private final Severity severity;
    private final String code;
    private final String message;

    public PlanFinding(Severity severity, String code, String message) {
        this.severity = severity == null ? Severity.WARNING : severity;
        this.code = code == null ? "" : code;
        this.message = message == null ? "" : message.trim();
    }

    public static PlanFinding blocking(String code, String message) {
        return new PlanFinding(Severity.BLOCKING, code, message);
    }

    public static PlanFinding warning(String code, String message) {
        return new PlanFinding(Severity.WARNING, code, message);
    }

    public Severity getSeverity() {
        return severity;
    }

    public boolean isBlocking() {
        return severity == Severity.BLOCKING;
    }

    /** A short machine code ("uncovered-topic", "shared-material", ...). */
    public String getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    @Override
    public String toString() {
        return severity + " " + code + ": " + message;
    }
}
