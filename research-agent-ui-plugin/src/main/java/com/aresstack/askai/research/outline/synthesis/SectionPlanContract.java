package com.aresstack.askai.research.outline.synthesis;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Phase 3b step 2: the sections of one planned chapter. The model sees only this chapter's material (its topics with
 * their sub-topics, or the sub-topics the chapter names) and answers with sections, each naming the material keys it
 * draws on. One item of material belongs to one section; every item of the chapter is used, as itself or through its
 * sub-topics: an item the answer leaves out becomes an untitled section of its own. Sub-topics a section leaves
 * unnamed stay with their topic and are placed by code.
 */
final class SectionPlanContract {

    static final String SYSTEM = "You divide one chapter of a non-fiction book into sections, using the research "
            + "material the chapter owns. Each section names the material it draws on. Answer with the JSON object "
            + "only.";

    static final String RULES = "Rules:\n"
            + "- Use every item of material of this chapter, as itself or through its sub-topics.\n"
            + "- One item belongs to one section; do not name a topic and one of its sub-topics in different "
            + "sections.\n"
            + "- A sub-topic with much distinct material may be a section of its own; repeated material is no reason "
            + "for more sections.\n"
            + "- Titles are short noun phrases; the purpose is one sentence.\n";

    static final String ANSWER = "Answer: {\"sections\":[{\"title\":\"...\",\"purpose\":\"one sentence\","
            + "\"material\":[\"T1.1\"]}]}";

    private SectionPlanContract() {
    }

    /** The keys a section of {@code chapter} may name: its keys plus the sub-topics of its topic keys. */
    static List<String> allowedKeys(SynthesisBrief brief, PlannedChapter chapter) {
        Set<String> keys = new LinkedHashSet<String>();
        for (String key : chapter.getMaterialKeys()) {
            keys.add(key);
            SynthesisBrief.TopicEntry topic = brief.topic(key);
            if (topic != null) {
                for (SynthesisBrief.SubtopicEntry subtopic : topic.getSubtopics()) {
                    keys.add(subtopic.getKey());
                }
            }
        }
        return new ArrayList<String>(keys);
    }

    static String prompt(SynthesisBrief brief, PlannedChapter chapter) {
        StringBuilder text = new StringBuilder(brief.getContext().header());
        text.append("Chapter: ").append(chapter.getTitle()).append('\n');
        if (!chapter.getPurpose().isEmpty()) {
            text.append("Chapter purpose: ").append(chapter.getPurpose()).append('\n');
        }
        text.append("Material of this chapter:\n");
        for (String key : chapter.getMaterialKeys()) {
            SynthesisBrief.TopicEntry topic = brief.topic(key);
            if (topic != null) {
                ChapterPlanContract.appendTopic(text, topic, false);
                for (SynthesisBrief.SubtopicEntry subtopic : topic.getSubtopics()) {
                    appendSubtopic(text, subtopic);
                }
                continue;
            }
            SynthesisBrief.SubtopicEntry subtopic = brief.subtopic(key);
            if (subtopic != null) {
                appendSubtopic(text, subtopic);
            }
        }
        text.append(RULES).append(ANSWER);
        return text.toString();
    }

    private static void appendSubtopic(StringBuilder text, SynthesisBrief.SubtopicEntry subtopic) {
        text.append("   ").append(subtopic.getKey()).append(' ')
                .append(PlanJson.quote(subtopic.getSubtopic().getTitle())).append(" - ")
                .append(subtopic.getSubtopic().getDistinctPassageCount()).append(" distinct passages, ")
                .append(subtopic.getSubtopic().getSourceCount()).append(" sources\n");
        if (!subtopic.getExcerpt().isEmpty()) {
            text.append("      excerpt: ").append(PlanJson.quote(subtopic.getExcerpt())).append('\n');
        }
    }

