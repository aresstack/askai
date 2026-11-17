package com.aresstack.askai.research.plan;

import com.aresstack.askai.research.domain.plan.ConceptQueryPlan;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SearchPlanProgressTest {

    private static SearchPlanRecord.Node node(String id) {
        return new SearchPlanRecord.Node(new ConceptQueryPlan.Node(id, id, Collections.singletonList(id), "", id),
                true);
    }

    @Test
    public void countsQueriesSourcesCoverageAndCycleWithoutGroupingSeparators() {
        Map<String, SearchPlanEntry> entries = new LinkedHashMap<String, SearchPlanEntry>();
        entries.put("a", SearchPlanEntries.done("a", "A", "query a", "s1", "s2"));
        entries.put("b", SearchPlanEntries.running("b", "B", "query b"));
        entries.put("c", SearchPlanEntries.pending("c", "C", "query c"));
        SearchPlanRecord record = new SearchPlanRecord("research", 1, "en",
                Arrays.asList(node("node-a"), node("node-b"), node("node-c")),
                Arrays.asList("a", "b", "c"), Collections.<String>emptyList(), 2);
        SearchPlanCoverage.Caps caps = new SearchPlanCoverage.Caps(2, 3, 0, 3);

        SearchPlanProgress progress = SearchPlanProgress.of(record, entries, caps, 4, false,
                Arrays.asList("Worker suggests search: x"));

        assertEquals("Searching: query b", progress.title());
        assertEquals("1/3 queries · 2 sources · 4 pages open · 1/3 nodes covered · cycle 2/3", progress.counters());
        assertTrue(progress.body().endsWith("\n- Worker suggests search: x"));
    }

    @Test
    public void replanningAndParallelSearchesHaveTheirOwnTitle() {
        Map<String, SearchPlanEntry> entries = new LinkedHashMap<String, SearchPlanEntry>();
        entries.put("a", SearchPlanEntries.running("a", "A", "query a"));
        entries.put("b", SearchPlanEntries.running("b", "B", "query b"));
        SearchPlanRecord record = new SearchPlanRecord("research", 1, "en",
                Arrays.asList(node("node-a"), node("node-b")), Arrays.asList("a", "b"),
                Collections.<String>emptyList());
        SearchPlanCoverage.Caps caps = new SearchPlanCoverage.Caps(1, 3, 0, 3);

        assertEquals("Searching 2 queries in parallel",
                SearchPlanProgress.of(record, entries, caps, 0, false, null).title());
        assertEquals("Planning research cycle 2 …",
                SearchPlanProgress.of(record, entries, caps, 0, true, null).title());
        assertEquals("0/2 queries · 0 sources · 0/2 nodes covered · cycle 1/3",
                SearchPlanProgress.of(record, entries, caps, 0, false, null).counters());
    }
}
