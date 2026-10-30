package com.aresstack.askai.research.runtime.orchestrator;

import com.aresstack.askai.research.domain.task.ResearchTask;
import com.aresstack.askai.research.domain.task.TaskArtifact;
import com.aresstack.askai.research.domain.task.TaskBudget;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Outline, evidence, run record, briefs and settings: the deterministic parts of the orchestrated run. */
public class OrchestratorModelTest {

    @Test
    public void theOutlineIsTheConceptsFirstLevel() {
        ResearchOutline outline = ResearchOutline.fromConceptRead("q", "revision=4\nparent=x\n"
                + "{\"FreeRTOS\":[\"Scheduling\",{\"Kommunikation & IPC\":[\"Queues\"]},\"Scheduling\",\"\"]}"
                + "\nSUPPRESSED IN THIS BRANCH: Linux");
        assertEquals(Arrays.asList("scheduling", "kommunikation-ipc", "scheduling-2"), outline.sectionIds());
        assertEquals("Kommunikation & IPC", outline.titleOf("kommunikation-ipc"));
        assertEquals(Collections.singletonList(ResearchOutline.QUESTION_SECTION),
                ResearchOutline.fromConceptRead("q", "no concept").sectionIds());
        assertEquals(Collections.singletonList(ResearchOutline.QUESTION_SECTION),
                ResearchOutline.fromConceptRead("q", "{\"Root\":[]}").sectionIds());
    }

    @Test
    public void evidenceIsMeasuredPerSectionFromTheBoard() {
        OrchestratorRun run = OrchestratorRun.builder("run-1", "q", 1L).state(OrchestratorRun.State.RUNNING)
                .sections(Arrays.asList(new ResearchOutline.Section("a", "A"), new ResearchOutline.Section("b", "B")))
                .build();
        ResearchTask t1 = submitted(run, "t1", "a").start("e", 2L)
                .withArtifact(new TaskArtifact("x1", TaskArtifact.Kind.SOURCE_REFERENCE,
                        "https://www.one.example/p Title", "s1", 3L))
                .withArtifact(new TaskArtifact("x2", TaskArtifact.Kind.SOURCE_REFERENCE,
                        "http://127.0.0.1:8081/p", "s2", 3L)).complete(4L);
        ResearchTask t2 = submitted(run, "t2", "a").start("e", 2L)
                .withArtifact(new TaskArtifact("x3", TaskArtifact.Kind.SOURCE_REFERENCE, "https://one.example/q",
                        "s1", 3L)).fail("x", 4L);
        ResearchTask foreign = ResearchTask.submit("other.t", "research", "web-researcher", null,
                TaskBriefs.inputsOf(new PlannedTask("t", "web-researcher", "q", "", Arrays.asList("b"), null)),
                "", 0, TaskBudget.unlimited(), 0, 1L);
        EvidenceSnapshot evidence = new EvidenceMeter(2, 2, 1).measure(run, Arrays.asList(t1, t2, foreign));
        assertEquals("a duplicate source counts once", 2, evidence.getDistinctSources());
        assertEquals("www. is dropped, an explicit port is kept", 2, evidence.getDistinctHosts());
        assertTrue(evidence.meetsRunRule());
        assertEquals(2, evidence.getSections().get(0).getSources());
        assertEquals(1, evidence.getSections().get(0).getFailedTasks());
        assertEquals(0, evidence.getSections().get(1).getSources());
        assertFalse("section b is uncovered", evidence.isSufficient());
        assertTrue(evidence.insufficiencyReason().contains("B"));
    }

    private static ResearchTask submitted(OrchestratorRun run, String id, String section) {
        return ResearchTask.submit(run.taskIdOf(id), "research", "web-researcher", null,
                TaskBriefs.inputsOf(new PlannedTask(id, "web-researcher", "query " + id, "brief",
                        Collections.singletonList(section), null)), "judge", 0, TaskBudget.unlimited(), 1, 1L);
    }

