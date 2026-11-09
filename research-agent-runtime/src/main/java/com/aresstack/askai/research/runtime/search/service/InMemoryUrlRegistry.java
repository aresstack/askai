package com.aresstack.askai.research.runtime.search.service;

import java.util.LinkedHashMap;
import java.util.Map;

/** A {@link UrlRegistry} held in memory only (tests, runs without a project folder). */
public final class InMemoryUrlRegistry implements UrlRegistry {

    private final Map<String, String> origins = new LinkedHashMap<String, String>();

    @Override
    public synchronized boolean isKnown(String normalizedUrl) {
        return origins.containsKey(normalizedUrl);
    }

    @Override
    public synchronized boolean register(String normalizedUrl, String origin) {
        if (normalizedUrl == null || normalizedUrl.isEmpty() || origins.containsKey(normalizedUrl)) {
            return false;
        }
        origins.put(normalizedUrl, origin == null ? "" : origin);
        return true;
    }

    public synchronized int size() {
        return origins.size();
    }
}
