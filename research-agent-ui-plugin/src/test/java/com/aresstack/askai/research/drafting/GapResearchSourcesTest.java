package com.aresstack.askai.research.drafting;

import com.aresstack.askai.research.manuscript.GapSources;
import com.aresstack.askai.research.plan.SearchPlanEntries;
import com.aresstack.askai.research.plan.SearchPlanEntry;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Live run 4, fix 5: which sections the gap searches brought new sources to. */
public class GapResearchSourcesTest {

    @Test
    public void theSourcesOfEveryGapEntryCountForTheirSectionOnce() {
        List<SearchPlanEntry> entries = Arrays.asList(
                SearchPlanEntries.gap("gap-sec-1-abc", "Ortung Helm Baustelle", 100, "s1", "s2"),
                SearchPlanEntries.gap("gap-sec-1-abc-def", "Ortung Helm Baustelle site:dguv.de", 200, "s2", "s3"),
                SearchPlanEntries.gap("gap-sec-1-2-ff", "Datenschutz Betriebsrat", 50, "s4"),
                SearchPlanEntries.gap("gap-sec-2-aa", "nothing found", 300),
                SearchPlanEntries.gap("gap-unknown-bb", "a section that is gone", 400, "s5"),
                SearchPlanEntries.done("plan-0001", "Node", "Base query", "s6"));
        List<GapSources> researched = GapResearch.researched(entries, Arrays.asList("sec-1", "sec-1-2", "sec-2"));
        assertEquals(2, researched.size());
        GapSources first = researched.get(0);
        assertEquals("sec-1", first.getSectionId());
        assertEquals(Arrays.asList("s1", "s2", "s3"), first.getSourceIds());
        assertEquals(200, first.getLastSourceAtMillis());
        assertEquals(Arrays.asList("Ortung Helm Baustelle", "Ortung Helm Baustelle site:dguv.de"),
                first.getQueries());
        assertEquals("the longest section id an entry names wins", "sec-1-2", researched.get(1).getSectionId());
        assertEquals(Collections.singletonList("s4"), researched.get(1).getSourceIds());
    }

    @Test
    public void onlyPriorityEntriesOfTheDocumentPhaseAreGapEntries() {
        assertTrue(GapResearch.isGapEntry(SearchPlanEntries.gap("gap-sec-1-abc", "q", 1, "s1")));
        assertFalse(GapResearch.isGapEntry(SearchPlanEntries.done("gap-sec-1-abc", "Node", "q", "s1")));
        assertFalse(GapResearch.isGapEntry(SearchPlanEntries.gap("priority-t1", "q", 1, "s1")));
        assertFalse(GapResearch.isGapEntry(null));
    }
}
