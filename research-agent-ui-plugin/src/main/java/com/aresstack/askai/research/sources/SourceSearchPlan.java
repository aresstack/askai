package com.aresstack.askai.research.sources;

import com.aresstack.askai.research.plan.SearchPlanEntry;

import java.util.List;

/** What the Sources tab reads from and does with the search plan; the session implements it. */
public interface SourceSearchPlan {

    List<SearchPlanEntry> entries();

    boolean running();

    /** Whether the plan may run now (research/running). */
    boolean runnable();

    /** The labels of the concept nodes no source covers yet. */
    List<String> uncovered();

    /**
     * The uncovered nodes whose sources would cover them by number but whose evidence is weak (too few distinct
     * passages, too few from the mission's preferred sources); a subset of {@link #uncovered()}.
     */
    default List<String> weak() {
        return java.util.Collections.emptyList();
    }

    void run();

    void stop();

    void drop(String entryId);

    void edit(String entryId, String query);

    void rerun(String entryId);

    void searchAgain();
}
