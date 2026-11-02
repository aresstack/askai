package com.aresstack.askai.hf.catalog;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A loaded {@link FilterCatalogs} together with where it came from ({@link CatalogOrigin}), the
 * per-group loaded counts (for the UI's "source + count" display), and an optional message (e.g. the
 * live error that forced a downgrade to cache/fallback).
 */
public final class CatalogBundle {

    private final FilterCatalogs catalogs;
    private final CatalogOrigin origin;
    private final String message;

    public CatalogBundle(FilterCatalogs catalogs, CatalogOrigin origin, String message) {
        this.catalogs = catalogs;
        this.origin = origin;
        this.message = message == null ? "" : message;
    }

    public FilterCatalogs getCatalogs() {
        return catalogs;
    }

    public CatalogOrigin getOrigin() {
        return origin;
    }

    public String getMessage() {
        return message;
    }

    /** @return per-group loaded counts, in display order (Tasks, Libraries, Languages, Licenses, Other, Apps). */
    public Map<String, Integer> getCounts() {
        Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
        counts.put("Tasks", catalogs.getTasks().size());
        counts.put("Libraries", catalogs.getLibraries().size());
        counts.put("Languages", catalogs.getLanguages().size());
        counts.put("Licenses", catalogs.getLicenses().size());
        counts.put("Other", catalogs.getOther().size());
        counts.put("Apps", catalogs.getApps().size());
        return counts;
    }

    /** @return a one-line summary like "Live · Tasks 52 · Libraries 53 · …". */
    public String describe() {
        StringBuilder builder = new StringBuilder(origin.getDisplayName());
        for (Map.Entry<String, Integer> entry : getCounts().entrySet()) {
            builder.append(" · ").append(entry.getKey()).append(' ').append(entry.getValue());
        }
        return builder.toString();
    }
}
