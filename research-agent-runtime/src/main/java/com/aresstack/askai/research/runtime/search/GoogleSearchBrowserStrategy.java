package com.aresstack.askai.research.runtime.search;

import com.aresstack.askai.browser.sidecar.SearchBrowser;
import com.aresstack.askai.research.runtime.search.provider.SearchEngine;
import com.aresstack.askai.research.runtime.search.provider.SearchProviderId;
import com.aresstack.askai.research.runtime.search.provider.async.AsyncModuleSearchProvider;
import com.aresstack.askai.research.search.serp4j.Serp4jWebSearchProvider;
import com.aresstack.serp4j.playwright.google.GooglePlaywrightSearchProvider;

import java.util.Locale;

/**
 * The browser-based initial search after askai #31: Google through Serp4J ({@code serp4j-playwright-google}) on
 * AskAI's own search browser, called directly in this JVM. It replaces the old browser SERP path (MCP tools,
 * layout analysis) and runs through the same Serp4J adapter chain as the API providers, so error mapping and
 * candidates behave alike.
 */
public final class GoogleSearchBrowserStrategy {

    /** Upper bound of one Google search including a possible browser (re)start. */
    public static final long SEARCH_TIMEOUT_MILLIS = 90_000L;

    private GoogleSearchBrowserStrategy() {
    }

    /**
     * @param searchBrowser AskAI's search browser (owned by the caller)
     * @param locale        language and country of the Google result page; empty parts leave Google's default
     */
    public static SearchStrategy create(SearchBrowser searchBrowser, Locale locale) {
        // askai #41 (lazy resolution): google.*/goto links stay as Google lists them; no request resolves them during
        // the search. The browser follows such a link when the research pipeline actually opens the hit.
        GooglePlaywrightSearchProvider.Builder google = GooglePlaywrightSearchProvider.builder();
        if (locale != null && !locale.getLanguage().isEmpty()) {
            google.defaultLanguage(locale.getLanguage());
        }
        if (locale != null && !locale.getCountry().isEmpty()) {
            google.defaultCountry(locale.getCountry());
        }
        Serp4jWebSearchProvider web = new Serp4jWebSearchProvider(
                com.aresstack.askai.research.search.api.SearchProviderId.GOOGLE_BROWSER,
                com.aresstack.askai.research.search.api.SearchEngine.GOOGLE,
                searchBrowser.asSearchProvider(google.build()));
        return new SingleProviderSearchStrategy(new AsyncModuleSearchProvider(SearchProviderId.GOOGLE_BROWSER,
                SearchEngine.GOOGLE, web, SEARCH_TIMEOUT_MILLIS), SearchEngine.GOOGLE);
    }
}
