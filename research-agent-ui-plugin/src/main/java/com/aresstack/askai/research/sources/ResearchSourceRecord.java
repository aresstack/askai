package com.aresstack.askai.research.sources;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * An immutable structured research source. Sources are NOT Markdown artifacts: they are records with a stable
 * id and a monotonic revision, managed through {@link ResearchSourceRepository}. Chapter links use stable
 * section ids derived from the outline.
 */
public final class ResearchSourceRecord {

    private final String sourceId;
    private final String title;
    private final String origin;
    private final String url;
    private final String sourceType;
    private final long capturedAt;
    private final String author;
    private final List<String> linkedSectionIds;
    private final String comment;
    private final SourceRelevance relevance;
    private final SourceReliability reliability;
    private final SourceStatus status;
    private final String snapshotReference;
    private final String checksum;
    private final long revision;
    /** The user web-search query that found this source (empty for agent-accepted sources). */
    private final String searchQuery;
    /** The manual-search REQUEST id that found this source ("" = agent path / legacy record). */
    private final String searchRequestId;
    /** The short excerpt/snippet taken from the search results (before the page was visited). */
    private final String excerpt;
    /** The full readable page text, filled ONLY after the page was successfully visited; empty = parked. */
    private final String fullText;
    /** The reranker relevance score for the search query, or {@code NaN} when unknown/not reranked. */
    private final double rerankScore;
    /**
     * The USER marked this source as relevant (the HUD ⭐ toggle) — a reversible signal DISTINCT from the model's
     * {@link #relevance} assessment and the {@link #rerankScore}. Fed to the Continuous Knowledge Processing
     * corpus (C5). Defaults to false.
     */
    private final boolean userRelevant;
    /** Plan PR 14: the research phase whose search found this source ("" = legacy record). */
    private final String phaseTag;
    /** Plan PR 14: the search plan entry whose query found this source ("" = typed search / agent path). */
    private final String planEntryId;

    private ResearchSourceRecord(Builder b) {
        this.sourceId = b.sourceId;
        this.title = str(b.title);
        this.origin = str(b.origin);
        this.url = str(b.url);
        this.sourceType = str(b.sourceType);
        this.capturedAt = b.capturedAt;
        this.author = str(b.author);
        this.linkedSectionIds = Collections.unmodifiableList(new ArrayList<String>(b.linkedSectionIds));
        this.comment = str(b.comment);
        this.relevance = b.relevance == null ? SourceRelevance.UNKNOWN : b.relevance;
        this.reliability = b.reliability == null ? SourceReliability.UNKNOWN : b.reliability;
        this.status = b.status == null ? SourceStatus.NEW : b.status;
        this.snapshotReference = str(b.snapshotReference);
        this.checksum = str(b.checksum);
        this.revision = b.revision;
        this.searchQuery = str(b.searchQuery);
        this.searchRequestId = str(b.searchRequestId);
        this.excerpt = str(b.excerpt);
        this.fullText = str(b.fullText);
        this.rerankScore = b.rerankScore;
        this.userRelevant = b.userRelevant;
        this.phaseTag = str(b.phaseTag);
        this.planEntryId = str(b.planEntryId);
    }

    private static String str(String v) {
        return v == null ? "" : v;
    }

    public String getSourceId() {
        return sourceId;
    }

    public String getTitle() {
        return title;
    }

    public String getOrigin() {
        return origin;
    }

    public String getUrl() {
        return url;
    }

    public String getSourceType() {
        return sourceType;
    }

    public long getCapturedAt() {
        return capturedAt;
    }

    public String getAuthor() {
        return author;
    }

    public List<String> getLinkedSectionIds() {
        return linkedSectionIds;
    }

    public String getComment() {
        return comment;
    }

    public SourceRelevance getRelevance() {
        return relevance;
    }

    public SourceReliability getReliability() {
        return reliability;
    }

    public SourceStatus getStatus() {
        return status;
    }

    public String getSnapshotReference() {
        return snapshotReference;
    }

    public String getChecksum() {
        return checksum;
    }

    public long getRevision() {
        return revision;
    }

    /** The user web-search query that found this source, or "" when it was accepted by the agent. */
    public String getSearchQuery() {
        return searchQuery;
    }

    /** The manual-search request id that found this source ("" = agent path / legacy record). */
    public String getSearchRequestId() {
        return searchRequestId;
    }

