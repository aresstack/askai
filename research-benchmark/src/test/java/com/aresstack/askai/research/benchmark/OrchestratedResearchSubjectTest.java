package com.aresstack.askai.research.benchmark;

import com.aresstack.askai.research.runtime.orchestrator.OrchestratorSettings;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class OrchestratedResearchSubjectTest {

    @Test
    public void theSubjectSwitchesOnTheOrchestratedModeAndKeepsEveryOtherDefault() {
        OrchestratorSettings settings = OrchestratorSettings.from(OrchestratedResearchSubject.environment());
        assertEquals(OrchestratorSettings.Mode.ORCHESTRATED, settings.getMode());
        assertEquals(OrchestratorSettings.DEFAULT_MAX_ROUNDS, settings.getMaxRounds());
        assertEquals(OrchestratorSettings.DEFAULT_TOKEN_BUDGET, settings.getTokenBudget());
    }
}
