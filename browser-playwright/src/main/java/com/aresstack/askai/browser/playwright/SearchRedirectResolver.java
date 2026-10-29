package com.aresstack.askai.browser.playwright;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;

/**
 * Static resolution of known search-engine redirect wrappers, applied BEFORE any domain classification or
 * link typing — the raw wrapper URL must never be judged as an internal engine link nor used as a
 * relevance signal. Supported formats:
 * <ul>
 * <li>Bing: {@code /ck/a?...&u=a1<base64url(target)>}</li>
 * <li>Google: {@code /url?q=<target>} (also {@code url=} / {@code u=})</li>
 * <li>DuckDuckGo: {@code /l/?uddg=<target>}</li>
 * </ul>
 * A wrapper whose target cannot be extracted safely is {@code UNRESOLVED} — the caller discards it in a
 * controlled way instead of misclassifying it by the engine's host.
 */
final class SearchRedirectResolver {

    /** The outcome of one resolution attempt. */
    enum Status {
        /** The URL is no known redirect wrapper — use it as-is. */
        NOT_A_REDIRECT,
        /** The wrapper's target was extracted; {@link Resolution#getTargetUrl()} carries it. */
        RESOLVED,
        /** A known wrapper whose target could not be extracted — discard, never classify by wrapper host. */
        UNRESOLVED
    }

    static final class Resolution {
        private final Status status;
        private final String targetUrl;

        /** A pass-through resolution (used when redirect resolution is disabled by settings). */
        static Resolution passThrough(String url) {
            return new Resolution(Status.NOT_A_REDIRECT, url);
        }

        private Resolution(Status status, String targetUrl) {
            this.status = status;
            this.targetUrl = targetUrl == null ? "" : targetUrl;
        }

        Status getStatus() {
            return status;
        }

        String getTargetUrl() {
            return targetUrl;
        }
    }

    private SearchRedirectResolver() {
    }

    static Resolution resolve(String url) {
        String lower = url == null ? "" : url.toLowerCase(Locale.ROOT);
        if (lower.isEmpty() || !lower.startsWith("http")) {
            return new Resolution(Status.NOT_A_REDIRECT, url);
        }
        String host = com.aresstack.askai.browser.domain.PublicSuffixDomainKeyResolver.hostOf(lower);
        String path = pathOf(lower);
        if (host.endsWith("bing.com") && path.startsWith("/ck/")) {
            String encoded = queryParameter(url, "u");
            String target = decodeBingTarget(encoded);
            return target == null ? new Resolution(Status.UNRESOLVED, "")
                    : new Resolution(Status.RESOLVED, target);
        }
        if (host.endsWith("google.com") && (path.equals("/url") || path.startsWith("/url?")
                || path.startsWith("/url/"))) {
            String target = firstNonEmpty(queryParameter(url, "q"), queryParameter(url, "url"),
                    queryParameter(url, "u"));
            return target != null && target.startsWith("http")
                    ? new Resolution(Status.RESOLVED, target) : new Resolution(Status.UNRESOLVED, "");
        }
        if (host.endsWith("duckduckgo.com") && path.startsWith("/l/")) {
            String target = queryParameter(url, "uddg");
            return target != null && target.startsWith("http")
                    ? new Resolution(Status.RESOLVED, target) : new Resolution(Status.UNRESOLVED, "");
        }
        return new Resolution(Status.NOT_A_REDIRECT, url);
    }

    /** Bing encodes the target as {@code a1} + base64url; anything else is unresolved. */
    private static String decodeBingTarget(String encoded) {
        if (encoded == null || !encoded.startsWith("a1")) {
            return null;
        }
        try {
            String base64 = encoded.substring(2);
            byte[] decoded = Base64.getUrlDecoder().decode(padBase64(base64));
            String target = new String(decoded, StandardCharsets.UTF_8);
            return target.startsWith("http") ? target : null;
        } catch (RuntimeException notBase64) {
            return null;
        }
    }

    private static String padBase64(String value) {
        int remainder = value.length() % 4;
        if (remainder == 2) {
            return value + "==";
        }
        if (remainder == 3) {
            return value + "=";
        }
        return value;
    }

    /** URL-decoded value of one query parameter, or null. */
    static String queryParameter(String url, String name) {
        int question = url.indexOf('?');
        if (question < 0) {
            return null;
        }
        String query = url.substring(question + 1);
        int fragment = query.indexOf('#');
        if (fragment >= 0) {
            query = query.substring(0, fragment);
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(name)) {
                try {
                    return URLDecoder.decode(pair.substring(eq + 1), "UTF-8");
                } catch (UnsupportedEncodingException | IllegalArgumentException ex) {
                    return null; // an undecodable value is never guessed
                }
            }
        }
        return null;
    }

    private static String pathOf(String url) {
        int scheme = url.indexOf("://");
        if (scheme < 0) {
            return "";
        }
        String rest = url.substring(scheme + 3);
        int slash = rest.indexOf('/');
        return slash < 0 ? "/" : stripQuery(rest.substring(slash));
    }

    private static String stripQuery(String path) {
        int question = path.indexOf('?');
        return question < 0 ? path : path.substring(0, question);
    }

    private static String firstNonEmpty(String... values) {
        for (String value : values) {
            if (value != null && !value.isEmpty()) {
                return value;
            }
        }
        return null;
    }

}
