package com.aresstack.askai.research.backend;

import com.aresstack.askai.research.state.ResearchCommand;
import com.aresstack.askai.research.state.ResearchCommandType;
import com.aresstack.askai.research.state.ResearchPhase;
import com.aresstack.askai.research.state.ResearchRunState;
import com.aresstack.askai.research.state.oo.OoResearchStateMachine;
import com.aresstack.askai.research.state.oo.ResearchStateIds;
import com.aresstack.askai.research.state.oo.ResearchStateMachinePort;
import com.aresstack.askai.research.state.oo.ResearchStateMemento;
import com.aresstack.askai.research.state.oo.ResearchStateTransitionResult;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A deterministic, event-driven simulation of a research agent behind {@link ResearchSessionBackend}. Its single
 * source of truth is the hierarchical OO {@link ResearchStateMemento}, advanced only through the native
 * {@link ResearchStateMachinePort} — never a legacy phase/run-state pair. The memento carries the exact
 * continuation of any interruption and the pending approval id, so pausing/blocking/failing an approval gate and
 * then resuming restores exactly the same gate with the same approval id. The run advances via the injected
 * {@link ResearchScheduler}; per session, events are delivered serially with a monotonic sequence number and
 * carry the state memento on every state change. {@code simulateBlocked}/{@code simulateFailure} are dev actions
 * producing recoverable BLOCKED and technical FAILED states.
 */
public final class FakeResearchSessionBackend implements ResearchSessionBackend {

    private final ResearchScheduler scheduler;
    private final ResearchClock clock;
    private final ResearchIdGenerator idGenerator;
    private final long stepDelayMillis;
    private final Map<String, FakeSession> sessions = new ConcurrentHashMap<String, FakeSession>();
    /** Phrases the fake agent's conversational texts (variant 0 = the playbook wording). */
    private final com.aresstack.askai.research.agent.ResearchNarrator narrator;

    /** English-default demo backend (tests). */
    public FakeResearchSessionBackend(ResearchScheduler scheduler, ResearchClock clock,
                                      ResearchIdGenerator idGenerator, long stepDelayMillis) {
        this(scheduler, clock, idGenerator, stepDelayMillis,
                com.aresstack.askai.research.agent.ResearchLanguage.ENGLISH);
    }

    /** Demo backend speaking the given language (the factory passes the persisted session default). */
    public FakeResearchSessionBackend(ResearchScheduler scheduler, ResearchClock clock,
                                      ResearchIdGenerator idGenerator, long stepDelayMillis,
                                      com.aresstack.askai.research.agent.ResearchLanguage language) {
        this.scheduler = scheduler;
        this.clock = clock;
        this.idGenerator = idGenerator;
        this.stepDelayMillis = stepDelayMillis;
        this.narrator = new com.aresstack.askai.research.agent.StaticNarrator(
                new com.aresstack.askai.research.agent.ResearchPlaybook(language));
    }

    @Override
    public ResearchSessionHandle createSession(ResearchProjectRequest request,
                                               ResearchSessionListener listener) {
        FakeSession session = new FakeSession(request.getSessionId(), request.getProjectId(), listener,
                idGenerator, clock);
        sessions.put(session.sessionId, session);
        // Deliberately NOTHING is dispatched here: no auto-run, no invented outline, no approval on
        // activation. The session waits for the USER'S research question; guidance is visible instead.
        synchronized (session) {
            emit(session, ResearchBackendEvent.builder(ResearchBackendEventType.ASSISTANT_MESSAGE)
                    .text(narrator.greeting()), null);
        }
        return session;
    }

    @Override
    public boolean canExecute(ResearchSessionHandle handle, ResearchCommandType command) {
        FakeSession session = resolve(handle);
        if (session == null || session.closed) {
            return false;
        }
        synchronized (session) {
            // Pure enablement check: no probe transition, so no id is consumed by merely asking.
            return session.machine.allowedCommands(session.state).contains(command);
        }
    }

    @Override
    public void executeCommand(ResearchSessionHandle handle, ResearchCommandType command) {
        FakeSession session = resolve(handle);
        if (session == null || session.closed) {
            return;
        }
        synchronized (session) {
            if (dispatch(session, command, null)) {
                scheduleAdvance(session);
            }
        }
    }

