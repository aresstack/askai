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
        Map<String, Double> shares = MissionWeights.read("Technologien kurz, Recht 50 %", CARDS);
        assertEquals(1, shares.size());
        assertEquals(0.50, shares.get(CARDS.get(2)), 1e-9);
    }
}
