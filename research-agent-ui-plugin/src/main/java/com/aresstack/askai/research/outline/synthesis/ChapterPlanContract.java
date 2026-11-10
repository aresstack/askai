package com.aresstack.askai.research.outline.synthesis;

import com.aresstack.askai.research.knowledge.topic.ConceptCoverage;
import com.aresstack.askai.research.knowledge.topic.TopicProfile;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Phase 3b step 1 (and step 4): the chapter hierarchy. The model gets the compact brief (concept requirements with
 * their evidence, research topics with material, diversity, sources, group and concept alignment, one or two short
 * excerpts) and answers with the chapters in reading order, each naming the material keys and concept requirement
 * keys it covers, plus the requirements it lists as gaps. Keys are constrained by the schema; the reader checks
 * them again.
 */
final class ChapterPlanContract {

    static final String SYSTEM = "You design the table of contents of a non-fiction book from the research that was "
            + "done for it. The concept says what the author wants to examine; the research topics show what the "
            + "sources actually contain and how much distinct material there is. Build a new chapter hierarchy from "
            + "both: do not simply copy the concept, and do not simply list the topics. Answer with the JSON object "
            + "only.";

    static final String RULES = "Rules:\n"
            + "- Every research topic is used by a chapter. A topic's sub-topics may go to different chapters; then "
            + "name the sub-topic keys instead of the topic key.\n"
            + "- A major topic with several sub-topics deserves a chapter of its own or several chapters.\n"
            + "- Topics of different groups are independent areas: do not put two major areas of different groups "
            + "into one chapter.\n"
            + "- Repeated material (many passages, few distinct ones) is confirmation, not a reason for more "
            + "chapters.\n"
            + "- Research topics outside the concept may become chapters when they serve the mission.\n"
            + "- Concept requirements may be merged, split or regrouped. A requirement without evidence goes to "
            + "\"gaps\"; never invent content for it and never make a chapter without research material.\n"
            + "- Order the chapters as the book should be read. Titles are short noun phrases.\n";

    static final String ANSWER = "Answer: {\"chapters\":[{\"title\":\"...\",\"purpose\":\"one sentence\","
            + "\"topics\":[\"T1\"],\"concepts\":[\"C1\"]}],\"gaps\":[\"C2\"]}";

    private ChapterPlanContract() {
    }

    static String prompt(SynthesisBrief brief) {
        StringBuilder text = new StringBuilder(brief.getContext().header());
        appendRequirements(text, brief);
        appendTopics(text, brief);
        appendGroups(text, brief);
        text.append(RULES).append(ANSWER);
        return text.toString();
    }

    /** Step 4: the same brief, the current plan and the review findings; the answer is the complete new list. */
    static String revisePrompt(SynthesisBrief brief, OutlinePlan plan, List<PlanFinding> findings) {
        StringBuilder text = new StringBuilder(brief.getContext().header());
        appendRequirements(text, brief);
        appendTopics(text, brief);
        appendGroups(text, brief);
        text.append("Current table of contents:\n").append(plan.describe());
        text.append("The review found these problems:\n");
        for (PlanFinding finding : findings) {
            text.append("- ").append(finding.getMessage()).append('\n');
        }
        text.append("Return the complete corrected chapter list that solves these problems.\n");
        text.append(RULES).append(ANSWER);
        return text.toString();
    }

    static void appendRequirements(StringBuilder text, SynthesisBrief brief) {
        if (brief.getRequirements().isEmpty()) {
            text.append("Concept requirements: none beyond the mission.\n");
            return;
        }
        text.append("Concept requirements (what the author wants to examine) and their evidence:\n");
        for (SynthesisBrief.RequirementEntry requirement : brief.getRequirements()) {
            ConceptCoverage coverage = requirement.getCoverage();
            text.append(requirement.getKey()).append(' ').append(coverage.getDepth() > 1 ? coverage.getPath()
                    : coverage.getLabel()).append(" - ");
            switch (coverage.getStatus()) {
                case COVERED:
                    text.append("covered: ");
                    break;
                case THIN:
                    text.append("thin (").append(thinReason(coverage)).append("): ");
                    break;
                default:
                    text.append("gap: no evidence\n");
                    continue;
            }
            text.append(coverage.getDistinctPassageCount()).append(" distinct passages from ")
                    .append(coverage.getSourceCount()).append(" sources\n");
        }
    }

    /** Why a requirement counts as thin, in the words of the analysis (quality, breadth and balance apart). */
    static String thinReason(ConceptCoverage coverage) {
        if (!coverage.isMaterialCovered()) {
            return "too little distinct material";
        }
        if (!coverage.isSourceConfirmed()) {
            return "too few independent sources";
        }
        if (!coverage.isBroad()) {
            return "only " + coverage.getCoveredChildCount() + " of " + coverage.getChildCount()
                    + " sub-themes covered";
        }
        return "one-sided: one sub-theme holds most of the material";
    }

    static void appendTopics(StringBuilder text, SynthesisBrief brief) {
        text.append("Research topics (what the sources contain; largest distinct material first):\n");
        for (SynthesisBrief.TopicEntry topic : brief.getTopics()) {
            appendTopic(text, topic, true);
        }
    }

