package com.aresstack.askai.research.runtime.search.service;

/**
 * The project's register of page addresses already handed to someone: a URL the serial search loop visited, or one
 * the search service already queued for the team, is never queued again. Keys are normalised URLs
 * ({@link com.aresstack.askai.research.runtime.search.SearchUrlNormalizer}). Implementations are thread-safe.
 */
public interface UrlRegistry {

    /** Whether the normalised URL is already registered. */
    boolean isKnown(String normalizedUrl);

    /**
     * Register the normalised URL with its origin (e.g. {@code search-service}, {@code chat-browser}).
     *
     * @return {@code true} when the URL was new, {@code false} when it was already registered
     */
    boolean register(String normalizedUrl, String origin);
}
