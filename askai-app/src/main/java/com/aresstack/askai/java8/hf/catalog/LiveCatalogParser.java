package com.aresstack.askai.java8.hf.catalog;

import com.aresstack.askai.java8.json.SimpleJson;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Parses HuggingFace's {@code /api/models-tags-by-type} JSON into {@link FilterCatalogs}. Every value
 * from the live source is kept (no curation/truncation); only the local <em>grouping</em> and, where
 * useful, display-name augmentation is added — never removing a value.
 *
 * <p>Task categories come straight from the live {@code subType} field on each {@code pipeline_tag}
 * entry (nlp/audio/multimodal/cv/rl/tabular/other), mapped to a display category name locally. The
 * "other" group is arranged into subgroups by a small local map, with any unmapped id falling into an
 * "Other" bucket so nothing is hidden.</p>
 */
public final class LiveCatalogParser {

    private LiveCatalogParser() {
    }

    /** subType code (live) → task category display name (local enrichment). */
    private static Map<String, String> taskCategories() {
        Map<String, String> map = new LinkedHashMap<String, String>();
        map.put("multimodal", "Multimodal");
        map.put("cv", "Computer Vision");
        map.put("nlp", "Natural Language Processing");
        map.put("audio", "Audio");
        map.put("tabular", "Tabular");
        map.put("rl", "Reinforcement Learning");
        map.put("other", "Other");
        return map;
    }

    /** "other" tag id → subgroup (local enrichment); unmapped ids fall into "Other". */
    private static Map<String, String> otherSubgroups() {
        Map<String, String> map = new LinkedHashMap<String, String>();
        map.put("4-bit", "Quantization");
        map.put("8-bit", "Quantization");
        map.put("moe", "Model type");
        map.put("merge", "Model type");
        map.put("custom_code", "Model type");
        map.put("text-generation-inference", "Inference");
        map.put("text-embeddings-inference", "Inference");
        map.put("eval-results", "Metadata");
        map.put("model-index", "Metadata");
        map.put("co2_eq_emissions", "Metadata");
        return map;
    }

    /**
     * @param json the raw response body of {@code /api/models-tags-by-type}
     * @return the parsed catalogs
     * @throws IllegalArgumentException when the JSON is not the expected object shape
     */
    public static FilterCatalogs parse(String json) {
        Object parsed = SimpleJson.parse(json);
        if (!(parsed instanceof Map)) {
            throw new IllegalArgumentException("models-tags-by-type: expected a JSON object");
        }
        Map root = (Map) parsed;
        Map<String, String> categories = taskCategories();
        Map<String, String> subgroups = otherSubgroups();

        List<CatalogEntry> tasks = new ArrayList<CatalogEntry>();
        for (Map entry : arrayOf(root, "pipeline_tag")) {
            String id = string(entry, "id");
            if (id.length() == 0) {
                continue;
            }
            String subType = string(entry, "subType").toLowerCase(Locale.ROOT);
            String category = categories.get(subType);
            tasks.add(new CatalogEntry(id, string(entry, "label"), category == null ? "Other" : category));
        }

        List<CatalogEntry> other = new ArrayList<CatalogEntry>();
        for (Map entry : arrayOf(root, "other")) {
            String id = string(entry, "id");
            if (id.length() == 0) {
                continue;
            }
            String subgroup = subgroups.get(id);
            other.add(new CatalogEntry(id, string(entry, "label"), subgroup == null ? "Other" : subgroup));
        }

        return new FilterCatalogs(
                tasks,
                flat(root, "library"),
                flat(root, "language"),
                flat(root, "license"),
                other);
    }

    private static List<CatalogEntry> flat(Map root, String key) {
        List<CatalogEntry> entries = new ArrayList<CatalogEntry>();
        for (Map entry : arrayOf(root, key)) {
            String id = string(entry, "id");
            if (id.length() > 0) {
                entries.add(new CatalogEntry(id, string(entry, "label"), ""));
            }
        }
        return entries;
    }

    @SuppressWarnings("unchecked")
    private static List<Map> arrayOf(Map root, String key) {
        Object value = root.get(key);
        List<Map> result = new ArrayList<Map>();
        if (value instanceof List) {
            List values = (List) value;
            for (int i = 0; i < values.size(); i++) {
                Object element = values.get(i);
                if (element instanceof Map) {
                    result.add((Map) element);
                }
            }
        }
        return result;
    }

    private static String string(Map map, String key) {
        Object value = map.get(key);
        return value == null ? "" : String.valueOf(value);
    }
}
