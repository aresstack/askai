package com.aresstack.askai.research.sectionplan;

import com.aresstack.askai.research.memory.ResearchTopic;
import com.aresstack.askai.research.transition.CorruptProjectFileException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class SectionPlansTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private static SectionPlan plan(long time) throws Exception {
        SectionPlannerTest data = new SectionPlannerTest();
        data.card("karte-rad", "Radverkehr", 0);
        data.found("t-helm", "Helmpflicht", ResearchTopic.Fence.INNEN);
        data.evidence("karte-rad", 4, 2);
        data.evidence("t-helm", 1, 1);
        return new SectionPlanner(data.memory).targetWords(900).plan(time);
    }

    @Test
    public void plansAreAppendedAndTheLatestHoldsAfterOpeningAgain() throws Exception {
        File project = folder.newFolder("projekt");
        SectionPlans plans = new SectionPlans(project);
        assertEquals(0, plans.open());
        assertNull(plans.latest());
        plans.record(plan(1L));
        plans.record(plan(2L));

        SectionPlans again = new SectionPlans(project);
        assertEquals(2, again.open());
        assertEquals(2, again.size());
        assertEquals(plan(2L).toJson(), again.latest().toJson());
        assertEquals(new File(new File(project, "section-plan"), "plans.jsonl"), again.logFile());
    }

    @Test
    public void anUnfinishedLastLineIsCutAndABrokenLineFailsClosed() throws Exception {
        File project = folder.newFolder("projekt");
        SectionPlans plans = new SectionPlans(project);
        plans.open();
        plans.record(plan(1L));
        Files.write(plans.logFile().toPath(), "{\"v\":1,\"art\":\"pl".getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.APPEND);

        SectionPlans cut = new SectionPlans(project);
        assertEquals(1, cut.open());
        String content = new String(Files.readAllBytes(plans.logFile().toPath()), StandardCharsets.UTF_8);
        assertTrue(content.endsWith("\n"));

        Files.write(plans.logFile().toPath(), "kaputt\n".getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
        try {
            new SectionPlans(project).open();
            fail("a broken line must stop the opening");
        } catch (CorruptProjectFileException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("line 2 of the section plans"));
        }
    }

    @Test
    public void aNewerVersionOrGenerationIsNeitherReadNorChanged() throws Exception {
        File project = folder.newFolder("projekt");
        File log = new File(new File(project, "section-plan"), "plans.jsonl");
        log.getParentFile().mkdirs();
        Files.write(log.toPath(), "{\"v\":2,\"art\":\"plan\",\"plan\":{}}\n".getBytes(StandardCharsets.UTF_8));
        try {
            new SectionPlans(project).open();
            fail("a newer version must stop the opening");
        } catch (SectionPlans.NewerVersionException expected) {
            assertEquals(log, expected.getFile());
        }

        File other = folder.newFolder("anderes");
        File pointer = new File(new File(other, "section-plan"), "current");
        pointer.getParentFile().mkdirs();
        Files.write(pointer.toPath(), "plans-2.jsonl".getBytes(StandardCharsets.UTF_8));
        try {
            new SectionPlans(other).open();
            fail("another generation must stop the opening");
        } catch (SectionPlans.NewerVersionException expected) {
            assertEquals(pointer, expected.getFile());
        }
    }

    @Test
    public void writingNeedsTheOpening() throws Exception {
        try {
            new SectionPlans(folder.newFolder("projekt")).record(plan(1L));
            fail("writing before opening");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("open the section plans"));
        }
    }
}
