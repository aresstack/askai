package com.aresstack.askai.research.host;

import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Repair plan step 5a: the weights of the mission reach the outline keyed by concept node id. */
public class OutlineGenerationSharesTest {

    @Test
    public void theOutlineGetsTheWeightsByConceptNodeId() {
        Map<String, String> labels = new LinkedHashMap<String, String>();
        labels.put("n-tech", "Technologien und Sensortypen");
        labels.put("n-law", "Recht, Datenschutz und Betriebsrat");
        List<String> notes = new ArrayList<String>();

        Map<String, Double> byId = OutlineGeneration.chapterShares("Umfang 20 Seiten. Gewichtung: Recht 40 %.",
                labels, notes);

        assertEquals(1, byId.size());
        assertEquals(0.40, byId.get("n-law"), 1e-9);
        assertEquals(1, notes.size());
        assertTrue(notes.get(0), notes.get(0).contains("Recht, Datenschutz und Betriebsrat 40 %"));
    }

    @Test
    public void aMissionWithoutWeightsGivesNoSharesAndNoNote() {
        Map<String, String> labels = new LinkedHashMap<String, String>();
        labels.put("n-tech", "Technologien und Sensortypen");
        List<String> notes = new ArrayList<String>();

        assertTrue(OutlineGeneration.chapterShares("Umfang 20 Seiten", labels, notes).isEmpty());
        assertTrue(notes.isEmpty());
    }
}
