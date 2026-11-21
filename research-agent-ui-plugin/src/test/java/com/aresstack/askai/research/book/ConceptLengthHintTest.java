package com.aresstack.askai.research.book;

import com.aresstack.askai.research.domain.scope.ResearchDeliverable;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Repair plan step 1: Phase 1 says which book length the later phases get, and says it when there is none. */
public class ConceptLengthHintTest {

    @Test
    public void namesTheLengthAndWhereItComesFrom() {
        assertEquals("Book length: 40-50 pages.", ConceptLengthHint.of(
                new ResearchDeliverable(40, 50, ResearchDeliverable.LengthUnit.PAGES, null), "20 bis 30 Seiten", true));
        assertEquals("Book length: 20-30 pages, read from the mission.", ConceptLengthHint.of(
                ResearchDeliverable.unspecified(), "Praxisbuch, Umfang 20–30 Seiten", true));
        assertFalse(ConceptLengthHint.isMissing(ConceptLengthHint.of(ResearchDeliverable.unspecified(),
                "8.000 Wörter", true)));
    }

    @Test
    public void saysWhenThereIsNoLength() {
        assertTrue(ConceptLengthHint.isMissing(ConceptLengthHint.of(ResearchDeliverable.unspecified(),
                "Wearables auf Baustellen", true)));
        assertTrue("switched off, the mission is not read", ConceptLengthHint.isMissing(ConceptLengthHint.of(
                ResearchDeliverable.unspecified(), "20 bis 30 Seiten", false)));
    }
}
