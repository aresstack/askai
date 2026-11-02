package com.aresstack.askai.hf;

import java.util.Collections;
import java.util.List;

/**
 * The result of a single HuggingFace {@code /api/models} HTTP call: the page of hits plus the next
 * page's URL, taken verbatim from the response's {@code Link: <url>; rel="next"} header. The URL
 * already encodes HuggingFace's opaque cursor and every filter/sort parameter from the original
 * request, so continuing pagination is just "GET this exact URL" — no cursor bookkeeping needed.
 */
public final class HuggingFaceSearchPage {

    private final List<HuggingFaceModel> models;
    private final String nextPageUrl;

    public HuggingFaceSearchPage(List<HuggingFaceModel> models, String nextPageUrl) {
        this.models = models == null ? Collections.<HuggingFaceModel>emptyList() : models;
        this.nextPageUrl = nextPageUrl;
    }

    public List<HuggingFaceModel> getModels() {
        return models;
    }

    /** @return the URL to fetch the next page, or {@code null} when this was the last page. */
    public String getNextPageUrl() {
        return nextPageUrl;
    }
}
