package com.aresstack.askai.java8.hf.catalog;

import com.aresstack.askai.java8.json.SimpleJson;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loads the "Apps" facet (local-app compatibility: Ollama, llama.cpp, vLLM, LM Studio, …) from the
 * real HuggingFace source. Unlike the other filter groups, apps are <em>not</em> part of the
 * {@code /api/models-tags-by-type} endpoint; the app list is server-rendered into the
 * {@code https://huggingface.co/models} page as inline JSON objects of the shape
 * {@code {"id":"ollama","label":"Ollama","type":"apps"}}. This parser extracts those, and also
 * round-trips a compact {@code [{"id","label"}]} JSON array used for the on-disk cache.
 *
 * <p>Only the <em>id</em> is sent to the API (as {@code apps=<id>}); the label is display-only. HTML
 * scraping is inherently more brittle than a JSON API, so callers keep a bundled/cached fallback.</p>
 */
public final class LiveAppCatalogParser {

    /** Matches an inline app entry in the models-page HTML (order of keys is fixed by the server). */
    private static final Pattern APP_ENTRY = Pattern.compile(
            "\\{\"id\":\"([^\"]+)\",\"label\":\"([^\"]+)\",\"type\":\"apps\"\\}");

    private LiveAppCatalogParser() {
    }

    /**
     * @param html the raw body of {@code https://huggingface.co/models}
     * @return the apps in first-seen order, de-duplicated by id; empty when none were found (e.g. the
     *         page layout changed) so the caller can fall back rather than treat it as a valid result
     */
    public static List<CatalogEntry> fromModelsPage(String html) {
        List<CatalogEntry> entries = new ArrayList<CatalogEntry>();
        if (html == null || html.length() == 0) {
            return entries;
        }
        Set<String> seen = new LinkedHashSet<String>();
        Matcher matcher = APP_ENTRY.matcher(html);
        while (matcher.find()) {
            String id = matcher.group(1);
            if (id.length() > 0 && seen.add(id)) {
                entries.add(new CatalogEntry(id, matcher.group(2), ""));
            }
        }
        return entries;
    }

    /** Parses the compact {@code [{"id","label"}]} cache array back into catalog entries. */
    @SuppressWarnings("unchecked")
    public static List<CatalogEntry> fromJsonArray(String json) {
        List<CatalogEntry> entries = new ArrayList<CatalogEntry>();
        if (json == null || json.trim().length() == 0) {
            return entries;
        }
        Object parsed = SimpleJson.parse(json);
        if (!(parsed instanceof List)) {
            return entries;
        }
        List<Object> values = (List<Object>) parsed;
        for (int i = 0; i < values.size(); i++) {
            Object value = values.get(i);
            if (value instanceof Map) {
                Map map = (Map) value;
                String id = string(map, "id");
                if (id.length() > 0) {
                    entries.add(new CatalogEntry(id, string(map, "label"), ""));
                }
            }
        }
        return entries;
    }

    /** Serializes apps to the compact {@code [{"id","label"}]} cache array (id/label JSON-escaped). */
    public static String toJsonArray(List<CatalogEntry> apps) {
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < apps.size(); i++) {
            CatalogEntry entry = apps.get(i);
            if (i > 0) {
                builder.append(',');
            }
            builder.append("{\"id\":\"").append(escape(entry.getId()))
                    .append("\",\"label\":\"").append(escape(entry.getDisplayName())).append("\"}");
        }
        return builder.append(']').toString();
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String string(Map map, String key) {
        Object value = map.get(key);
        return value == null ? "" : String.valueOf(value);
    }
}
