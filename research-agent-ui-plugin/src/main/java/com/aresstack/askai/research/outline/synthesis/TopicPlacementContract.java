package com.aresstack.askai.research.outline.synthesis;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 3b step 1b: the research topics the chapter plan did not use (because they were beyond the prompt's tranche
 * or the model left them out) are placed into the planned chapters, one tranche of topics per call. The model sees the
 * chapters (title, purpose, concept requirements, the topics they hold) and the tranche, and names for each topic the
 * chapter it serves, or 0 when it serves none. Topics the answer does not place stay unused; the quality check places
 * them by fit. Live run 5: 45 topics did not fit the chapter prompt and 14 more were left out; all 59 went to the one
 * chapter of their topic group.
 */
final class TopicPlacementContract {

    static final String SYSTEM = "You place further research topics into the chapters of a non-fiction book's table "
            + "of contents. Answer with the JSON object only.";

    static final String RULES = "Rules:\n"
            + "- Place each topic into the chapter whose subject it serves best, judged by its title and excerpts.\n"
            + "- Spread the topics by subject; do not put topics into a chapter only because it is the largest.\n"
            + "- Use chapter 0 for a topic that serves no chapter.\n";

    static final String ANSWER = "Answer: {\"placements\":[{\"topic\":\"T41\",\"chapter\":2}]}";

    private TopicPlacementContract() {
    }

    static String prompt(SynthesisBrief brief, OutlinePlan plan, List<String> tranche) {
        StringBuilder text = new StringBuilder(brief.getContext().header());
        text.append("Chapters:\n");
        int index = 0;
        for (PlannedChapter chapter : plan.getChapters()) {
            index++;
            text.append(index).append(' ').append(PlanJson.quote(chapter.getTitle()));
            if (!chapter.getPurpose().isEmpty()) {
                text.append(" - ").append(chapter.getPurpose());
            }
            for (String key : chapter.getConceptKeys()) {
                SynthesisBrief.RequirementEntry requirement = brief.requirement(key);
                if (requirement != null) {
                    text.append("; concept ").append(PlanJson.quote(requirement.getCoverage().getLabel()));
                }
            }
            text.append("\n   holds: ");
            for (int i = 0; i < chapter.getMaterialKeys().size(); i++) {
                text.append(i > 0 ? "; " : "").append(PlanJson.quote(titleOf(brief,
                        chapter.getMaterialKeys().get(i))));
            }
            text.append('\n');
        }
        text.append("Topics to place:\n");
        for (String key : tranche) {
            ChapterPlanContract.appendTopic(text, brief.topic(key), false);
        }
        text.append(RULES).append(ANSWER);
        return text.toString();
    }

    private static String titleOf(SynthesisBrief brief, String key) {
        SynthesisBrief.TopicEntry topic = brief.topic(key);
        if (topic != null) {
            return topic.getProfile().getTitle();
        }
        SynthesisBrief.SubtopicEntry subtopic = brief.subtopic(key);
        return subtopic == null ? key : subtopic.getSubtopic().getTitle();
    }

    static String schema(OutlinePlan plan, List<String> tranche) {
        return "{\"type\":\"object\",\"required\":[\"placements\"],\"properties\":{\"placements\":{\"type\":\"array\","
                + "\"items\":{\"type\":\"object\",\"required\":[\"topic\",\"chapter\"],\"properties\":{"
                + "\"topic\":" + PlanJson.keyItems(tranche) + ",\"chapter\":{\"type\":\"integer\",\"minimum\":0,"
                + "\"maximum\":" + plan.getChapters().size() + "}}}}}}";
    }

    /** The strict reader: topic key to chapter index (0-based); topics placed in chapter 0 are left out. */
    static Map<String, Integer> read(String answer, OutlinePlan plan, List<String> tranche)
            throws PlanAnswerException {
        JsonObject root = PlanJson.object(answer);
        JsonArray placements = PlanJson.array(root, "placements");
        if (placements == null) {
            throw new PlanAnswerException("\"placements\" must be an array");
        }
        List<String> errors = new ArrayList<String>();
        Map<String, Integer> chapterOf = new LinkedHashMap<String, Integer>();
        int index = 0;
        for (JsonElement element : placements) {
            index++;
            if (!element.isJsonObject()) {
                errors.add("placement " + index + " is not an object");
                continue;
            }
            JsonObject placement = element.getAsJsonObject();
            String topic = PlanJson.string(placement.get("topic"));
            if (!tranche.contains(topic)) {
                errors.add("placement " + index + " names " + topic + ", which is not a topic to place");
                continue;
            }
            int chapter;
            try {
                chapter = placement.get("chapter").getAsInt();
            } catch (RuntimeException notANumber) {
                errors.add("placement " + index + " has no chapter number");
                continue;
            }
            if (chapter < 0 || chapter > plan.getChapters().size()) {
                errors.add("placement " + index + " names chapter " + chapter + ", which does not exist");
                continue;
            }
            Integer previous = chapterOf.get(topic);
            if (previous != null && previous != chapter - 1) {
                errors.add(topic + " is placed twice");
                continue;
            }
            if (chapter > 0) {
                chapterOf.put(topic, chapter - 1);
            }
        }
        if (!errors.isEmpty()) {
            throw new PlanAnswerException(errors);
        }
        return chapterOf;
    }

    /** The plan with each placed topic added to its chapter's material. */
    static OutlinePlan apply(OutlinePlan plan, Map<String, Integer> chapterOf) {
        List<PlannedChapter> chapters = new ArrayList<PlannedChapter>();
        for (int i = 0; i < plan.getChapters().size(); i++) {
            PlannedChapter chapter = plan.getChapters().get(i);
            List<String> material = new ArrayList<String>(chapter.getMaterialKeys());
            for (Map.Entry<String, Integer> placed : chapterOf.entrySet()) {
                if (placed.getValue() == i && !material.contains(placed.getKey())) {
                    material.add(placed.getKey());
                }
            }
            chapters.add(new PlannedChapter(chapter.getTitle(), chapter.getPurpose(), material,
                    chapter.getConceptKeys(), chapter.getSections(), chapter.isUntitled()));
        }
        return plan.withChapters(chapters);
    }
}
