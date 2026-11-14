package com.aresstack.askai.research.runtime.team;

/**
 * The typed outcome of one main-model chat call. Success carries the assistant's raw text; every failure is
 * NAMED (transport, timeout, HTTP, empty answer) and never fabricated as a success — the TeamAgent turns a
 * non-OK result into an honest {@code MODEL_UNAVAILABLE} status for the user, offering a retry.
 */
public final class MainModelChatResult {

    public enum Status {
        OK,
        TIMEOUT,
        PROVIDER_FAILURE,
        INVALID_RESPONSE
    }

    private final Status status;
    private final String text;
    private final String detail;
    private final boolean truncated;

    private MainModelChatResult(Status status, String text, String detail, boolean truncated) {
        this.status = status;
        this.text = text == null ? "" : text;
        this.detail = detail == null ? "" : detail;
        this.truncated = truncated;
    }

    public static MainModelChatResult ok(String text) {
        return new MainModelChatResult(Status.OK, text, "", false);
    }

    /**
     * The model answered, but the provider ended the answer at the OUTPUT TOKEN LIMIT (finish reason
     * {@code length}): the text is whatever fitted the budget and may stop mid-sentence or mid-JSON. Still
     * {@link Status#OK} — the call itself worked — but {@link #isTruncated()} tells the caller the answer is
     * incomplete, so a cut-off answer is never mistaken for a finished one.
     */
    public static MainModelChatResult okTruncated(String text) {
        return new MainModelChatResult(Status.OK, text, "", true);
    }

    public static MainModelChatResult failure(Status status, String detail) {
        return new MainModelChatResult(status, "", detail, false);
    }

    /** True when the provider cut the answer at the output token limit; the text is then incomplete. */
    public boolean isTruncated() {
        return truncated;
    }

    public Status getStatus() {
        return status;
    }

    public boolean isOk() {
        return status == Status.OK;
    }

    /** The assistant's raw text (empty on any failure). */
    public String getText() {
        return text;
    }

    /** A human-readable, secret-free reason for a non-OK status. */
    public String getDetail() {
        return detail;
    }
}
