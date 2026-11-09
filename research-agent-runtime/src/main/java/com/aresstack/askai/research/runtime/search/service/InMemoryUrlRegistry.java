package com.aresstack.askai.research.runtime.search.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A {@link UrlRegistry} held in memory only (tests, runs without a project folder). */
public final class InMemoryUrlRegistry implements UrlRegistry {

    /** null value = COMPLETED, otherwise the PENDING page. */
    private final Map<String, PageCandidate> entries = new LinkedHashMap<String, PageCandidate>();

    @Override
    public synchronized boolean isKnown(String url) {
        return entries.containsKey(url);
    }

    @Override
    public synchronized boolean isCompleted(String url) {
        return entries.containsKey(url) && entries.get(url) == null;
    }

    @Override
    public synchronized boolean enqueue(PageCandidate page, String origin) {
        if (entries.containsKey(page.getUrl())) {
            return false;
        }
        entries.put(page.getUrl(), page);
        return true;
    }

    @Override
    public synchronized void complete(String url, String outcome) {
        if (url != null && !url.isEmpty()) {
            entries.remove(url);
            entries.put(url, null);
        }
    }

    @Override
    public synchronized List<PageCandidate> pending() {
        List<PageCandidate> open = new ArrayList<PageCandidate>();
        for (PageCandidate page : entries.values()) {
            if (page != null) {
                open.add(page);
            }
        }
        return open;
    }
}