    @Override
    public void submitPrompt(ResearchSessionHandle handle, ResearchPrompt prompt) {
        FakeSession session = resolve(handle);
        if (session == null || session.closed) {
            return;
        }
        synchronized (session) {
            emit(session, ResearchBackendEvent.builder(ResearchBackendEventType.USER_MESSAGE)
                    .text(prompt.getText()), null);
            ResearchRunState run = runOf(session);
            if (run == ResearchRunState.PAUSED || run == ResearchRunState.CANCELLED
                    || run == ResearchRunState.FAILED || run == ResearchRunState.COMPLETED) {
                emit(session, ResearchBackendEvent.builder(ResearchBackendEventType.ASSISTANT_MESSAGE)
                        .text("The agent is " + run + " and cannot act on this prompt right now."), null);
                return;
            }
            // The FIRST user text is the research question: only now does the session start moving.
            if (session.machine.allowedCommands(session.state).contains(ResearchCommandType.START)) {
                session.researchQuestion = prompt.getText();
                String thinkId = idGenerator.newId();
                emit(session, ResearchBackendEvent.builder(ResearchBackendEventType.ACTIVITY)
                        .activity(thinkId, ResearchActivityKind.THINKING_STARTED, "Thinking",
                                "Understanding the research question"), null);
                emit(session, ResearchBackendEvent.builder(ResearchBackendEventType.ACTIVITY)
                        .activity(thinkId, ResearchActivityKind.THINKING_FINISHED, "Thinking",
                                "Question captured"), null);
                dispatch(session, ResearchCommandType.START, null);
                scheduleAdvance(session);
                return;
            }
            String section = prompt.getActiveSectionId().isEmpty()
                    ? "the whole document" : "section " + prompt.getActiveSectionId();
            String activityId = idGenerator.newId();
            emit(session, ResearchBackendEvent.builder(ResearchBackendEventType.ACTIVITY)
                    .activity(activityId, ResearchActivityKind.THINKING_STARTED, "Thinking",
                            "Interpreting the request about " + section), null);
            emit(session, ResearchBackendEvent.builder(ResearchBackendEventType.ACTIVITY)
                    .activity(activityId, ResearchActivityKind.THINKING_FINISHED, "Thinking", "Ready"), null);
            emit(session, ResearchBackendEvent.builder(ResearchBackendEventType.ASSISTANT_MESSAGE)
                    .text("Noted for " + section + ": " + prompt.getText()), null);
        }
    }

    @Override
    public void approve(ResearchSessionHandle handle, String approvalId) {
        FakeSession session = resolve(handle);
        if (session == null || session.closed) {
            return;
        }
        synchronized (session) {
            if (session.processedApprovals.contains(approvalId)) {
                return; // idempotent
            }
            String pending = session.state.getPendingApprovalId();
            if (pending == null || !pending.equals(approvalId)) {
                emit(session, ResearchBackendEvent.builder(ResearchBackendEventType.ERROR)
                        .messages("Unknown or stale approval.", "approvalId=" + approvalId), null);
                return;
            }
            ResearchCommandType approveCommand = approveCommandFor(phaseOf(session));
            // Mark processed ONLY after the transition is accepted, so a rejected dispatch does not wedge the gate.
            if (approveCommand != null && dispatch(session, approveCommand, null)) {
                session.processedApprovals.add(approvalId);
                scheduleAdvance(session);
            }
        }
    }

    @Override
    public void reject(ResearchSessionHandle handle, String approvalId, String reason) {
        FakeSession session = resolve(handle);
        if (session == null || session.closed) {
            return;
        }
        synchronized (session) {
            if (session.processedApprovals.contains(approvalId)) {
                return;
            }
            String pending = session.state.getPendingApprovalId();
            if (pending == null || !pending.equals(approvalId)) {
                emit(session, ResearchBackendEvent.builder(ResearchBackendEventType.ERROR)
                        .messages("Unknown or stale approval.", "approvalId=" + approvalId), null);
                return;
            }
            ResearchCommandType changesCommand = requestChangesCommandFor(phaseOf(session));
            // Only after an accepted transition do we mark the approval processed and announce the change.
            if (changesCommand != null && dispatch(session, changesCommand, null)) {
                session.processedApprovals.add(approvalId);
                emit(session, ResearchBackendEvent.builder(ResearchBackendEventType.ASSISTANT_MESSAGE)
                        .text("Changes requested: " + (reason == null ? "" : reason)), null);
                scheduleAdvance(session);
            }
        }
    }

