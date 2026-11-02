package com.aresstack.serp4j.playwright.google;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.PlaywrightException;

import java.time.Duration;
import java.util.List;

/**
 * Google {@code /goto} links for hosts (askai #41): a hit whose {@code /goto} link could not be resolved during the
 * search stays a candidate, and the host can try again later, before it opens the hit, in the same search browser
 * (whose session Google redirects). Only the {@code Location} of the redirect is read; the target is not loaded.
 */
public final class GoogleGotoLinks {

    private GoogleGotoLinks() {
    }

    /** {@code true} for {@code https://www.google.<tld>/goto?…}. */
    public static boolean isGoogleGoto(String url) {
        return BrowserGotoResolver.isGoogleGoto(url);
    }

    /**
     * Resolves one {@code /goto} link in the browser's first context (the search session).
     *
     * @return the target URL, or {@code null} when the link is no Google {@code /goto}, the browser has no context,
     *         Google does not redirect, or the request fails or times out
     */
    public static String resolve(Browser browser, String url, Duration timeout) {
        if (browser == null || !isGoogleGoto(url)) {
            return null;
        }
        try {
            List<BrowserContext> contexts = browser.contexts();
            if (contexts.isEmpty()) {
                return null;
            }
            return BrowserGotoResolver.resolveOne(contexts.get(0).request(), url,
                    Math.max(1L, timeout == null ? BrowserGotoResolver.PER_LINK_TIMEOUT.toMillis() : timeout.toMillis()));
        } catch (PlaywrightException e) {
            return null;
        }
    }
}
