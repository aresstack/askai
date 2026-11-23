package com.aresstack.askai.research.book;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Repair plan step 5a: the weight of a main theme, written into the mission. */
public class MissionWeightsTest {

    private static final List<String> CARDS = Arrays.asList("Technologien und Sensortypen",
            "Arbeitsschutz und Wirksamkeit", "Recht, Datenschutz und Betriebsrat", "Implementierung und Kosten");

    @Test
    public void readsTheWeightAfterOrBeforeTheThemesName() {
        Map<String, Double> shares = MissionWeights.read("Praxisbuch, Umfang 20-30 Seiten. Gewichtung: "
                + "Technologien 20 %, Arbeitsschutz 30 Prozent, 35 % für Recht, Implementierung: 15%.", CARDS);
        assertEquals(0.20, shares.get(CARDS.get(0)), 1e-9);
        assertEquals(0.30, shares.get(CARDS.get(1)), 1e-9);
        assertEquals(0.35, shares.get(CARDS.get(2)), 1e-9);
        assertEquals(0.15, shares.get(CARDS.get(3)), 1e-9);
        assertEquals("Technologien und Sensortypen 20 %, Arbeitsschutz und Wirksamkeit 30 %, "
                + "Recht, Datenschutz und Betriebsrat 35 %, Implementierung und Kosten 15 %",
                MissionWeights.describe(shares));
    }

    @Test
    public void aWordOnlyOneCardHoldsNamesIt() {
        Map<String, Double> shares = MissionWeights.read("Datenschutz soll 40 % des Buchs ausmachen.", CARDS);
        assertEquals(1, shares.size());
        assertEquals(0.40, shares.get(CARDS.get(2)), 1e-9);
    }

    @Test
    public void noPercentageNoWeight() {
        assertTrue(MissionWeights.read("Erstelle das Konzept für ein Praxisbuch, Umfang 20-30 Seiten. Vier "
                + "Hauptthemen: (1) Technologien und Sensortypen, (2) Arbeitsschutz, (3) Recht, (4) Implementierung "
                + "mit Kosten.", CARDS).isEmpty());
        assertTrue(MissionWeights.read("", CARDS).isEmpty());
    }

    @Test
    public void aPercentageGoesToTheNearestName() {
        Map<String, Double> shares = MissionWeights.read("Schwerpunkte: Technologien kurz, Recht 50 %", CARDS);
        assertEquals(1, shares.size());
        assertEquals(0.50, shares.get(CARDS.get(2)), 1e-9);
    }

    /** Architect review of 21.11.: a figure of the subject near a card name is no weight. */
    @Test
    public void aFigureOfTheSubjectIsNoWeight() {
        List<String> cards = Arrays.asList("Förderung", "Dämmung und Fenster", "Heizung");
        assertTrue(MissionWeights.read("Sanierungsratgeber, Umfang 20 Seiten. Förderung bis zu 20 % Zuschuss, "
                + "Dämmung spart 30 % Energie.", cards).isEmpty());
        assertTrue("a weight word in another sentence does not count", MissionWeights.read("Schwerpunkt ist die "
                + "Praxis. Förderung gibt es mit 20 % Zuschuss.", cards).isEmpty());
        assertTrue("a comparison is never a weight", MissionWeights.read("Gewichtung: Heizung um 30 % senken.",
                cards).isEmpty());
        assertTrue("a bare Umfang is no weight word", MissionWeights.read("Umfang 20 Seiten, Förderung 20 % "
                + "Zuschuss.", cards).isEmpty());
    }

    @Test
    public void percentagesOfTwoCardsThatFitInTheWholeAreWeightsWithoutAWeightWord() {
        Map<String, Double> shares = MissionWeights.read("Technologien 60 %, Recht ca. 40 %", CARDS);
        assertEquals(2, shares.size());
        assertEquals(0.60, shares.get(CARDS.get(0)), 1e-9);
        assertEquals(0.40, shares.get(CARDS.get(2)), 1e-9);
        assertTrue("more than the whole is no weight list",
                MissionWeights.read("Technologien 80 %, Recht 70 %", CARDS).isEmpty());
    }

    @Test
    public void aWeightWordMakesASingleShareCount() {
        Map<String, Double> shares = MissionWeights.read("Umfang 20 Seiten. Recht soll einen Anteil von 30 % haben.",
                CARDS);
        assertEquals(1, shares.size());
        assertEquals(0.30, shares.get(CARDS.get(2)), 1e-9);
    }
}
