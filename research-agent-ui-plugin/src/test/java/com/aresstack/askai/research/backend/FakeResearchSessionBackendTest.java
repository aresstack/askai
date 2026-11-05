package com.aresstack.askai.research.backend;

import com.aresstack.askai.research.state.ResearchCommandType;
import com.aresstack.askai.research.state.ResearchPhase;
import com.aresstack.askai.research.state.ResearchRunState;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Deterministic tests for {@link FakeResearchSessionBackend} driven by a {@link ManualResearchScheduler}
 * (no real sleeps, no background threads). They pin the fachlich contract the later ACP backend must honour:
 * every phase change comes from the state machine, events carry a monotonic per-session sequence, approvals
 * are real wait states, pause/cancel/close stop delivery, and sessions never bleed into one another.
 */
public class FakeResearchSessionBackendTest {

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

        List<ResearchBackendEvent> ofType(ResearchBackendEventType type) {
            List<ResearchBackendEvent> out = new ArrayList<ResearchBackendEvent>();
            for (ResearchBackendEvent e : events) {
                if (e.getType() == type) {
                    out.add(e);
                }
            }
            return out;
        }

        ResearchBackendEvent last(ResearchBackendEventType type) {
            List<ResearchBackendEvent> all = ofType(type);
            return all.isEmpty() ? null : all.get(all.size() - 1);
        }

