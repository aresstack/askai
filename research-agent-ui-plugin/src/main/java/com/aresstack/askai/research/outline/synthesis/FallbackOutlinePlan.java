package com.aresstack.askai.research.outline.synthesis;

import com.aresstack.askai.research.knowledge.topic.ConceptCoverage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Phase 3b: the plan from the topic analysis alone, for when the model gives no valid plan (or a chapter's sections).
 * Topic groups become chapters, topics become sections, and a major topic with sub-topics gets a section per
 * sub-topic. Titles are the topics' evidence-bound titles, flagged untitled so the label call names them; the
 * requirements without evidence are the gaps. It never copies the concept.
 */
final class FallbackOutlinePlan {

    private FallbackOutlinePlan() {
    }

    static OutlinePlan plan(SynthesisBrief brief) {
        List<PlannedChapter> chapters = new ArrayList<PlannedChapter>();
        for (Map.Entry<String, List<String>> group : brief.getGroupTopicKeys().entrySet()) {
            List<String> keys = group.getValue();
            SynthesisBrief.TopicEntry lead = brief.topic(keys.get(0));
            List<String> concepts = new ArrayList<String>();
            for (String key : keys) {
                String concept = brief.topic(key).getConceptKey();
                if (!concept.isEmpty() && !concepts.contains(concept)) {
                    concepts.add(concept);
                }
            }
            PlannedChapter chapter = new PlannedChapter(lead.getProfile().getTitle(), "", keys, concepts, null,
                    true);
            chapters.add(chapter.withSections(sections(brief, chapter)));
        }
        return new OutlinePlan(chapters, gaps(brief), true);
    }

    /** One section per material key; a major topic with sub-topics gets one per sub-topic. */
    static List<PlannedSection> sections(SynthesisBrief brief, PlannedChapter chapter) {
        List<PlannedSection> sections = new ArrayList<PlannedSection>();
        for (String key : chapter.getMaterialKeys()) {
            SynthesisBrief.TopicEntry topic = brief.topic(key);
            if (topic != null && topic.getProfile().isMajor() && !topic.getSubtopics().isEmpty()) {
                for (SynthesisBrief.SubtopicEntry subtopic : topic.getSubtopics()) {
                    sections.add(new PlannedSection(subtopic.getSubtopic().getTitle(), "",
                            Collections.singletonList(subtopic.getKey()), true));
                }
                continue;
            }
            String title = topic != null ? topic.getProfile().getTitle()
                    : brief.subtopic(key) != null ? brief.subtopic(key).getSubtopic().getTitle() : key;
            sections.add(new PlannedSection(title, "", Collections.singletonList(key), true));
        }
        return sections;
    }

    static List<String> gaps(SynthesisBrief brief) {
        List<String> gaps = new ArrayList<String>();
        for (SynthesisBrief.RequirementEntry requirement : brief.getRequirements()) {
            if (requirement.getCoverage().getStatus() == ConceptCoverage.Status.GAP) {
                gaps.add(requirement.getKey());
            }
        }
        return gaps;
    }
}
