package com.aresstack.askai.research.searchplanner;

import com.aresstack.askai.research.memory.SearchRecord;

/** One search the planner wrote: the record in the memory (without yield) and the sentence on what it should find. */
public final class PlannedSearch {

    private final SearchRecord record;
    private final String purpose;

    PlannedSearch(SearchRecord record, String purpose) {
        this.record = record;
        this.purpose = purpose == null ? "" : purpose;
    }

    /** The search as the memory holds it: id, query, the one question, the topic of a topic task, the strategy. */
    public SearchRecord getRecord() {
        return record;
    }

    public String getId() {
        return record.getId();
    }

    public String getQuery() {
        return record.getQuery();
    }

    /** The question the search is for. */
    public String getQuestionId() {
        return record.getQuestionIds().get(0);
    }

    public String getStrategy() {
        return record.getStrategy();
    }

    /** One sentence on what the search should find. */
    public String getPurpose() {
        return purpose;
    }

    @Override
    public String toString() {
        return record.getId() + " " + getQuestionId() + " [" + getStrategy() + "] " + getQuery();
    }
}
