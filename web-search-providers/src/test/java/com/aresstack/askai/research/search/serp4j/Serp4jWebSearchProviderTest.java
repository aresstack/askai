package com.aresstack.askai.research.search.serp4j;

import com.aresstack.askai.research.search.api.SearchEngine;
import com.aresstack.askai.research.search.api.SearchProviderId;
import com.aresstack.askai.research.search.api.WebSearchException;
import com.aresstack.askai.research.search.api.WebSearchRequest;
import com.aresstack.askai.research.search.api.WebSearchResult;
import com.aresstack.askai.research.search.http.HttpResponseException;
import com.aresstack.serp4j.core.error.SearchErrorCategory;
import com.aresstack.serp4j.core.error.SearchProviderException;
import com.aresstack.serp4j.core.model.SearchRequest;
import com.aresstack.serp4j.core.model.SearchResponse;
import com.aresstack.serp4j.core.model.data.ResultProvenance;
import com.aresstack.serp4j.core.model.data.ResultType;
import com.aresstack.serp4j.core.model.data.SearchData;
import com.aresstack.serp4j.core.model.data.SearchResultItem;
import com.aresstack.serp4j.core.model.data.SerpPosition;
import com.aresstack.serp4j.core.model.ext.RawPayload;
import com.aresstack.serp4j.core.model.ext.RawPayloadPolicy;
import com.aresstack.serp4j.core.model.id.ElementId;
import com.aresstack.serp4j.core.model.id.ProviderId;
import com.aresstack.serp4j.core.model.id.SearchEngineId;
import com.aresstack.serp4j.core.model.id.SearchVertical;
import com.aresstack.serp4j.core.model.metadata.QueryInfo;
import com.aresstack.serp4j.core.model.metadata.SearchMetadata;
import com.aresstack.serp4j.core.port.ProviderDescriptor;
import com.aresstack.serp4j.core.port.SearchContext;
import com.aresstack.serp4j.core.port.SearchProvider;
import org.junit.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Adapter contract with a fake Serp4J provider (no HTTP); all data here is synthetic test input. */
public class Serp4jWebSearchProviderTest {

    private static final ProviderId DFS = ProviderId.of("dataforseo");

    @Test
    public void passesConfiguredEngineLocalePagingAndAsksForRawPayload() throws Exception {
        FakeProvider fake = new FakeProvider(CompletableFuture.completedFuture(response(SearchEngineId.BING, null)));
        Serp4jWebSearchProvider adapter =
                new Serp4jWebSearchProvider(SearchProviderId.DATA_FOR_SEO, SearchEngine.BING, fake);

        adapter.search(WebSearchRequest.builder("java").countryCode("de").languageCode("DE")
                .maximumResults(5).offset(10).build()).get();

        SearchRequest sent = fake.lastRequest.get();
        assertEquals(SearchEngineId.BING, sent.getEngine().get());
        assertEquals(RawPayloadPolicy.ALWAYS, sent.getRawPayloadPolicy());
        assertEquals(5, sent.getPaging().getLimit());
        assertEquals(10, sent.getPaging().getOffset());
        assertEquals("DE", sent.getLocale().get().getCountryCode().get());
        assertEquals("de", sent.getLocale().get().getLanguageCode().get());
    }

    @Test
    public void labelsHitsWithTheEngineSerp4jReportsNotGoogle() throws Exception {
        Serp4jWebSearchProvider adapter = new Serp4jWebSearchProvider(SearchProviderId.DATA_FOR_SEO,
                SearchEngine.BING, new FakeProvider(CompletableFuture.completedFuture(
                response(SearchEngineId.BING, "{\"tasks\":[]}"))));

        WebSearchResult result = adapter.search(WebSearchRequest.builder("java").build()).get();

        assertEquals(SearchEngine.BING, result.getSearchEngine());
        assertEquals(2, result.getHits().size());
        assertEquals(SearchEngine.BING, result.getHits().get(0).getSearchEngine());
        assertEquals(3, result.getHits().get(0).getRank());
        assertEquals("https://example.org/a?id=1", result.getHits().get(0).getUrl());
        // An item without a title falls back to its URL instead of being dropped.
        assertEquals("https://example.org/a?id=2", result.getHits().get(1).getTitle());
        assertEquals("{\"tasks\":[]}", result.getRawResponse());
    }

