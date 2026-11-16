package com.aresstack.askai.research.tabs;

/**
 * The colour code every list of the sidebar tabs speaks (Sources, Outline, Document), in the order a list
 * shows its rows: what is still to do on top, what runs below, then what came out: clean first, then what
 * needs a second look, the failures last.
 */
public enum RowSignal {
    /** Still to do. Grey. */
    PENDING,
    /** Being worked on right now. Blue. */
    RUNNING,
    /** Done without trouble. Green. */
    DONE,
    /** Done, but it needs a second look. Yellow. */
    CAUTION,
    /** Failed or blocked. Red. */
    FAILED,
    /** Outside the work (skipped, excluded) or simply not rated. No colour. */
    NONE
}
