package com.aresstack.askai.research.backend;

import com.aresstack.askai.research.state.ResearchCommandType;

/**
 * The ONE seam between the chat/workspace UI and a research session's controls. Plain text goes through
 * {@link #submitPrompt}; STRUCTURED actions (approve, review, pause, …) go through {@link #dispatch} with the
 * EXISTING domain {@link ResearchCommandType} — there is deliberately no second command hierarchy, no Swing,
 * no transport and no host type here, and backends never interpret chat text by string matching. Availability
 * comes from the session's state projection (the state machine stays the single source of truth); the UI
 * never re-implements phase rules.
 */
public interface ResearchSessionCommandPort {

    /** Free-form user text for the agent (never used to smuggle commands). */
    void submitPrompt(String text);

    /**
     * A structured user action. @param argument optional payload (e.g. a request-changes reason), may be
     * {@code null}. @return the structured outcome — never silently ignored.
     */
    ResearchCommandDispatchResult dispatch(ResearchCommandType command, String argument);
}
