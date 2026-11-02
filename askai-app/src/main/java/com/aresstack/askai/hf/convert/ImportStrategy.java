package com.aresstack.askai.hf.convert;

/**
 * A registrable import strategy for one input format. New formats are added by registering a
 * strategy with {@link ConverterService}, without touching the search or UI logic (spec §17.2).
 */
public interface ImportStrategy {

    /** @return a short strategy name (e.g. "GGUF direct import"). */
    String name();

    /** @return true when this strategy is the one that should judge the given repository. */
    boolean recognizes(RepositoryAnalysis analysis);

    /** @return this strategy's verdict for a repository it {@link #recognizes}. */
    SupportDecision evaluate(RepositoryAnalysis analysis, OllamaEnvironment environment);
}
