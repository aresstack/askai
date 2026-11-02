package com.aresstack.serp4j.playwright.google;

import com.aresstack.serp4j.core.error.SearchErrorCategory;
import com.aresstack.serp4j.core.error.SearchProviderException;
import com.aresstack.serp4j.core.model.SearchRequest;
import com.aresstack.serp4j.core.model.SearchResponse;
import com.aresstack.serp4j.playwright.PlaywrightSearchFailures;
import com.aresstack.serp4j.playwright.PlaywrightSearchProvider;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.Response;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contract of the synchronous serp4j-playwright provider against a scripted browser (no real browser, no network):
 * the host's browser is used, never closed; only the provider's own page is closed.
 */
class GooglePlaywrightSearchProviderTest {

    private final PlaywrightSearchProvider provider = GooglePlaywrightSearchProvider.builder()
            .defaultLanguage("de").defaultCountry("DE").build();

    @Test
    void mapsRecordedResultPageAndClosesOnlyItsPage() {
        FakeBrowser fake = new FakeBrowser("https://www.google.com/search?q=serp4j&hl=de",
                GoogleSerpHtmlParserTest.html("browser-web-de.html"), 200);

        SearchResponse response = provider.search(SearchRequest.builder("serp4j").build(), fake.browser());

        assertThat(response.getData().getResults()).isNotEmpty();
        assertThat(fake.navigated).hasSize(1);
        assertThat(fake.navigated.get(0)).startsWith("https://www.google.com/search?").doesNotContain("udm=50");
        assertThat(fake.pageClosed).isTrue();
        assertThat(fake.browserClosed).isFalse();
    }

    @Test
    void aGotoResolvedInTheBrowserKeepsTheGotoLinkAsOriginalUrl() {
        FakeBrowser fake = new FakeBrowser("https://www.google.com/search?q=serp4j&hl=de",
                GoogleSerpHtmlParserTest.html("browser-web-de.html"), 200);
        fake.gotoTarget = "https://example.org/target";

        SearchResponse response = provider.search(SearchRequest.builder("serp4j").build(), fake.browser());

        com.aresstack.serp4j.core.model.data.SearchResultItem first = response.getData().getResults().get(0);
        assertThat(first.getUrl()).isEqualTo("https://example.org/target");
        assertThat(first.getExtensions().get(com.aresstack.serp4j.http.resolve.ResultUrlEnricher.ORIGINAL_URL))
                .hasValueSatisfying(url -> assertThat(url).startsWith("https://www.google.com/goto?url="));
        assertThat(first.getExtensions().get(com.aresstack.serp4j.http.resolve.ResultUrlEnricher.URL_RESOLUTION))
                .contains(com.aresstack.serp4j.http.resolve.ResultUrlEnricher.RESOLVED);
    }

    @Test
    void waitsForTheResultPageAfterGooglesJavaScriptStartPage() {
        // Live with Camoufox (02.11.): /search first answers with a JavaScript start page whose noscript block
        // points to /httpservice/retry/enablejs; #search appears only after that script ran.
        FakeBrowser fake = new FakeBrowser("https://www.google.com/search?q=serp4j&hl=de",
                GoogleSerpHtmlParserTest.html("browser-web-de.html"), 200);

        provider.search(SearchRequest.builder("serp4j").build(), fake.browser());

        assertThat(fake.awaited).containsExactly(GooglePlaywrightSearchProvider.RESULT_PAGE_SELECTOR);
        assertThat(GooglePlaywrightSearchProvider.RESULT_PAGE_SELECTOR)
                .contains("#search", "#rso", "#captcha-form", "consent.google.");
    }

    @Test
    void startPageThatNeverRendersIsReportedAsJavaScriptRequired() {
        FakeBrowser fake = new FakeBrowser("https://www.google.com/search?q=serp4j&hl=de",
                "<html><head><noscript><meta content=\"0;url=/httpservice/retry/enablejs?sei=x\" "
                        + "http-equiv=\"refresh\"></noscript></head><body></body></html>", 200);
        fake.waitTimesOut = true;

        assertThatThrownBy(() -> provider.search(SearchRequest.builder("serp4j").build(), fake.browser()))
                .isInstanceOfSatisfying(SearchProviderException.class, e -> {
                    assertThat(e.getCategory()).isEqualTo(SearchErrorCategory.BLOCKED);
                    assertThat(e.getDetails())
                            .containsEntry(PlaywrightSearchFailures.DETAIL_OBSTACLE, "javascript_required");
                });
        assertThat(fake.pageClosed).isTrue();
    }

    @Test
    void challengePageIsBlockedWithoutClicking() {
        FakeBrowser fake = new FakeBrowser("https://www.google.com/sorry/index?continue=x",
                "<html><body><form id=\"captcha-form\"></form></body></html>", 429);

        assertThatThrownBy(() -> provider.search(SearchRequest.builder("serp4j").build(), fake.browser()))
                .isInstanceOfSatisfying(SearchProviderException.class, e -> {
                    assertThat(e.getCategory()).isEqualTo(SearchErrorCategory.BLOCKED);
                    assertThat(e.getDetails()).containsEntry(PlaywrightSearchFailures.DETAIL_OBSTACLE, "challenge");
                });
        assertThat(fake.pageClosed).isTrue();
    }

