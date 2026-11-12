package com.aresstack.askai.research.outline.synthesis;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Phase 3b, between the chapter plan and the sections: a research topic that several chapters name as a whole is
 * divided among them by its sub-topics. Concept parts already belong to the chapter of their requirement
 * ({@link OutlineQualityCheck#owners}); the model decides for the other sub-topics which of these chapters each one
 * fits, the code applies the answer.
 * <p>
 * Live outline acceptance 2026-11-12 (bge-m3): four independent areas formed one topic with five sub-topics, the
 * model planned four chapters that all named it, and first-come ownership kept everything in the first chapter and
 * dropped the other three as repeated material, also after two revision rounds.
 */
final class SharedTopicContract {

    static final String SYSTEM = "You divide one research topic among the chapters of a book that all name it. Each "
            + "sub-topic goes to exactly one chapter. Answer with the JSON object only.";

    static final String RULES = "Rules:\n"
            + "- Give every sub-topic to exactly one of these chapters: the one whose title and purpose it fits best.\n"
            + "- A chapter may get several sub-topics or none.\n"
            + "- An introduction, overview or conclusion chapter gets a sub-topic only when no other chapter fits "
            + "it.\n";

    private SharedTopicContract() {
    }

    /** One topic named as a whole by several chapters, with the sub-topics that are still to be divided. */
    static final class Shared {
        final String topicKey;
        final List<Integer> chapters;
        final List<String> open;

        Shared(String topicKey, List<Integer> chapters, List<String> open) {
            this.topicKey = topicKey;
            this.chapters = chapters;
            this.open = open;
        }
    }

    /**
     * The topics that two or more chapters name as a whole and that have sub-topics no concept requirement places
     * (0-based chapter indices, in plan order).
     */
    static List<Shared> shared(OutlinePlan plan, SynthesisBrief brief) {
        Map<String, List<Integer>> namedBy = new LinkedHashMap<String, List<Integer>>();
        for (int i = 0; i < plan.getChapters().size(); i++) {
            for (String key : plan.getChapters().get(i).getMaterialKeys()) {
                SynthesisBrief.TopicEntry topic = brief.topic(key);
                if (topic == null || topic.getSubtopics().size() < 2) {
                    continue;
                }
                List<Integer> chapters = namedBy.get(key);
                if (chapters == null) {
                    chapters = new ArrayList<Integer>();
                    namedBy.put(key, chapters);
                }
                if (!chapters.contains(i)) {
                    chapters.add(i);
                }
            }
        }
        Map<String, Integer> placed = OutlineQualityCheck.conceptOwners(plan, brief);
        List<Shared> result = new ArrayList<Shared>();
        for (Map.Entry<String, List<Integer>> entry : namedBy.entrySet()) {
            if (entry.getValue().size() < 2) {
                continue;
            }
            List<String> open = new ArrayList<String>();
            for (SynthesisBrief.SubtopicEntry subtopic : brief.topic(entry.getKey()).getSubtopics()) {
                if (!placed.containsKey(subtopic.getKey())) {
                    open.add(subtopic.getKey());
                }
            }
            if (!open.isEmpty()) {
                result.add(new Shared(entry.getKey(), entry.getValue(), open));
            }
        }
        return result;
    }

    static String prompt(SynthesisBrief brief, OutlinePlan plan, Shared shared) {
        SynthesisBrief.TopicEntry topic = brief.topic(shared.topicKey);
        StringBuilder text = new StringBuilder(brief.getContext().header());
        text.append("These chapters all name the whole research topic ").append(shared.topicKey).append(' ')
                .append(PlanJson.quote(topic.getProfile().getTitle())).append(":\n");
        for (int index : shared.chapters) {
            PlannedChapter chapter = plan.getChapters().get(index);
            text.append(index + 1).append(' ').append(PlanJson.quote(chapter.getTitle()));
            if (!chapter.getPurpose().isEmpty()) {
                text.append(" - ").append(chapter.getPurpose());
            }
            text.append('\n');
        }
        text.append("Sub-topics to divide:\n");
        for (String key : shared.open) {
            SynthesisBrief.SubtopicEntry subtopic = brief.subtopic(key);
            text.append(key).append(' ').append(PlanJson.quote(subtopic.getSubtopic().getTitle())).append(" (")
                    .append(subtopic.getSubtopic().getDistinctPassageCount()).append(" distinct)\n");
            if (!subtopic.getExcerpt().isEmpty()) {
                text.append("   excerpt: ").append(PlanJson.quote(subtopic.getExcerpt())).append('\n');
            }
        }
        text.append(RULES).append("Answer: {");
        for (int i = 0; i < shared.open.size(); i++) {
            text.append(i > 0 ? "," : "").append('"').append(shared.open.get(i)).append("\":")
                    .append(shared.chapters.get(i % shared.chapters.size()) + 1);
        }
        return text.append("} (chapter numbers)").toString();
    }

    static String schema(Shared shared) {
        StringBuilder numbers = new StringBuilder();
        for (int i = 0; i < shared.chapters.size(); i++) {
            numbers.append(i > 0 ? "," : "").append(shared.chapters.get(i) + 1);
        }
        StringBuilder required = new StringBuilder();
        StringBuilder properties = new StringBuilder();
        for (int i = 0; i < shared.open.size(); i++) {
            String key = shared.open.get(i);
            required.append(i > 0 ? "," : "").append('"').append(key).append('"');
            properties.append(i > 0 ? "," : "").append('"').append(key).append("\":{\"type\":\"integer\",\"enum\":[")
                    .append(numbers).append("]}");
        }
        return "{\"type\":\"object\",\"required\":[" + required + "],\"properties\":{" + properties + "}}";
    }

    /** The answer as sub-topic key to 0-based chapter index; every open sub-topic must go to one named chapter. */
    static Map<String, Integer> read(String answer, Shared shared) throws PlanAnswerException {
        JsonObject root = PlanJson.object(answer);
        List<String> errors = new ArrayList<String>();
        Map<String, Integer> chapterOf = new LinkedHashMap<String, Integer>();
        for (String key : shared.open) {
            JsonElement value = root.get(key);
            int number;
            try {
                number = value == null || value.isJsonNull() ? 0 : value.getAsInt();
            } catch (RuntimeException notANumber) {
                number = 0;
            }
            if (number == 0) {
                errors.add(key + " has no chapter number");
            } else if (!shared.chapters.contains(number - 1)) {
                errors.add(key + " names chapter " + number + ", which does not name " + shared.topicKey);
            } else {
                chapterOf.put(key, number - 1);
            }
        }
        if (!errors.isEmpty()) {
            throw new PlanAnswerException(errors);
        }
        return chapterOf;
    }

    /**
     * The plan with the shared topic replaced, in each chapter naming it, by the sub-topics that chapter keeps: its
     * concept parts and the sub-topics the answer gives it. A chapter left with no material is dropped (noted).
     */
    static OutlinePlan apply(OutlinePlan plan, SynthesisBrief brief, Shared shared, Map<String, Integer> chapterOf,
                             List<String> notes) {
        Map<String, Integer> placed = OutlineQualityCheck.conceptOwners(plan, brief);
        List<PlannedChapter> chapters = new ArrayList<PlannedChapter>();
        for (int i = 0; i < plan.getChapters().size(); i++) {
            PlannedChapter chapter = plan.getChapters().get(i);
            if (!shared.chapters.contains(i)) {
                chapters.add(chapter);
                continue;
            }
            Set<String> material = new LinkedHashSet<String>();
            for (String key : chapter.getMaterialKeys()) {
                if (!key.equals(shared.topicKey)) {
                    material.add(key);
                    continue;
                }
                for (SynthesisBrief.SubtopicEntry subtopic : brief.topic(shared.topicKey).getSubtopics()) {
                    Integer owner = placed.containsKey(subtopic.getKey()) ? placed.get(subtopic.getKey())
                            : chapterOf.get(subtopic.getKey());
                    if (owner != null && owner == i) {
                        material.add(subtopic.getKey());
                    }
                }
            }
            if (material.isEmpty()) {
                notes.add("\"" + chapter.getTitle() + "\" got no part of " + shared.topicKey + " and had no other "
                        + "material; it was dropped.");
                continue;
            }
            chapters.add(new PlannedChapter(chapter.getTitle(), chapter.getPurpose(),
                    new ArrayList<String>(material), chapter.getConceptKeys(), chapter.getSections(),
                    chapter.isUntitled()));
        }
        return plan.withChapters(chapters);
    }
}
