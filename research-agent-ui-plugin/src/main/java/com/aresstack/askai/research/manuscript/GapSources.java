package com.aresstack.askai.research.manuscript;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What the research of a section's writer gaps brought in (live run 4, fix 5): the sources its "Research this gap"
 * entries accepted, when the newest of them was accepted, and the queries that found them. A written section whose
 * current revision is older than the newest source did not have them in its evidence pack
 * ({@link ManuscriptWorkspace#markGapsResearched}).
 */
public final class GapSources {

    private final String sectionId;
    private final List<String> sourceIds;
    private final long lastSourceAtMillis;
    private final List<String> queries;

    public GapSources(String sectionId, List<String> sourceIds, long lastSourceAtMillis, List<String> queries) {
        if (sectionId == null || sectionId.trim().isEmpty()) {
            throw new IllegalArgumentException("gap sources need their section");
        }
        this.sectionId = sectionId;
        this.sourceIds = Collections.unmodifiableList(new ArrayList<String>(
                sourceIds == null ? Collections.<String>emptyList() : sourceIds));
        this.lastSourceAtMillis = lastSourceAtMillis;
        this.queries = Collections.unmodifiableList(new ArrayList<String>(
                queries == null ? Collections.<String>emptyList() : queries));
    }

    public String getSectionId() {
        return sectionId;
    }

    /** The accepted sources, each once, in acceptance order. */
    public List<String> getSourceIds() {
        return sourceIds;
    }

    public long getLastSourceAtMillis() {
        return lastSourceAtMillis;
    }

    /** The gap queries that found sources, each once. */
    public List<String> getQueries() {
        return queries;
    }
}
