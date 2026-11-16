package com.aresstack.askai.research.backend;

import com.aresstack.askai.research.state.ResearchCommandType;
import com.aresstack.askai.research.state.oo.ResearchStateIds;
import com.aresstack.askai.research.state.oo.ResearchStateMemento;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Commit 22 at the backend boundary: SESSION_STATE_CHANGED events carry the exact {@link ResearchStateMemento},
 * and blocking then unblocking an approval gate through the live backend restores the very same approval id.
 */
public class MementoBackendEventTest {

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

    private static final class RecordingListener implements ResearchSessionListener {
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

    @Test
    public void everyStateChangeCarriesAConsistentMemento() {
        ManualResearchScheduler scheduler = new ManualResearchScheduler();
        FakeResearchSessionBackend backend =
                new FakeResearchSessionBackend(scheduler, fixedClock(), sequentialIds(), 10L);
        RecordingListener listener = new RecordingListener();
        ResearchSessionHandle handle =
                backend.createSession(new ResearchProjectRequest("s", "p", "t"), listener);
        // The session no longer auto-runs on creation: the FIRST user question starts it.
        backend.submitPrompt(handle, new ResearchPrompt("investigate pf4j", ""));
        FakeUserCommands.toSourcesGate(backend, handle, scheduler);

        int stateChanges = 0;
        for (ResearchBackendEvent e : listener.events) {
            if (e.getType() == ResearchBackendEventType.SESSION_STATE_CHANGED) {
                stateChanges++;
                ResearchStateMemento m = e.getStateMemento();
                assertNotNull("state-change events must carry the memento", m);
                // Legacy getters must be derived from the memento (consistent, not a parallel field).
                assertEquals(ResearchStateIds.phase(m.getPhaseId()), e.getPhase());
                assertEquals(ResearchStateIds.runState(m.getStateId()), e.getRunState());
                assertEquals(m.getRevision(), e.getRevision());
            }
        }
        assertTrue("expected several state changes on the way to the first approval", stateChanges >= 3);

        ResearchBackendEvent lastState = listener.last(ResearchBackendEventType.SESSION_STATE_CHANGED);
        assertNotNull(lastState);
        assertEquals(ResearchStateIds.RESEARCH, lastState.getStateMemento().getPhaseId());
        assertEquals(ResearchStateIds.WAITING_APPROVAL, lastState.getStateMemento().getStateId());
        assertNotNull(lastState.getStateMemento().getPendingApprovalId());
    }

    @Test
    public void approvalIdSurvivesBlockAndUnblockThroughTheBackend() {
        ManualResearchScheduler scheduler = new ManualResearchScheduler();
        FakeResearchSessionBackend backend =
                new FakeResearchSessionBackend(scheduler, fixedClock(), sequentialIds(), 10L);
        RecordingListener listener = new RecordingListener();
        ResearchSessionHandle handle =
                backend.createSession(new ResearchProjectRequest("s", "p", "t"), listener);
        // The session no longer auto-runs on creation: the FIRST user question starts it.
        backend.submitPrompt(handle, new ResearchPrompt("investigate pf4j", ""));
        FakeUserCommands.toSourcesGate(backend, handle, scheduler);

        ResearchBackendEvent approval = listener.last(ResearchBackendEventType.APPROVAL_REQUESTED);
        assertNotNull("expected to reach the evidence approval gate", approval);
        String originalApprovalId = approval.getApprovalId();

        backend.simulateBlocked(handle, "network down");
        assertEquals(ResearchStateIds.BLOCKED,
                listener.last(ResearchBackendEventType.SESSION_STATE_CHANGED).getStateMemento().getStateId());

        backend.executeCommand(handle, ResearchCommandType.UNBLOCK);
        scheduler.runUntilIdle();

        ResearchStateMemento restored =
                listener.last(ResearchBackendEventType.SESSION_STATE_CHANGED).getStateMemento();
        assertEquals(ResearchStateIds.WAITING_APPROVAL, restored.getStateId());
        assertEquals("the same approval gate must be restored", originalApprovalId,
                restored.getPendingApprovalId());
        // The backend re-announces the same approval id (not a fresh one) after unblocking.
        assertEquals(originalApprovalId,
                listener.last(ResearchBackendEventType.APPROVAL_REQUESTED).getApprovalId());

        // The restored gate is actionable: approving advances the run.
        backend.approve(handle, originalApprovalId);
        scheduler.runUntilIdle();
        ResearchStateMemento afterApprove =
                listener.last(ResearchBackendEventType.SESSION_STATE_CHANGED).getStateMemento();
        assertTrue("approval must move past the evidence gate",
                !ResearchStateIds.RESEARCH.equals(afterApprove.getPhaseId())
                        || !ResearchStateIds.WAITING_APPROVAL.equals(afterApprove.getStateId()));
    }
}
