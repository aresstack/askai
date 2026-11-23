package com.aresstack.askai.research.transition;

/** Runs one named effect of a committed transition; must be idempotent per transition id (D3). */
public interface TransitionEffect {

    void run(PhaseTransition transition) throws Exception;
}
