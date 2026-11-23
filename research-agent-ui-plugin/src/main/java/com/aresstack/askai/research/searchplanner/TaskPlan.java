package com.aresstack.askai.research.searchplanner;

import com.aresstack.askai.research.lead.LeadTask;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** The searches of one task of the research lead, or why it has none. Immutable. */
public final class TaskPlan {

    /** MODELL: the model wrote at least one search that passed. FEHLER: none passed or the call failed. */
    public enum Status { MODELL, FEHLER }

    private final LeadTask task;
    private final Status status;
    private final List<PlannedSearch> searches;
    private final List<Rejection> rejected;
    private final String problem;
    private final List<String> answers;
    private final String model;
    private final long millis;

    TaskPlan(LeadTask task, Status status, List<PlannedSearch> searches, List<Rejection> rejected, String problem,
             List<String> answers, String model, long millis) {
        this.task = task;
        this.status = status;
        this.searches = Collections.unmodifiableList(new ArrayList<PlannedSearch>(searches));
        this.rejected = Collections.unmodifiableList(new ArrayList<Rejection>(rejected));
        this.problem = problem == null ? "" : problem;
        this.answers = Collections.unmodifiableList(new ArrayList<String>(answers));
        this.model = model == null ? "" : model;
        this.millis = millis;
    }

    public LeadTask getTask() {
        return task;
    }

    public Status getStatus() {
        return status;
    }

    /** The searches written to the memory, in the model's order. */
    public List<PlannedSearch> getSearches() {
        return searches;
    }

    /** The searches the model wrote that were not taken, with the reason. */
    public List<Rejection> getRejected() {
        return rejected;
    }

    /** Why the task has no search ({@code ""} with status MODELL). */
    public String getProblem() {
        return problem;
    }

    /** The raw answers of the calls ({@code ""} for a failed call). */
    public List<String> getAnswers() {
        return answers;
    }

    public String getModel() {
        return model;
    }

    public long getMillis() {
        return millis;
    }
}
