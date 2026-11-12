package com.aresstack.askai.research.runtime.writer;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The boilerplate filter against the real evidence of live run 3. */
public class ProsePassageFilterTest {

    private final ProsePassageFilter filter = new ProsePassageFilter(ProsePassageFilter.Criteria.defaults());

    @Test
    public void everyNavigationAndHeadingPassageOfLiveRun3IsBoilerplate() {
        for (String passage : LiveRun3Fixtures.BOILERPLATE) {
            assertFalse(passage, filter.isProse(passage));
        }
    }

    @Test
    public void everyProsePassageOfLiveRun3Stays() {
        for (String passage : LiveRun3Fixtures.PROSE) {
            assertTrue(filter.reason(passage), filter.isProse(passage));
        }
    }

    @Test
    public void theReasonNamesTheCriterion() {
        assertTrue(filter.reason(LiveRun3Fixtures.HEXAGON_NAVIGATION), filter.reason(
                LiveRun3Fixtures.HEXAGON_NAVIGATION).contains("without punctuation"));
        assertEquals("5 words < 10", filter.reason(LiveRun3Fixtures.HEADING_COSTS));
        assertTrue(filter.reason(LiveRun3Fixtures.DORMAKABA_BREADCRUMB).contains("finished sentences"));
    }

    @Test
    public void theCriteriaAreSettings() {
        ProsePassageFilter lenient = new ProsePassageFilter(new ProsePassageFilter.Criteria(0, 0, 200));
        assertTrue(lenient.isProse(LiveRun3Fixtures.HEXAGON_NAVIGATION));
        ProsePassageFilter strict = new ProsePassageFilter(new ProsePassageFilter.Criteria(20, 1, 30));
        assertFalse("12 words", strict.isProse(LiveRun3Fixtures.HEXAGON_GNSS));
    }

    /** Live run 4: footer lines passed because symbols and numbers counted as words. */
    @Test
    public void onlyTokensWithALetterAreWords() {
        assertEquals("5 words < 10", filter.reason("© 2026 Oxeltech GmbH · Mahlsdorfer Str. 10, 12555 Berlin"));
        assertFalse(filter.isProse("FSS sp. z o.o. ul. Agawy 7\n43-300 Bielsko-Biała, Polen"));
        assertTrue(filter.isProse("Smarte Helme melden Stürze sofort an die Leitstelle, sodass Ersthelfer "
                + "schneller eintreffen."));
    }

    /** Live run 4: calendar slogans between the product lines of a shop page formed a topic of their own. */
    @Test
    public void aLoneShortSentenceOnAPageOfAlmostNoProseIsACaption() {
        java.util.List<String> shop = new java.util.ArrayList<String>();
        for (int i = 0; i < 60; i++) { // 2 prose passages of 60: below the share 0.05
            shop.add("2026 Link Kalender Wandkalender");
        }
        shop.set(10, "Refresh your first aid training regularly to be prepared for emergencies.");
        shop.set(20, "Das Arbeitsschutzgesetz regelt für alle Tätigkeitsbereiche die grundlegenden Pflichten des "
                + "Arbeitgebers, die Gefährdungen am Arbeitsplatz zu beurteilen, geeignete Maßnahmen zu treffen "
                + "und alles schriftlich festzuhalten.");

        assertEquals("the long sentence is no caption", java.util.Collections.singleton(10), filter.captions(shop));

        assertTrue("a page with enough prose has no captions",
                filter.captions(java.util.Arrays.asList("Kontakt", shop.get(10), "Impressum")).isEmpty());
        ProsePassageFilter off = new ProsePassageFilter(new ProsePassageFilter.Criteria(10, 1, 30, 22, 0));
        assertTrue("share 0 turns the rule off", off.captions(shop).isEmpty());
    }
}
