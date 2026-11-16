package com.aresstack.askai.research.transition;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

/**
 * A typed hook on every state transition (D3). Each side is optional:
 * <ul>
 * <li>{@link #check} runs before anything is written and may refuse with a reason;</li>
 * <li>{@link #record} writes what the transition approves (pins, approval records) after the
 * {@code prepared} journal record and before the memento; a failure refuses the transition;</li>
 * <li>{@link #effects} names the effects that run after the commit, in order, on one worker thread. The
 * names are journalled, so a crash before an effect ran re-runs it on the next open; every effect must
 * therefore be idempotent per transition id.</li>
 * </ul>
 */
public interface PhaseTransitionHook {

    /** @return {@code null} to let the transition pass, otherwise the reason shown to the user. */
    default String check(PhaseTransition transition) {
        return null;
    }

    /** Write the records of this transition; throwing refuses it (the memento is not written). */
    default void record(PhaseTransition transition) throws IOException {
    }

    /** The effect names to run after the commit, each handled by a registered {@link TransitionEffect}. */
    default List<String> effects(PhaseTransition transition) {
        return Collections.emptyList();
    }
}