    @Override
    public void pause(ResearchSessionHandle handle) {
        FakeSession session = resolve(handle);
        if (session == null || session.closed) {
            return;
        }
        synchronized (session) {
            if (dispatch(session, ResearchCommandType.PAUSE, null)) {
                cancelPending(session); // no further scheduled progress; the logical position is preserved
            }
        }
    }

    @Override
    public void resume(ResearchSessionHandle handle) {
        FakeSession session = resolve(handle);
        if (session == null || session.closed) {
            return;
        }
        synchronized (session) {
            if (dispatch(session, ResearchCommandType.RESUME, null)) {
                scheduleAdvance(session);
            }
        }
    }

    @Override
    public void cancel(ResearchSessionHandle handle) {
        FakeSession session = resolve(handle);
        if (session == null || session.closed) {
            return;
        }
        synchronized (session) {
            cancelPending(session);
            dispatch(session, ResearchCommandType.CANCEL, null); // functional cancel; still readable until close
        }
    }

    @Override
    public void close(ResearchSessionHandle handle) {
        FakeSession session = resolve(handle);
        if (session == null) {
            return;
        }
        synchronized (session) {
            if (session.closed) {
                return; // idempotent
            }
            session.closed = true;
            cancelPending(session);
            session.listener = null; // no listener call can happen after close
        }
        sessions.remove(session.sessionId);
    }

    /** Dev action: put the session into a recoverable BLOCKED state with a visible reason. */
    public void simulateBlocked(ResearchSessionHandle handle, String reason) {
        FakeSession session = resolve(handle);
        if (session == null || session.closed) {
            return;
        }
        synchronized (session) {
            if (dispatch(session, ResearchCommandType.BLOCK, null)) {
                cancelPending(session);
                emit(session, ResearchBackendEvent.builder(ResearchBackendEventType.BLOCKED)
                        .messages(reason == null ? "Blocked." : reason, ""), null);
            }
        }
    }

    /** Dev action: fail the session with a public message plus a (non-public-facing) technical detail. */
    public void simulateFailure(ResearchSessionHandle handle, String publicMessage, String technicalDetail) {
        FakeSession session = resolve(handle);
        if (session == null || session.closed) {
            return;
        }
        synchronized (session) {
            if (dispatch(session, ResearchCommandType.FAIL, null)) {
                cancelPending(session);
                emit(session, ResearchBackendEvent.builder(ResearchBackendEventType.ERROR)
                        .messages(publicMessage == null ? "The run failed." : publicMessage,
                                technicalDetail == null ? "" : technicalDetail), null);
            }
        }
    }

    public void shutdown() {
        for (FakeSession session : sessions.values()) {
            close(session);
        }
        scheduler.shutdown();
    }

    // ------------------------------------------------------------------ automatic progression

    private void scheduleAdvance(final FakeSession session) {
        if (!canAdvance(session)) {
            return;
        }
        cancelPending(session);
        session.pending = scheduler.schedule(new Runnable() {
            public void run() {
                synchronized (session) {
                    session.pending = null;
                    if (!canAdvance(session)) {
                        return;
                    }
                    // PR 05: the fake shows the phase's work once and never switches phases by
                    // itself; the next step waits for the user's command.
                    advanceOneStep(session);
                }
            }
        }, stepDelayMillis);
    }

    private boolean canAdvance(FakeSession session) {
        if (session.closed) {
            return false;
        }
        ResearchRunState run = runOf(session);
        if (run == ResearchRunState.PAUSED || run == ResearchRunState.CANCELLED
                || run == ResearchRunState.FAILED || run == ResearchRunState.BLOCKED
                || run == ResearchRunState.COMPLETED) {
            return false;
        }
        // An approval gate (WAITING_APPROVAL with a pending approval) stops automatic progress.
        return session.state.getPendingApprovalId() == null;
    }

