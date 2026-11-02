package com.aresstack.serp4j.playwright.google;

import com.microsoft.playwright.APIRequestContext;
import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.PlaywrightException;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BrowserGotoResolverTest {

    private final GoogleSerpPage parsed = new GoogleSerpHtmlParser()
            .parse(GoogleSerpHtmlParserTest.html("browser-web-de.html"));

    @Test
    void resolvesGotoLinksWithTheBrowserSessionsRedirect() {
        // Live with Camoufox (02.11.): /goto answers 302 + Location inside the session that got the result page.
        List<String> requested = new ArrayList<String>();
        APIRequestContext request = request(url -> {
            requested.add(url);
            return response(302, "https://example.org/" + requested.size());
        });

        GoogleSerpPage resolved = BrowserGotoResolver.resolve(request, parsed, Clock.systemUTC(), 10_000);

        for (GoogleSerpPage.Organic hit : resolved.getOrganicResults()) {
            assertThat(hit.getUrl()).startsWith("https://example.org/");
            for (GoogleSerpPage.Link link : hit.getSiteLinks()) {
                assertThat(link.getUrl()).startsWith("https://example.org/");
            }
        }
        assertThat(requested).isNotEmpty().allMatch(BrowserGotoResolver::isGoogleGoto).doesNotHaveDuplicates();
        assertThat(resolved.getRelatedQueries()).isEqualTo(parsed.getRelatedQueries());
    }

    @Test
    void keepsTheGotoLinkWhenGoogleDoesNotRedirectOrTheRequestFails() {
        APIRequestContext request = request(url -> {
            if (url.endsWith("ANONYMIZED")) {
                throw new PlaywrightException("net::ERR_TIMED_OUT");
            }
            return response(200, null);
        });

        GoogleSerpPage resolved = BrowserGotoResolver.resolve(request, parsed, Clock.systemUTC(), 10_000);

        for (int i = 0; i < parsed.getOrganicResults().size(); i++) {
            assertThat(resolved.getOrganicResults().get(i).getUrl())
                    .isEqualTo(parsed.getOrganicResults().get(i).getUrl());
        }
    }

    @Test
    void retriesAFailedOrganicLinkOnceBeforeTheSitelinks() {
        String first = parsed.getOrganicResults().get(0).getUrl();
        List<String> requested = new ArrayList<String>();
        APIRequestContext request = request(url -> {
            requested.add(url);
            if (url.equals(first) && requested.indexOf(url) == requested.size() - 1) {
                throw new PlaywrightException("Timeout 1500ms exceeded.");
            }
            return response(302, "https://example.org/" + requested.size());
        });

        GoogleSerpPage resolved = BrowserGotoResolver.resolve(request, parsed, Clock.systemUTC(), 10_000);

        assertThat(resolved.getOrganicResults().get(0).getUrl()).startsWith("https://example.org/");
        assertThat(requested.stream().filter(first::equals).count()).isEqualTo(2);
        String firstSiteLink = parsed.getOrganicResults().get(0).getSiteLinks().get(0).getUrl();
        assertThat(requested.lastIndexOf(first)).isLessThan(requested.indexOf(firstSiteLink));
    }

    @Test
    void noBudgetMeansNoRequest() {
        List<String> requested = new ArrayList<String>();
        BrowserGotoResolver.resolve(request(url -> {
            requested.add(url);
            return response(302, "https://example.org/");
        }), parsed, Clock.systemUTC(), 0);

        assertThat(requested).isEmpty();
    }

    @Test
    void targetUnwrapsTranslateAndRejectsGooglePages() {
        String gotoUrl = "https://www.google.com/goto?url=X";
        assertThat(BrowserGotoResolver.targetOf(gotoUrl, "https://pf4j.org/")).isEqualTo("https://pf4j.org/");
        assertThat(BrowserGotoResolver.targetOf(gotoUrl,
                "https://translate.google.com/translate?u=https://pf4j.org/&hl=de&sl=en&tl=de&client=search"))
                .isEqualTo("https://pf4j.org/");
        assertThat(BrowserGotoResolver.targetOf(gotoUrl, "https://www.google.com/sorry/index?continue=x")).isNull();
        assertThat(BrowserGotoResolver.targetOf(gotoUrl, "javascript:alert(1)")).isNull();
        assertThat(BrowserGotoResolver.targetOf(gotoUrl, null)).isNull();
        assertThat(BrowserGotoResolver.isGoogleGoto("https://www.google.de/goto?url=X")).isTrue();
        assertThat(BrowserGotoResolver.isGoogleGoto("https://example.org/goto?url=X")).isFalse();
    }

    private interface Answer {
        APIResponse answer(String url);
    }

    private static APIRequestContext request(Answer answer) {
        return (APIRequestContext) Proxy.newProxyInstance(APIRequestContext.class.getClassLoader(),
                new Class<?>[]{APIRequestContext.class}, (p, m, a) -> {
                    if (m.getName().equals("get")) {
                        return answer.answer((String) a[0]);
                    }
                    throw new UnsupportedOperationException(m.getName());
                });
    }

    private static APIResponse response(int status, String location) {
        Map<String, String> headers = location == null ? Collections.<String, String>emptyMap()
                : Collections.singletonMap("location", location);
        return (APIResponse) Proxy.newProxyInstance(APIResponse.class.getClassLoader(),
                new Class<?>[]{APIResponse.class}, (p, m, a) -> {
                    switch (m.getName()) {
                        case "status":
                            return status;
                        case "headers":
                            return headers;
                        case "dispose":
                            return null;
                        default:
                            throw new UnsupportedOperationException(m.getName());
                    }
                });
    }
}
