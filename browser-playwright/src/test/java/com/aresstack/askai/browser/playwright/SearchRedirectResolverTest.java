package com.aresstack.askai.browser.playwright;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.Assert.assertEquals;

/**
 * Static resolution of known search-engine redirect wrappers — applied BEFORE any domain judgement.
 * An unresolvable wrapper is UNRESOLVED (controlled discard), never misclassified by the wrapper host.
 */
public class SearchRedirectResolverTest {

    private static String bingWrapped(String target) {
        return "https://www.bing.com/ck/a?!&&p=abc&u=a1"
                + Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(target.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void bingCkWrappersDecodeTheirBase64Target() {
        SearchRedirectResolver.Resolution resolution =
                SearchRedirectResolver.resolve(bingWrapped("https://pf4j.org/doc/getting-started.html"));
        assertEquals(SearchRedirectResolver.Status.RESOLVED, resolution.getStatus());
        assertEquals("https://pf4j.org/doc/getting-started.html", resolution.getTargetUrl());
    }

    @Test
    public void googleUrlAndDuckDuckGoWrappersDecodeTheirParameter() {
        SearchRedirectResolver.Resolution google = SearchRedirectResolver.resolve(
                "https://www.google.com/url?q=https%3A%2F%2Fexample.org%2Fa&sa=U");
        assertEquals(SearchRedirectResolver.Status.RESOLVED, google.getStatus());
        assertEquals("https://example.org/a", google.getTargetUrl());

        SearchRedirectResolver.Resolution ddg = SearchRedirectResolver.resolve(
                "https://duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.net%2Fb&rut=x");
        assertEquals(SearchRedirectResolver.Status.RESOLVED, ddg.getStatus());
        assertEquals("https://example.net/b", ddg.getTargetUrl());
    }

    @Test
    public void unresolvableWrappersAreDiscardedNotMisclassified() {
        assertEquals("bing wrapper without a decodable target",
                SearchRedirectResolver.Status.UNRESOLVED,
                SearchRedirectResolver.resolve("https://www.bing.com/ck/a?!&&p=abc").getStatus());
        assertEquals("google /url without target",
                SearchRedirectResolver.Status.UNRESOLVED,
                SearchRedirectResolver.resolve("https://www.google.com/url?sa=U").getStatus());
    }

    @Test
    public void ordinaryUrlsPassThroughUntouched() {
        SearchRedirectResolver.Resolution plain =
                SearchRedirectResolver.resolve("https://github.com/pf4j/pf4j");
        assertEquals(SearchRedirectResolver.Status.NOT_A_REDIRECT, plain.getStatus());
        assertEquals("https://github.com/pf4j/pf4j", plain.getTargetUrl());
        assertEquals("a bing page OUTSIDE /ck/ is no wrapper",
                SearchRedirectResolver.Status.NOT_A_REDIRECT,
                SearchRedirectResolver.resolve("https://www.bing.com/videos/search?q=x").getStatus());
    }
}
