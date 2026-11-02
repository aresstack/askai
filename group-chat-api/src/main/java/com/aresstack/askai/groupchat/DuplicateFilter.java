package com.aresstack.askai.groupchat;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Thread-safe bounded duplicate detector for message IDs.
 *
 * <p>Backed by an LRU-bounded {@link LinkedHashMap}; once the capacity is exceeded the oldest
 * entries are forgotten, which is acceptable because duplicates arrive close to the original in
 * practice (rebroadcasts, partition merges).</p>
 */
public final class DuplicateFilter {

    /** Default remembered-ID capacity. */
    private static final int DEFAULT_CAPACITY = 4096;

    private final Map<String, Boolean> seen;

    /** Create a filter remembering up to 4096 IDs. */
    public DuplicateFilter() {
        this(DEFAULT_CAPACITY);
    }

    /**
     * @param capacity maximum number of remembered IDs; must be positive
     */
    public DuplicateFilter(final int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.seen = new LinkedHashMap<String, Boolean>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                return size() > capacity;
            }
        };
    }

    /**
     * @return {@code true} only the first time {@code messageId} is seen; {@code false} for
     *         duplicates (and for {@code null} IDs)
     */
    public synchronized boolean firstTime(String messageId) {
        if (messageId == null) {
            return false;
        }
        return seen.put(messageId, Boolean.TRUE) == null;
    }
}
