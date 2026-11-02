package com.aresstack.askai.java8.ui.setup;

/**
 * One item of the setup wizard's final check: what was checked, whether it works, and why not.
 *
 * <p>Ported from the Enterprise AI Client's setup wizard ({@code exedra.ui.setup.SetupCheck}); an optional item
 * that was left out (no reranker, no voice) is reported as {@link #ok(String, String) ok} with a detail saying so,
 * because optional capabilities never block the basic setup.</p>
 */
public final class SetupCheck {

    private final String label;
    private final boolean ok;
    private final String detail;

    private SetupCheck(String label, boolean ok, String detail) {
        if (label == null || label.trim().isEmpty()) {
            throw new IllegalArgumentException("label must not be empty");
        }
        this.label = label;
        this.ok = ok;
        this.detail = detail == null ? "" : detail;
    }

    public static SetupCheck ok(String label, String detail) {
        return new SetupCheck(label, true, detail);
    }

    public static SetupCheck failed(String label, String detail) {
        return new SetupCheck(label, false, detail);
    }

    public String label() {
        return label;
    }

    public boolean isOk() {
        return ok;
    }

    public String detail() {
        return detail;
    }

    @Override
    public String toString() {
        return (ok ? "OK " : "FAILED ") + label + (detail.isEmpty() ? "" : ": " + detail);
    }
}
