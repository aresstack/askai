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
}
