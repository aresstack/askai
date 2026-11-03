package com.aresstack.askai.research.agent;

import com.aresstack.askai.research.runtime.orchestrator.OrchestratorRun;
import com.aresstack.askai.research.runtime.orchestrator.PlannedTask;
import com.aresstack.askai.research.runtime.orchestrator.ResearchOutline;
import org.junit.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ResearchStateViewRunTest {

    @Test
    public void aProposedPlanShowsItsPackagesAndTheEnvelope() {
        OrchestratorRun run = OrchestratorRun.builder("run-1", "Why PF4J?", 1L).state(OrchestratorRun.State.PLAN_PROPOSED)
                .sections(ResearchOutline.ofQuestion("Why PF4J?").getSections())
                .plan("one package", Collections.singletonList(new PlannedTask("p1", "web-researcher",
                        "pf4j plugin loading", "", Collections.singletonList(ResearchOutline.QUESTION_SECTION),
                        null)))
                .envelope(4, 12, 1000, 60_000L).build();
        List<String> lines = ResearchStateView.runLines(run);
        assertEquals("plan-proposed · Why PF4J?", lines.get(0));
        assertEquals("rounds 0/4 · packages 0/12 · tokens 0/1000 · minutes 0/1", lines.get(1));
        assertTrue(lines.contains("plan p1 · web-researcher · pf4j plugin loading"));
    }
}
