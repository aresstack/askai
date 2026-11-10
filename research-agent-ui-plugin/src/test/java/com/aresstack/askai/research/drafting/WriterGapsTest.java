package com.aresstack.askai.research.drafting;

import com.aresstack.askai.research.plan.SourcePreference;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** The writer's gaps become the next research of the search plan, with the mission's preferred sources. */
public class WriterGapsTest {

    private static final List<String> WORDS = Arrays.asList("evidence", "beleg");

    @Test
    public void failedForLackOfEvidenceAndGapLinesAreGapsInOutlineOrder() {
        List<WriterGaps.Section> sections = Arrays.asList(
                new WriterGaps.Section("s1", "Sensorik", false, "", Arrays.asList("Grenzwerte für Vibration fehlen",
                        "Grenzwerte für Vibration fehlen")),
                new WriterGaps.Section("s2", "Rechtsrahmen", true, "no evidence passages for this section",
                        Collections.<String>emptyList()),
                new WriterGaps.Section("s3", "Akkus", true, "model timeout", Collections.<String>emptyList()));
        List<WriterGaps.Gap> gaps = WriterGaps.of(sections, WORDS, 10);
        assertEquals(2, gaps.size());
        assertEquals("s2", gaps.get(0).getSectionId());
        assertEquals("Rechtsrahmen", gaps.get(0).getText()); // the failure names nothing to search: the title does
        assertEquals("Grenzwerte für Vibration fehlen", gaps.get(1).getText()); // the duplicate counts once
        assertEquals(1, WriterGaps.of(sections, WORDS, 1).size());
        assertTrue(WriterGaps.of(sections, WORDS, 0).isEmpty());
    }

    @Test
    public void aGapGoesToThePlanWithThePreferredPublishers() throws Exception {
        final List<String> queries = new ArrayList<String>();
        final List<String> ids = new ArrayList<String>();
        Map<String, String> publishers = new LinkedHashMap<String, String>();
        publishers.put("BAuA", "baua.de");
        SourcePreference preference = SourcePreference.of("Bevorzuge BAuA.", new SourcePreference.Settings(true,
                Arrays.asList("bevorzuge"), publishers, null, null, 1, 3));
        String id = new GapResearch(new GapResearch.Queue() {
            public String addPriority(String entryId, String phase, String query, String label) {
                ids.add(entryId);
                queries.add(query);
                return entryId;
            }

            public void run() {
            }
        }, preference).research("s1", "Sensorik", "Vibration Grenzwerte");
        assertEquals(ids.get(0), id);
        assertEquals(Arrays.asList("Sensorik Vibration Grenzwerte", "Sensorik Vibration Grenzwerte site:baua.de"),
                queries);
    }
}
