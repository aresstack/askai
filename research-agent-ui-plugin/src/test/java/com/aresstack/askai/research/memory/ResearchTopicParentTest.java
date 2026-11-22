package com.aresstack.askai.research.memory;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Component 7 with the merge (15): a topic can be a subtopic of another (Unterthema von), a merged topic keeps no copy
 * of the findings of the topics merged into it, and the memory reads them over the merge.
 */
public class ResearchTopicParentTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void aSubtopicNeedsAKnownParentAndNoCircle() throws Exception {
        ResearchMemory memory = new ResearchMemory();
        memory.addTopic(ResearchTopic.ofCard("T-rad", "Radverkehr", "anchor-concept-radverkehr", 0, 1L));
        try {
            memory.addTopic(ResearchTopic.found("thema-0001", "Radwege", "", null, "m", 2L)
                    .withParent("T-unbekannt", "m", 2L));
            fail("unknown parent");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("T-unbekannt"));
        }
        memory.addTopic(ResearchTopic.found("thema-0001", "Radwege", "", null, "m", 2L)
                .withParent("T-rad", "m", 2L));
        memory.addTopic(ResearchTopic.found("thema-0002", "Radwege an Landstraßen", "", null, "m", 3L)
                .withParent("thema-0001", "m", 3L));
        try {
            memory.updateTopic(memory.topic("thema-0001").withParent("thema-0002", "m", 4L));
            fail("a circle");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("subtopic of itself"));
        }
        try {
            ResearchTopic.found("thema-0003", "x", "", null, "m", 1L).withParent("thema-0003", "m", 1L);
            fail("itself");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("subtopic of itself"));
        }
        assertEquals("T-rad", memory.topic("thema-0001").getParentId());
        assertEquals(Arrays.asList("T-rad", "thema-0001", "thema-0002"), ids(memory));
    }

    @Test
    public void theParentAndTheOwnFindingsSurviveTheStore() throws Exception {
        File project = tmp.newFolder("project");
        ResearchMemoryStore store = new ResearchMemoryStore(project);
        ResearchMemory memory = store.open();
        memory.addTopic(ResearchTopic.ofCard("T-rad", "Radverkehr", "anchor-concept-radverkehr", 0, 1L));
        ResearchTopic a = ResearchTopic.found("vs-1", "Radwege", "", Collections.singletonList(
                new ResearchTopic.Finding("s1", "p1", "Radwege sind breit")), "m", 2L);
        ResearchTopic b = ResearchTopic.found("vs-2", "Radwegebau", "", Collections.singletonList(
                new ResearchTopic.Finding("s2", "p2", "gebaut werden")), "m", 2L);
        memory.addTopic(a);
        memory.addTopic(b);
        memory.addTopic(ResearchTopic.found("thema-0001", "Radwege", "", null, "m", 3L)
                .withParent("T-rad", "m", 3L).withMerged(Arrays.asList(a, b), "m", 3L));

        ResearchMemory back = new ResearchMemoryStore(project).open();
        ResearchTopic topic = back.topic("thema-0001");
        assertEquals("T-rad", topic.getParentId());
        assertEquals(Arrays.asList("vs-1", "vs-2"), topic.getMergedFrom());
        assertTrue("no copy of the merged findings", topic.getFindings().isEmpty());
        assertEquals(2, back.findingsOfTopic("thema-0001").size());
        assertEquals("", back.topic("T-rad").getParentId());
        assertEquals(2, back.evidenceOfTopic("thema-0001").getFindings());
        assertTrue(back.findingsOfTopic("T-unbekannt").isEmpty());
    }

    private static java.util.List<String> ids(ResearchMemory memory) {
        java.util.List<String> ids = new java.util.ArrayList<String>();
        for (ResearchTopic topic : memory.topics()) {
            ids.add(topic.getId());
        }
        return ids;
    }
}
