package com.aresstack.askai.hf;

import java.util.Collections;
import java.util.List;

/**
 * The use-case-level result of a search or load-more step: the hits, whether "load more" can
 * continue from here, and an optional human-readable note (e.g. a Base-only shortfall, or that
 * pagination isn't available yet for a multi-library merge) for the UI to surface in its log.
 */
public final class HuggingFaceSearchResult {

    private final List<HuggingFaceModel> models;
    private final String nextPageUrl;
    private final boolean loadMoreSupported;
    private final String note;
    private final MergedPagination merged;

    public HuggingFaceSearchResult(List<HuggingFaceModel> models, String nextPageUrl,
                                   boolean loadMoreSupported, String note) {
        this(models, nextPageUrl, loadMoreSupported, note, null);
    }

    public HuggingFaceSearchResult(List<HuggingFaceModel> models, String nextPageUrl,
                                   boolean loadMoreSupported, String note, MergedPagination merged) {
        this.models = models == null ? Collections.<HuggingFaceModel>emptyList() : models;
        this.nextPageUrl = nextPageUrl;
        this.loadMoreSupported = loadMoreSupported;
        this.note = note;
        this.merged = merged;
    }

    public List<HuggingFaceModel> getModels() {
        return models;
    }

    public String getNextPageUrl() {
        return nextPageUrl;
    }

    public boolean isLoadMoreSupported() {
        return loadMoreSupported;
    }

    /** @return an optional note for the UI log (shortfall/limitation explanation), or {@code null}. */
    public String getNote() {
        return note;
    }

    /** @return merged-stream pagination state for an OR/multi-value search, or {@code null} for a
     *          single-request search (which paginates through {@link #getNextPageUrl()} instead). */
    public MergedPagination getMerged() {
        return merged;
    }
}
