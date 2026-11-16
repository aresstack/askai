package com.aresstack.askai.research.book;

import com.aresstack.askai.research.state.ResearchCommandType;
import com.aresstack.askai.research.transition.PhaseTransition;
import com.aresstack.askai.research.transition.PhaseTransitionHook;

/**
 * PR 12 (Q12): the embedding model is required. Before apply, {@code SUBMIT_SCOPE} is refused while
 * no embedding model is ready, because the Sources filter, the Outline and the writing all need one.
 * Each check re-resolves the model, so one installed after the chat was opened is found on the next
 * submit ("Check again") without a new chat. Nothing is downloaded here.
 */
public final class EmbeddingRequiredHook implements PhaseTransitionHook {

    public static final String NO_EMBEDDING = "Sources need an embedding model. Open Models -> Setup.";

    /** Whether an embedding model is ready now; may re-resolve it. */
    public interface Readiness {
        boolean ready();
    }

    private final Readiness readiness;

    public EmbeddingRequiredHook(Readiness readiness) {
        this.readiness = readiness;
    }

    @Override
    public String check(PhaseTransition transition) {
        if (transition.getCommand() != ResearchCommandType.SUBMIT_SCOPE) {
            return null;
        }
        return readiness.ready() ? null : NO_EMBEDDING;
    }
}
