package com.aresstack.askai.research.runtime.writer;

/**
 * One finding of the citation check (plan D8, stage 1). Findings are flags on the stored revision; the text they
 * point to is never removed. Immutable.
 */
public final class ClaimFlag {

    public enum Kind {
        /** A sentence without any {@code [n]} marker. */
        UNCITED_SENTENCE("uncited sentence"),
        /** A paragraph none of whose sentences cites a passage. */
        UNCITED_PARAGRAPH("uncited paragraph"),
        /** A number, year or percentage that no passage cited by the sentence or its paragraph contains. */
        UNSUPPORTED_NUMBER("unsupported number"),
        /** A quotation that no passage cited by the sentence or its paragraph contains. */
        UNSUPPORTED_QUOTE("unsupported quote");

        private final String label;

        Kind(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private final Kind kind;
    private final int paragraph;
    private final String sentence;
    private final String detail;

    public ClaimFlag(Kind kind, int paragraph, String sentence, String detail) {
        this.kind = kind;
        this.paragraph = paragraph;
        this.sentence = sentence == null ? "" : sentence;
        this.detail = detail == null ? "" : detail;
    }

    public Kind getKind() {
        return kind;
    }

    /** The 0-based paragraph the finding is in. */
    public int getParagraph() {
        return paragraph;
    }

    public String getSentence() {
        return sentence;
    }

    /** The number or quotation that was not found; empty for uncited findings. */
    public String getDetail() {
        return detail;
    }

    /** {@code unsupported number "15 %": <sentence>} — the line of the repair's error list. */
    public String describe() {
        String what = detail.isEmpty() ? kind.label() : kind.label() + " \"" + detail + "\"";
        return what + ": " + sentence;
    }

    @Override
    public String toString() {
        return describe();
    }
}
