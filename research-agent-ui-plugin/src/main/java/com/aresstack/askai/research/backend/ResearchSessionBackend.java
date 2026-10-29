package com.aresstack.askai.research.backend;

import com.aresstack.askai.research.state.ResearchCommandType;

/**
 * The fachlich port between the research UI and whatever runs the research. A deterministic fake
 * implements it, and so does the productive {@code InProcessResearchSessionBackend} (the agent running
 * inside the plugin), so no runtime type ever reaches the UI. Free of Swing/PF4J/Solon/executor types.
 */
public interface ResearchSessionBackend {

    ResearchSessionHandle createSession(ResearchProjectRequest request, ResearchSessionListener listener);

    /** @return whether the command is currently legal for this session (for button enablement). */
    boolean canExecute(ResearchSessionHandle session, ResearchCommandType command);

    void executeCommand(ResearchSessionHandle session, ResearchCommandType command);

    void submitPrompt(ResearchSessionHandle session, ResearchPrompt prompt);

    /**
     * Carry a typed SERVICE COMMAND control envelope (e.g. a user web search, {@code #RSC1#}) to the runtime.
     * Semantically SEPARATE from {@link #submitPrompt} — a service command is never a chat turn — even though a
     * transport may physically reuse the same channel underneath. The default ignores it: a fake/clickdummy
     * backend has no service transport. The productive backend overrides it.
     */
    default void submitServiceCommand(ResearchSessionHandle session, String controlEnvelope) {
        // no-op by default
    }

    void approve(ResearchSessionHandle session, String approvalId);

    void reject(ResearchSessionHandle session, String approvalId, String reason);

    void pause(ResearchSessionHandle session);

    void resume(ResearchSessionHandle session);

    void cancel(ResearchSessionHandle session);

    void close(ResearchSessionHandle session);
}
