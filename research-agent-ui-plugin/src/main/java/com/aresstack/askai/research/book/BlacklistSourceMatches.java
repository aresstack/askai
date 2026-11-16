package com.aresstack.askai.research.book;

import com.aresstack.askai.research.domain.scope.TypedBlacklist;
import com.aresstack.askai.research.sources.ResearchSourceRecord;
import com.aresstack.askai.research.sources.SourceEligibility;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PR 12 (D4, Q2): which ACCEPTED sources a typed blacklist hits — the same term and domain filter
 * the search uses, over title, URL and, where present, the stored search excerpt. It only reports:
 * no source is excluded or changed here.
 */
public final class BlacklistSourceMatches {

    private BlacklistSourceMatches() {
    }

    /** The accepted sources the list hits, in repository order. */
    public static List<ResearchSourceRecord> accepted(TypedBlacklist blacklist,
                                                      Collection<ResearchSourceRecord> records) {
        List<ResearchSourceRecord> hits = new ArrayList<ResearchSourceRecord>();
        if (blacklist == null || blacklist.isEmpty() || records == null) {
            return hits;
        }
        for (ResearchSourceRecord record : records) {
            if (SourceEligibility.isResearchSource(record)
                    && blacklist.matches(record.getTitle(), record.getExcerpt(), record.getUrl())) {
                hits.add(record);
            }
        }
        return hits;
    }

    /**
     * The accepted sources the {@code next} record hits that the {@code previous} one did not, by
     * source id — the sources a re-submit newly keeps out of outline generation and evidence packs.
     */
    public static List<ResearchSourceRecord> newlyHit(TypedBlacklist previous, TypedBlacklist next,
                                                      Collection<ResearchSourceRecord> records) {
        Map<String, ResearchSourceRecord> before = new LinkedHashMap<String, ResearchSourceRecord>();
        for (ResearchSourceRecord record : accepted(previous, records)) {
            before.put(record.getSourceId(), record);
        }
        List<ResearchSourceRecord> fresh = new ArrayList<ResearchSourceRecord>();
        for (ResearchSourceRecord record : accepted(next, records)) {
            if (!before.containsKey(record.getSourceId())) {
                fresh.add(record);
            }
        }
        return fresh;
    }

    /** "Title (url)" for a list line. */
    public static String describe(ResearchSourceRecord record) {
        String title = record.getTitle() == null || record.getTitle().trim().isEmpty()
                ? record.getSourceId() : record.getTitle().trim();
        String url = record.getUrl() == null ? "" : record.getUrl().trim();
        return url.isEmpty() ? title : title + " (" + url + ")";
    }
}
