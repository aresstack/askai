package com.aresstack.askai.research.mcp;

import com.aresstack.askai.research.sources.ResearchSourceRecord;
import com.aresstack.askai.research.sources.SourceStatus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The bounded state block of the sources (plan PR 16): counts per source status and the ten newest titles,
 * instead of every source. Its size does not grow with the number of sources.
 */
public final class SourceOverview {

    /** How many of the newest titles the block names. */
    public static final int NEWEST_TITLES = 10;
    /** A longer title is cut at this many characters. */
    public static final int MAX_TITLE_CHARS = 56;

    private SourceOverview() {
    }

    /** {@code "No sources."} for an empty list, otherwise the counts and the newest titles. */
    public static String render(List<ResearchSourceRecord> sources) {
        if (sources == null || sources.isEmpty()) {
            return "No sources.";
        }
        Map<SourceStatus, Integer> counts = new EnumMap<SourceStatus, Integer>(SourceStatus.class);
        for (ResearchSourceRecord source : sources) {
            Integer previous = counts.get(source.getStatus());
            counts.put(source.getStatus(), previous == null ? 1 : previous + 1);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Sources: ").append(sources.size()).append(" total");
        for (Map.Entry<SourceStatus, Integer> count : counts.entrySet()) {
            sb.append(", ").append(count.getValue()).append(' ').append(count.getKey().name());
        }
        sb.append('\n');
        List<ResearchSourceRecord> newest = new ArrayList<ResearchSourceRecord>(sources);
        Collections.sort(newest, new Comparator<ResearchSourceRecord>() {
            public int compare(ResearchSourceRecord a, ResearchSourceRecord b) {
                return Long.compare(b.getCapturedAt(), a.getCapturedAt());
            }
        });
        int shown = Math.min(NEWEST_TITLES, newest.size());
        sb.append("Newest ").append(shown).append(":\n");
        for (int i = 0; i < shown; i++) {
            ResearchSourceRecord source = newest.get(i);
            sb.append("- ").append(source.getSourceId()).append(": ").append(title(source.getTitle()))
              .append(" [").append(source.getStatus().name()).append("]\n");
        }
        return sb.toString();
    }

    private static String title(String title) {
        String flat = title == null ? "" : title.replace('\r', ' ').replace('\n', ' ').trim();
        if (flat.isEmpty()) {
            return "(untitled)";
        }
        return flat.length() <= MAX_TITLE_CHARS ? flat : flat.substring(0, MAX_TITLE_CHARS - 1) + "…";
    }
}
