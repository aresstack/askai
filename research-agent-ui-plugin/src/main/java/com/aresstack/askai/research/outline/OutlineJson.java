package com.aresstack.askai.research.outline;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;

/**
 * The outline's file format: {@code {"schema":1,"chapters":[…]}}, the ids inside the records. Order comes from
 * the array position; the stored {@code order} field mirrors it.
 */
public final class OutlineJson {

    public static final int SCHEMA = 1;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private OutlineJson() {
    }

    public static String write(Outline outline) {
        JsonObject root = new JsonObject();
        root.addProperty("schema", SCHEMA);
        JsonArray chapters = new JsonArray();
        for (OutlineChapter chapter : outline.getChapters()) {
            JsonObject json = entry(chapter);
            JsonArray sections = new JsonArray();
            for (OutlineSection section : chapter.getSections()) {
                sections.add(entry(section));
            }
            json.add("sections", sections);
            chapters.add(json);
        }
        root.add("chapters", chapters);
        return GSON.toJson(root) + "\n";
    }

    /**
     * @throws IllegalArgumentException when the text is not an outline of this schema
     */
    public static Outline read(String text) {
        JsonObject root;
        try {
            root = JsonParser.parseString(text).getAsJsonObject();
        } catch (RuntimeException broken) {
            throw new IllegalArgumentException("The outline is not valid JSON: " + broken.getMessage(), broken);
        }
        if (!root.has("schema") || root.get("schema").getAsInt() != SCHEMA || !root.has("chapters")
                || !root.get("chapters").isJsonArray()) {
            throw new IllegalArgumentException("The outline has an unknown layout.");
        }
        List<OutlineChapter> chapters = new ArrayList<OutlineChapter>();
        try {
            for (JsonElement element : root.getAsJsonArray("chapters")) {
                JsonObject json = element.getAsJsonObject();
                List<OutlineSection> sections = new ArrayList<OutlineSection>();
                JsonElement array = json.get("sections");
                if (array != null && array.isJsonArray()) {
                    for (JsonElement sectionElement : array.getAsJsonArray()) {
                        JsonObject section = sectionElement.getAsJsonObject();
                        sections.add(new OutlineSection(string(section, "id"), string(section, "parent"),
                                integer(section, "order"), string(section, "title"), brief(section),
                                provenance(section), bool(section, "userEdited"), string(section, "stale")));
                    }
                }
                chapters.add(new OutlineChapter(string(json, "id"), integer(json, "order"), string(json, "title"),
                        brief(json), provenance(json), bool(json, "userEdited"), string(json, "stale"), sections));
            }
        } catch (IllegalArgumentException invalid) {
            throw invalid;
        } catch (RuntimeException broken) {
            throw new IllegalArgumentException("The outline is damaged: " + broken.getMessage(), broken);
        }
        return new Outline(chapters);
    }

    private static JsonObject entry(OutlineEntry entry) {
        JsonObject json = new JsonObject();
        json.addProperty("id", entry.getId());
        if (!entry.getParentId().isEmpty()) {
            json.addProperty("parent", entry.getParentId());
        }
        json.addProperty("order", entry.getOrder());
        json.addProperty("title", entry.getTitle());
        JsonObject brief = new JsonObject();
        brief.addProperty("purpose", entry.getBrief().getPurpose());
        brief.add("keyPoints", strings(entry.getBrief().getKeyPoints()));
        brief.addProperty("wordTarget", entry.getBrief().getWordTarget());
        json.add("brief", brief);
        JsonObject provenance = new JsonObject();
        provenance.addProperty("conceptNode", entry.getProvenance().getConceptNodeId());
        provenance.add("clusters", strings(entry.getProvenance().getClusterIds()));
        provenance.add("passages", strings(entry.getProvenance().getPassageIds()));
        json.add("provenance", provenance);
        json.addProperty("userEdited", entry.isUserEdited());
        json.addProperty("stale", entry.getStaleReason());
        return json;
    }

    private static JsonArray strings(List<String> values) {
        JsonArray array = new JsonArray();
        for (String value : values) {
            array.add(value);
        }
        return array;
    }

    private static OutlineBrief brief(JsonObject json) {
        JsonElement element = json.get("brief");
        if (element == null || !element.isJsonObject()) {
            return OutlineBrief.EMPTY;
        }
        JsonObject brief = element.getAsJsonObject();
        return new OutlineBrief(string(brief, "purpose"), list(brief, "keyPoints"), integer(brief, "wordTarget"));
    }

    private static OutlineProvenance provenance(JsonObject json) {
        JsonElement element = json.get("provenance");
        if (element == null || !element.isJsonObject()) {
            return OutlineProvenance.NONE;
        }
        JsonObject provenance = element.getAsJsonObject();
        return new OutlineProvenance(string(provenance, "conceptNode"), list(provenance, "clusters"),
                list(provenance, "passages"));
    }

    private static List<String> list(JsonObject json, String key) {
        List<String> values = new ArrayList<String>();
        JsonElement array = json.get(key);
        if (array != null && array.isJsonArray()) {
            for (JsonElement value : array.getAsJsonArray()) {
                values.add(value.getAsString());
            }
        }
        return values;
    }

    private static String string(JsonObject json, String key) {
        JsonElement value = json.get(key);
        return value == null || value.isJsonNull() ? "" : value.getAsString();
    }

    private static int integer(JsonObject json, String key) {
        JsonElement value = json.get(key);
        return value == null || value.isJsonNull() ? 0 : value.getAsInt();
    }

    private static boolean bool(JsonObject json, String key) {
        JsonElement value = json.get(key);
        return value != null && !value.isJsonNull() && value.getAsBoolean();
    }
}