    @Test
    public void theRunRecordSurvivesItsJsonForm() {
        OrchestratorRun run = OrchestratorRun.builder("run-7", "Why?", 10L)
                .state(OrchestratorRun.State.AWAITING_APPROVAL)
                .sections(Arrays.asList(new ResearchOutline.Section("a", "A")))
                .plan("because", Arrays.asList(new PlannedTask("p1", "web-researcher", "q1", "b1",
                        Arrays.asList("a"), null)))
                .pending(Arrays.asList(new PlannedTask("f1", "web-researcher", "q2", "b2", Arrays.asList("a"),
                        Arrays.asList("p1"))), "over budget")
                .envelope(4, 12, 1000, 60_000L).spent(2, 3, 500, 30_000L)
                .approved(new java.util.LinkedHashSet<String>(Arrays.asList("a")),
                        new java.util.LinkedHashSet<String>(Arrays.asList("web-researcher")))
                .findings(Arrays.asList(new RoundDecision.Finding("a", "thin")),
                        Collections.<RoundDecision.Finding>emptyList(), "note")
                .evaluated(Arrays.asList("p1")).userNote("PF4J")
                .journal("same").journal("same").updatedAt(20L).build();
        OrchestratorRun back = OrchestratorRunCodec.decode(OrchestratorRunCodec.encode(run));
        assertEquals(OrchestratorRunCodec.encode(run), OrchestratorRunCodec.encode(back));
        assertEquals(Arrays.asList("same", "same"), back.getJournal());
        assertEquals("p1", back.getPending().get(0).getDependsOn().get(0));
        assertEquals(10, back.remainingTasks() + 1);
    }

    @Test
    public void briefsCarryQueryAndSections() {
        PlannedTask planned = new PlannedTask("p1", "web-researcher", "plugin\nloading", "read the docs",
                Arrays.asList("a", "b"), null);
        assertEquals("plugin loading", TaskBriefs.queryOf(TaskBriefs.inputsOf(planned)));
        assertEquals("read the docs", TaskBriefs.briefOf(TaskBriefs.inputsOf(planned)));
        assertEquals(Arrays.asList("a", "b"), TaskBriefs.sectionsOf(TaskBriefs.inputsOf(planned)));
    }

    @Test
    public void settingsComeFromTheEnvironmentWithDefaultsForAnythingInvalid() {
        Map<String, String> env = new HashMap<String, String>();
        env.put(OrchestratorSettings.ENV_MODE, "orchestrated");
        env.put(OrchestratorSettings.ENV_MAX_ROUNDS, "7");
        env.put(OrchestratorSettings.ENV_TOKEN_BUDGET, "0");
        env.put(OrchestratorSettings.ENV_MAX_TASKS, "-3");
        env.put(OrchestratorSettings.ENV_BROWSER_SLOTS, "abc");
        OrchestratorSettings settings = OrchestratorSettings.from(env);
        assertEquals(OrchestratorSettings.Mode.ORCHESTRATED, settings.getMode());
        assertEquals(7, settings.getMaxRounds());
        assertEquals("0 = not limited", 0, settings.getTokenBudget());
        assertEquals(OrchestratorSettings.DEFAULT_MAX_TASKS, settings.getMaxTasks());
        assertEquals(OrchestratorSettings.DEFAULT_BROWSER_SLOTS, settings.getBrowserSlots());
        assertEquals(OrchestratorSettings.Mode.LOOP, OrchestratorSettings.from(null).getMode());
    }

    @Test
    public void webResearchStopReasonsMapOntoTheTaskTable() {
        assertEquals(WorkerResult.Kind.COMPLETED, WebResearchWorker.map(
                com.aresstack.askai.research.runtime.loop.ResearchStopReason.NO_SEMANTIC_MATCHES, "").getKind());
        assertEquals(WorkerResult.Kind.RETRYABLE, WebResearchWorker.map(
                com.aresstack.askai.research.runtime.loop.ResearchStopReason.SEARCH_TECHNICAL_PROBLEM, "").getKind());
        assertEquals(WorkerResult.Kind.FAILED, WebResearchWorker.map(
                com.aresstack.askai.research.runtime.loop.ResearchStopReason.STATE_CHANGED, "").getKind());
        assertEquals(WorkerResult.Kind.CANCELED, WebResearchWorker.map(
                com.aresstack.askai.research.runtime.loop.ResearchStopReason.USER_CANCELLED, "").getKind());
    }
}
