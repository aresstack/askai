package com.aresstack.askai.research.visualize;

import com.aresstack.askai.research.sources.ResearchSourceRecord;
import com.aresstack.askai.research.sources.SourceStatus;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The sources mindmap is built MECHANICALLY — structure from data, sanitized labels, no model —
 * so it must always be valid mindmap syntax: root, one branch per search query, best-rated leaves.
 */
public class SourceMindmapTest {

    @Test
    public void queriesBecomeBranchesAndSourcesBecomeLeaves() {
        String mermaid = SourceMindmap.mermaid("Oldtimer als Wertanlage?", Arrays.asList(
                source("Historische Fahrzeuge", "oldtimer wertanlage", 0.12, SourceStatus.NEW),
                source("AutoScout Marktanalyse", "oldtimer preise", 0.08, SourceStatus.NEW)));
        assertTrue(mermaid.startsWith("mindmap\n  root((Oldtimer als Wertanlage?))\n"));
        assertTrue(mermaid.contains("    (oldtimer wertanlage)\n      [Historische Fahrzeuge]"));
        assertTrue(mermaid.contains("    (oldtimer preise)\n      [AutoScout Marktanalyse]"));
    }

    @Test
    public void withoutAResearchQuestionTheSingleQueryIsTheCenter() {
        String mermaid = SourceMindmap.mermaid(null, Arrays.asList(
                source("Energetisch Sanieren", "energetisches sanieren basics", 0.2, SourceStatus.NEW)));
        assertEquals("mindmap\n  root((energetisches sanieren basics))\n"
                + "    [Energetisch Sanieren]\n", mermaid);
    }

    @Test
    public void onlyTheBestRatedPerBranchSurviveButUserMarkedAlwaysStay() {
        List<ResearchSourceRecord> sources = new ArrayList<ResearchSourceRecord>();
        for (int i = 0; i < 8; i++) {
            sources.add(source("Quelle " + i, "query", i / 100.0, SourceStatus.NEW));
        }
        ResearchSourceRecord marked = builder("Vom User markiert", "query", 0.0, SourceStatus.NEW)
                .userRelevant(true).build();
        sources.add(marked);

        String mermaid = SourceMindmap.mermaid("Thema", sources);
        int leaves = mermaid.split("\\[", -1).length - 1;
        assertEquals("top " + SourceMindmap.MAX_PER_QUERY + " + the user-marked one",
                SourceMindmap.MAX_PER_QUERY + 1, leaves);
        assertTrue(mermaid.contains("[Vom User markiert]"));
        assertTrue("the best score is in", mermaid.contains("[Quelle 7]"));
        assertFalse("the weakest unmarked one is out", mermaid.contains("[Quelle 0]"));
    }

    @Test
    public void duplicatesExcludedAndSupersededNeverAppear() {
        String mermaid = SourceMindmap.mermaid("Thema", Arrays.asList(
                source("Gute Quelle", "q", 0.1, SourceStatus.ACCEPTED),
                source("Doppelt", "q", 0.9, SourceStatus.DUPLICATE),
                source("Raus", "q", 0.9, SourceStatus.EXCLUDED),
                source("Ersetzt", "q", 0.9, SourceStatus.SUPERSEDED)));
        assertTrue(mermaid.contains("[Gute Quelle]"));
        assertFalse(mermaid.contains("Doppelt"));
        assertFalse(mermaid.contains("Raus"));
        assertFalse(mermaid.contains("Ersetzt"));
    }

    @Test
    public void labelsAreSanitizedForTheMindmapGrammar() {
        assertEquals("Autos & mehr — Kaufen Verkaufen",
                SourceMindmap.label("Autos & mehr — [Kaufen] (Verkaufen)\n", "x"));
        assertTrue("overlong labels are capped with an ellipsis",
                SourceMindmap.label(repeat('a', 80), "x").endsWith("…"));
        assertEquals("fallback fills a blank label", "Ersatz", SourceMindmap.label("  ", "Ersatz"));
    }

    @Test
    public void noQualifyingSourcesMeansNullNotAnEmptyDiagram() {
        assertNull(SourceMindmap.mermaid("Thema", Collections.<ResearchSourceRecord>emptyList()));
        assertNull(SourceMindmap.mermaid("Thema", null));
        assertNull(SourceMindmap.mermaid("Thema", Collections.singletonList(
                source("Nur ein Duplikat", "q", 0.9, SourceStatus.DUPLICATE))));
    }

    // --- helpers ---

    private static ResearchSourceRecord source(String title, String query, double score,
                                               SourceStatus status) {
        return builder(title, query, score, status).build();
    }

    private static ResearchSourceRecord.Builder builder(String title, String query, double score,
                                                        SourceStatus status) {
        return ResearchSourceRecord.builder(java.util.UUID.randomUUID().toString())
                .title(title).searchQuery(query).rerankScore(score).status(status);
    }

    private static String repeat(char c, int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            sb.append(c);
        }
        return sb.toString();
    }
}