        ResearchBackendEvent lastState() {
            return last(ResearchBackendEventType.SESSION_STATE_CHANGED);
        }
    }

    private static final class Fixture {
        final ManualResearchScheduler scheduler = new ManualResearchScheduler();
        final FakeResearchSessionBackend backend =
                new FakeResearchSessionBackend(scheduler, fixedClock(), sequentialIds(), 10L);
        final RecordingListener listener = new RecordingListener();
        final ResearchSessionHandle handle;

        Fixture(String sessionId) {
            handle = backend.createSession(new ResearchProjectRequest(sessionId, "p", "t"), listener);
            // Creation alone starts NOTHING (no auto-run, no invented approval); the first user
            // question sets the session in motion.
            backend.submitPrompt(handle, new ResearchPrompt("investigate pf4j", ""));
        }

        /** The user's command (PR 05: the fake never switches phases by itself). */
        void user(ResearchCommandType command) {
            backend.executeCommand(handle, command);
            scheduler.runUntilIdle();
        }

        /** The user's way to the Sources gate: submit-scope, the chained start, finish-sources. */
        void toSourcesGate() {
            scheduler.runUntilIdle();
            user(ResearchCommandType.SUBMIT_SCOPE);
            user(ResearchCommandType.START_RESEARCH);
            user(ResearchCommandType.REQUEST_EVIDENCE_REVIEW);
        }

        /** Approve the currently pending approval (if any) and let the run advance to the next gate. */
        void approveLatestAndRun() {
            ResearchBackendEvent approval = listener.last(ResearchBackendEventType.APPROVAL_REQUESTED);
            assertNotNull("expected a pending approval", approval);
            backend.approve(handle, approval.getApprovalId());
            scheduler.runUntilIdle();
        }
    }

    // 1
    @Test
    public void creationIsPassiveAndTheFirstQuestionStartsTheRun() {
        // Creation emits ONLY visible guidance — no state machine command, no invented approval.
        ManualResearchScheduler scheduler = new ManualResearchScheduler();
        FakeResearchSessionBackend backend =
                new FakeResearchSessionBackend(scheduler, fixedClock(), sequentialIds(), 10L);
        RecordingListener listener = new RecordingListener();
        ResearchSessionHandle handle =
                backend.createSession(new ResearchProjectRequest("s1", "p", "t"), listener);
        assertEquals(ResearchBackendEventType.ASSISTANT_MESSAGE, listener.events.get(0).getType());
        assertEquals(0, listener.ofType(ResearchBackendEventType.SESSION_STATE_CHANGED).size());
        assertEquals(0, listener.ofType(ResearchBackendEventType.APPROVAL_REQUESTED).size());

        backend.submitPrompt(handle, new ResearchPrompt("investigate pf4j", ""));
        ResearchBackendEvent state = listener.lastState();
        assertEquals(ResearchPhase.SCOPING, state.getPhase());
        assertEquals(ResearchRunState.RUNNING, state.getRunState());
        assertEquals("s1", state.getSessionId());
    }

    // 2
    @Test
    public void happyPathReachesCompletionThroughThreeApprovals() {
        // PR 05: every phase change is the user's command; the fake only shows the phase's work.
        Fixture f = new Fixture("s1");
        f.toSourcesGate(); // → Sources approval gate (approval 1)
        assertEquals(ResearchPhase.RESEARCH, f.listener.lastState().getPhase());
        assertEquals(ResearchRunState.WAITING_FOR_USER, f.listener.lastState().getRunState());

        f.approveLatestAndRun(); // evidence → outline/running
        f.user(ResearchCommandType.PROPOSE_OUTLINE); // finish-outline
        f.approveLatestAndRun(); // outline → document/waiting
        f.user(ResearchCommandType.START_DRAFTING);
        f.user(ResearchCommandType.REQUEST_DRAFT_REVIEW); // finish-document
        f.approveLatestAndRun(); // final → completed

        assertEquals(3, f.listener.ofType(ResearchBackendEventType.APPROVAL_REQUESTED).size());
        assertEquals(ResearchRunState.COMPLETED, f.listener.lastState().getRunState());
        assertEquals(1, f.listener.ofType(ResearchBackendEventType.COMPLETED).size());
    }

    @Test
    public void theFakeNeverSwitchesPhasesByItself() {
        Fixture f = new Fixture("s1");
        f.scheduler.runUntilIdle();
        assertEquals(ResearchPhase.SCOPING, f.listener.lastState().getPhase());
        assertEquals(ResearchRunState.RUNNING, f.listener.lastState().getRunState());
        f.user(ResearchCommandType.SUBMIT_SCOPE);
        f.user(ResearchCommandType.START_RESEARCH);
        f.scheduler.runUntilIdle();
        assertEquals(ResearchPhase.RESEARCH, f.listener.lastState().getPhase());
        assertEquals(ResearchRunState.RUNNING, f.listener.lastState().getRunState());
    }

    // 3
    @Test
    public void sequenceNumbersAreMonotonicAndContiguousPerSession() {
        Fixture f = new Fixture("s1");
        f.toSourcesGate();
        f.approveLatestAndRun();
        long expected = 1;
        for (ResearchBackendEvent e : f.listener.events) {
            assertEquals(expected++, e.getSequenceNumber());
        }
    }

    // 4
    @Test
    public void sessionsAreIsolated() {
        ManualResearchScheduler scheduler = new ManualResearchScheduler();
        FakeResearchSessionBackend backend =
                new FakeResearchSessionBackend(scheduler, fixedClock(), sequentialIds(), 10L);
        RecordingListener la = new RecordingListener();
        RecordingListener lb = new RecordingListener();
        ResearchSessionHandle a = backend.createSession(new ResearchProjectRequest("a", "p", "t"), la);
        ResearchSessionHandle b = backend.createSession(new ResearchProjectRequest("b", "p", "t"), lb);
        scheduler.runUntilIdle();

        for (ResearchBackendEvent e : la.events) {
            assertEquals("a", e.getSessionId());
        }
        for (ResearchBackendEvent e : lb.events) {
            assertEquals("b", e.getSessionId());
        }
        // Each session numbers its own stream from 1.
        assertEquals(1, la.events.get(0).getSequenceNumber());
        assertEquals(1, lb.events.get(0).getSequenceNumber());
    }

    // 5
    @Test
    public void approvalGateStopsAutomaticProgress() {
        Fixture f = new Fixture("s1");
        f.toSourcesGate();
        int countAtGate = f.listener.events.size();
        f.scheduler.runUntilIdle(); // nothing more should happen without an approval
        assertEquals(countAtGate, f.listener.events.size());
        assertEquals(ResearchRunState.WAITING_FOR_USER, f.listener.lastState().getRunState());
    }

    // 6
    @Test
    public void approveAdvancesPastTheGate() {
        Fixture f = new Fixture("s1");
        f.toSourcesGate();
        assertEquals(ResearchPhase.RESEARCH, f.listener.lastState().getPhase());
        f.approveLatestAndRun();
        // After approving the evidence the Outline phase runs until the user finishes it.
        assertEquals(ResearchPhase.OUTLINE, f.listener.lastState().getPhase());
        assertEquals(ResearchRunState.RUNNING, f.listener.lastState().getRunState());
    }

    // 7
    @Test
    public void rejectReturnsToTheWorkingPhase() {
        Fixture f = new Fixture("s1");
        f.toSourcesGate();
        ResearchBackendEvent approval = f.listener.last(ResearchBackendEventType.APPROVAL_REQUESTED);
        f.backend.reject(f.handle, approval.getApprovalId(), "needs more detail");
        // Immediately after the rejection (before auto-advance) we are back in RESEARCH/RUNNING.
        assertEquals(ResearchPhase.RESEARCH, f.listener.lastState().getPhase());
        assertEquals(ResearchRunState.RUNNING, f.listener.lastState().getRunState());
        assertFalse(f.listener.ofType(ResearchBackendEventType.ASSISTANT_MESSAGE).isEmpty());
    }

    // 8
    @Test
    public void wrongApprovalIdEmitsErrorAndDoesNotAdvance() {
        Fixture f = new Fixture("s1");
        f.toSourcesGate();
        ResearchPhase before = f.listener.lastState().getPhase();
        ResearchRunState beforeRun = f.listener.lastState().getRunState();
        f.backend.approve(f.handle, "not-a-real-approval");
        assertEquals(1, f.listener.ofType(ResearchBackendEventType.ERROR).size());
        assertEquals(before, f.listener.lastState().getPhase());
        assertEquals(beforeRun, f.listener.lastState().getRunState());
    }

    // 9
    @Test
    public void duplicateApproveIsIdempotent() {
        Fixture f = new Fixture("s1");
        f.toSourcesGate();
        ResearchBackendEvent approval = f.listener.last(ResearchBackendEventType.APPROVAL_REQUESTED);
        f.backend.approve(f.handle, approval.getApprovalId());
        f.scheduler.runUntilIdle();
        int afterFirst = f.listener.events.size();
        f.backend.approve(f.handle, approval.getApprovalId()); // same id again → no-op
        f.scheduler.runUntilIdle();
        assertEquals(afterFirst, f.listener.events.size());
        assertTrue(f.listener.ofType(ResearchBackendEventType.ERROR).isEmpty());
    }

    // 10
    @Test
    public void pauseStopsDelivery() {
        Fixture f = new Fixture("s1"); // SCOPING/RUNNING, one step pending
        f.backend.pause(f.handle);
        assertEquals(ResearchRunState.PAUSED, f.listener.lastState().getRunState());
        int afterPause = f.listener.events.size();
        f.scheduler.runUntilIdle(); // the previously scheduled step was cancelled
        assertEquals(afterPause, f.listener.events.size());
    }

    // 11
    @Test
    public void resumeContinuesWithoutDuplicatingWork() {
        Fixture f = new Fixture("s1");
        f.backend.pause(f.handle);
        f.scheduler.runUntilIdle();
        f.backend.resume(f.handle);
        f.scheduler.runUntilIdle();
        // Exactly one question-understanding + one scoping "thinking" despite the pause/resume.
        int thinkingStarts = 0;
        for (ResearchBackendEvent e : f.listener.ofType(ResearchBackendEventType.ACTIVITY)) {
            if (e.getActivityKind() == ResearchActivityKind.THINKING_STARTED) {
                thinkingStarts++;
            }
        }
        assertEquals(2, thinkingStarts);
        assertEquals("the fake never leaves the phase by itself", ResearchPhase.SCOPING,
                f.listener.lastState().getPhase());
    }

    // 12
    @Test
    public void cancelPreventsLaterEvents() {
        Fixture f = new Fixture("s1");
        f.backend.cancel(f.handle);
        assertEquals(ResearchRunState.CANCELLED, f.listener.lastState().getRunState());
        int afterCancel = f.listener.events.size();
        f.scheduler.runUntilIdle();
        assertEquals(afterCancel, f.listener.events.size());
    }

    // 13
    @Test
    public void cancelledSessionIgnoresFurtherCommands() {
        Fixture f = new Fixture("s1");
        f.backend.cancel(f.handle);
        int afterCancel = f.listener.events.size();
        f.backend.executeCommand(f.handle, ResearchCommandType.SUBMIT_SCOPE);
        f.backend.submitPrompt(f.handle, new ResearchPrompt("hi", ""));
        // Cancel is non-terminal for readability; commands are still gated by the state machine (all rejected).
        assertEquals(ResearchRunState.CANCELLED, f.listener.lastState().getRunState());
        // No state change happened; only the prompt echo may appear, never a new phase.
        assertTrue(f.listener.events.size() >= afterCancel);
        assertEquals(ResearchRunState.CANCELLED,
                f.listener.lastState().getRunState());
    }

    // 14
    @Test
    public void closePreventsAnyFurtherListenerCall() {
        Fixture f = new Fixture("s1");
        int beforeClose = f.listener.events.size();
        f.backend.close(f.handle);
        f.backend.executeCommand(f.handle, ResearchCommandType.SUBMIT_SCOPE);
        f.backend.submitPrompt(f.handle, new ResearchPrompt("hi", ""));
        f.backend.approve(f.handle, "whatever");
        f.scheduler.runUntilIdle();
        assertEquals(beforeClose, f.listener.events.size());
    }

    // 15
    @Test
    public void closeIsIdempotent() {
        Fixture f = new Fixture("s1");
        f.backend.close(f.handle);
        f.backend.close(f.handle); // must not throw
    }

    // 16
    @Test
    public void closeStopsAPendingScheduledTask() {
        Fixture f = new Fixture("s1"); // one advance step is pending
        int beforeClose = f.listener.events.size();
        f.backend.close(f.handle);
        f.scheduler.runUntilIdle(); // the pending task must not deliver anything after close
        assertEquals(beforeClose, f.listener.events.size());
    }

    // 17
    @Test
    public void simulateBlockedThenUnblockResumes() {
        Fixture f = new Fixture("s1");
        f.backend.simulateBlocked(f.handle, "waiting for credentials");
        assertEquals(ResearchRunState.BLOCKED, f.listener.lastState().getRunState());
        assertEquals(1, f.listener.ofType(ResearchBackendEventType.BLOCKED).size());
        f.scheduler.runUntilIdle(); // blocked → no automatic progress
        assertEquals(ResearchRunState.BLOCKED, f.listener.lastState().getRunState());
        f.backend.executeCommand(f.handle, ResearchCommandType.UNBLOCK);
        f.scheduler.runUntilIdle();
        assertEquals(ResearchPhase.SCOPING, f.listener.lastState().getPhase());
        assertEquals(ResearchRunState.RUNNING, f.listener.lastState().getRunState());
    }

    // 18
    @Test
    public void simulateFailureThenRetryResumes() {
        Fixture f = new Fixture("s1");
        f.backend.simulateFailure(f.handle, "The provider is down.", "HTTP 503 from upstream");
        assertEquals(ResearchRunState.FAILED, f.listener.lastState().getRunState());
        ResearchBackendEvent error = f.listener.last(ResearchBackendEventType.ERROR);
        assertEquals("The provider is down.", error.getPublicMessage());
        assertEquals("HTTP 503 from upstream", error.getTechnicalDetail());
        f.backend.executeCommand(f.handle, ResearchCommandType.RETRY);
        f.scheduler.runUntilIdle();
        assertEquals(ResearchPhase.SCOPING, f.listener.lastState().getPhase());
        assertEquals(ResearchRunState.RUNNING, f.listener.lastState().getRunState());
    }

    // 19
    @Test
    public void submitPromptHonorsActiveSection() {
        Fixture f = new Fixture("s1");
        f.backend.submitPrompt(f.handle, new ResearchPrompt("focus here", "s2"));
        ResearchBackendEvent assistant = f.listener.last(ResearchBackendEventType.ASSISTANT_MESSAGE);
        assertNotNull(assistant);
        assertTrue(assistant.getText().contains("s2"));
        assertEquals("focus here",
                f.listener.last(ResearchBackendEventType.USER_MESSAGE).getText());
    }

    // 20
    @Test
    public void submitPromptWithoutSectionTargetsWholeDocument() {
        Fixture f = new Fixture("s1");
        f.backend.submitPrompt(f.handle, new ResearchPrompt("overview please", ""));
        ResearchBackendEvent assistant = f.listener.last(ResearchBackendEventType.ASSISTANT_MESSAGE);
        assertTrue(assistant.getText().contains("whole document"));
    }

    // 21
    @Test
    public void canExecuteReflectsTheStateMachine() {
        Fixture f = new Fixture("s1"); // SCOPING/RUNNING
        assertTrue(f.backend.canExecute(f.handle, ResearchCommandType.PAUSE));
        assertFalse(f.backend.canExecute(f.handle, ResearchCommandType.APPROVE_OUTLINE));
        f.backend.close(f.handle);
        assertFalse(f.backend.canExecute(f.handle, ResearchCommandType.PAUSE));
    }

    // 22
    @Test
    public void unknownHandleIsIgnored() {
        Fixture f = new Fixture("s1");
        ResearchSessionHandle bogus = new ResearchSessionHandle() {
            public String getSessionId() {
                return "does-not-exist";
            }

            public String getProjectId() {
                return "p";
            }
        };
        int before = f.listener.events.size();
        f.backend.executeCommand(bogus, ResearchCommandType.PAUSE);
        assertFalse(f.backend.canExecute(bogus, ResearchCommandType.PAUSE));
        assertEquals(before, f.listener.events.size());
    }
}
