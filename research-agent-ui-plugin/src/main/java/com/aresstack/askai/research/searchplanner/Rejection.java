package com.aresstack.askai.research.searchplanner;

/** A search the model wrote that the planner did not take, with the reason. */
public final class Rejection {

    private final String query;
    private final String reason;
    private final String question;
    private final boolean strategy;

    Rejection(String query, String reason) {
        this(query, reason, "", false);
    }

    /** With the question the search named and whether it missed only the rule of the task's strategy. */
    Rejection(String query, String reason, String question, boolean strategy) {
        this.query = query == null ? "" : query;
        this.reason = reason == null ? "" : reason;
        this.question = question == null ? "" : question;
        this.strategy = strategy;
    }

    public String getQuery() {
        return query;
    }

    public String getReason() {
        return reason;
    }

    /** The question the search named; {@code ""} when the reason is not about the search alone. */
    String getQuestion() {
        return question;
    }

    /** Whether the search kept every rule but the one of the task's strategy (a repair names that rule). */
    boolean isStrategy() {
        return strategy;
    }

    @Override
    public String toString() {
        return "\"" + query + "\" " + reason;
    }
}
