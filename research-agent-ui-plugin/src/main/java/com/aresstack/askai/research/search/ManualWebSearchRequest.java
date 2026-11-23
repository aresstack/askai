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
    /** Plan T5: run as research-team work (its pages read in a worker browser by a page worker). */
    private final boolean team;
    /** Plan T5: the concept node of a team query (the page worker's brief); empty otherwise. */
    private final String teamNode;
    /** Plan T5: the book title of a team query (the page worker's brief); empty otherwise. */
    private final String teamBook;
    /** Plan T4: at most this many links a page worker may pick per page. */
    private final int teamFollow;
    /** Plan T4: at most this much page text a page worker reads. */
    private final int teamPageCharacters;
    /** Plan T4: the longest answer of one page worker turn (tokens). */
    private final int teamAnswerTokens;
    private int teamWorkers;
    private int teamSearches;
    private String teamArea = "";
    /** The hosts the mission prefers (ranking moves them up); empty = no preference. */
    private java.util.List<String> preferredHosts = java.util.Collections.emptyList();
    /** The URL parts of vendor pages (ranking moves them down). */
    private java.util.List<String> demotedMarkers = java.util.Collections.emptyList();
    /** How many places a preferred hit moves up and a vendor page moves down; 0 = none. */
    private int preferenceShift;

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
        this(query, language, ranking, visitLimit, sourceCap, false, "", "", 0, 0, 0);
    }

    private ManualWebSearchRequest(String query, ResearchLanguage language, String ranking, int visitLimit,
                                   int sourceCap, boolean team, String teamNode, String teamBook, int teamFollow,
                                   int teamPageCharacters, int teamAnswerTokens) {
        this.team = team;
        this.teamAnswerTokens = Math.max(0, teamAnswerTokens);
        this.teamFollow = Math.max(0, teamFollow);
        this.teamPageCharacters = Math.max(0, teamPageCharacters);
        this.teamNode = teamNode == null ? "" : teamNode.trim();
        this.teamBook = teamBook == null ? "" : teamBook.trim();
        this.query = query == null ? "" : query.trim();
        this.language = language == null ? ResearchLanguage.ENGLISH : language;
        this.ranking = ranking == null ? "" : ranking.trim();
        this.visitLimit = Math.max(0, visitLimit);
        this.sourceCap = Math.max(0, sourceCap);
    }

    /**
     * Plan T5: the same planned query as research-team work. The search itself still goes through the one web
     * search tool; the runtime reads its pages in a worker browser of its own, beside the other team queries.
     */
    public ManualWebSearchRequest asTeam(String nodeLabel, String bookTitle, int followLinks, int pageCharacters,
                                         int answerTokens) {
        return new ManualWebSearchRequest(query, language, ranking, visitLimit, sourceCap, true, nodeLabel,
                bookTitle, followLinks, pageCharacters, answerTokens);
    }

    /**
     * Plan T4: the same team search with its tab count, the query suggestions a page worker may make per page and
     * the Weidezaun those suggestions must stay inside.
     */
    public ManualWebSearchRequest withTeamLanes(int workers, int searches, String area) {
        ManualWebSearchRequest copy = new ManualWebSearchRequest(query, language, ranking, visitLimit, sourceCap, team,
                teamNode, teamBook, teamFollow, teamPageCharacters, teamAnswerTokens);
        copy.teamWorkers = Math.max(0, workers);
        copy.teamSearches = Math.max(0, searches);
        copy.teamArea = area == null ? "" : area;
        return copy;
    }

    /** The same request with the mission's source preference for the ranking of its hits. */
    public ManualWebSearchRequest withSourcePreference(com.aresstack.askai.research.plan.SourcePreference preference) {
        ManualWebSearchRequest copy = new ManualWebSearchRequest(query, language, ranking, visitLimit, sourceCap, team,
                teamNode, teamBook, teamFollow, teamPageCharacters, teamAnswerTokens);
        copy.teamWorkers = teamWorkers;
        copy.teamSearches = teamSearches;
        copy.teamArea = teamArea;
        if (preference != null && preference.getRankShift() > 0) {
            copy.preferredHosts = preference.getPreferredHosts();
            copy.demotedMarkers = preference.getDemotedMarkers();
            copy.preferenceShift = preference.getRankShift();
        }
        return copy;
    }

    public java.util.List<String> getPreferredHosts() {
        return preferredHosts;
    }

    public java.util.List<String> getDemotedMarkers() {
        return demotedMarkers;
    }

    public int getPreferenceShift() {
        return preferenceShift;
    }

    public int getTeamWorkers() {
        return teamWorkers;
    }

    public int getTeamSearches() {
        return teamSearches;
    }

    public String getTeamArea() {
        return teamArea;
    }

    public int getTeamAnswerTokens() {
        return teamAnswerTokens;
    }

    public int getTeamFollow() {
        return teamFollow;
    }

    public int getTeamPageCharacters() {
        return teamPageCharacters;
    }

    public boolean isTeam() {
        return team;
    }

    public String getTeamNode() {
        return teamNode;
    }

    public String getTeamBook() {
        return teamBook;
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
