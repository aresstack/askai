package com.aresstack.askai.research.outline.synthesis;

import com.aresstack.askai.research.outline.OutlineChapter;
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
            + "- Titles are short noun phrases; the purpose is one sentence.\n"
            + "- A section title names what the section adds to the chapter; it never repeats the chapter title.\n";

    static final String ANSWER = "Answer: {\"sections\":[{\"title\":\"...\",\"purpose\":\"one sentence\","
            + "\"material\":[\"T1.1\"]}]}";

    private SectionPlanContract() {
    }

    /**
     * Overlaps are resolved by code instead of refusing the answer (live run 5: the section plan of the largest chapter
     * was refused twice for "T5 (section 2) and its sub-topic T5.1 (section 1) overlap", and code split the chapter
     * into 63 sections): an item named by several sections stays with the first; a topic whose sub-topics other
     * sections name gives that section only its sub-topics no other section names. Sections left empty are dropped
     * by the caller.
     */
    static void resolveOverlaps(List<List<String>> materials, SynthesisBrief brief) {
        Set<String> seen = new LinkedHashSet<String>();
        for (int i = 0; i < materials.size(); i++) {
            List<String> kept = new ArrayList<String>();
            for (String key : materials.get(i)) {
                if (seen.add(key)) {
                    kept.add(key);
                }
            }
            materials.set(i, kept);
        }
        for (int i = 0; i < materials.size(); i++) {
            List<String> resolved = new ArrayList<String>();
            for (String key : materials.get(i)) {
                SynthesisBrief.TopicEntry topic = brief.topic(key);
                if (topic == null || topic.getSubtopics().isEmpty()) {
                    resolved.add(key);
                    continue;
                }
                List<String> free = new ArrayList<String>();
                boolean claimedElsewhere = false;
                for (SynthesisBrief.SubtopicEntry subtopic : topic.getSubtopics()) {
                    boolean elsewhere = false;
                    for (int j = 0; j < materials.size(); j++) {
                        elsewhere |= j != i && materials.get(j).contains(subtopic.getKey());
                    }
                    claimedElsewhere |= elsewhere;
                    if (!elsewhere && !materials.get(i).contains(subtopic.getKey())) {
                        free.add(subtopic.getKey());
                    }
                }
                if (claimedElsewhere) {
                    resolved.addAll(free);
                } else {
                    resolved.add(key);
                }
            }
            // A topic and its own sub-topic in one section: the topic stands for both.
            List<String> clean = new ArrayList<String>();
            for (String key : resolved) {
                SynthesisBrief.SubtopicEntry subtopic = brief.subtopic(key);
                if (subtopic != null && resolved.contains(subtopic.getTopicKey())) {
                    continue;
                }
                if (!clean.contains(key)) {
                    clean.add(key);
                }
            }
            materials.set(i, clean);
        }
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
    static List<PlannedSection> read(String answer, SynthesisBrief brief, PlannedChapter chapter, int maxSections)
            throws PlanAnswerException {
        JsonObject root = PlanJson.object(answer);
        JsonArray sections = PlanJson.array(root, "sections");
        if (sections == null || sections.size() == 0) {
            throw new PlanAnswerException("\"sections\" must be a non-empty array");
        }
        Set<String> allowed = new LinkedHashSet<String>(allowedKeys(brief, chapter));
        List<String> errors = new ArrayList<String>();
        List<String> titles = new ArrayList<String>();
        List<String> purposes = new ArrayList<String>();
        List<List<String>> materials = new ArrayList<List<String>>();
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
            if (OutlineChapter.repeatsChapterTitle(title, chapter.getTitle())) {
                errors.add(what + " repeats the chapter title; name what the section adds to the chapter");
            }
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
                }
            }
            titles.add(title);
            purposes.add(purpose);
            materials.add(material);
        }
        if (!errors.isEmpty()) {
            throw new PlanAnswerException(errors);
        }
        resolveOverlaps(materials, brief);
        List<PlannedSection> planned = new ArrayList<PlannedSection>();
        Map<String, Integer> usedBy = new HashMap<String, Integer>();
        for (int i = 0; i < materials.size(); i++) {
            if (materials.get(i).isEmpty()) {
                continue;
            }
            for (String key : materials.get(i)) {
                usedBy.put(key, i);
            }
            planned.add(new PlannedSection(titles.get(i), purposes.get(i), materials.get(i), false));
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
        // At most the chapter's section limit in all (the leftovers are packed; the model's sections stay).
        planned.addAll(FallbackOutlinePlan.sections(brief, new PlannedChapter(chapter.getTitle(), "", unused, null,
                null, true), Math.max(1, maxSections - planned.size())));
        return planned;
    }
}