    private void advanceOneStep(FakeSession session) {
        ResearchPhase phase = phaseOf(session);
        ResearchRunState run = runOf(session);
        if (run != ResearchRunState.RUNNING) {
            return; // waiting states wait for the user's "continue"; gates for the user's decision
        }
        switch (phase) {
            case SCOPING:
                thinking(session, "Scoping the request");
                break;
            case RESEARCH:
                toolRun(session, "Search web", "Capturing sources");
                emit(session, ResearchBackendEvent.builder(ResearchBackendEventType.SOURCE_ADDED)
                        .title("Captured source"), null);
                emit(session, ResearchBackendEvent.builder(ResearchBackendEventType.FINDING_ADDED)
                        .title("Extracted finding"), null);
                assistant(session, "Sources captured. Press \"Finish sources\" when you are done.");
                break;
            case OUTLINE:
                toolRun(session, "Draft outline", "Proposing sections");
                assistant(session, "Outline proposed. Press \"Finish outline\" when you are done.");
                break;
            case DRAFT:
                assistant(session, "Drafting the sections. Press \"Finish document\" when you are done.");
                break;
            default:
                break;
        }
    }

    private void thinking(FakeSession session, String what) {
        String id = idGenerator.newId();
        emit(session, ResearchBackendEvent.builder(ResearchBackendEventType.ACTIVITY)
                .activity(id, ResearchActivityKind.THINKING_STARTED, "Thinking", what), null);
        emit(session, ResearchBackendEvent.builder(ResearchBackendEventType.ACTIVITY)
                .activity(id, ResearchActivityKind.THINKING_FINISHED, "Thinking", "Done"), null);
    }

    private void toolRun(FakeSession session, String tool, String detail) {
        String id = idGenerator.newId();
        emit(session, ResearchBackendEvent.builder(ResearchBackendEventType.ACTIVITY)
                .activity(id, ResearchActivityKind.TOOL_STARTED, tool, detail), null);
        emit(session, ResearchBackendEvent.builder(ResearchBackendEventType.ACTIVITY)
                .activity(id, ResearchActivityKind.TOOL_COMPLETED, tool, "Completed"), null);
    }

    private void assistant(FakeSession session, String text) {
        emit(session, ResearchBackendEvent.builder(ResearchBackendEventType.ASSISTANT_MESSAGE)
                .text(text), null);
    }

    // ------------------------------------------------------------------ state machine + emission

    /**
     * Dispatch a command through the native memento machine. On acceptance the session's memento advances, a
     * SESSION_STATE_CHANGED event carrying the exact memento is emitted, and if the new state is an approval gate
     * the matching APPROVAL_REQUESTED is raised (covering both fresh approvals and approvals restored after an
     * interruption). A rejected command leaves the memento unchanged.
     */
    private boolean dispatch(FakeSession session, ResearchCommandType type, String commandId) {
        ResearchCommand cmd = command(type);
        ResearchStateTransitionResult result = session.machine.dispatch(session.state, cmd);
        if (!result.isAccepted()) {
            return false;
        }
        session.state = result.getNextMemento();
        emit(session, ResearchBackendEvent.builder(ResearchBackendEventType.SESSION_STATE_CHANGED)
                .stateMemento(session.state), commandId == null ? cmd.getCommandId() : commandId);
        String pendingApprovalId = session.state.getPendingApprovalId();
        if (ResearchStateIds.WAITING_APPROVAL.equals(session.state.getStateId()) && pendingApprovalId != null) {
            emit(session, ResearchBackendEvent.builder(ResearchBackendEventType.APPROVAL_REQUESTED)
                    .approval(pendingApprovalId, approvalPromptFor(session, session.state.getPhaseId())), null);
        }
        if (ResearchStateIds.COMPLETED.equals(session.state.getStateId())) {
            emit(session, ResearchBackendEvent.builder(ResearchBackendEventType.COMPLETED)
                    .text("Research completed."), null);
        }
        return true;
    }