    @Test
    void disconnectedBrowserIsUnavailableBeforeAnyPageIsOpened() {
        FakeBrowser fake = new FakeBrowser("about:blank", "", 200);
        fake.connected = false;

        assertThatThrownBy(() -> provider.search(SearchRequest.builder("serp4j").build(), fake.browser()))
                .isInstanceOfSatisfying(SearchProviderException.class,
                        e -> assertThat(e.getCategory()).isEqualTo(SearchErrorCategory.UNAVAILABLE));
        assertThat(fake.navigated).isEmpty();
    }

    @Test
    void closedTargetDuringNavigationIsUnavailable() {
        FakeBrowser fake = new FakeBrowser("about:blank", "", 200);
        fake.navigationFailure = new PlaywrightException("Target page, context or browser has been closed");

        assertThatThrownBy(() -> provider.search(SearchRequest.builder("serp4j").build(), fake.browser()))
                .isInstanceOfSatisfying(SearchProviderException.class,
                        e -> assertThat(e.getCategory()).isEqualTo(SearchErrorCategory.UNAVAILABLE));
        assertThat(fake.pageClosed).isTrue();
        assertThat(fake.browserClosed).isFalse();
    }

    /** Scripted Playwright objects via dynamic proxies; only the methods the provider uses are answered. */
    private static final class FakeBrowser {
        final String finalUrl;
        final String html;
        final int status;
        final List<String> navigated = new ArrayList<String>();
        final List<String> awaited = new ArrayList<String>();
        boolean waitTimesOut;
        boolean connected = true;
        boolean pageClosed;
        boolean browserClosed;
        RuntimeException navigationFailure;
        /** Where Google's /goto redirects inside the browser session; {@code null}: no redirect. */
        String gotoTarget;

        FakeBrowser(String finalUrl, String html, int status) {
            this.finalUrl = finalUrl;
            this.html = html;
            this.status = status;
        }

        Browser browser() {
            // /goto links answer without redirect here, so the URLs stay as parsed.
            com.microsoft.playwright.APIResponse noRedirect = proxy(com.microsoft.playwright.APIResponse.class,
                    (name, args) -> {
                        switch (name) {
                            case "status":
                                return gotoTarget == null ? 200 : 302;
                            case "headers":
                                return Collections.singletonMap("location", gotoTarget);
                            default:
                                return null;
                        }
                    });
            com.microsoft.playwright.APIRequestContext request = proxy(
                    com.microsoft.playwright.APIRequestContext.class, (name, args) -> {
                        if (name.equals("get")) {
                            return noRedirect;
                        }
                        throw new UnsupportedOperationException(name);
                    });
            BrowserContext[] holder = new BrowserContext[1];
            holder[0] = proxy(BrowserContext.class, (name, args) -> {
                if (name.equals("newPage")) {
                    return page(holder[0]);
                }
                if (name.equals("request")) {
                    return request;
                }
                throw new UnsupportedOperationException(name);
            });
            BrowserContext context = holder[0];
            return proxy(Browser.class, (name, args) -> {
                switch (name) {
                    case "isConnected":
                        return connected;
                    case "contexts":
                        return Collections.singletonList(context);
                    case "close":
                        browserClosed = true;
                        return null;
                    default:
                        throw new UnsupportedOperationException(name);
                }
            });
        }

        private Page page(BrowserContext context) {
            Response response = proxy(Response.class, (name, args) -> {
                if (name.equals("status")) {
                    return status;
                }
                throw new UnsupportedOperationException(name);
            });
            return proxy(Page.class, (name, args) -> {
                switch (name) {
                    case "setDefaultTimeout":
                        return null;
                    case "navigate":
                        navigated.add((String) args[0]);
                        if (navigationFailure != null) {
                            throw navigationFailure;
                        }
                        return response;
                    case "waitForSelector":
                        awaited.add((String) args[0]);
                        if (waitTimesOut) {
                            throw new com.microsoft.playwright.TimeoutError("Timeout 15000ms exceeded.");
                        }
                        return null;
                    case "url":
                        return finalUrl;
                    case "content":
                        return html;
                    case "context":
                        return context;
                    case "close":
                        pageClosed = true;
                        return null;
                    default:
                        throw new UnsupportedOperationException(name);
                }
            });
        }

        private interface Handler {
            Object handle(String name, Object[] args);
        }

        @SuppressWarnings("unchecked")
        private static <T> T proxy(Class<T> type, Handler handler) {
            return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (p, m, a) -> {
                if (m.getDeclaringClass() == Object.class) {
                    return m.getName().equals("toString") ? type.getSimpleName() : m.invoke(handler, a);
                }
                return handler.handle(m.getName(), a);
            });
        }
    }
}
