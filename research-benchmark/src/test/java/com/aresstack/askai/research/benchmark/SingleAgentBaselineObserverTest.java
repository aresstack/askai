package com.aresstack.askai.research.benchmark;

import com.aresstack.askai.research.backend.ResearchBackendEvent;
import com.aresstack.askai.research.backend.ResearchBackendEventType;
import com.aresstack.askai.research.backend.ResearchRunOutcomeInfo;
import com.aresstack.askai.research.backend.ResearchRunProgressInfo;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** How the baseline turns the session's events into the run's numbers (no browser, no model). */
public class SingleAgentBaselineObserverTest {

    @Test
    public void eventsBecomeToolCallsFailuresStatementsAndOutcomes() throws Exception {
        SingleAgentBaselineSubject.Observer observer = new SingleAgentBaselineSubject.Observer();
        observer.onEvent(progress(0, 3));
        assertEquals("no source yet", 1, observer.firstSource.getCount());
        observer.onEvent(progress(1, 7));
        observer.onEvent(progress(2, 5)); // progress events may arrive out of order: the maximum counts
        observer.onEvent(ResearchBackendEvent.builder(ResearchBackendEventType.PROBLEM_REPORTED).text("x").build());
        observer.onEvent(ResearchBackendEvent.builder(ResearchBackendEventType.ERROR).text("y").build());
        observer.onEvent(ResearchBackendEvent.builder(ResearchBackendEventType.ASSISTANT_MESSAGE)
                .messages("I found three sources.", "").build());
        observer.onEvent(ResearchBackendEvent.builder(ResearchBackendEventType.ASSISTANT_MESSAGE)
                .text("  ").build());
        assertFalse("no outcome yet", observer.awaitOutcomes(1, 0));
        assertTrue("a source was accepted before any outcome", observer.awaitFirstSourceBeforeOutcome(1));
        observer.onEvent(ResearchBackendEvent.builder(ResearchBackendEventType.RUN_OUTCOME)
                .runOutcome(new ResearchRunOutcomeInfo("p", "MAX_PAGES", 9, 2, 1, 3, 2, true,
                        "Only one host was found.", "Search again"))
                .build());

        assertEquals(0, observer.firstSource.getCount());
        assertEquals(7, observer.toolCalls.get());
        assertEquals(2, observer.failures.get());
        assertEquals(2, observer.statements.size());
        assertEquals("Only one host was found.", observer.statements.get(1));
        assertTrue(observer.awaitOutcomes(1, 1));
        assertEquals("MAX_PAGES", observer.lastOutcome.getStopReason());
    }

    @Test
    public void aRunThatEndsWithoutASourceCannotBeInterrupted() throws Exception {
        SingleAgentBaselineSubject.Observer observer = new SingleAgentBaselineSubject.Observer();
        observer.onEvent(ResearchBackendEvent.builder(ResearchBackendEventType.RUN_OUTCOME)
                .runOutcome(new ResearchRunOutcomeInfo("p", "NO_RESULTS", 0, 0, 0, 3, 2, true, "", ""))
                .build());
        long start = System.nanoTime();
        assertFalse(observer.awaitFirstSourceBeforeOutcome(30));
        assertTrue("returns on the outcome, not after the timeout", System.nanoTime() - start < 5_000_000_000L);
    }

    private static ResearchBackendEvent progress(int accepted, int toolCalls) {
        return ResearchBackendEvent.builder(ResearchBackendEventType.RUN_PROGRESS)
                .runProgress(new ResearchRunProgressInfo("p", 1, accepted, 1, toolCalls, "", ""))
                .build();
    }
}
