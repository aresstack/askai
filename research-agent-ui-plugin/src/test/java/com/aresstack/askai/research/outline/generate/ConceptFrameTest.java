package com.aresstack.askai.research.outline.generate;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** D6: a single first-level concept node is the book frame; its children are the main themes. */
public class ConceptFrameTest {

    private static ConceptOutlineNode node(String id, String label, ConceptOutlineNode... children) {
        return new ConceptOutlineNode(id, label, Arrays.asList(children));
    }

    @Test
    public void aSingleRootIsTheFrameAndItsChildrenAreTheMainThemes() {
        List<ConceptOutlineNode> concept = Collections.singletonList(node("root", "Wearables on sites",
                node("a", "Safety", node("a1", "Helmets")), node("b", "Data"), node("c", "Costs"),
                node("d", "Law")));

        ConceptFrame frame = ConceptFrame.of("The Smart Site", concept);

        assertTrue(frame.hasFrame());
        assertEquals("Wearables on sites", frame.getFrameLabel());
        assertEquals("the root itself is no theme", 5, frame.nodes().size());
        ConceptFrame.Node safety = frame.nodes().get(0);
        assertEquals("a", safety.getId());
        assertEquals(1, safety.getDepth());
        assertEquals("", safety.getParentId());
        assertEquals("The Smart Site > Wearables on sites > Safety", safety.getPath());
        ConceptFrame.Node helmets = frame.nodes().get(1);
        assertEquals(2, helmets.getDepth());
        assertEquals("a", helmets.getParentId());
        assertEquals("The Smart Site > Wearables on sites > Safety > Helmets", helmets.getPath());
        assertEquals("d", frame.nodes().get(4).getId());
    }

    @Test
    public void severalFirstLevelNodesAreThemselvesTheMainThemes() {
        ConceptFrame frame = ConceptFrame.of("Book", Arrays.asList(node("a", "Safety"), node("b", "Data")));

        assertFalse(frame.hasFrame());
        assertEquals("", frame.getFrameLabel());
        assertEquals(2, frame.nodes().size());
        assertEquals("Book > Safety", frame.nodes().get(0).getPath());
        assertEquals(1, frame.nodes().get(1).getDepth());
    }

    @Test
    public void withoutATitleThePathStartsAtTheFirstLabel() {
        ConceptFrame frame = ConceptFrame.of("", Collections.singletonList(node("r", "Root", node("a", "A"))));
        assertEquals("Root > A", frame.nodes().get(0).getPath());
    }
}
