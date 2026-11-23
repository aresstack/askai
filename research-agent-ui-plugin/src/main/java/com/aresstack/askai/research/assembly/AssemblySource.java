package com.aresstack.askai.research.assembly;

/**
 * One source as the book assembler sees it (plan PR 30): the reference fields (title, site, URL, access date), the
 * stored checksum and the write-once text a citation is checked against, and whether it was accepted after the
 * Sources approval. Built from {@code FileResearchSourceRepository} by {@link BookAssemblyInputs}.
 */
public final class AssemblySource {

    private final String sourceId;
    private final String title;
    private final String site;
    private final String url;
    private final long accessedAt;
    private final String checksum;
    private final String text;
    private final boolean acceptedAfterApproval;

    public AssemblySource(String sourceId, String title, String site, String url, long accessedAt, String checksum,
                          String text, boolean acceptedAfterApproval) {
        if (sourceId == null || sourceId.trim().isEmpty()) {
            throw new IllegalArgumentException("sourceId must not be empty");
        }
        this.sourceId = sourceId.trim();
        this.title = title == null ? "" : title.trim();
        this.site = site == null ? "" : site.trim();
        this.url = url == null ? "" : url.trim();
        this.accessedAt = accessedAt;
        this.checksum = checksum == null ? "" : checksum.trim();
        this.text = text == null ? "" : text;
        this.acceptedAfterApproval = acceptedAfterApproval;
    }

    public String getSourceId() {
        return sourceId;
    }

    public String getTitle() {
        return title;
    }

    public String getSite() {
        return site;
    }

    public String getUrl() {
        return url;
    }

    /** Capture time in epoch milliseconds, printed as the access date; 0 when unknown. */
    public long getAccessedAt() {
        return accessedAt;
    }

    public String getChecksum() {
        return checksum;
    }

    /** The stored source text a citation's cited text must occur in. */
    public String getText() {
        return text;
    }

    /** Whether the source joined the pool after the Sources approval (marked in the references). */
    public boolean isAcceptedAfterApproval() {
        return acceptedAfterApproval;
    }
}
