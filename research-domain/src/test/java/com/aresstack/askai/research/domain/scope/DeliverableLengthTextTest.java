package com.aresstack.askai.research.domain.scope;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * Repair plan step 1: the length stated in a mission reaches the later phases in the one form they read, whatever
 * dash, word or separator the user typed.
 */
public class DeliverableLengthTextTest {

    private static String read(String text) {
        return DeliverableLengthText.describe(DeliverableLengthText.read(text));
    }

    /** The mission of the 17 Nov run (workspace 18fbde60), verbatim; its frozen concept had no length. */
    static final String MISSION_17_NOV = "Erstelle das Konzept für ein kompaktes, vollständig belegtes deutsches "
            + "Praxisbuch \"Wearables auf Baustellen: Sicherheit, Sensorik und Datenschutz\" für "
            + "Sicherheitsfachkräfte, Umfang 20-30 Seiten. Vier Hauptthemen: (1) Technologien und Sensortypen, (2) "
            + "reale Arbeitsschutz-Gefährdungen und praktische Wirksamkeit, (3) Recht, Datenschutz und Betriebsrat in "
            + "Deutschland, (4) Implementierung mit Kosten, Grenzen und Praxisbeispielen. Bevorzuge frei zugängliche "
            + "Primärquellen (DGUV, BAuA, EU-OSHA, Gesetze, wissenschaftliche Studien); keine Buchhändler, "
            + "Shopkataloge oder Verkaufsseiten als Nachweise.";

    @Test
    public void theMissionOfThe17NovRunGivesItsLength() {
        assertEquals("20-30 pages", DeliverableLengthText.effective(ResearchDeliverable.unspecified(),
                MISSION_17_NOV, true));
        assertEquals("20-30 pages", read(MISSION_17_NOV.replace("20-30", "20–30")));
    }

    @Test
    public void readsTheVariantsOfARange() {
        assertEquals("20-30 pages", read("Ein Praxisbuch mit 20-30 Seiten über Wearables"));
        assertEquals("20-30 pages", read("Ein Praxisbuch mit 20–30 Seiten über Wearables"));
        assertEquals("20-30 pages", read("Ein Praxisbuch mit 20 — 30 Seiten"));
        assertEquals("20-30 pages", read("Umfang: 20 bis 30 Seiten, Zielgruppe Betriebsräte"));
        assertEquals("20-30 pages", read("Umfang 20 bis zu 30 DIN-A4-Seiten"));
        assertEquals("20-30 pages", read("a practical book of 20 to 30 pages"));
        assertEquals("20-30 pages", read("a practical book of 20-30 pages"));
    }

    @Test
    public void readsSingleLengthsAndWordCounts() {
        assertEquals("25 pages", read("ca. 25 Seiten"));
        assertEquals("25 pages", read("etwa 25 Seiten"));
        assertEquals("8000 words", read("rund 8.000 Wörter"));
        assertEquals("8000 words", read("about 8,000 words"));
        assertEquals("8000 words", read("8 000 Woerter"));
        assertEquals("6000-9000 words", read("6.000–9.000 Wörter"));
        assertEquals("120 pages", read("120 pages"));
        assertEquals("20000-30000 words", read("20000-30000 words"));
    }

    @Test
    public void namesNoLengthWhereThereIsNone() {
        assertNull(DeliverableLengthText.read("Wearables am Arbeitsplatz: Technik, Recht, Praxis"));
        assertNull(DeliverableLengthText.read("2,5 Seiten"));
        assertNull(DeliverableLengthText.read(""));
        assertNull(DeliverableLengthText.read(null));
        assertNull(DeliverableLengthText.read("Seite 3 des Leitfadens"));
        assertEquals("", read("4 Hauptthemen und 3 Zielgruppen"));
    }

    @Test
    public void aReversedRangeIsOrdered() {
        assertEquals("20-30 pages", read("30 bis 20 Seiten"));
    }

    @Test
    public void theStatedLengthWinsAndTheMissionOnlyFillsAGap() {
        ResearchDeliverable stated = new ResearchDeliverable(40, 50, ResearchDeliverable.LengthUnit.PAGES, null);
        assertEquals("40-50 pages", DeliverableLengthText.effective(stated, "20 bis 30 Seiten", true));
        assertEquals("20-30 pages", DeliverableLengthText.effective(ResearchDeliverable.unspecified(),
                "20 bis 30 Seiten", true));
        assertEquals("", DeliverableLengthText.effective(ResearchDeliverable.unspecified(), "20 bis 30 Seiten",
                false));
        assertEquals("", DeliverableLengthText.effective(null, "ohne Umfang", true));
    }
}