    private ResearchCommand command(ResearchCommandType type) {
        return ResearchCommand.of(type, type + "-" + idGenerator.newId());
    }

    /** Serial, sequence-numbered emission; never fires after close. */
    private void emit(FakeSession session, ResearchBackendEvent.Builder builder, String commandId) {
        if (session.closed || session.listener == null) {
            return;
        }
        long seq = ++session.sequence;
        ResearchBackendEvent event = builder
                .envelope(idGenerator.newId(), session.sessionId, session.projectId,
                        session.state.getRevision(), clock.now(), seq, commandId)
                .build();
        session.listener.onEvent(event);
    }

    private void cancelPending(FakeSession session) {
        if (session.pending != null) {
            session.pending.cancel();
            session.pending = null;
        }
    }

    private FakeSession resolve(ResearchSessionHandle handle) {
        return handle == null ? null : sessions.get(handle.getSessionId());
    }

    private static ResearchPhase phaseOf(FakeSession session) {
        return ResearchStateIds.phase(session.state.getPhaseId());
    }

    private static ResearchRunState runOf(FakeSession session) {
        return ResearchStateIds.runState(session.state.getStateId());
    }

    private static String approvalPromptFor(FakeSession session, String phaseId) {
        if (ResearchStateIds.OUTLINE.equals(phaseId)) {
            // The proposal SHOWS what is being approved and where it came from — never a bare gate.
            return "Proposed outline for: " + (char) 34 + session.researchQuestion + (char) 34 + "\n"
                    + "1. Background" + "\n" + "2. Evidence" + "\n" + "3. Conclusions" + "\n"
                    + "Approve to start writing the document, or request changes.";
        }
        if (ResearchStateIds.RESEARCH.equals(phaseId)) {
            return "Approve the collected evidence?";
        }
        if (ResearchStateIds.DRAFT.equals(phaseId)) {
            return "Approve the final document?";
        }
        return "Approval required.";
    }

    private static ResearchCommandType approveCommandFor(ResearchPhase phase) {
        switch (phase) {
            case OUTLINE:
                return ResearchCommandType.APPROVE_OUTLINE;
            case RESEARCH:
                return ResearchCommandType.APPROVE_EVIDENCE; // Sources' closing approval
            case DRAFT:
                return ResearchCommandType.APPROVE_FINAL; // Document's closing approval
            default:
                return null;
        }
    }

    private static ResearchCommandType requestChangesCommandFor(ResearchPhase phase) {
        switch (phase) {
            case OUTLINE:
                return ResearchCommandType.REQUEST_OUTLINE_CHANGES;
            case RESEARCH:
            case DRAFT:
                return ResearchCommandType.REQUEST_REVISION;
            default:
                return null;
        }
    }

    /** One isolated session: its own memento-based state machine, sequence, pending task and approvals. */
    private static final class FakeSession implements ResearchSessionHandle {
        private final String sessionId;
        private final String projectId;
        private ResearchSessionListener listener;
        private final ResearchStateMachinePort machine;
        private ResearchStateMemento state;
        private long sequence;
        private boolean closed;
        private final Set<String> processedApprovals = new HashSet<String>();
        private ResearchScheduler.Cancellable pending;
        /** The user's first prompt — the research question everything derives from. */
        private String researchQuestion = "";

        private FakeSession(String sessionId, String projectId, ResearchSessionListener listener,
                            final ResearchIdGenerator ids, final ResearchClock clock) {
            this.sessionId = sessionId;
            this.projectId = projectId;
            this.listener = listener;
            // Deterministic: the state machine shares the backend's injected id/clock sources, so an identical
            // command sequence with identical generators yields identical approval ids and mementos.
            OoResearchStateMachine machine = new OoResearchStateMachine(sessionId,
                    new OoResearchStateMachine.IdGenerator() {
                        public String newId() {
                            return ids.newId();
                        }
                    },
                    new OoResearchStateMachine.TimeSource() {
                        public long now() {
                            return clock.now();
                        }
                    });
            this.machine = machine;
            this.state = machine.initialMemento();
        }

        public String getSessionId() {
            return sessionId;
        }

        public String getProjectId() {
            return projectId;
        }
    }
}