    static String schema(SynthesisBrief brief, PlannedChapter chapter) {
        return "{\"type\":\"object\",\"required\":[\"sections\"],\"properties\":{\"sections\":{\"type\":\"array\","
                + "\"minItems\":1,\"items\":{\"type\":\"object\",\"required\":[\"title\",\"purpose\",\"material\"],"
                + "\"properties\":{\"title\":{\"type\":\"string\"},\"purpose\":{\"type\":\"string\"},"
                + "\"material\":{\"type\":\"array\",\"minItems\":1,\"items\":"
                + PlanJson.keyItems(allowedKeys(brief, chapter)) + "}}}}}}";
    }

    /** The strict reader. */
    static List<PlannedSection> read(String answer, SynthesisBrief brief, PlannedChapter chapter)
            throws PlanAnswerException {
        JsonObject root = PlanJson.object(answer);
        JsonArray sections = PlanJson.array(root, "sections");
        if (sections == null || sections.size() == 0) {
            throw new PlanAnswerException("\"sections\" must be a non-empty array");
        }
        Set<String> allowed = new LinkedHashSet<String>(allowedKeys(brief, chapter));
        List<String> errors = new ArrayList<String>();
        List<PlannedSection> planned = new ArrayList<PlannedSection>();
        Map<String, Integer> usedBy = new HashMap<String, Integer>();
        int index = 0;
        for (JsonElement element : sections) {
            index++;
            String what = "section " + index;
            if (!element.isJsonObject()) {
                errors.add(what + " is not an object");
                continue;
            }
            JsonObject section = element.getAsJsonObject();
            String title = PlanJson.string(section.get("title"));
            errors.addAll(PlanJson.titleErrors(what, title));
            String purpose = PlanJson.string(section.get("purpose"));
            if (purpose.isEmpty()) {
                errors.add(what + " has no purpose");
            }
            List<String> material = PlanJson.strings(section.get("material"));
            if (material.isEmpty()) {
                errors.add(what + " names no material");
            }
            for (String key : material) {
                if (!allowed.contains(key)) {
                    errors.add(what + " names " + key + ", which is not material of this chapter");
                    continue;
                }
                Integer previous = usedBy.put(key, index);
                if (previous != null && previous != index) {
                    errors.add(key + " is named by section " + previous + " and section " + index);
                }
            }
            planned.add(new PlannedSection(title, purpose, material, false));
        }
        // A topic and one of its sub-topics in different sections overlap.
        for (Map.Entry<String, Integer> use : usedBy.entrySet()) {
            SynthesisBrief.SubtopicEntry subtopic = brief.subtopic(use.getKey());
            if (subtopic == null) {
                continue;
            }
            Integer topicSection = usedBy.get(subtopic.getTopicKey());
            if (topicSection != null && !topicSection.equals(use.getValue())) {
                errors.add(subtopic.getTopicKey() + " (section " + topicSection + ") and its sub-topic "
                        + use.getKey() + " (section " + use.getValue() + ") overlap: name either the topic or its "
                        + "sub-topics");
            }
        }
        List<String> unused = new ArrayList<String>();
        for (String key : chapter.getMaterialKeys()) {
            if (usedBy.containsKey(key)) {
                continue;
            }
            boolean throughSubtopic = false;
            SynthesisBrief.TopicEntry topic = brief.topic(key);
            if (topic != null) {
                for (SynthesisBrief.SubtopicEntry subtopic : topic.getSubtopics()) {
                    throughSubtopic |= usedBy.containsKey(subtopic.getKey());
                }
            }
            if (!throughSubtopic) {
                unused.add(key);
            }
        }
        if (!errors.isEmpty()) {
            throw new PlanAnswerException(errors);
        }
        // Material the model left out becomes untitled sections of its own (the label call names them), as the
        // quality check places unused material; the model's sections stay. Live run 4: six of eight section answers
        // were refused for leaving out a 14-passage advertising topic, and code split the chapters by material.
        planned.addAll(FallbackOutlinePlan.sections(brief, new PlannedChapter(chapter.getTitle(), "", unused, null,
                null, true)));
        return planned;
    }
}
