package com.aresstack.serp4j.playwright.google;

import com.microsoft.playwright.APIRequestContext;
import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.RequestOptions;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Resolves Google {@code /goto?url=…} result links inside the search browser's context, i.e. with the cookies of the
 * session that produced the result page. Google answers such a link with {@code 302 Location: <target>} only for
 * that session; a cookie-less HTTP client gets a page without redirect ({@code not_a_redirect}). Each link is one
 * request with {@code maxRedirects=0}; the target site itself is never fetched. Organic links come first, then
 * one retry of those that failed, then sitelinks. Links that do not resolve within
 * the budget stay {@code /goto} and are left to the provider's {@code ResultUrlEnricher}.
 */
final class BrowserGotoResolver {

    /** Successful answers take 0.1–0.6 s live; a link that hangs longer is retried once at the end. */
    static final Duration PER_LINK_TIMEOUT = Duration.ofMillis(1500);
    static final Duration TOTAL_BUDGET = Duration.ofSeconds(8);

    /** Marker for a request that failed (timeout, network) and is worth one retry. */
    private static final String FAILED = "\u0000failed";

    private BrowserGotoResolver() {
    }

    /**
     * Order: organic result links first, then those that failed once again, then sitelinks.
     *
     * @param request         the browser context's request API ({@code page.context().request()})
     * @param page            the parsed result page
     * @param remainingMillis time left for the whole search; the resolution never exceeds it
     */
    static GoogleSerpPage resolve(APIRequestContext request, GoogleSerpPage page, Clock clock,
                                  double remainingMillis) {
        Instant deadline = clock.instant().plusMillis(
                (long) Math.max(0, Math.min(TOTAL_BUDGET.toMillis(), remainingMillis)));
        Set<String> organic = new LinkedHashSet<String>();
        Set<String> siteLinks = new LinkedHashSet<String>();
        for (GoogleSerpPage.Organic hit : page.getOrganicResults()) {
            if (isGoogleGoto(hit.getUrl())) {
                organic.add(hit.getUrl());
            }
            for (GoogleSerpPage.Link link : hit.getSiteLinks()) {
                if (isGoogleGoto(link.getUrl())) {
                    siteLinks.add(link.getUrl());
                }
            }
        }
        siteLinks.removeAll(organic);
        Map<String, String> resolved = new HashMap<String, String>();
        resolveAll(request, organic, resolved, clock, deadline);
        List<String> retry = new ArrayList<String>();
        for (String url : organic) {
            if (FAILED.equals(resolved.get(url))) {
                retry.add(url);
            }
        }
        resolveAll(request, retry, resolved, clock, deadline);
        resolveAll(request, siteLinks, resolved, clock, deadline);
        return page.withResolvedUrls(url -> {
            String target = resolved.get(url);
            return FAILED.equals(target) ? null : target;
        });
    }

    private static void resolveAll(APIRequestContext request, Iterable<String> urls, Map<String, String> resolved,
                                   Clock clock, Instant deadline) {
        for (String url : urls) {
            long left = Duration.between(clock.instant(), deadline).toMillis();
            if (left <= 0) {
                return;
            }
            resolved.put(url, fetchLocation(request, url, Math.min(left, PER_LINK_TIMEOUT.toMillis())));
        }
    }

    /** @return the target, {@code null} when Google did not redirect, {@link #FAILED} when the request failed */
    private static String fetchLocation(APIRequestContext request, String url, long timeoutMillis) {
        APIResponse response = null;
        try {
            response = request.get(url, RequestOptions.create().setMaxRedirects(0).setTimeout(timeoutMillis));
            int status = response.status();
            if (status < 300 || status >= 400) {
                return null;
            }
            return targetOf(url, response.headers().get("location"));
        } catch (PlaywrightException e) {
            return FAILED;
        } finally {
            if (response != null) {
                try {
                    response.dispose();
                } catch (PlaywrightException ignored) {
                    // best effort
                }
            }
        }
    }

    /** The redirect target as an absolute http(s) URL; Google's translate wrapper is unwrapped. */
    static String targetOf(String gotoUrl, String location) {
        if (location == null || location.trim().isEmpty()) {
            return null;
        }
        URI target;
        try {
            target = URI.create(gotoUrl).resolve(location.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
        String scheme = target.getScheme() == null ? "" : target.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            return null;
        }
        String host = target.getHost() == null ? "" : target.getHost().toLowerCase(Locale.ROOT);
        if (host.startsWith("translate.google.") && target.getRawQuery() != null) {
            String original = queryParameter(target.getRawQuery(), "u");
            return original != null && (original.startsWith("http://") || original.startsWith("https://"))
                    ? original : null;
        }
        if (isGoogleHost(host)) {
            return null; // another Google page (sorry, consent, a further /goto): not a result target
        }
        return target.toString();
    }

    static boolean isGoogleGoto(String url) {
        if (url == null) {
            return false;
        }
        try {
            URI uri = URI.create(url);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            return isGoogleHost(host) && "/goto".equals(uri.getPath());
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean isGoogleHost(String host) {
        return host.equals("google.com") || host.startsWith("google.") || host.startsWith("www.google.")
                || host.contains(".google.");
    }

    private static String queryParameter(String rawQuery, String name) {
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(name)) {
                try {
                    return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8.name());
                } catch (java.io.UnsupportedEncodingException | IllegalArgumentException e) {
                    return null;
                }
            }
        }
        return null;
    }
}
