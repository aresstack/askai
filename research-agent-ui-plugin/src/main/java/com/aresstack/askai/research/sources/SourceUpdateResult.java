package com.aresstack.askai.research.sources;

/**
 * Outcome of a source update: success with the new record, a revision conflict carrying the current record
 * (no silent overwrite), or not-found. Optimistic locking mirrors the Markdown artifact store.
 */
public final class SourceUpdateResult {

    public enum Status {
        UPDATED,
        CONFLICT,
        NOT_FOUND,
        /** The store could not write the update (I/O error); nothing changed. */
        FAILED
    }

    private final Status status;
    private final ResearchSourceRecord record;
    private final String reason;

    private SourceUpdateResult(Status status, ResearchSourceRecord record, String reason) {
        this.status = status;
        this.record = record;
        this.reason = reason == null ? "" : reason;
    }

    public static SourceUpdateResult updated(ResearchSourceRecord record) {
        return new SourceUpdateResult(Status.UPDATED, record, "");
    }

    public static SourceUpdateResult conflict(ResearchSourceRecord current) {
        return new SourceUpdateResult(Status.CONFLICT, current, "The source changed since it was loaded.");
    }

    public static SourceUpdateResult notFound(String sourceId) {
        return new SourceUpdateResult(Status.NOT_FOUND, null, "Unknown source: " + sourceId);
    }

    public static SourceUpdateResult failed(ResearchSourceRecord current, String reason) {
        return new SourceUpdateResult(Status.FAILED, current, "Could not save the source: " + reason);
    }

    public Status getStatus() {
        return status;
    }

    public boolean isSuccess() {
        return status == Status.UPDATED;
    }

    /** The new record on success, or the current record on conflict, or {@code null} when not found. */
    public ResearchSourceRecord getRecord() {
        return record;
    }

    public String getReason() {
        return reason;
    }
}
