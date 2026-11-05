package com.aresstack.askai.research.backend;

import com.aresstack.askai.research.state.ResearchCommandType;

/**
 * PR 05: the fake backend never switches phases by itself, so tests reach a gate with the user's
 * commands (submit-scope, its chained start, finish-sources, ...), exactly as a user would.
 */
public final class FakeUserCommands {

    private FakeUserCommands() {
    }

    /** Dispatch each command as the user and let the fake show the phase's work. */
    public static void run(FakeResearchSessionBackend backend, ResearchSessionHandle handle,
                           ManualResearchScheduler scheduler, ResearchCommandType... commands) {
        scheduler.runUntilIdle();
        for (ResearchCommandType command : commands) {
            backend.executeCommand(handle, command);
            scheduler.runUntilIdle();
        }
    }

    /** From scoping/running to the Sources gate. */
    public static void toSourcesGate(FakeResearchSessionBackend backend, ResearchSessionHandle handle,
                                     ManualResearchScheduler scheduler) {
        run(backend, handle, scheduler, ResearchCommandType.SUBMIT_SCOPE,
                ResearchCommandType.START_RESEARCH, ResearchCommandType.REQUEST_EVIDENCE_REVIEW);
    }
}
