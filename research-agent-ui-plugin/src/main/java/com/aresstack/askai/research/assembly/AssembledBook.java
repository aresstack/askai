package com.aresstack.askai.research.assembly;

/** The result of one assembly (plan PR 30): the Markdown of {@code document.md}, its citations and its report. */
public final class AssembledBook {

    private final String markdown;
    private final CitationRegistry citations;
    private final BookQualityReport report;

    AssembledBook(String markdown, CitationRegistry citations, BookQualityReport report) {
        this.markdown = markdown;
        this.citations = citations;
        this.report = report;
    }

    public String getMarkdown() {
        return markdown;
    }

    public CitationRegistry getCitations() {
        return citations;
    }

    public BookQualityReport getReport() {
        return report;
    }
}
