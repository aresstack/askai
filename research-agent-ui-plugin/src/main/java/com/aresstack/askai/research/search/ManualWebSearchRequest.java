package com.aresstack.askai.research.search;

import com.aresstack.askai.research.agent.ResearchLanguage;

/**
 * A user-triggered web search request: the query plus an IMMUTABLE language snapshot taken when the user
 * raised the search — a session language switch during a running search never changes that search. Provider,
 * country and result count stay owned by the existing research/search configuration (RA §5); the request
 * language OVERRIDES the provider's default language, exactly like {@code WebSearchRequest} field semantics.
 */
public final class ManualWebSearchRequest {

    private final String query;
    private final ResearchLanguage language;
    /** Plan PR 14: {@code bm25}, {@code reranker} or "" (typed search: the user's setting). */
    private final String ranking;
    private final int visitLimit;
    private final int sourceCap;

    /** English snapshot convenience (legacy callers/tests without a session language). */
    public ManualWebSearchRequest(String query) {
        this(query, null);
    }

    public ManualWebSearchRequest(String query, ResearchLanguage language) {
        this(query, language, "", 0, 0);
    }

    /**
     * Plan PR 14: a planned query with its ranking choice ({@code bm25} or {@code reranker}), its visit limit (top N
     * ranked hits) and its per-query source cap.
     */
    public ManualWebSearchRequest(String query, ResearchLanguage language, String ranking, int visitLimit,
                                  int sourceCap) {
        this.query = query == null ? "" : query.trim();
        this.language = language == null ? ResearchLanguage.ENGLISH : language;
        this.ranking = ranking == null ? "" : ranking.trim();
        this.visitLimit = Math.max(0, visitLimit);
        this.sourceCap = Math.max(0, sourceCap);
    }

    public String getRanking() {
        return ranking;
    }

    public int getVisitLimit() {
        return visitLimit;
    }

    public int getSourceCap() {
        return sourceCap;
    }

    public String getQuery() {
        return query;
    }

    public ResearchLanguage getLanguage() {
        return language;
    }

    public boolean isBlank() {
        return query.isEmpty();
    }
}
