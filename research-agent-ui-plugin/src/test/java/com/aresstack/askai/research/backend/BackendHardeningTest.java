package com.aresstack.askai.research.backend;

import com.aresstack.askai.research.state.ResearchCommandType;
import com.aresstack.askai.research.state.oo.ResearchStateIds;
import com.aresstack.askai.research.state.oo.ResearchStateMemento;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Hardening of the memento backend: the state machine shares the backend's injected id/clock (so identical runs
 * are byte-for-byte deterministic), {@code canExecute} is a pure query that consumes no id, and an approval is
 * only marked processed after an accepted transition (a no-op reject never wedges the gate).
 */
public class BackendHardeningTest {

    private static ResearchIdGenerator sequentialIds() {
        final AtomicInteger counter = new AtomicInteger();
        return new ResearchIdGenerator() {
            public String newId() {
                return "id-" + counter.incrementAndGet();
            }
        };
    }

    private static ResearchClock fixedClock() {
        return new ResearchClock() {
            public long now() {
                return 1_000L;
            }
        };
    }

    private static final class Recorder implements ResearchSessionListener {
        final List<ResearchBackendEvent> events = new ArrayList<ResearchBackendEvent>();

        public void onEvent(ResearchBackendEvent event) {
            events.add(event);
        }

        ResearchBackendEvent last(ResearchBackendEventType type) {
            ResearchBackendEvent found = null;
            for (ResearchBackendEvent e : events) {
                if (e.getType() == type) {
                    found = e;
                }
            }
            return found;
        }
    }

    /** Drive a fresh backend to the first approval gate and return the approval id it produced. */
    private static String firstApprovalId(boolean probeCanExecuteBeforeRunning) {
        ManualResearchScheduler scheduler = new ManualResearchScheduler();
        FakeResearchSessionBackend backend =
                new FakeResearchSessionBackend(scheduler, fixedClock(), sequentialIds(), 10L);
        Recorder recorder = new Recorder();
        ResearchSessionHandle handle =
                backend.createSession(new ResearchProjectRequest("s", "p", "t"), recorder);
        // Creation is passive now: the first user question starts the run.
        backend.submitPrompt(handle, new ResearchPrompt("investigate pf4j", ""));
        if (probeCanExecuteBeforeRunning) {
            // Hammer the pure enablement query; it must not consume any id from the shared generator.
            for (int i = 0; i < 25; i++) {
                backend.canExecute(handle, ResearchCommandType.PAUSE);
                backend.canExecute(handle, ResearchCommandType.APPROVE_OUTLINE);
                backend.canExecute(handle, ResearchCommandType.CANCEL);
            }
        }
        FakeUserCommands.toSourcesGate(backend, handle, scheduler);
        ResearchBackendEvent approval = recorder.last(ResearchBackendEventType.APPROVAL_REQUESTED);
        assertNotNull(approval);
        return approval.getApprovalId();
    }

    @Test
    public void identicalRunsAreDeterministic() {
        assertEquals("same command sequence + same generators must yield the same approval id",
                firstApprovalId(false), firstApprovalId(false));
    }

    @Test
    public void canExecuteConsumesNoIds() {
        assertEquals("probing canExecute must not perturb the id stream",
                firstApprovalId(false), firstApprovalId(true));
    }

    @Test
    public void noOpRejectDoesNotWedgeTheGate() {
        ManualResearchScheduler scheduler = new ManualResearchScheduler();
        FakeResearchSessionBackend backend =
                new FakeResearchSessionBackend(scheduler, fixedClock(), sequentialIds(), 10L);
        Recorder recorder = new Recorder();
        ResearchSessionHandle handle =
                backend.createSession(new ResearchProjectRequest("s", "p", "t"), recorder);
        // Creation is passive now: the first user question starts the run.
        backend.submitPrompt(handle, new ResearchPrompt("investigate pf4j", ""));

        // #43: advance to the FINAL approval gate — Document ("draft") waiting_approval. In
        // the 4-phase model every gate has a request-changes command, so a reject RETURNS to
        // drafting instead of being a no-op; the gate machinery must survive that round trip.
        FakeUserCommands.toSourcesGate(backend, handle, scheduler);
        backend.approve(handle, recorder.last(ResearchBackendEventType.APPROVAL_REQUESTED).getApprovalId());
        FakeUserCommands.run(backend, handle, scheduler, ResearchCommandType.PROPOSE_OUTLINE);
        backend.approve(handle, recorder.last(ResearchBackendEventType.APPROVAL_REQUESTED).getApprovalId());
        FakeUserCommands.run(backend, handle, scheduler, ResearchCommandType.START_DRAFTING,
                ResearchCommandType.REQUEST_DRAFT_REVIEW);
        ResearchStateMemento m = recorder.last(ResearchBackendEventType.SESSION_STATE_CHANGED)
                .getStateMemento();
        String finalGateId = ResearchStateIds.DRAFT.equals(m.getPhaseId())
                && ResearchStateIds.WAITING_APPROVAL.equals(m.getStateId())
                ? recorder.last(ResearchBackendEventType.APPROVAL_REQUESTED).getApprovalId() : null;
        assertNotNull("expected to reach the Document approval gate", finalGateId);

        // Reject → back to drafting.
        backend.reject(handle, finalGateId, "nope");
        assertEquals(ResearchStateIds.RUNNING,
                recorder.last(ResearchBackendEventType.SESSION_STATE_CHANGED)
                        .getStateMemento().getStateId());
        // PR 05: the user finishes the document again; the gate is raised with a FRESH id.
        FakeUserCommands.run(backend, handle, scheduler, ResearchCommandType.REQUEST_DRAFT_REVIEW);
        ResearchBackendEvent reraised = recorder.last(ResearchBackendEventType.APPROVAL_REQUESTED);
        assertNotNull(reraised);
        assertFalse("a NEW approval id after the round trip", finalGateId.equals(
                reraised.getApprovalId()));

        // The re-raised gate completes the run.
        backend.approve(handle, reraised.getApprovalId());
        scheduler.runUntilIdle();
        assertTrue("the gate must stay actionable after the reject round trip",
                recorder.last(ResearchBackendEventType.COMPLETED) != null
                        || ResearchStateIds.COMPLETED.equals(
                        recorder.last(ResearchBackendEventType.SESSION_STATE_CHANGED)
                                .getStateMemento().getStateId()));
    }
}
