package com.aresstack.askai.research.runtime.search.service;

/**
 * One page the search service found and queued: its resolved, normalised address (never a search engine address),
 * what the engine showed for it, and the query that produced it.
 */
public final class PageCandidate {

    private final String url;
    private final String title;
    private final String snippet;
    private final String query;
    private final String engineHost;
    private final int rank;

    public PageCandidate(String url, String title, String snippet, String query, String engineHost, int rank) {
        if (url == null || url.trim().isEmpty()) {
            throw new IllegalArgumentException("A page candidate needs a URL");
        }
        this.url = url.trim();
        this.title = title == null ? "" : title;
        this.snippet = snippet == null ? "" : snippet;
        this.query = query == null ? "" : query;
        this.engineHost = engineHost == null ? "" : engineHost;
        this.rank = rank;
    }

    public String getUrl() {
        return url;
    }

    public String getTitle() {
        return title;
    }

    public String getSnippet() {
        return snippet;
    }

    public String getQuery() {
        return query;
    }

    public String getEngineHost() {
        return engineHost;
    }

    public int getRank() {
        return rank;
    }

    @Override
    public String toString() {
        return url;
    }
}
