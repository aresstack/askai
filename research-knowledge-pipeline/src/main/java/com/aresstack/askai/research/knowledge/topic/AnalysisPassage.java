package com.aresstack.askai.research.knowledge.topic;

/**
 * Phase 3: one stored passage as the topic analysis reads it: its source and that source's web domain (for source
 * diversity) and its text. The vector comes separately (stored, never embedded again). Immutable.
 */
public final class AnalysisPassage {

    private final String passageId;
    private final String sourceId;
    private final String domain;
    private final String text;

    /**
     * @param domain the source's host ("" when unknown; the source id then stands for its domain)
     */
    public AnalysisPassage(String passageId, String sourceId, String domain, String text) {
        this.passageId = passageId == null ? "" : passageId;
        this.sourceId = sourceId == null ? "" : sourceId;
        this.domain = domain == null || domain.trim().isEmpty() ? this.sourceId : domain.trim().toLowerCase(
                java.util.Locale.ROOT);
        this.text = text == null ? "" : text;
    }

    public String getPassageId() {
        return passageId;
    }

    public String getSourceId() {
        return sourceId;
    }

    public String getDomain() {
        return domain;
    }

    public String getText() {
        return text;
    }
}
