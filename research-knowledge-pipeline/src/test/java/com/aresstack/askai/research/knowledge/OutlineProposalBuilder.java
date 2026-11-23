package com.aresstack.askai.research.knowledge;

import com.aresstack.askai.research.domain.ConceptPaper;
import com.aresstack.askai.research.domain.IdSequence;
import com.aresstack.askai.research.domain.Lifecycle;
import com.aresstack.askai.research.domain.OutlineProposal;
import com.aresstack.askai.research.domain.Passage;
import com.aresstack.askai.research.domain.ResearchBrief;
import com.aresstack.askai.research.domain.TopicProposal;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Test fixture (plan PR 22 removed the unwired production builder; the legacy vertical slice still needs proposals).
 *
 * Concept + outline PROPOSALS from evidence: confirmed brief + topic clusters + representative passages
 * + the brief's questions — never straight from a model prompt. Questions are assigned to the topic with
 * the highest token overlap of its representative text; questions no topic covers become IDENTIFIED GAPS
 * of the proposal (the input of the later detail research). Deterministic throughout.
 */
public final class OutlineProposalBuilder {

    /** Both proposals belong together — the concept references the same topics the outline sections use. */
    public static final class Proposals {
        private final ConceptPaper concept;
        private final OutlineProposal outline;

        Proposals(ConceptPaper concept, OutlineProposal outline) {
            this.concept = concept;
            this.outline = outline;
        }

        public ConceptPaper getConcept() {
            return concept;
        }

        public OutlineProposal getOutline() {
            return outline;
        }
    }

    private final IdSequence ids;

    public OutlineProposalBuilder(IdSequence ids) {
        this.ids = ids;
    }

    public Proposals build(ResearchBrief brief, List<TopicProposal> topics,
                           Map<String, Passage> passagesById) {
        // Largest clusters first: the strongest evidence leads the document.
        List<TopicProposal> ordered = new ArrayList<TopicProposal>(topics);
        java.util.Collections.sort(ordered, new java.util.Comparator<TopicProposal>() {
            public int compare(TopicProposal a, TopicProposal b) {
                return Integer.compare(b.getMemberPassageIds().size(), a.getMemberPassageIds().size());
            }
        });

        // Assign each brief question to the best-overlapping topic; unmatched → identified gap.
        List<List<String>> questionsPerTopic = new ArrayList<List<String>>();
        for (int i = 0; i < ordered.size(); i++) {
            questionsPerTopic.add(new ArrayList<String>());
        }
        List<String> unmatchedQuestions = new ArrayList<String>();
        for (String question : brief.getInitialQuestions()) {
            int best = -1;
            int bestOverlap = 0;
            for (int i = 0; i < ordered.size(); i++) {
                int overlap = tokenOverlap(question,
                        representativeText(ordered.get(i), passagesById));
                if (overlap > bestOverlap) {
                    bestOverlap = overlap;
                    best = i;
                }
            }
            if (best >= 0) {
                questionsPerTopic.get(best).add(question);
            } else {
                unmatchedQuestions.add(question);
            }
        }

        List<OutlineProposal.SectionProposal> sections =
                new ArrayList<OutlineProposal.SectionProposal>();
        List<String> topicIds = new ArrayList<String>();
        for (int i = 0; i < ordered.size(); i++) {
            TopicProposal topic = ordered.get(i);
            topicIds.add(topic.getProposalId());
            List<String> gaps = new ArrayList<String>();
            if (questionsPerTopic.get(i).isEmpty()) {
                gaps.add("no confirmed research question covered yet");
            }
            sections.add(new OutlineProposal.SectionProposal("sp-" + (i + 1),
                    topic.getSuggestedTitle(), "",
                    java.util.Collections.singletonList(topic.getProposalId()),
                    questionsPerTopic.get(i),
                    java.util.Collections.singletonList("at least one supported claim per question"),
                    topic.getMemberPassageIds(), gaps));
        }
        // Questions without ANY evidence become a dedicated gap section proposal — visible, not dropped.
        if (!unmatchedQuestions.isEmpty()) {
            sections.add(new OutlineProposal.SectionProposal("sp-gaps", "Open questions", "",
                    null, unmatchedQuestions, null, null,
                    java.util.Collections.singletonList("no orientation evidence found yet")));
        }

        ConceptPaper concept = new ConceptPaper(ids.next("concept-proposal"), 0L,
                brief.getResearchQuestion(), brief.getGoal(), brief.getInitialQuestions(), topicIds,
                "evidence-led: strongest topics first", null, unmatchedQuestions, null,
                Lifecycle.PROPOSED);
        OutlineProposal outline = new OutlineProposal(ids.next("outline-proposal"),
                brief.getRevision(), sections, Lifecycle.PROPOSED);
        return new Proposals(concept, outline);
    }

    private static String representativeText(TopicProposal topic, Map<String, Passage> passagesById) {
        StringBuilder sb = new StringBuilder(topic.getSuggestedTitle());
        for (String passageId : topic.getRepresentativePassageIds()) {
            Passage passage = passagesById.get(passageId);
            if (passage != null) {
                sb.append(' ').append(passage.getText());
            }
        }
        return sb.toString();
    }

    /** Case-insensitive shared tokens of length >= 4 — cheap, deterministic, language-neutral enough. */
    private static int tokenOverlap(String a, String b) {
        java.util.Set<String> tokensA = tokens(a);
        java.util.Set<String> tokensB = tokens(b);
        tokensA.retainAll(tokensB);
        return tokensA.size();
    }

    private static java.util.Set<String> tokens(String text) {
        java.util.Set<String> tokens = new java.util.LinkedHashSet<String>();
        for (String raw : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (raw.length() >= 4) {
                tokens.add(raw);
            }
        }
        return tokens;
    }
}
