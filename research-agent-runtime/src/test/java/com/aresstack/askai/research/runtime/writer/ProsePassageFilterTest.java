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
}
