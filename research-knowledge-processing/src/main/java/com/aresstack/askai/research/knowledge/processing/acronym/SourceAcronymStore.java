package com.aresstack.askai.research.knowledge.processing.acronym;

/** Persists one {@link SourceAcronymRecord} per source id. */
public interface SourceAcronymStore {

    /** The stored record, or {@code null}. */
    SourceAcronymRecord load(String sourceId);

    void save(SourceAcronymRecord record);
}
