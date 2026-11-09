package com.aresstack.askai.research.acceptance;

/**
 * One miss of the data-integrity level of the first-book acceptance run (plan PR 32): what was checked, which record
 * it concerns and why it failed. Immutable.
 */
public final class IntegrityFinding {

    /** The integrity checks of PR 32, in report order. */
    public enum Check {
        PIN_HASH("Pin matches its content hash"),
        CITATION_SOURCE("Citation resolves to source and checksum"),
        CITATION_TEXT("Cited text is found in its source"),
        CITATION_MARKER("Every [n] resolves to a citation"),
        REFERENCE("Every [n] in document.md resolves to a reference"),
        TEXT_ENCODING("Umlauts and typographic quotes are intact");

        private final String label;

        Check(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private final Check check;
    private final String subject;
    private final String message;

    public IntegrityFinding(Check check, String subject, String message) {
        if (check == null) {
            throw new IllegalArgumentException("a finding needs its check");
        }
        this.check = check;
        this.subject = subject == null ? "" : subject;
        this.message = message == null ? "" : message;
    }

    public Check getCheck() {
        return check;
    }

    public String getSubject() {
        return subject;
    }

    public String getMessage() {
        return message;
    }

    @Override
    public String toString() {
        return check + " " + subject + ": " + message;
    }
}
