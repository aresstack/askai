package com.aresstack.askai.research.runtime.search.service;

import java.util.List;

/**
 * The project's durable hand-off of page addresses between the search service and whoever visits the pages. A page
 * is first {@link #enqueue enqueued} (state PENDING, with everything needed to queue it again), and only
 * {@link #complete completed} once its visit has ended for good. "Known" therefore never means "processed": after a
 * crash every PENDING page comes back through {@link #pending()} and is queued again, and a failed visit simply leaves
 * the page PENDING for the next attempt. Keys are normalised URLs (engine wrappers keep their raw address).
 * Implementations are thread-safe; a write that cannot be made durable fails and leaves the registry unchanged.
 */
public interface UrlRegistry {

    /** Whether the address is registered at all (PENDING or COMPLETED). */
    boolean isKnown(String url);

    /** Whether the address is registered and its visit has ended. */
    boolean isCompleted(String url);

    /**
     * Register the page as PENDING.
     *
     * @return {@code true} when it was new, {@code false} when it was already registered (nothing written)
     * @throws UrlRegistryException when the entry could not be made durable (the registry is unchanged)
     */
    boolean enqueue(PageCandidate page, String origin) throws UrlRegistryException;

    /**
     * Mark the page's visit as ended for good (read, rejected, duplicate, permanently unreadable, resolved to another
     * address). Completing an unknown address registers it as COMPLETED (e.g. a page the chat browser visited).
     */
    void complete(String url, String outcome) throws UrlRegistryException;

    /** Every PENDING page in registration order: what has to go back on the queue after a restart. */
    List<PageCandidate> pending();
}
