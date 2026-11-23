package com.aresstack.askai.research.transition;

/** Told after every committed transition, on the dispatching thread, after the engine's lock is released. */
public interface TransitionListener {

    void committed(PhaseTransition transition);
}
