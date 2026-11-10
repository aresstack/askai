package com.aresstack.askai.research.sources;

import com.aresstack.askai.research.plan.SearchPlanEntry;
import com.aresstack.askai.research.tabs.RowSignal;

/**
 * Where a row of the Sources tab stands — a found source or a planned search — in the order its table shows
 * it: what is still to do on top, what runs below, then what came out: clean first, then what needs a second
 * look, the failures after it. Each state has one colour, the same in both tables.
 */
public enum SourceRowState {
    /** Not done yet: a search that waits, or a found source whose page was not read yet. Grey. */
    PENDING,
    /** The search that runs right now. Blue. */
    RUNNING,
    /** Done without trouble: a finished search, or a source read without a security check. Green. */
    DONE,
    /** The source's website demanded a security check and it was solved. Yellow. */
    CHECK_SOLVED,
    /** A failed search, or a source whose website's security check was never solved. Red. */
    FAILED,
    /** Out of the work: a skipped search, or an excluded, duplicate or superseded source. No colour. */
    SET_ASIDE;

    /** The colour this state shows in a list. */
    public RowSignal signal() {
        switch (this) {
            case PENDING:
                return RowSignal.PENDING;
            case RUNNING:
                return RowSignal.RUNNING;
            case DONE:
                return RowSignal.DONE;
            case CHECK_SOLVED:
                return RowSignal.CAUTION;
            case FAILED:
                return RowSignal.FAILED;
            default:
                return RowSignal.NONE;
        }
    }

    /** The state of a planned search. */
    public static SourceRowState of(SearchPlanEntry.Status status) {
        switch (status) {
            case PENDING:
                return PENDING;
            case RUNNING:
                return RUNNING;
            case DONE:
                return DONE;
            case FAILED:
                return FAILED;
            default:
                return SET_ASIDE;
        }
    }
}
