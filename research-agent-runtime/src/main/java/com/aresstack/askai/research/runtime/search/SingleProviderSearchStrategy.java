package com.aresstack.askai.research.runtime.search;

import com.aresstack.askai.browser.search.SearchResultCandidate;
import com.aresstack.askai.browser.search.SearchResultSiteLink;
import com.aresstack.askai.browser.search.inference.CancellationSignal;
import com.aresstack.askai.research.runtime.search.provider.SearchEngine;
import com.aresstack.askai.research.runtime.search.provider.SearchHit;
import com.aresstack.askai.research.runtime.search.provider.SearchProvider;
import com.aresstack.askai.research.runtime.search.provider.SearchProviderRequest;
import com.aresstack.askai.research.runtime.search.provider.SearchProviderResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The API strategy that uses exactly ONE configured {@link SearchProvider}. It turns the neutral request
 * into a {@link SearchProviderRequest} for the configured {@link SearchEngine}, calls the provider once,
 * normalizes each organic hit's URL and maps it onto the same neutral {@link SearchResultCandidate} the
 * existing reranker consumes — so from the returned {@link InitialSearchResult} onward the loop is identical
 * to the browser path. It never falls back to another provider or to the browser: a provider failure
 * propagates as a typed provider exception (surfaced visibly by the loop).
 *
 * <p>API results are DIRECT target URLs, so there are no transit provider hosts and no challenges — those
 * lists are always empty here.</p>
 */
public final class SingleProviderSearchStrategy implements SearchStrategy {

    private final SearchProvider provider;
    private final SearchEngine searchEngine;

    public SingleProviderSearchStrategy(SearchProvider provider, SearchEngine searchEngine) {
        if (provider == null) {
            throw new IllegalArgumentException("provider must not be null");
        }
        if (searchEngine == null) {
            throw new IllegalArgumentException("searchEngine must not be null");
        }
        this.provider = provider;
        this.searchEngine = searchEngine;
    }

    @Override
    public InitialSearchResult search(InitialSearchRequest request, CancellationSignal cancellation,
                                      SearchBudgetGate budget) {
        // A provider call is a budgeted external call; honour cancellation and the central budget first.
        if (cancellation != null && cancellation.isCancelled()) {
            return InitialSearchResult.empty(
                    Collections.singletonList("initial search cancelled before the provider call"));
        }
        if (!budget.beforeToolCall()) {
            return InitialSearchResult.empty(
                    Collections.singletonList("initial search budget exhausted before the provider call"));
        }

        SearchProviderRequest providerRequest = new SearchProviderRequest(request.getQuery(), searchEngine,
                request.getRequestedResultCount(), request.getLanguage(), request.getCountry());
        SearchProviderResult providerResult = provider.search(providerRequest);

        List<SearchResultCandidate> candidates = new ArrayList<SearchResultCandidate>();
        for (SearchHit hit : providerResult.getHits()) {
            candidates.add(toCandidate(hit));
        }
        List<String> diagnostics = new ArrayList<String>();
        diagnostics.add(
                "provider " + providerResult.getProviderId() + " / " + providerResult.getSearchEngine()
                        + " returned " + candidates.size() + " organic candidate(s)"
                        + (providerResult.getRawResponse().isEmpty() ? ""
                        : ", raw response " + providerResult.getRawResponse().length() + " chars"));
        List<String> unresolved = providerResult.getUnresolvedRedirects();
        if (!unresolved.isEmpty()) {
            diagnostics.add(unresolved.size() + " further hit(s) found but excluded as sources: redirect target"
                    + " not resolved");
            for (String line : unresolved) {
                diagnostics.add("unresolved redirect: " + line);
            }
        }
        return new InitialSearchResult(candidates, Collections.<String>emptyList(),
                Collections.<com.aresstack.askai.browser.search.repair.SearchChallengeState>emptyList(),
                diagnostics);
    }

    /**
     * Map a provider hit onto the neutral candidate: the normalized URL is what gets navigated, the raw
     * provider URL is kept as diagnostic provenance, and DOM-only fields are empty with full confidence
     * (an API hit is a certain, structured result, not an inferred SERP block).
     */
    private static SearchResultCandidate toCandidate(SearchHit hit) {
        String normalized = SearchUrlNormalizer.normalize(hit.getUrl());
        return new SearchResultCandidate(
                hit.getProviderId() + "#" + hit.getRank(),
                "",
                normalized,
                hit.getUrl(),
                hit.getTitle(),
                hit.getSnippet(),
                hit.getDomain(),
                hit.getRank(),
                "",
                "",
                1.0,
                1.0,
                Collections.<SearchResultSiteLink>emptyList());
    }
}
