package com.aresstack.askai.research.plan;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** The mission's source preference: the clause, the publisher queries per cycle and what the ranking gets. */
public class SourcePreferenceTest {

    /** The mission of the 2026-11-10 live run. */
    static final String RUN3 = "Erstelle das Konzept für ein kompaktes, vollständig belegtes deutsches Praxisbuch "
            + "„Wearables auf Baustellen: Sicherheit, Sensorik und Datenschutz“ für Sicherheitsfachkräfte, Umfang "
            + "20–30 Seiten. Bevorzuge frei zugängliche Primärquellen (DGUV, BAuA, EU-OSHA, Gesetze, wissenschaftliche "
            + "Studien); keine Buchhändler, Shopkataloge oder Verkaufsseiten als Nachweise.";

    static SourcePreference.Settings settings(int queries, int shift) {
        Map<String, String> publishers = new LinkedHashMap<String, String>();
        publishers.put("DGUV", "dguv.de");
        publishers.put("BAuA", "baua.de");
        publishers.put("EU-OSHA", "osha.europa.eu");
        publishers.put("Gesetze", "gesetze-im-internet.de");
        return new SourcePreference.Settings(true, Arrays.asList("bevorzuge", "prefer"), publishers,
                Arrays.asList(".bund.de"), Arrays.asList("/blog"), queries, shift);
    }

    @Test
    public void theRun3MissionNamesFivePreferredSources() {
        SourcePreference p = SourcePreference.of(RUN3, settings(1, 3));
        assertEquals(Arrays.asList("DGUV", "BAuA", "EU-OSHA", "Gesetze", "wissenschaftliche Studien"), p.getTerms());
        assertEquals(Arrays.asList("site:dguv.de", "site:baua.de", "site:osha.europa.eu",
                "site:gesetze-im-internet.de", "wissenschaftliche Studien"), p.getTargets());
        assertEquals(Arrays.asList("dguv.de", "baua.de", "osha.europa.eu", "gesetze-im-internet.de", ".bund.de"),
                p.getPreferredHosts());
        assertEquals(Collections.singletonList("/blog"), p.getDemotedMarkers());
        assertEquals(3, p.getRankShift());
    }

    @Test
    public void eachCycleAsksTheNextPublisher() {
        SourcePreference p = SourcePreference.of(RUN3, settings(1, 3));
        assertEquals(Collections.singletonList("Wearables Baustelle site:dguv.de"),
                p.queriesFor("Wearables Baustelle", 0, 1));
        assertEquals(Collections.singletonList("Wearables Baustelle site:baua.de"),
                p.queriesFor("Wearables Baustelle", 0, 2));
        assertEquals(Collections.singletonList("Wearables Baustelle site:baua.de"),
                p.queriesFor("Wearables Baustelle", 1, 1)); // the next node starts one publisher further
        assertEquals(Collections.singletonList("Wearables Baustelle wissenschaftliche Studien"),
                p.queriesFor("Wearables Baustelle", 4, 1));
        assertTrue(p.queriesFor("Lärm site:baua.de", 0, 1).isEmpty()); // never a second site: operator
    }

    @Test
    public void domainsInTheMissionNeedNoEntryAndPlainClausesWork() {
        SourcePreference p = SourcePreference.of("Write a guide. Prefer: who.int, Cochrane and NICE. Keep it short.",
                settings(2, 0));
        assertEquals(Arrays.asList("who.int", "Cochrane", "NICE"), p.getTerms());
        assertEquals(Arrays.asList("site:who.int", "Cochrane", "NICE"), p.getTargets());
        List<String> two = p.queriesFor("hand hygiene", 0, 1);
        assertEquals(Arrays.asList("hand hygiene site:who.int", "hand hygiene Cochrane"), two);
    }

    @Test
    public void offOrWithoutClauseThereAreNoQueries() {
        assertSame(SourcePreference.NONE, SourcePreference.of(RUN3, new SourcePreference.Settings(false,
                Arrays.asList("bevorzuge"), null, null, null, 1, 3)));
        SourcePreference noClause = SourcePreference.of("Ein Buch über Wearables.", settings(1, 3));
        assertTrue(noClause.getTargets().isEmpty());
        assertTrue(noClause.queriesFor("Wearables", 0, 1).isEmpty());
        assertEquals(Collections.singletonList(".bund.de"), noClause.getPreferredHosts()); // authorities still weigh
    }
}
