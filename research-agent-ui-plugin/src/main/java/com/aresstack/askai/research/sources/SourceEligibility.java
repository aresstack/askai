package com.aresstack.askai.research.sources;

/**
 * The one rule for "this source counts": a source the acquisition funnel accepted (readiness judge, relevance,
 * scope gate, reranker) and that was actually read. It is the set the search plan counts for coverage, the set
 * {@code APPROVE_EVIDENCE} pins, and the set the Outline and Document phases read from.
 * <ul>
 * <li>{@link SourceStatus#NEW}, {@link SourceStatus#REVIEWED} and {@link SourceStatus#ACCEPTED} are in — the
 * user's "Übernommen" ({@code ACCEPTED}) is an explicit confirmation, not a precondition.</li>
 * <li>{@link SourceStatus#PARKED} candidates (no page text yet), {@link SourceStatus#EXCLUDED},
 * {@link SourceStatus#DUPLICATE} and {@link SourceStatus#SUPERSEDED} sources are out.</li>
 * <li>A record whose full text is empty counts only when the user confirmed it ({@code ACCEPTED}); a {@code NEW}
 * or {@code REVIEWED} record without text is a parked candidate.</li>
 * </ul>
 * Before this rule the plan counted every non-parked record (duplicates included) while the pin took only
 * {@code ACCEPTED} ones, so an autonomous Sources phase ended with a covered plan and an empty pin.
 */
public final class SourceEligibility {

    private SourceEligibility() {
    }

    /** Whether the status alone keeps a source in the research set. */
    public static boolean isEligibleStatus(SourceStatus status) {
        return status == SourceStatus.NEW || status == SourceStatus.REVIEWED || status == SourceStatus.ACCEPTED;
    }

    /** Whether the record is a read, non-excluded research source. */
    public static boolean isResearchSource(ResearchSourceRecord record) {
        return record != null && isEligibleStatus(record.getStatus())
                && (!record.isParked() || record.getStatus() == SourceStatus.ACCEPTED);
    }

    /**
     * The least page text a source needs to count as evidence for coverage and for the pin. A page with less is a
     * teaser, a listing, a stub or an error page with navigation: it may stay in the Sources list, but it covers
     * no concept node and is not pinned unless the user confirms it ({@code ACCEPTED}).
     */
    public static final int MIN_EVIDENCE_CHARS = 1000;

    /**
     * Whether the record is evidence: a research source whose text is long enough to carry something, or one the
     * user confirmed. The search plan's coverage and the Sources pin count these; GPT's review of 2026-11-09
     * asked that a {@code NEW} record never counts blindly.
     */
    public static boolean isEvidence(ResearchSourceRecord record) {
        if (!isResearchSource(record)) {
            return false;
        }
        return record.getStatus() == SourceStatus.ACCEPTED
                || record.getFullText().trim().length() >= MIN_EVIDENCE_CHARS;
    }
}
