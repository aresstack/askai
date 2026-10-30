package com.aresstack.askai.research.benchmark;

import com.aresstack.askai.research.domain.task.ResearchTask;
import com.aresstack.askai.research.domain.task.TaskArtifact;
import com.aresstack.askai.research.domain.task.TaskBudget;
import com.aresstack.askai.research.domain.task.TaskInputs;
import com.aresstack.askai.research.runtime.orchestrator.OrchestratorRun;
import com.aresstack.askai.research.runtime.orchestrator.OrchestratorSettings;
import com.aresstack.askai.research.runtime.orchestrator.ResearchOutline;
import com.aresstack.askai.research.runtime.orchestrator.WorkerRoleSettings;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class OrchestratedResearchSubjectTest {

    @Test
    public void theSubjectSwitchesOnTheOrchestratedModeAndKeepsEveryOtherDefault() {
        OrchestratorSettings settings = OrchestratorSettings.from(OrchestratedResearchSubject.environment());
        assertEquals(OrchestratorSettings.Mode.ORCHESTRATED, settings.getMode());
        assertEquals(OrchestratorSettings.DEFAULT_MAX_ROUNDS, settings.getMaxRounds());
        assertEquals(OrchestratorSettings.DEFAULT_TOKEN_BUDGET, settings.getTokenBudget());
    }

    /** Lane F's subject stays the orchestrator alone, so its numbers remain comparable. */
    @Test
    public void theOrchestratorAloneHasEveryLaneGRoleOff() {
        WorkerRoleSettings roles = WorkerRoleSettings.from(OrchestratedResearchSubject.environment());
        assertFalse(roles.isSearchPlanner());
        assertFalse(roles.isSourceVerifier());
        assertFalse(roles.isWriter());
    }

    /** Lane G: every role is measured on its own, on top of the same orchestrated run. */
    @Test
    public void eachRoleSubjectSwitchesOnExactlyItsOwnRole() {
        WorkerRoleSettings planner = WorkerRoleSettings.from(
                OrchestratedResearchSubject.environment(OrchestratedResearchSubject.Role.SEARCH_PLANNER));
        assertTrue(planner.isSearchPlanner());
        assertFalse(planner.isSourceVerifier() || planner.isWriter());
        WorkerRoleSettings verifier = WorkerRoleSettings.from(
                OrchestratedResearchSubject.environment(OrchestratedResearchSubject.Role.SOURCE_VERIFIER));
        assertTrue(verifier.isSourceVerifier());
        assertFalse(verifier.isSearchPlanner() || verifier.isWriter());
        WorkerRoleSettings writer = WorkerRoleSettings.from(
                OrchestratedResearchSubject.environment(OrchestratedResearchSubject.Role.WRITER));
        assertTrue(writer.isWriter());
        assertFalse(writer.isSearchPlanner() || writer.isSourceVerifier());

        List<String> names = new ArrayList<String>();
        for (BenchmarkSubject subject : OrchestratedResearchSubject.all(null)) {
            names.add(subject.name());
        }
        assertEquals(Arrays.asList("orchestrated-run", "orchestrated+search-planner", "orchestrated+source-verifier",
                "orchestrated+writer"), names);
    }

    @Test
    public void theSubjectSelectionRefusesAnUnknownName() {
        assertEquals(5, ResearchBenchmarkLiveTest.select(null, Collections.<String>emptyList()).size());
        assertEquals(1, ResearchBenchmarkLiveTest.select(null, Collections.singletonList("orchestrated+writer"))
                .size());
        try {
            ResearchBenchmarkLiveTest.select(null, Collections.singletonList("orchestrated+typo"));
            throw new AssertionError("unknown subject accepted");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("orchestrated+typo"));
        }
    }

    /** The role observation reads the run's task record: rejections, contradictions, drafts as statements. */
    @Test
    public void theRoleObservationReadsTheTaskRecord() {
        OrchestratorRun run = OrchestratorRun.builder("run", "q", 1L).state(OrchestratorRun.State.CLOSED)
                .sections(ResearchOutline.ofQuestion("q").getSections()).envelope(4, 12, 0, 0).build();
        ResearchTask web = completed("run.web", "web-researcher",
                new TaskArtifact("w1", TaskArtifact.Kind.SOURCE_REFERENCE, "http://127.0.0.1:1/a Page A", "src-a", 2L),
                new TaskArtifact("w2", TaskArtifact.Kind.SOURCE_REFERENCE, "http://127.0.0.1:2/b Page B", "src-b", 2L));
        ResearchTask plan = completed("run.plan", "search-planner",
                new TaskArtifact("p1", TaskArtifact.Kind.TEXT, "query one", "planned-query", 2L));
        ResearchTask verify = completed("run.verify", "source-verifier",
                new TaskArtifact("v1", TaskArtifact.Kind.VERDICT, "off-topic: band page", "src-b", 2L),
                new TaskArtifact("v2", TaskArtifact.Kind.VERDICT, "supports: datasheet", "src-a", 2L),
                new TaskArtifact("v3", TaskArtifact.Kind.VERDICT, "4,200 vs 3,800 mAh (sources src-a, src-b)",
                        "contradiction:question", 2L));
        ResearchTask write = completed("run.write", "writer",
                new TaskArtifact("d1", TaskArtifact.Kind.TEXT, "First paragraph. [src-a]\n\nSecond one. [src-a, src-c]",
                        "draft:question", 2L));
        ResearchTask foreign = completed("other.web", "web-researcher",
                new TaskArtifact("f1", TaskArtifact.Kind.TEXT, "ignored", "planned-query", 2L));
        BenchmarkRunRecord record = new BenchmarkRunRecord("orchestrated+writer", "m", "q", 1);
        record.statements.add("The research looks sufficient.");

        OrchestratedResearchSubject.observeRoles(record, run, Arrays.asList(web, plan, verify, write, foreign));

        BenchmarkRunRecord.RoleObservation roles = record.getRoles();
        assertEquals(1, roles.getPlannerPackages());
        assertEquals(1, roles.getPlannedQueries());
        assertEquals(1, roles.getVerifierPackages());
        assertEquals(2, roles.getVerifiedSources());
        assertEquals(Collections.singletonList("http://127.0.0.1:2/b"), roles.getRejectedUrls());
        assertEquals(1, roles.getVerifierContradictions());
        assertTrue(record.reportedContradictions.get(0).contains("4,200 vs 3,800"));
        assertEquals(1, roles.getWriterPackages());
        assertEquals(2, roles.getDraftParagraphs());
        assertEquals("the drafts are the run's statements, citations stripped",
                Arrays.asList("First paragraph.", "Second one."), record.getStatements());
    }

    private static ResearchTask completed(String id, String role, TaskArtifact... artifacts) {
        ResearchTask task = ResearchTask.submit(id, "research", role, Collections.<String>emptyList(),
                new TaskInputs("query: q", Collections.singletonList("section:question")), "", 0,
                TaskBudget.unlimited(), 1, 1L).start("local", 2L);
        for (TaskArtifact artifact : artifacts) {
            task = task.withArtifact(artifact);
        }
        return task.complete(3L);
    }

    private static OrchestratorRun.Builder run(OrchestratorRun.State state) {
        return OrchestratorRun.builder("run-1", "q", 1L).state(state)
                .sections(ResearchOutline.ofQuestion("q").getSections());
    }

    @Test
    public void aClosedRunScoresAsTheExpectedSemanticOutcome() {
        assertEquals("SUFFICIENT_EVIDENCE",
                OrchestratedResearchSubject.stopReasonOf(run(OrchestratorRun.State.CLOSED).build()));
        assertEquals("RUN_AWAITING_APPROVAL",
                OrchestratedResearchSubject.stopReasonOf(run(OrchestratorRun.State.AWAITING_APPROVAL).build()));
    }

    @Test
    public void anInterruptionHasSettledOnceTheCycleJournaledIt() {
        assertFalse(OrchestratedResearchSubject.isSettled(run(OrchestratorRun.State.RUNNING)
                .journal("Plan approved by the user.").build()));
        assertTrue(OrchestratedResearchSubject.isSettled(run(OrchestratorRun.State.RUNNING)
                .journal("Interrupted; continue to resume.").build()));
        assertTrue(OrchestratedResearchSubject.isSettled(run(OrchestratorRun.State.STOPPED).build()));
    }
}
