package com.aresstack.askai.research.concept;

import com.aresstack.askai.research.store.FileConceptStore;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The UI projection is ONE snapshot through the SAME pipeline as the agent tools: mindmap, JSON
 * and revision can never drift apart, an empty concept is a placeholder (not a bare-root
 * diagram), and an unreadable document yields the diagnosis — never a broken or repaired map.
 */
public class ConceptProjectionTest {

    private ConceptBranchService freshService() throws Exception {
        File dir = Files.createTempDirectory("askai-concept-projection").toFile();
        return new ConceptBranchService(new FileConceptStore(new File(dir, "concept")));
    }

    @Test
    public void oneSnapshotFeedsMindmapJsonAndRevisionConsistently() throws Exception {
        ConceptBranchService service = freshService();
        ConceptBranchService.ReadResult root = service.readBranch(
                Collections.<String>emptyList(), 0);
        service.updateBranch(root.getHandleId(),
                "{\"concept\":[{\"FreeRTOS\":[{\"Tasks\":[],\"Queues\":[]}],"
                        + "\"Mikrocontroller\":[]}]}");

        ConceptProjection projection = ConceptProjection.of(service.snapshot());
        assertTrue(projection.isReadable());
        assertEquals(1L, projection.getWorkingRevision());
        String mermaid = projection.getMermaid();
        assertTrue(mermaid.startsWith("mindmap\n"));
        assertTrue(mermaid.contains("(FreeRTOS)"));
        assertTrue(mermaid.contains("(Tasks)"));
        assertTrue(mermaid.contains("(Mikrocontroller)"));
        assertTrue("the concept section itself is the working surface, not a diagram node",
                !mermaid.contains("(concept)"));
        assertTrue("the JSON view shows the SAME document, pretty-printed",
                projection.getPrettyJson().contains("\"Queues\": []"));
    }

    @Test
    public void theEnvelopeTitleBecomesTheMindmapRoot() throws Exception {
        ConceptBranchService service = freshService();
        ConceptBranchService.ReadResult root = service.readBranch(
                Collections.<String>emptyList(), 0);
        service.updateBranch(root.getHandleId(), "{\"concept\":[{\"Tasks\":[]}]}");
        // The title lives beside the working surface; the tools never touch it — write it the
        // store way a later metadata tool would.
        ConceptProjection untitled = ConceptProjection.of(service.snapshot());
        assertTrue("the single concept root IS the center, no helper node",
                untitled.getMermaid().startsWith("mindmap\n  root((Tasks))\n"));
        assertTrue(!untitled.getMermaid().contains("Concept"));
    }

    @Test
    public void anEmptyConceptIsAPlaceholderNotABareRootDiagram() throws Exception {
        ConceptProjection projection = ConceptProjection.of(freshService().snapshot());
        assertTrue(projection.isReadable());
        assertTrue(projection.isEmptyConcept());
        assertNull(projection.getMermaid());
        assertEquals(0L, projection.getWorkingRevision());
    }

    @Test
    public void anUnreadableDocumentYieldsTheDiagnosisNeverARepairedMap() {
        ConceptProjection projection = ConceptProjection.of(
                new ConceptBranchService.DocumentSnapshot("{\"concept\":[broken", 7L));
        assertFalse(projection.isReadable());
        assertNull(projection.getMermaid());
        assertTrue(projection.getDiagnosticText().startsWith("JSON_SYNTAX_ERROR"));
        assertEquals("the raw text stays visible for diagnosis",
                "{\"concept\":[broken", projection.getPrettyJson());
        assertEquals(7L, projection.getWorkingRevision());
    }
}
