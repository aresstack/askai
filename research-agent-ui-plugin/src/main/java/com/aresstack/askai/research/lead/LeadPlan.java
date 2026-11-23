package com.aresstack.askai.research.lead;

import com.aresstack.askai.research.memory.LeadDecision;
import com.aresstack.askai.research.memory.ResearchQuestion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What the research lead decided for one round (component 8): the tasks for the search planner, every decision as
 * recorded in the memory, the questions it took in, the guardrail notes, and when the research ends the open list.
 * Immutable.
 */
public final class LeadPlan {

    /** How the plan came about. */
    public enum Status {
        /** The model judged the situation; guardrails may have corrected single decisions (see the notes). */
        MODELL,
        /** A rule decided without a model call: budget used up, standstill, nothing left to search. */
        REGEL,
        /** The model call failed (after one repair); only the guardrails planned this round, and that is noted. */
        FEHLER
    }

    private final int round;
    private final Status status;
    private final boolean end;
    private final List<LeadTask> tasks;
    private final List<LeadDecision> decisions;
    private final List<ResearchQuestion> newQuestions;
    private final List<String> notes;
    private final String situation;
    private final OpenList openList;
    private final int searchBudget;
    private final String problem;
    private final List<String> answers;
    private final String model;
    private final long millis;

    LeadPlan(int round, Status status, boolean end, List<LeadTask> tasks, List<LeadDecision> decisions,
             List<ResearchQuestion> newQuestions, List<String> notes, String situation, OpenList openList,
             int searchBudget, String problem, List<String> answers, String model, long millis) {
        this.round = round;
        this.status = status;
        this.end = end;
        this.tasks = copy(tasks);
        this.decisions = copy(decisions);
        this.newQuestions = copy(newQuestions);
        this.notes = copy(notes);
        this.situation = situation == null ? "" : situation;
        this.openList = openList;
        this.searchBudget = Math.max(0, searchBudget);
        this.problem = problem == null ? "" : problem;
        this.answers = copy(answers);
        this.model = model == null ? "" : model;
        this.millis = millis;
        if (end && openList == null) {
            throw new IllegalArgumentException("an end needs its open list");
        }
    }

    /** The round this plan is for. */
    public int getRound() {
        return round;
    }

    public Status getStatus() {
        return status;
    }

    /** Whether the research ends; then there are no tasks and {@link #getOpenList()} holds what is left open. */
    public boolean isEnd() {
        return end;
    }

    /** The tasks of the round, in the order the planner should take them. */
    public List<LeadTask> getTasks() {
        return tasks;
    }

    /** Every decision of the round as recorded in the memory, the run decision last. */
    public List<LeadDecision> getDecisions() {
        return decisions;
    }

    /** The questions the lead took in this round (origin LEITUNG), as the memory holds them. */
    public List<ResearchQuestion> getNewQuestions() {
        return newQuestions;
    }

    /** What the guardrails changed or added, one line each. */
    public List<String> getNotes() {
        return notes;
    }

    /** The model's judgement of the situation ({@code lage}); {@code ""} without a model answer. */
    public String getSituation() {
        return situation;
    }

    /** What is left open; {@code null} unless the research ends. */
    public OpenList getOpenList() {
        return openList;
    }

    /** The most searches the planner may start this round. */
    public int getSearchBudget() {
        return searchBudget;
    }

    /** Why the model call failed; {@code ""} otherwise. */
    public String getProblem() {
        return problem;
    }

    /** The raw answers of the model, one per call. */
    public List<String> getAnswers() {
        return answers;
    }

    /** The model that judged; {@code ""} when no model was asked. */
    public String getModel() {
        return model;
    }

    public long getMillis() {
        return millis;
    }

    private static <T> List<T> copy(List<T> list) {
        return list == null ? Collections.<T>emptyList() : Collections.unmodifiableList(new ArrayList<T>(list));
    }
}
