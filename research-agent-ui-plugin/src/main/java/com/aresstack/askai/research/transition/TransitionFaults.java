package com.aresstack.askai.research.transition;

/**
 * Test seam for the fault-injection tests: called after each write step of a transition
 * ({@code prepared}, {@code memento}, {@code committed}, {@code effect:<name>} before its {@code done}).
 * Throwing from here stands in for a crash at that point.
 */
public interface TransitionFaults {

    TransitionFaults NONE = new TransitionFaults() {
        public void after(String step, PhaseTransition transition) {
        }
    };

    void after(String step, PhaseTransition transition);
}
