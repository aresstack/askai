package com.aresstack.askai.research.benchmark;

import com.aresstack.askai.research.runtime.orchestrator.OrchestratorRun;
import com.aresstack.askai.research.runtime.orchestrator.OrchestratorSettings;
import com.aresstack.askai.research.runtime.orchestrator.ResearchOutline;
import org.junit.Test;

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