    /** The search-result excerpt/snippet, or "" when none was captured. */
    public String getExcerpt() {
        return excerpt;
    }

    /** The full readable page text, or "" when the page has not been (successfully) read yet — "parked". */
    public String getFullText() {
        return fullText;
    }

    /** True when this source is parked: it has a score/excerpt but the page text was never read. */
    public boolean isParked() {
        return fullText.isEmpty();
    }

    /** The reranker relevance score for the search query, or {@code NaN} when unknown. */
    public double getRerankScore() {
        return rerankScore;
    }

    /** True when a reranker score is present (not {@code NaN}). */
    public boolean hasRerankScore() {
        return !Double.isNaN(rerankScore);
    }

    /** True when the USER marked this source relevant via the HUD ⭐ toggle (reversible; distinct from relevance). */
    public boolean isUserRelevant() {
        return userRelevant;
    }

    /** The research phase whose search found this source ("" for a legacy record). */
    public String getPhaseTag() {
        return phaseTag;
    }

    /** The search plan entry whose query found this source ("" for a typed search or the agent path). */
    public String getPlanEntryId() {
        return planEntryId;
    }

    public Builder toBuilder() {
        return new Builder(sourceId)
                .title(title).origin(origin).url(url).sourceType(sourceType).capturedAt(capturedAt)
                .author(author).linkedSectionIds(linkedSectionIds).comment(comment).relevance(relevance)
                .reliability(reliability).status(status).snapshotReference(snapshotReference)
                .checksum(checksum).revision(revision).searchQuery(searchQuery)
                .searchRequestId(searchRequestId)
                .excerpt(excerpt).fullText(fullText).rerankScore(rerankScore).userRelevant(userRelevant)
                .phaseTag(phaseTag).planEntryId(planEntryId);
    }

    public static Builder builder(String sourceId) {
        return new Builder(sourceId);
    }

    public static final class Builder {
        private final String sourceId;
        private String title;
        private String origin;
        private String url;
        private String sourceType;
        private long capturedAt;
        private String author;
        private List<String> linkedSectionIds = new ArrayList<String>();
        private String comment;
        private SourceRelevance relevance;
        private SourceReliability reliability;
        private SourceStatus status;
        private String snapshotReference;
        private String checksum;
        private long revision;
        private String searchQuery;
        private String searchRequestId;
        private String excerpt;
        private String fullText;
        private double rerankScore = Double.NaN;
        private boolean userRelevant;
        private String phaseTag;
        private String planEntryId;

        private Builder(String sourceId) {
            if (sourceId == null || sourceId.trim().isEmpty()) {
                throw new IllegalArgumentException("sourceId must not be empty");
            }
            this.sourceId = sourceId;
        }

        public Builder title(String v) { this.title = v; return this; }
        public Builder origin(String v) { this.origin = v; return this; }
        public Builder url(String v) { this.url = v; return this; }
        public Builder sourceType(String v) { this.sourceType = v; return this; }
        public Builder capturedAt(long v) { this.capturedAt = v; return this; }
        public Builder author(String v) { this.author = v; return this; }

        public Builder linkedSectionIds(List<String> v) {
            this.linkedSectionIds = new ArrayList<String>(v == null ? new ArrayList<String>() : v);
            return this;
        }

        public Builder comment(String v) { this.comment = v; return this; }
        public Builder relevance(SourceRelevance v) { this.relevance = v; return this; }
        public Builder reliability(SourceReliability v) { this.reliability = v; return this; }
        public Builder status(SourceStatus v) { this.status = v; return this; }
        public Builder snapshotReference(String v) { this.snapshotReference = v; return this; }
        public Builder checksum(String v) { this.checksum = v; return this; }
        public Builder revision(long v) { this.revision = v; return this; }
        public Builder searchQuery(String v) { this.searchQuery = v; return this; }
        public Builder searchRequestId(String v) { this.searchRequestId = v; return this; }
        public Builder excerpt(String v) { this.excerpt = v; return this; }
        public Builder fullText(String v) { this.fullText = v; return this; }
        public Builder rerankScore(double v) { this.rerankScore = v; return this; }
        public Builder userRelevant(boolean v) { this.userRelevant = v; return this; }
        public Builder phaseTag(String v) { this.phaseTag = v; return this; }
        public Builder planEntryId(String v) { this.planEntryId = v; return this; }

        public ResearchSourceRecord build() {
            return new ResearchSourceRecord(this);
        }
    }
}
