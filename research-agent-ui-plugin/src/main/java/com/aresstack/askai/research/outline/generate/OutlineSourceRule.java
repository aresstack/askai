package com.aresstack.askai.research.outline.generate;

import com.aresstack.askai.research.domain.scope.TypedBlacklist;
import com.aresstack.askai.research.sources.ResearchSourceRecord;
import com.aresstack.askai.research.sources.SourceEligibility;
import com.aresstack.askai.research.sources.SourceStatus;

import java.util.Collection;
import java.util.Set;
import java.util.TreeSet;

/**
 * Plan PR 22 (D4): the sources the outline is built from: the approved source set plus sources accepted later,
 * minus excluded sources and minus sources hit by the blacklist recorded at {@code SUBMIT_SCOPE}. Entries added to
 * the blacklist after {@code SUBMIT_SCOPE} only filter future searches and never remove a source here (Q2).
 */
public final class OutlineSourceRule {

    private OutlineSourceRule() {
    }

    /**
     * @param pinnedSourceIds    the source ids of the approved source set (the sources pin), empty before one
     * @param records            every source record
     * @param recordedBlacklist  the blacklist of the Phase-1 record
     */
    public static Set<String> eligible(Collection<String> pinnedSourceIds, Collection<ResearchSourceRecord> records,
                                       TypedBlacklist recordedBlacklist) {
        Set<String> pinned = new TreeSet<String>(pinnedSourceIds);
        Set<String> eligible = new TreeSet<String>();
        for (ResearchSourceRecord record : records) {
            SourceStatus status = record.getStatus();
            boolean inSet = SourceEligibility.isResearchSource(record)
                    || (pinned.contains(record.getSourceId()) && status != SourceStatus.EXCLUDED
                            && status != SourceStatus.DUPLICATE && status != SourceStatus.SUPERSEDED);
            if (!inSet) {
                continue;
            }
            if (recordedBlacklist != null && !recordedBlacklist.isEmpty()
                    && recordedBlacklist.matches(record.getTitle(), record.getExcerpt(), record.getUrl())) {
                continue;
            }
            eligible.add(record.getSourceId());
        }
        return eligible;
    }
}
