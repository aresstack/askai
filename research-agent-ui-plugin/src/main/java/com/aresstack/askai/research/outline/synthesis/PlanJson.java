package com.aresstack.askai.research.outline.synthesis;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;

/** Phase 3b: the strict JSON reading shared by the plan contracts. */
final class PlanJson {

    /** A title is a short noun phrase: at most this many words ... */
    static final int MAX_TITLE_WORDS = 12;
    /** ... and characters. */
    static final int MAX_TITLE_CHARS = 120;

    private PlanJson() {
    }

    static JsonObject object(String answer) throws PlanAnswerException {
        String text = answer == null ? "" : answer.trim();
        if (text.startsWith("```")) {
            int start = text.indexOf('\n');
            int end = text.lastIndexOf("```");
            text = start >= 0 && end > start ? text.substring(start + 1, end).trim() : text;
        }
        try {
            JsonElement parsed = JsonParser.parseString(text);
            if (!parsed.isJsonObject()) {
                throw new PlanAnswerException("the answer is not a JSON object");
            }
            return parsed.getAsJsonObject();
        } catch (RuntimeException invalid) {
            throw new PlanAnswerException("the answer is not valid JSON");
        }
    }

    static String string(JsonElement element) {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            return "";
        }
        return element.getAsString().replaceAll("\\s+", " ").trim();
    }

    /** The distinct strings of an array (order kept); a missing field is an empty list. */
    static List<String> strings(JsonElement element) {
        List<String> values = new ArrayList<String>();
        if (element != null && element.isJsonArray()) {
            for (JsonElement item : element.getAsJsonArray()) {
                String value = string(item);
                if (!value.isEmpty() && !values.contains(value)) {
                    values.add(value);
                }
            }
        }
        return values;
    }

    static JsonArray array(JsonObject root, String field) {
        JsonElement element = root.get(field);
        return element != null && element.isJsonArray() ? element.getAsJsonArray() : null;
    }

    /** The title errors of one entry ("chapter 2", "section 1"); empty when the title is fine. */
    static List<String> titleErrors(String what, String title) {
        List<String> errors = new ArrayList<String>();
        if (title.isEmpty()) {
            errors.add(what + " has no title");
            return errors;
        }
        if (title.split(" ").length > MAX_TITLE_WORDS || title.length() > MAX_TITLE_CHARS) {
            errors.add(what + ": the title must be a short noun phrase of at most " + MAX_TITLE_WORDS + " words");
        }
        char last = title.charAt(title.length() - 1);
        if (last == '.' || last == '!' || last == ':' || last == ';') {
            errors.add(what + ": the title must not end with punctuation");
        }
        return errors;
    }

    /** {@code {"type":"string","enum":[...]}}, or a plain string type without keys. */
    static String keyItems(List<String> keys) {
        if (keys.isEmpty()) {
            return "{\"type\":\"string\"}";
        }
        StringBuilder text = new StringBuilder("{\"type\":\"string\",\"enum\":[");
        for (int i = 0; i < keys.size(); i++) {
            text.append(i > 0 ? "," : "").append('"').append(keys.get(i)).append('"');
        }
        return text.append("]}").toString();
    }

    static String quote(String value) {
        return "\"" + (value == null ? "" : value.replace("\"", "'")) + "\"";
    }
}
