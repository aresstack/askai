package com.aresstack.askai.research.outline.brief;

/** A label call's answer: a short title and a one-sentence summary. */
public final class OutlineLabel {

    private final String title;
    private final String summary;

    public OutlineLabel(String title, String summary) {
        this.title = title == null ? "" : title.trim();
        this.summary = summary == null ? "" : summary.trim();
    }

    public String getTitle() {
        return title;
    }

    public String getSummary() {
        return summary;
    }
}