    @Test
    public void translatesSerp4jCategories() {
        assertStatus(SearchErrorCategory.AUTHENTICATION, 401);
        assertStatus(SearchErrorCategory.RATE_LIMITED, 429);
        assertStatus(SearchErrorCategory.QUOTA_EXHAUSTED, 429);
        assertStatus(SearchErrorCategory.UNAVAILABLE, 503);
        assertTrue(Serp4jWebSearchProvider.translate(failure(SearchErrorCategory.UNSUPPORTED))
                instanceof IllegalArgumentException);
        assertTrue(Serp4jWebSearchProvider.translate(failure(SearchErrorCategory.RESPONSE_INVALID))
                instanceof WebSearchException);
    }

    @Test
    public void failedFutureCarriesTranslatedException() throws Exception {
        CompletableFuture<SearchResponse> failed = new CompletableFuture<SearchResponse>();
        failed.completeExceptionally(failure(SearchErrorCategory.AUTHENTICATION));
        Serp4jWebSearchProvider adapter =
                new Serp4jWebSearchProvider(SearchProviderId.BRAVE, SearchEngine.BRAVE, new FakeProvider(failed));
        try {
            adapter.search(WebSearchRequest.builder("java").build()).get();
            fail("expected failure");
        } catch (ExecutionException expected) {
            assertTrue(expected.getCause() instanceof HttpResponseException);
            assertEquals(401, ((HttpResponseException) expected.getCause()).getStatusCode());
        }
    }

    @Test
    public void closeClosesProviderAndOwnedTransport() {
        FakeProvider fake = new FakeProvider(new CompletableFuture<SearchResponse>());
        final AtomicBoolean transportClosed = new AtomicBoolean();
        Serp4jWebSearchProvider adapter = new Serp4jWebSearchProvider(SearchProviderId.BRAVE, SearchEngine.BRAVE,
                fake, () -> transportClosed.set(true));
        adapter.close();
        assertTrue(fake.closed.get());
        assertTrue(transportClosed.get());
    }

    @Test
    public void braveEndpointIsReducedToApiBase() {
        assertEquals("https://api.search.brave.com/res/v1",
                Serp4jProviderFactory.braveBaseUrl("https://api.search.brave.com/res/v1/web/search/"));
        assertFalse(Serp4jProviderFactory.braveBaseUrl(null).isEmpty());
    }

    private static void assertStatus(SearchErrorCategory category, int status) {
        Throwable mapped = Serp4jWebSearchProvider.translate(failure(category));
        assertTrue(category + " -> " + mapped, mapped instanceof HttpResponseException);
        assertEquals(status, ((HttpResponseException) mapped).getStatusCode());
    }

    private static SearchProviderException failure(SearchErrorCategory category) {
        return new SearchProviderException(DFS, category, "synthetic " + category);
    }

    private static SearchResponse response(SearchEngineId engine, String raw) {
        SearchResultItem first = SearchResultItem.builder(ElementId.of("r1"), ResultType.ORGANIC,
                        "https://example.org/a?id=1")
                .title("A1").snippet("first")
                .position(SerpPosition.builder().rankInGroup(1).rankAbsolute(3).build())
                .provenance(ResultProvenance.builder(DFS, engine).build())
                .build();
        SearchResultItem second = SearchResultItem.builder(ElementId.of("r2"), ResultType.ORGANIC,
                        "https://example.org/a?id=2")
                .provenance(ResultProvenance.builder(DFS, engine).build())
                .build();
        SearchMetadata.Builder metadata = SearchMetadata.forProvider(DFS, engine, SearchVertical.WEB,
                QueryInfo.of("java"));
        if (raw != null) {
            metadata.rawPayload(RawPayload.ofText("application/json", raw));
        }
        return SearchResponse.of(SearchData.builder().result(first).result(second).build(), metadata.build());
    }

    private static final class FakeProvider implements SearchProvider {
        final AtomicReference<SearchRequest> lastRequest = new AtomicReference<SearchRequest>();
        final AtomicBoolean closed = new AtomicBoolean();
        private final CompletableFuture<SearchResponse> answer;

        FakeProvider(CompletableFuture<SearchResponse> answer) {
            this.answer = answer;
        }

        @Override
        public ProviderDescriptor getDescriptor() {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<SearchResponse> search(SearchRequest request, SearchContext context) {
            lastRequest.set(request);
            return answer;
        }

        @Override
        public void close() {
            closed.set(true);
        }
    }
}
