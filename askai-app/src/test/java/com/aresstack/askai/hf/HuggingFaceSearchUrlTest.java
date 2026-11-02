package com.aresstack.askai.hf;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Locks the query encoding of {@link HuggingFaceClient#buildSearchUrl}: apps use the dedicated
 * {@code apps=} facet (not {@code filter=}), the warm-inference switch emits {@code inference=warm},
 * and tag groups stay {@code filter=}. Verified against the live API in the change that introduced it.
 */
public class HuggingFaceSearchUrlTest {

    @Test
    public void appsUseAppsParamNotFilter() {
        String url = HuggingFaceClient.buildSearchUrl(ModelSearchCriteria.builder()
                .libraries(Collections.<String>emptyList())
                .apps(Collections.singletonList("ollama"))
                .build());
        assertTrue(url, url.contains("apps=ollama"));
        assertFalse(url, url.contains("filter=ollama"));
    }

    @Test
    public void librariesTasksLicensesStayFilterTags() {
        String url = HuggingFaceClient.buildSearchUrl(ModelSearchCriteria.builder()
                .libraries(Collections.singletonList("gguf"))
                .tasks(Collections.singletonList("text-generation"))
                .licenses(Collections.singletonList("license:mit"))
                .build());
        assertTrue(url, url.contains("filter=gguf"));
        assertTrue(url, url.contains("filter=text-generation"));
        assertTrue(url, url.contains("filter=license%3Amit"));
    }

    @Test
    public void inferenceSwitchEmitsWarm() {
        String on = HuggingFaceClient.buildSearchUrl(ModelSearchCriteria.builder().inference(true).build());
        assertTrue(on, on.contains("inference=warm"));
        String off = HuggingFaceClient.buildSearchUrl(ModelSearchCriteria.builder().inference(false).build());
        assertFalse(off, off.contains("inference=warm"));
    }

    @Test
    public void gatedAndSearchAndSort() {
        String url = HuggingFaceClient.buildSearchUrl(ModelSearchCriteria.builder()
                .searchText("qwen coder")
                .gated(true)
                .sortOrder(SortOrder.MOST_LIKES)
                .pageSize(42)
                .apps(Arrays.asList("ollama", "vllm"))
                .build());
        assertTrue(url, url.contains("search=qwen+coder") || url.contains("search=qwen%20coder"));
        assertTrue(url, url.contains("gated=true"));
        assertTrue(url, url.contains("sort=likes"));
        assertTrue(url, url.contains("limit=42"));
        assertTrue(url, url.contains("apps=ollama"));
        assertTrue(url, url.contains("apps=vllm"));
    }
}
