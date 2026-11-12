package com.aresstack.askai.research.agent;

import com.aresstack.askai.research.agentwire.ResearchAgentEventMapper;
import com.aresstack.askai.research.backend.ResearchBackendEvent;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Live run 4 (2026-11-10): the search plan got the German chat line of a failed planned query instead of its reason
 * token, so its NO_WORKER_BROWSER branch never ran and a team without a free tab paused the plan as a failing
 * engine. The plan reads the reason token of a failed search and the text of every other event.
 */
public class PlanEventDetailTest {

    @Test
    public void aFailedPlannedQueryHandsTheReasonTokenToThePlan() {
        ResearchBackendEvent failed = ResearchAgentEventMapper.mapMessage("p1",
                "#RSX1# manual_search_failed request_id=R1 reason=NO_WORKER_BROWSER").build();
        assertEquals("NO_WORKER_BROWSER", ResearchAgentSession.planEventDetail(failed));
    }

    @Test
    public void everyOtherEventHandsItsText() {
        ResearchBackendEvent completed = ResearchAgentEventMapper.mapMessage("p1",
                "#RSX1# manual_search_completed request_id=R1 results=3 status=RESULTS").build();
        assertEquals("3 Treffer", ResearchAgentSession.planEventDetail(completed));
    }
}
