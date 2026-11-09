package com.aresstack.askai.browser.playwright;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The search browser's memory of recent answers: the same search (same provider, same request) within the time to
 * live is answered from here, so a query that several chats or workers ask is one engine request. Bounded, least
 * recently used entries leave first. Thread-safe.
 */
final class RecentSearches<K, V> {

    private final int capacity;
    private final long ttlMillis;
    private final SearchEngineThrottle.Clock clock;
    private final LinkedHashMap<K, Entry<V>> entries;

    private static final class Entry<V> {
        final V value;
        final long at;

        Entry(V value, long at) {
            this.value = value;
            this.at = at;
        }
    }

    RecentSearches(int capacity, long ttlMillis, SearchEngineThrottle.Clock clock) {
        this.capacity = Math.max(1, capacity);
        this.ttlMillis = Math.max(1L, ttlMillis);
        this.clock = clock == null ? SearchEngineThrottle.SYSTEM : clock;
        this.entries = new LinkedHashMap<K, Entry<V>>(16, 0.75f, true);
    }

    /** The remembered answer, or null when there is none or it is too old. */
    synchronized V get(K key) {
        Entry<V> e = entries.get(key);
        if (e == null) {
            return null;
        }
        if (clock.nowMillis() - e.at > ttlMillis) {
            entries.remove(key);
            return null;
        }
        return e.value;
    }

    synchronized void put(K key, V value) {
        if (key == null || value == null) {
            return;
        }
        entries.put(key, new Entry<V>(value, clock.nowMillis()));
        Iterator<Map.Entry<K, Entry<V>>> it = entries.entrySet().iterator();
        while (entries.size() > capacity && it.hasNext()) {
            it.next();
            it.remove();
        }
    }

    synchronized int size() {
        return entries.size();
    }
}
