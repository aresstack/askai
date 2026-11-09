package com.aresstack.askai.research.knowledge.processing.acronym;

import com.aresstack.askai.research.domain.SourceCapture;

/**
 * The write-once text of one accepted source that acronym findings point into: the capture's structural blocks in
 * reading order, joined by a blank line. Spans of {@link SourceAcronymRecord} are character offsets into exactly
 * this string, so a re-embedding (new passage ids, new passage boundaries) never invalidates them.
 */
public final class SourceText {

    static final String BLOCK_SEPARATOR = "\n\n";

    private final String sourceId;
    private final String checksum;
    private final String text;

    public SourceText(String sourceId, String checksum, String text) {
        if (sourceId == null || sourceId.trim().isEmpty()) {
            throw new IllegalArgumentException("sourceId must not be blank");
        }
        this.sourceId = sourceId;
        this.checksum = checksum == null ? "" : checksum;
        this.text = text == null ? "" : text;
    }

    /** The write-once text of a capture. */
    public static SourceText of(SourceCapture capture) {
        StringBuilder text = new StringBuilder();
        for (SourceCapture.StructuralBlock block : capture.getBlocks()) {
            String blockText = block.getText();
            if (blockText == null || blockText.isEmpty()) {
                continue;
            }
            if (text.length() > 0) {
                text.append(BLOCK_SEPARATOR);
            }
            text.append(blockText);
        }
        return new SourceText(capture.getSourceId(), capture.getChecksum(), text.toString());
    }

    public String sourceId() {
        return sourceId;
    }

    public String checksum() {
        return checksum;
    }

    public String text() {
        return text;
    }
}
