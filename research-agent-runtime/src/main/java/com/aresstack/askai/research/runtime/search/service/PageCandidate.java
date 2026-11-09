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
    private final boolean wrapped;

    public PageCandidate(String url, String title, String snippet, String query, String engineHost, int rank) {
        this(url, title, snippet, query, engineHost, rank, false);
    }

    /**
     * @param wrapped {@code url} is still the engine's redirect wrapper (Google {@code /goto}); it is resolved only
     *                when a worker actually takes this page, through {@link SearchService#resolveForVisit}
     */
    public PageCandidate(String url, String title, String snippet, String query, String engineHost, int rank,
                         boolean wrapped) {
        if (url == null || url.trim().isEmpty()) {
            throw new IllegalArgumentException("A page candidate needs a URL");
        }
        this.url = url.trim();
        this.title = title == null ? "" : title;
        this.snippet = snippet == null ? "" : snippet;
        this.query = query == null ? "" : query;
        this.engineHost = engineHost == null ? "" : engineHost;
        this.rank = rank;
        this.wrapped = wrapped;
    }

    /** Whether {@link #getUrl()} is an unresolved engine wrapper that must go through the search service first. */
    public boolean isWrapped() {
        return wrapped;
    }

    /** The same page at its resolved address. */
    PageCandidate resolvedTo(String targetUrl) {
        return new PageCandidate(targetUrl, title, snippet, query, engineHost, rank, false);
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