    static void appendTopic(StringBuilder text, SynthesisBrief.TopicEntry topic, boolean withSubtopics) {
        TopicProfile profile = topic.getProfile();
        text.append(topic.getKey()).append(' ').append(PlanJson.quote(profile.getTitle())).append(" - ")
                .append(profile.getPassageCount()).append(" passages, ").append(profile.getDistinctPassageCount())
                .append(" distinct, ").append(profile.getSourceCount()).append(" sources, ")
                .append(profile.getDomainCount()).append(" domains; group ").append(topic.getGroupKey());
        if (!topic.getConceptKey().isEmpty()) {
            text.append("; concept ").append(topic.getConceptKey());
        } else {
            text.append("; not in the concept");
        }
        if (profile.isMajor()) {
            text.append("; major area");
        }
        text.append('\n');
        if (withSubtopics && !topic.getSubtopics().isEmpty()) {
            text.append("   sub-topics: ");
            for (int i = 0; i < topic.getSubtopics().size(); i++) {
                SynthesisBrief.SubtopicEntry subtopic = topic.getSubtopics().get(i);
                text.append(i > 0 ? "; " : "").append(subtopic.getKey()).append(' ')
                        .append(PlanJson.quote(subtopic.getSubtopic().getTitle())).append(" (")
                        .append(subtopic.getSubtopic().getDistinctPassageCount()).append(" distinct)");
            }
            text.append('\n');
        }
        for (String excerpt : topic.getExcerpts()) {
            text.append("   excerpt: ").append(PlanJson.quote(excerpt)).append('\n');
        }
    }

    static void appendGroups(StringBuilder text, SynthesisBrief brief) {
        text.append("Topic groups (closely related topics):");
        for (Map.Entry<String, List<String>> group : brief.getGroupTopicKeys().entrySet()) {
            text.append(' ').append(group.getKey()).append(": ").append(String.join(", ", group.getValue()))
                    .append('.');
        }
        text.append('\n');
    }

    static String schema(SynthesisBrief brief) {
        List<String> requirements = brief.requirementKeys();
        String conceptItems = PlanJson.keyItems(requirements);
        String conceptArray = requirements.isEmpty() ? "{\"type\":\"array\",\"maxItems\":0}"
                : "{\"type\":\"array\",\"items\":" + conceptItems + "}";
        return "{\"type\":\"object\",\"required\":[\"chapters\",\"gaps\"],\"properties\":{"
                + "\"chapters\":{\"type\":\"array\",\"minItems\":1,\"items\":{\"type\":\"object\","
                + "\"required\":[\"title\",\"purpose\",\"topics\",\"concepts\"],\"properties\":{"
                + "\"title\":{\"type\":\"string\"},\"purpose\":{\"type\":\"string\"},"
                + "\"topics\":{\"type\":\"array\",\"minItems\":1,\"items\":" + PlanJson.keyItems(brief.materialKeys())
                + "},\"concepts\":" + conceptArray + "}}},"
                + "\"gaps\":" + conceptArray + "}}";
    }

    /** The strict reader: chapters without sections and the gaps; every key must be known. */
    static OutlinePlan read(String answer, SynthesisBrief brief) throws PlanAnswerException {
        JsonObject root = PlanJson.object(answer);
        JsonArray chapters = PlanJson.array(root, "chapters");
        if (chapters == null || chapters.size() == 0) {
            throw new PlanAnswerException("\"chapters\" must be a non-empty array");
        }
        List<String> errors = new ArrayList<String>();
        List<PlannedChapter> planned = new ArrayList<PlannedChapter>();
        int index = 0;
        for (JsonElement element : chapters) {
            index++;
            String what = "chapter " + index;
            if (!element.isJsonObject()) {
                errors.add(what + " is not an object");
                continue;
            }
            JsonObject chapter = element.getAsJsonObject();
            String title = PlanJson.string(chapter.get("title"));
            errors.addAll(PlanJson.titleErrors(what, title));
            String purpose = PlanJson.string(chapter.get("purpose"));
            if (purpose.isEmpty()) {
                errors.add(what + " has no purpose");
            }
            List<String> material = PlanJson.strings(chapter.get("topics"));
            if (material.isEmpty()) {
                errors.add(what + " names no research material; a concept requirement without material belongs "
                        + "under \"gaps\"");
            }
            for (String key : material) {
                if (!brief.isMaterial(key)) {
                    errors.add(what + " names the unknown topic " + key);
                }
            }
            List<String> concepts = PlanJson.strings(chapter.get("concepts"));
            for (String key : concepts) {
                if (brief.requirement(key) == null) {
                    errors.add(what + " names the unknown concept requirement " + key);
                }
            }
            planned.add(new PlannedChapter(title, purpose, material, concepts, null, false));
        }
        List<String> gaps = PlanJson.strings(root.get("gaps"));
        for (String key : gaps) {
            if (brief.requirement(key) == null) {
                errors.add("\"gaps\" names the unknown concept requirement " + key);
            }
        }
        if (!errors.isEmpty()) {
            throw new PlanAnswerException(errors);
        }
        return new OutlinePlan(planned, gaps, false);
    }
}
