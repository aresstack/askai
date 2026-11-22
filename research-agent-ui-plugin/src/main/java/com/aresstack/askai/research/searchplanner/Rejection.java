package com.aresstack.askai.research.searchplanner;

/** A search the model wrote that the planner did not take, with the reason. */
public final class Rejection {

    private final String query;
    private final String reason;

    Rejection(String query, String reason) {
        this.query = query == null ? "" : query;
        this.reason = reason == null ? "" : reason;
    }

    public String getQuery() {
        return query;
    }

    public String getReason() {
        return reason;
    }

    @Override
    public String toString() {
        return "\"" + query + "\" " + reason;
    }
}
