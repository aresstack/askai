package com.aresstack.askai.hf;

/**
 * Server-side sort options for HuggingFace model search. Each entry carries the verified real
 * {@code sort} query-parameter value (HuggingFace uses camelCase field names, not the
 * {@code trending_score}-style names suggested by the website's visible labels) and always sorts
 * descending — HuggingFace's API rejects ascending order for {@code likes}/{@code downloads}/
 * {@code trendingScore} ("only descending sort is supported"), so direction is not a separate,
 * user-choosable axis here.
 *
 * <p>"Most parameters" / "Least parameters" are intentionally not represented: {@code sort=params}
 * and {@code sort=numParameters} both return HTTP 400 ("Invalid sort parameter") when probed against
 * the real API, so a server-side implementation does not exist. Implementing them client-side would
 * only sort whatever page happens to be loaded, which is exactly the "sort a subset" behavior this
 * search deliberately avoids everywhere else — so they are left out rather than offered and silently
 * wrong.</p>
 */
public enum SortOrder {

    TRENDING("trendingScore", "Trending"),
    MOST_LIKES("likes", "Most likes"),
    MOST_DOWNLOADS("downloads", "Most downloads"),
    RECENTLY_CREATED("createdAt", "Recently created"),
    RECENTLY_UPDATED("lastModified", "Recently updated");

    private final String apiField;
    private final String displayName;

    SortOrder(String apiField, String displayName) {
        this.apiField = apiField;
        this.displayName = displayName;
    }

    /** @return the real HuggingFace {@code sort} query-parameter value. */
    public String getApiField() {
        return apiField;
    }

    public String getDisplayName() {
        return displayName;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
