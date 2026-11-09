package com.aresstack.askai.research.runtime.search.service;

/**
 * One search the search service runs for a caller: the query, the project it belongs to (query dedup is per
 * project), the project's URL registry and the queue its pages go to.
 */
public final class SearchJob {

    private final String projectKey;
    private final String query;
    private final int resultCount;
    private final String language;
    private final String country;
    private final String requester;
    private final UrlRegistry registry;
    private final PageCandidateQueue queue;

    private SearchJob(Builder b) {
        if (b.projectKey == null || b.projectKey.trim().isEmpty()) {
            throw new IllegalArgumentException("A search job needs a project key");
        }
        if (b.query == null || b.query.trim().isEmpty()) {
            throw new IllegalArgumentException("A search job needs a query");
        }
        if (b.registry == null || b.queue == null) {
            throw new IllegalArgumentException("A search job needs a URL registry and a page queue");
        }
        this.projectKey = b.projectKey.trim();
        this.query = b.query.trim();
        this.resultCount = b.resultCount <= 0 ? 10 : b.resultCount;
        this.language = b.language;
        this.country = b.country;
        this.requester = b.requester == null ? "" : b.requester;
        this.registry = b.registry;
        this.queue = b.queue;
    }

    public static Builder builder(String projectKey, String query) {
        return new Builder(projectKey, query);
    }

    public String getProjectKey() {
        return projectKey;
    }

    public String getQuery() {
        return query;
    }

    public int getResultCount() {
        return resultCount;
    }

    public String getLanguage() {
        return language;
    }

    public String getCountry() {
        return country;
    }

    /** Who asked (e.g. {@code orchestrator}, a chat id) — for the log only. */
    public String getRequester() {
        return requester;
    }

    public UrlRegistry getRegistry() {
        return registry;
    }

    public PageCandidateQueue getQueue() {
        return queue;
    }

    public static final class Builder {
        private final String projectKey;
        private final String query;
        private int resultCount = 10;
        private String language;
        private String country;
        private String requester;
        private UrlRegistry registry;
        private PageCandidateQueue queue;

        private Builder(String projectKey, String query) {
            this.projectKey = projectKey;
            this.query = query;
        }

        public Builder resultCount(int resultCount) {
            this.resultCount = resultCount;
            return this;
        }

        public Builder locale(String language, String country) {
            this.language = language;
            this.country = country;
            return this;
        }

        public Builder requester(String requester) {
            this.requester = requester;
            return this;
        }

        public Builder registry(UrlRegistry registry) {
            this.registry = registry;
            return this;
        }

        public Builder queue(PageCandidateQueue queue) {
            this.queue = queue;
            return this;
        }

        public SearchJob build() {
            return new SearchJob(this);
        }
    }
}
