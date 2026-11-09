package com.aresstack.askai.research.runtime.writer;

/**
 * One numbered passage of a section's evidence pack (plan D8, PR 27): the local number {@code [n]} the writer
 * cites, the source it comes from with that source's checksum, the passage id as a derived link, and the text
 * exactly as it was cut for the pack. Immutable.
 */
public final class EvidencePassage {

    private final int number;
    private final String sourceId;
    private final String sourceChecksum;
    private final String passageId;
    private final String text;

    public EvidencePassage(int number, String sourceId, String sourceChecksum, String passageId, String text) {
        if (number < 1) {
            throw new IllegalArgumentException("number must be 1 or more");
        }
        this.number = number;
        this.sourceId = sourceId == null ? "" : sourceId;
        this.sourceChecksum = sourceChecksum == null ? "" : sourceChecksum;
        this.passageId = passageId == null ? "" : passageId;
        this.text = text == null ? "" : text;
    }

    public int getNumber() {
        return number;
    }

    public String getSourceId() {
        return sourceId;
    }

    public String getSourceChecksum() {
        return sourceChecksum;
    }

    public String getPassageId() {
        return passageId;
    }

    public String getText() {
        return text;
    }
}
