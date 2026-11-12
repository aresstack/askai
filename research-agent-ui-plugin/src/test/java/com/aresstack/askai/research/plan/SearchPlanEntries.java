package com.aresstack.askai.research.plan;

/** Plan entries in a given status, for tests outside this package (the transitions are package-private). */
public final class SearchPlanEntries {

    private static final String PHASE = "research";
    private static final long AT = 1L;

    private SearchPlanEntries() {
    }

    public static SearchPlanEntry pending(String id, String nodeLabel, String query) {
        return SearchPlanEntry.submit(id, PHASE, "node-" + id, nodeLabel, query, SearchPlanEntry.Origin.BASE, AT);
    }

    public static SearchPlanEntry running(String id, String nodeLabel, String query) {
        return pending(id, nodeLabel, query).started("request-" + id, AT);
    }

    public static SearchPlanEntry done(String id, String nodeLabel, String query, String... sourceIds) {
        SearchPlanEntry entry = running(id, nodeLabel, query);
        for (String sourceId : sourceIds) {
            entry = entry.withSource(sourceId, AT);
        }
        return entry.done(AT);
    }

    public static SearchPlanEntry failed(String id, String nodeLabel, String query, String reason) {
        SearchPlanEntry entry = running(id, nodeLabel, query).failedRun(reason, AT);
        while (entry.getStatus() == SearchPlanEntry.Status.PENDING) { // a failed run retries until none is left
            entry = entry.started("request-" + id, AT).failedRun(reason, AT);
        }
        return entry;
    }

    /** A writer gap's entry of the Document phase, done, with sources accepted at {@code atMillis}. */
    public static SearchPlanEntry gap(String id, String query, long atMillis, String... sourceIds) {
        SearchPlanEntry entry = SearchPlanEntry.submit(id, "draft", "", "Section", query,
                SearchPlanEntry.Origin.PRIORITY, AT).started("request-" + id, AT);
        for (String sourceId : sourceIds) {
            entry = entry.withSource(sourceId, atMillis);
        }
        return entry.done(atMillis);
    }
}
