package com.aresstack.askai.hf;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Merge/pagination regression for {@link HuggingFaceSearchUseCase}, driven by a scripted
 * {@link HuggingFaceSearchGateway} so no live HTTP is involved. Covers the single-stream pass-through,
 * merged first page de-dup, merged "load more" across several cursored streams, and Base-only within
 * a merge.
 */
public class HuggingFaceSearchUseCaseTest {

    private static HuggingFaceModel model(String id, String... tags) {
        return new HuggingFaceModel(id, "text-generation", 0, 0, Arrays.asList(tags));
    }

    /** A gateway whose search() answer is keyed by the request's first library, and whose loadMore()
     *  answer is keyed by the cursor URL. Each page carries an optional next-cursor to chain. */
    private static final class FakeGateway implements HuggingFaceSearchGateway {
        final Map<String, HuggingFaceSearchPage> byLibrary = new HashMap<String, HuggingFaceSearchPage>();
        final Map<String, HuggingFaceSearchPage> byCursor = new HashMap<String, HuggingFaceSearchPage>();

        public HuggingFaceSearchPage searchModels(ModelSearchCriteria criteria) throws IOException {
            String key = criteria.getLibraries().isEmpty() ? "" : criteria.getLibraries().get(0);
            HuggingFaceSearchPage page = byLibrary.get(key);
            if (page == null) {
                throw new IOException("no scripted page for library " + key);
            }
            return page;
        }

        public HuggingFaceSearchPage loadMore(String nextPageUrl) throws IOException {
            HuggingFaceSearchPage page = byCursor.get(nextPageUrl);
            if (page == null) {
                throw new IOException("no scripted page for cursor " + nextPageUrl);
            }
            return page;
        }
    }

    private static ModelSearchCriteria libraries(String... libs) {
        return ModelSearchCriteria.builder().libraries(Arrays.asList(libs)).build();
    }

    @Test
    public void singleStreamPassesThroughWithCursor() throws IOException {
        FakeGateway gateway = new FakeGateway();
        gateway.byLibrary.put("gguf", new HuggingFaceSearchPage(
                Arrays.asList(model("a/1"), model("a/2")), "next-gguf"));
        HuggingFaceSearchResult result = new HuggingFaceSearchUseCase(gateway).search(libraries("gguf"));

        assertEquals(2, result.getModels().size());
        assertEquals("next-gguf", result.getNextPageUrl());
        assertTrue(result.isLoadMoreSupported());
        assertEquals(null, result.getMerged());
    }

    @Test
    public void mergedFirstPageDeduplicatesAcrossStreams() throws IOException {
        FakeGateway gateway = new FakeGateway();
        // Two OR'd libraries; "a/1" appears in both and must be returned once.
        gateway.byLibrary.put("gguf", new HuggingFaceSearchPage(
                Arrays.asList(model("a/1"), model("a/2")), "cursor-gguf"));
        gateway.byLibrary.put("safetensors", new HuggingFaceSearchPage(
                Arrays.asList(model("a/1"), model("a/3")), null));

        HuggingFaceSearchResult result = new HuggingFaceSearchUseCase(gateway)
                .search(libraries("gguf", "safetensors"));

        assertEquals(ids("a/1", "a/2", "a/3"), ids(result));
        assertEquals(null, result.getNextPageUrl());        // merged streams don't use the single cursor
        assertTrue(result.isLoadMoreSupported());            // gguf stream still has a cursor
        assertTrue(result.getMerged() != null);
        assertEquals(Arrays.asList("cursor-gguf", null), result.getMerged().getCursors());
    }

    @Test
    public void mergedLoadMoreAdvancesOpenStreamsAndDedupes() throws IOException {
        FakeGateway gateway = new FakeGateway();
        gateway.byLibrary.put("gguf", new HuggingFaceSearchPage(
                Collections.singletonList(model("a/1")), "gguf-p2"));
        gateway.byLibrary.put("safetensors", new HuggingFaceSearchPage(
                Collections.singletonList(model("a/2")), "safe-p2"));
        // Page 2 of each stream; "a/2" repeats (already returned) and must be dropped.
        gateway.byCursor.put("gguf-p2", new HuggingFaceSearchPage(
                Arrays.asList(model("a/3"), model("a/2")), null));       // gguf now exhausted
        gateway.byCursor.put("safe-p2", new HuggingFaceSearchPage(
                Collections.singletonList(model("a/4")), "safe-p3"));    // safetensors still open

        HuggingFaceSearchUseCase useCase = new HuggingFaceSearchUseCase(gateway);
        ModelSearchCriteria criteria = libraries("gguf", "safetensors");
        HuggingFaceSearchResult first = useCase.search(criteria);
        assertEquals(ids("a/1", "a/2"), ids(first));

        HuggingFaceSearchResult more = useCase.loadMore(criteria, first);
        assertEquals(ids("a/3", "a/4"), ids(more));          // a/2 de-duplicated away
        assertTrue(more.isLoadMoreSupported());               // safetensors still open
        assertEquals(Arrays.asList(null, "safe-p3"), more.getMerged().getCursors());

        // Final round: safetensors' last page, nothing new -> load more turns off.
        gateway.byCursor.put("safe-p3", new HuggingFaceSearchPage(Collections.<HuggingFaceModel>emptyList(), null));
        HuggingFaceSearchResult last = useCase.loadMore(criteria, more);
        assertTrue(last.getModels().isEmpty());
        assertFalse(last.isLoadMoreSupported());
    }

    @Test
    public void mergedFiltersBaseOnly() throws IOException {
        FakeGateway gateway = new FakeGateway();
        gateway.byLibrary.put("gguf", new HuggingFaceSearchPage(
                Arrays.asList(model("root/1"), model("deriv/1", "base_model:quantized:root/1")), null));
        gateway.byLibrary.put("safetensors", new HuggingFaceSearchPage(
                Collections.singletonList(model("root/2")), null));

        ModelSearchCriteria criteria = ModelSearchCriteria.builder()
                .libraries(Arrays.asList("gguf", "safetensors")).baseOnly(true).build();
        HuggingFaceSearchResult result = new HuggingFaceSearchUseCase(gateway).search(criteria);

        assertEquals(ids("root/1", "root/2"), ids(result));   // the base_model: derivative is dropped
        assertFalse(result.isLoadMoreSupported());            // both streams exhausted
    }

    private static List<String> ids(HuggingFaceSearchResult result) {
        List<String> out = new ArrayList<String>();
        for (HuggingFaceModel m : result.getModels()) {
            out.add(m.getId());
        }
        return out;
    }

    private static List<String> ids(String... values) {
        return Arrays.asList(values);
    }
}
