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

    void run();

    void stop();

    void drop(String entryId);

    void edit(String entryId, String query);

    void rerun(String entryId);

    void searchAgain();
}
