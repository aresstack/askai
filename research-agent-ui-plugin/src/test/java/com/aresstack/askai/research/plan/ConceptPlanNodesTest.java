package com.aresstack.askai.research.plan;

import com.aresstack.askai.research.concept.ConceptIdentity;
import com.aresstack.askai.research.domain.plan.ConceptQueryPlan;
import com.google.gson.JsonParser;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Plan PR 14 (D5): the plan nodes of the frozen concept and their deterministic base queries. */
public class ConceptPlanNodesTest {

    static final String CONCEPT = "{\"title\":\"Plugins\",\"subtitle\":\"\",\"concept\":["
            + "{\"Plugin-Isolation\":[{\"Classloader\":[]},{\"Modulsystem\":[]},{\"Spring Boot\":[]}]},"
            + "{\"Sicherheit\":[\"Sandbox\"]}]}";

    static final String MISSION = "Das Buch erklärt Java-Entwicklern, wie Plugins mit Classloadern und JPMS "
            + "sicher isoliert werden.";

    @Test
    public void everyCardBecomesANodeInConceptOrderWithItsBaseQuery() {
        List<ConceptQueryPlan.Node> nodes = ConceptPlanNodes.of(CONCEPT, CONCEPT, null, MISSION,
                Collections.<String>emptyList());
        assertEquals(Arrays.asList("Plugin-Isolation", "Classloader", "Modulsystem", "Spring Boot", "Sicherheit",
                "Sandbox"), labels(nodes));
        ConceptQueryPlan.Node classloader = nodes.get(1);
        assertEquals(nodes.get(0).getId(), classloader.getParentId());
        // a first-level card adds the mission's content words; a deeper card carries its parent instead
        assertEquals("Plugin-Isolation Java-Entwicklern Plugins Classloadern", nodes.get(0).getBaseQuery());
        assertEquals("Classloader Plugin-Isolation", classloader.getBaseQuery());
        assertEquals(Arrays.asList("Plugin-Isolation", "Classloader"), classloader.getPath());
    }

    @Test
    public void wordsAboutTheBookOrTheTaskNeverReachAQuery() {
        // the live run of 2026-11-09: every query ended in "erstellen deutschsprachiges Testbuch"
        String mission = "Wir erstellen ein deutschsprachiges Testbuch mit dem Titel 'Wearables für sicheren "
                + "Baustellenbetrieb'. Ziel: ein kurzes praxisorientiertes Buch für Sicherheitsfachkräfte. Lege bitte "
                + "jetzt ein klares Concept mit mehreren Themenkarten an: Sensorarten, Gefahren, Datenschutz, "
                + "Einführung und Grenzen. Arbeite zur Prüfung der App.";
        String concept = "{\"title\":\"\",\"subtitle\":\"\",\"concept\":[{\"Wearables für sicheren "
                + "Baustellenbetrieb\":[{\"Sensorarten\":[]},{\"Datenschutz\":[]}]}]}";
        List<ConceptQueryPlan.Node> nodes = ConceptPlanNodes.of(concept, concept, null, mission,
                Collections.<String>emptyList());
        assertEquals("Wearables für sicheren Baustellenbetrieb Sicherheitsfachkräfte Sensorarten Gefahren",
                nodes.get(0).getBaseQuery());
        assertEquals("Sensorarten Wearables für sicheren Baustellenbetrieb", nodes.get(1).getBaseQuery());
        assertEquals("Datenschutz Wearables für sicheren Baustellenbetrieb", nodes.get(2).getBaseQuery());
        for (ConceptQueryPlan.Node node : nodes) {
            assertFalse(node.getBaseQuery(), node.getBaseQuery().matches("(?iu).*(erstellen|testbuch|titel).*"));
        }
    }

    @Test
    public void theBlacklistRemovesNodesAndTermsFromEveryQuery() {
        List<ConceptQueryPlan.Node> nodes = ConceptPlanNodes.of(CONCEPT, CONCEPT, null, MISSION,
                Arrays.asList("Spring Boot", "JPMS", "Plugins"));
        assertFalse(labels(nodes).contains("Spring Boot"));
        for (ConceptQueryPlan.Node node : nodes) {
            assertFalse(node.getBaseQuery(), node.getBaseQuery().contains("JPMS"));
            assertFalse(node.getBaseQuery(), node.getBaseQuery().matches("(?i).*\\bplugins\\b.*"));
        }
    }

    @Test
    public void aBaseQueryNeverExceedsTheLimit() {
        StringBuilder label = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            label.append("Begriff").append(i).append(' ');
        }
        String query = ConceptPlanNodes.baseQuery(label.toString(), "Eltern",
                Arrays.asList("eins", "zwei"), Collections.<String>emptyList());
        assertTrue(query.length() <= ConceptQueryPlan.MAX_QUERY_LENGTH);
        assertFalse(query.endsWith(" "));
    }

    @Test
    public void aRenamedCardKeepsItsSidecarId() {
        ConceptIdentity identity = ConceptIdentity.mintFor(JsonParser.parseString(CONCEPT));
        String sidecar = identity.stampedJson(1L, "hash");
        String renamed = CONCEPT.replace("Modulsystem", "JPMS-Layer");
        List<ConceptQueryPlan.Node> before = ConceptPlanNodes.of(CONCEPT, CONCEPT, sidecar, MISSION,
                Collections.<String>emptyList());
        List<ConceptQueryPlan.Node> after = ConceptPlanNodes.of(renamed, renamed, sidecar, MISSION,
                Collections.<String>emptyList());
        assertEquals(before.get(2).getId(), after.get(2).getId());
        assertEquals("JPMS-Layer", after.get(2).getLabel());
        assertFalse(before.get(2).getBaseQuery().equals(after.get(2).getBaseQuery()));
        assertEquals(before.get(1).getBaseQuery(), after.get(1).getBaseQuery());
    }

    static List<String> labels(List<ConceptQueryPlan.Node> nodes) {
        String[] out = new String[nodes.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = nodes.get(i).getLabel();
        }
        return Arrays.asList(out);
    }
}
