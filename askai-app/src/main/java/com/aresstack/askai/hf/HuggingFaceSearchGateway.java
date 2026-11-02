package com.aresstack.askai.hf;

import java.io.IOException;

/**
 * The narrow HTTP surface {@link HuggingFaceSearchUseCase} needs: run one all-ANDed search request,
 * or continue an existing cursor. {@link HuggingFaceClient} is the production implementation; the
 * interface exists so the multi-request merge/pagination logic in the use case can be unit-tested
 * with a scripted fake instead of live HTTP.
 */
public interface HuggingFaceSearchGateway {

    /** Runs one {@code /api/models} request built from the (already all-ANDed) criteria. */
    HuggingFaceSearchPage searchModels(ModelSearchCriteria criteria) throws IOException;

    /** Continues pagination from a previous page's opaque {@code rel="next"} cursor URL. */
    HuggingFaceSearchPage loadMore(String nextPageUrl) throws IOException;
}
