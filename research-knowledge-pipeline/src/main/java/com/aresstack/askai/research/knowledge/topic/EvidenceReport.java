package com.aresstack.askai.research.knowledge.topic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Phase 2: what the research should do next, read off one {@link TopicAnalysis}: per concept node a {@link Status}
 * (evidence quality, breadth and balance judged apart, see {@link ConceptCoverage}) and the significant research
 * areas no concept node covers, each with its search-fence reading. Each comes with a few key terms for follow-up
 * queries. Pure code; the same analysis gives the same report. Immutable.
 */
public final class EvidenceReport {

    /**
     * What a concept node needs next, most urgent first: MISSING (no material), REDUNDANT (many passages but too few
     * distinct ones), THIN (too few passages), UNCONFIRMED (enough distinct material, too few sources or domains),
     * ONE_SIDED (good evidence, but too few children covered or one child holds most of it), OK.
     */
    public enum Status { MISSING, REDUNDANT, THIN, UNCONFIRMED, ONE_SIDED, OK }

    /** The evidence of one concept node. */
    public static final class NodeEvidence {
        private final ConceptCoverage coverage;
        private final Status status;
        private final List<String> keyTerms;

        NodeEvidence(ConceptCoverage coverage, Status status, List<String> keyTerms) {
            this.coverage = coverage;
            this.status = status;
            this.keyTerms = Collections.unmodifiableList(new ArrayList<String>(keyTerms));
        }

        /** The concept node id (ConceptOutlineNode id, the same the outline generation uses). */
        public String getAnchorId() {
            return coverage.getAnchorId();
        }

        public String getLabel() {
            return coverage.getLabel();
        }

        public String getPath() {
            return coverage.getPath();
        }

        public int getDepth() {
            return coverage.getDepth();
        }

        public String getParentId() {
            return coverage.getParentId();
        }

        public Status getStatus() {
            return status;
        }

        public int getPassages() {
            return coverage.getPassageIds().size();
        }

        public int getDistinctPassages() {
            return coverage.getDistinctPassageCount();
        }

        public int getSources() {
            return coverage.getSourceCount();
        }

        public int getDomains() {
            return coverage.getDomainCount();
        }

        /** The measurements behind the status (quality, breadth and balance apart). */
        public ConceptCoverage getCoverage() {
            return coverage;
        }

        /** Characteristic terms of its material, or the label words when it has none (seeds for follow-up queries). */
        public List<String> getKeyTerms() {
            return keyTerms;
        }
    }

    /** A significant research topic without a concept node. */
    public static final class ResearchArea {
        private final TopicProfile topic;
        private final List<String> keyTerms;

        ResearchArea(TopicProfile topic, List<String> keyTerms) {
            this.topic = topic;
            this.keyTerms = Collections.unmodifiableList(new ArrayList<String>(keyTerms));
        }

        public String getTopicId() {
            return topic.getTopicId();
        }

        public String getTitle() {
            return topic.getTitle();
        }

        public int getPassages() {
            return topic.getPassageCount();
        }

        public int getDistinctPassages() {
            return topic.getDistinctPassageCount();
        }

        public int getSources() {
            return topic.getSourceCount();
        }

        public int getDomains() {
            return topic.getDomainCount();
        }

        /** Where it lies relative to the search fence (IN / OUT / UNCLASSIFIED): new to the concept is not OUT. */
        public ScopeAlignment getScopeAlignment() {
            return topic.getScopeAlignment();
        }

        public TopicProfile getTopic() {
            return topic;
        }

        public List<String> getKeyTerms() {
            return keyTerms;
        }
    }

    private final List<NodeEvidence> nodes;
    private final List<ResearchArea> novelAreas;

    private EvidenceReport(List<NodeEvidence> nodes, List<ResearchArea> novelAreas) {
        this.nodes = Collections.unmodifiableList(nodes);
        this.novelAreas = Collections.unmodifiableList(novelAreas);
    }

    /**
     * @param texts        the passage texts of the corpus (the key-term document frequencies)
     * @param keyTermCount key terms per node or area
     */
    public static EvidenceReport of(TopicAnalysis analysis, Map<String, String> texts, int keyTermCount) {
        KeyTerms terms = new KeyTerms(texts.values());
        TopicAnalysisSettings settings = analysis.getSettings();
        List<NodeEvidence> nodes = new ArrayList<NodeEvidence>();
        for (ConceptCoverage coverage : analysis.getCoverage()) {
            Status status = statusOf(coverage, settings);
            List<String> keyTerms = status == Status.MISSING ? KeyTerms.labelWords(coverage.getPath())
                    : terms.of(textsOf(coverage.getPassageIds(), texts), keyTermCount);
            nodes.add(new NodeEvidence(coverage, status, keyTerms));
        }
        List<ResearchArea> areas = new ArrayList<ResearchArea>();
        for (TopicProfile topic : analysis.getTopics()) {
            if (topic.isNovel()) {
                areas.add(new ResearchArea(topic, terms.of(textsOf(topic.getPassageIds(), texts), keyTermCount)));
            }
        }
        return new EvidenceReport(nodes, areas);
    }

    static Status statusOf(ConceptCoverage coverage, TopicAnalysisSettings settings) {
        if (coverage.getStatus() == ConceptCoverage.Status.GAP) {
            return Status.MISSING;
        }
        if (!coverage.isMaterialCovered()) {
            return coverage.getPassageIds().size() >= settings.getMinimumPassages() ? Status.REDUNDANT : Status.THIN;
        }
        if (!coverage.isSourceConfirmed()) {
            return Status.UNCONFIRMED;
        }
        if (!coverage.isBroad() || !coverage.isBalanced()) {
            return Status.ONE_SIDED;
        }
        return Status.OK;
    }

    private static List<String> textsOf(List<String> ids, Map<String, String> texts) {
        List<String> result = new ArrayList<String>();
        for (String id : ids) {
            String text = texts.get(id);
            if (text != null) {
                result.add(text);
            }
        }
        return result;
    }

    /** Every concept node below the frame, in concept order. */
    public List<NodeEvidence> getNodes() {
        return nodes;
    }

    /** Significant research topics without a concept node, largest distinct material first. */
    public List<ResearchArea> getNovelAreas() {
        return novelAreas;
    }
}
