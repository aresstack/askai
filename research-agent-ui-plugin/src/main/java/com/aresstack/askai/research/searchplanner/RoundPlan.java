package com.aresstack.askai.research.searchplanner;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** The searches of one round, per task of the research lead in its order. Immutable. */
public final class RoundPlan {

    private final List<TaskPlan> tasks;
    private final long millis;

    RoundPlan(List<TaskPlan> tasks, long millis) {
        this.tasks = Collections.unmodifiableList(new ArrayList<TaskPlan>(tasks));
        this.millis = millis;
    }

    public List<TaskPlan> getTasks() {
        return tasks;
    }

    /** Every search of the round, in task order. */
    public List<PlannedSearch> getSearches() {
        List<PlannedSearch> all = new ArrayList<PlannedSearch>();
        for (TaskPlan task : tasks) {
            all.addAll(task.getSearches());
        }
        return all;
    }

    /** The tasks left without a search. */
    public List<TaskPlan> getFailed() {
        List<TaskPlan> failed = new ArrayList<TaskPlan>();
        for (TaskPlan task : tasks) {
            if (task.getStatus() == TaskPlan.Status.FEHLER) {
                failed.add(task);
            }
        }
        return failed;
    }

    public long getMillis() {
        return millis;
    }
}
