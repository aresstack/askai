package com.aresstack.askai.research.jsontree;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The structural projection keeps ONLY ArrayNodes — filtered by ORIGINAL node kind, never by
 * child count: an empty ArrayNode must survive, ValueLeafs/ObjectLeafs must vanish, and the
 * Mermaid renderer on top must stay mechanical.
 */
public class StructuralTreeExtractorTest {

    private static StructuralForest extract(String json) {
        JsonTreeParseResult result = JsonTreeParser.parse(json);
        assertTrue(result.isOk());
        return StructuralTreeExtractor.extract(result.getTree());
    }

    @Test
    public void onlyArrayNodesSurviveAndTheEmptyOneExplicitlyStays() {
        // The acceptance example: Tasks/Queues stay (Queues is EMPTY), importance/status/settings go.
        StructuralForest forest = extract("{\"FreeRTOS\": [ {\"Tasks\": [ {\"Scheduling\": []} ], "
                + "\"Queues\": [], \"importance\": 0.9, \"status\": \"confirmed\", "
                + "\"settings\": {\"whatever\": []} } ]}");
        assertEquals(1, forest.getRoots().size());
        StructuralNode freertos = forest.getRoots().get(0);
        assertEquals("FreeRTOS", freertos.getName());
        assertEquals(2, freertos.getChildren().size());
        StructuralNode tasks = freertos.getChildren().get(0);
        assertEquals("Tasks", tasks.getName());
        assertEquals("Scheduling", tasks.getChildren().get(0).getName());
        StructuralNode queues = freertos.getChildren().get(1);
        assertEquals("the EMPTY ArrayNode must not disappear", "Queues", queues.getName());
        assertTrue(queues.getChildren().isEmpty());
    }

    @Test
    public void arraysInsideAnObjectLeafAreNeverFound() {
        assertTrue(extract("{\"settings\": {\"profiles\": []}}").isEmpty());
    }

    @Test
    public void aDocumentWithoutAnyArrayIsAnEmptyForest() {
        assertTrue(extract("{\"a\": 1, \"b\": {\"c\": 2}}").isEmpty());
    }

    @Test
    public void multipleTopLevelArraysBecomeMultipleRoots() {
        StructuralForest forest = extract("{\"A\": [], \"x\": 1, \"B\": []}");
        assertEquals(2, forest.getRoots().size());
        assertEquals("A", forest.getRoots().get(0).getName());
        assertEquals("B", forest.getRoots().get(1).getName());
    }

    @Test
    public void theMermaidMindmapRendersTheStructureOnly() {
        StructuralForest forest = extract("{\"FreeRTOS\": [ {\"Tasks\": [], \"Queues\": [], "
                + "\"importance\": 0.9, \"settings\": {\"whatever\": []} } ]}");
        String mermaid = MermaidMindmapRenderer.render(forest, "Konzept");
        assertEquals("the single root is the center — no synthetic Konzept node above it",
                "mindmap\n"
                + "  root((FreeRTOS))\n"
                + "    (Tasks)\n"
                + "    (Queues)\n", mermaid);
        assertTrue("no metadata leaks into the diagram", !mermaid.contains("importance"));
    }

    @Test
    public void severalRootsHangUnderTheTitleRoot() {
        assertEquals("mindmap\n  root((Konzept))\n    (A)\n    (B)\n",
                MermaidMindmapRenderer.render(extract("{\"A\": [], \"B\": []}"), "Konzept"));
    }

    @Test
    public void anEmptyForestRendersAsTheBareRoot() {
        assertEquals("mindmap\n  root((Konzept))\n",
                MermaidMindmapRenderer.render(extract("{}"), "Konzept"));
    }

    @Test
    public void labelsAreSanitizedForTheMindmapGrammar() {
        StructuralForest forest = extract("{\"Ta[sk]s (neu)\": []}");
        String mermaid = MermaidMindmapRenderer.render(forest, "K");
        assertTrue(mermaid.contains("(Ta sk s neu)"));
    }
}
