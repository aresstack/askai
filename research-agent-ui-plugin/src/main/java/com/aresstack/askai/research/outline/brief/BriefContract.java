package com.aresstack.askai.research.outline.brief;

import com.aresstack.askai.research.outline.OutlineBrief;
import com.aresstack.askai.research.outline.OutlineChapter;
import com.aresstack.askai.research.outline.OutlineSection;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The brief contract of plan PR 24 (D11): one call per chapter addresses its sections by local index
 * {@code [1..k]} and returns purpose and three to five key points per section. The answer budget is 150 tokens per
 * section plus 100; a chapter with more than {@link #MAX_SECTIONS_PER_CALL} sections is briefed in two calls.
 */
final class BriefContract {

    static final int MAX_SECTIONS_PER_CALL = 6;
    static final int TOKENS_PER_SECTION = 150;
    static final int TOKENS_PER_CALL = 100;
    static final int MIN_KEY_POINTS = 3;
    static final int MAX_KEY_POINTS = 5;

    static final String SYSTEM = "You plan the sections of one book chapter. For each numbered section write its "
            + "purpose (one sentence) and three to five key points (short phrases). Use every section number "
            + "exactly once. Answer with the JSON object only.";

    private BriefContract() {
    }

    static int budget(int sections) {
        return TOKENS_PER_SECTION * sections + TOKENS_PER_CALL;
    }

    /** The section batches of one chapter: one batch, or two halves above {@link #MAX_SECTIONS_PER_CALL}. */
    static List<List<OutlineSection>> batches(List<OutlineSection> sections) {
        List<List<OutlineSection>> batches = new ArrayList<List<OutlineSection>>();
        if (sections.size() <= MAX_SECTIONS_PER_CALL) {
            batches.add(sections);
        } else {
            int half = (sections.size() + 1) / 2;
            batches.add(sections.subList(0, half));
            batches.add(sections.subList(half, sections.size()));
        }
        return batches;
    }

    static String prompt(BriefContext context, OutlineChapter chapter, List<OutlineSection> sections,
                         String comment) {
        StringBuilder text = new StringBuilder(context.header());
        text.append("Chapter: ").append(chapter.getTitle()).append('\n');
        if (!chapter.getBrief().getPurpose().isEmpty()) {
            text.append("Chapter purpose: ").append(chapter.getBrief().getPurpose()).append('\n');
        }
        if (comment != null && !comment.trim().isEmpty()) {
            text.append("Author's request: ").append(comment.trim()).append('\n');
        }
        text.append("Sections:\n");
        for (int i = 0; i < sections.size(); i++) {
            OutlineSection section = sections.get(i);
            text.append('[').append(i + 1).append("] ").append(section.getTitle());
            OutlineBrief brief = section.getBrief();
            if (!brief.getKeyPoints().isEmpty()) {
                text.append(" (material: ").append(String.join("; ", brief.getKeyPoints())).append(')');
            }
            text.append('\n');
        }
        text.append("Answer: {\"sections\":[{\"index\":1,\"purpose\":\"...\",\"keyPoints\":[\"...\"]}]}");
        return text.toString();
    }

    static String schema(int sections) {
        return "{\"type\":\"object\",\"required\":[\"sections\"],\"properties\":{\"sections\":{\"type\":\"array\","
                + "\"minItems\":" + sections + ",\"maxItems\":" + sections + ",\"items\":{\"type\":\"object\","
                + "\"required\":[\"index\",\"purpose\",\"keyPoints\"],\"properties\":{"
                + "\"index\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":" + sections + "},"
                + "\"purpose\":{\"type\":\"string\"},"
                + "\"keyPoints\":{\"type\":\"array\",\"minItems\":" + MIN_KEY_POINTS + ",\"maxItems\":"
                + MAX_KEY_POINTS + ",\"items\":{\"type\":\"string\"}}}}}}}";
    }

    /**
     * The strict reader: purpose and key points by local index (1-based); every index occurs exactly once.
     * The word target is the caller's.
     */
    static Map<Integer, OutlineBrief> read(String answer, int sections) throws BriefAnswerException {
        JsonObject root;
        try {
            JsonElement parsed = JsonParser.parseString(answer == null ? "" : answer.trim());
            if (!parsed.isJsonObject()) {
                throw new BriefAnswerException("the answer is not a JSON object");
            }
            root = parsed.getAsJsonObject();
        } catch (RuntimeException invalid) {
            throw new BriefAnswerException("the answer is not valid JSON");
        }
        JsonElement list = root.get("sections");
        if (list == null || !list.isJsonArray()) {
            throw new BriefAnswerException("\"sections\" must be an array");
        }
        List<String> errors = new ArrayList<String>();
        Map<Integer, OutlineBrief> briefs = new LinkedHashMap<Integer, OutlineBrief>();
        JsonArray array = list.getAsJsonArray();
        for (JsonElement element : array) {
            if (!element.isJsonObject()) {
                errors.add("every entry of \"sections\" must be an object");
                continue;
            }
            JsonObject entry = element.getAsJsonObject();
            Integer index = integer(entry.get("index"));
            if (index == null || index < 1 || index > sections) {
                errors.add("index must be a number from 1 to " + sections);
                continue;
            }
            if (briefs.containsKey(index)) {
                errors.add("index " + index + " occurs more than once");
                continue;
            }
            String purpose = string(entry.get("purpose"));
            if (purpose.isEmpty()) {
                errors.add("section " + index + " has no purpose");
            }
            List<String> points = new ArrayList<String>();
            JsonElement keyPoints = entry.get("keyPoints");
            if (keyPoints != null && keyPoints.isJsonArray()) {
                for (JsonElement point : keyPoints.getAsJsonArray()) {
                    String value = string(point);
                    if (!value.isEmpty() && !points.contains(value)) {
                        points.add(value);
                    }
                }
            }
            if (points.size() < MIN_KEY_POINTS || points.size() > MAX_KEY_POINTS) {
                errors.add("section " + index + " needs " + MIN_KEY_POINTS + " to " + MAX_KEY_POINTS
                        + " distinct key points, not " + points.size());
            }
            briefs.put(index, new OutlineBrief(purpose, points, 0));
        }
        for (int i = 1; i <= sections; i++) {
            if (!briefs.containsKey(i)) {
                errors.add("section " + i + " is missing");
            }
        }
        if (!errors.isEmpty()) {
            throw new BriefAnswerException(errors);
        }
        return briefs;
    }

    static Integer integer(JsonElement element) {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            return null;
        }
        double value = element.getAsDouble();
        return value == Math.rint(value) ? Integer.valueOf((int) value) : null;
    }

    static String string(JsonElement element) {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            return "";
        }
        return element.getAsString().trim();
    }
}
