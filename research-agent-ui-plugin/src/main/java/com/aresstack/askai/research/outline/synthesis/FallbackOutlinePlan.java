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

    static OutlinePlan plan(SynthesisBrief brief, int maxSections) {
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
            chapters.add(chapter.withSections(sections(brief, chapter, maxSections)));
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

    /**
     * {@link #sections(SynthesisBrief, PlannedChapter)}, packed into at most {@code maxSections} sections when there
     * are more: the largest pieces first, each into the section with the least distinct material so far; the sections
     * keep the reading order of their first piece and are titled by their largest piece (untitled, the label call
     * names them). Live run 5: a chapter split by material got 63 sections.
     */
    static List<PlannedSection> sections(SynthesisBrief brief, PlannedChapter chapter, int maxSections) {
        return packed(brief, sections(brief, chapter), maxSections);
    }

    /** {@code sections} packed into at most {@code maxSections} (see above). */
    static List<PlannedSection> packed(SynthesisBrief brief, List<PlannedSection> sections, int maxSections) {
        int limit = Math.max(1, maxSections);
        if (sections.size() <= limit) {
            return sections;
        }
        final int[] weight = new int[sections.size()];
        List<Integer> order = new ArrayList<Integer>();
        for (int i = 0; i < sections.size(); i++) {
            weight[i] = brief.getAnalysis().distinctCount(OutlineQualityCheck.passagesOf(
                    sections.get(i).getMaterialKeys(), brief));
            order.add(i);
        }
        Collections.sort(order, new java.util.Comparator<Integer>() {
            public int compare(Integer a, Integer b) {
                return weight[a] != weight[b] ? weight[b] - weight[a] : a - b;
            }
        });
        List<List<Integer>> buckets = new ArrayList<List<Integer>>();
        long[] load = new long[limit];
        for (int b = 0; b < limit; b++) {
            buckets.add(new ArrayList<Integer>());
        }
        for (int index : order) {
            int lightest = 0;
            for (int b = 1; b < limit; b++) {
                if (load[b] < load[lightest]) {
                    lightest = b;
                }
            }
            buckets.get(lightest).add(index);
            load[lightest] += Math.max(1, weight[index]);
        }
        // The heaviest piece of each bucket names it; buckets in the reading order of their first piece.
        Collections.sort(buckets, new java.util.Comparator<List<Integer>>() {
            public int compare(List<Integer> a, List<Integer> b) {
                return Collections.min(a) - Collections.min(b);
            }
        });
        List<PlannedSection> result = new ArrayList<PlannedSection>();
        for (List<Integer> bucket : buckets) {
            if (bucket.isEmpty()) {
                continue;
            }
            PlannedSection lead = sections.get(bucket.get(0));
            List<Integer> inOrder = new ArrayList<Integer>(bucket);
            Collections.sort(inOrder);
            List<String> keys = new ArrayList<String>();
            for (int index : inOrder) {
                keys.addAll(sections.get(index).getMaterialKeys());
            }
            result.add(new PlannedSection(lead.getTitle(), "", keys, true));
        }
        return result;
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
