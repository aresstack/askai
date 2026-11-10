package com.aresstack.askai.research.knowledge.topic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Phase 2: what the research should do next, read off one {@link TopicAnalysis}: per concept node whether its
 * evidence is OK, THIN (below the topic minimums), REDUNDANT (enough passages, but too little distinct material:
 * the sources repeat each other) or MISSING, and the significant research areas no concept node covers. Each comes
 * with a few key terms for follow-up queries. Pure code; the same analysis gives the same report. Immutable.
 */
public final class EvidenceReport {

    public enum Status { OK, THIN, REDUNDANT, MISSING }

    /** The evidence of one concept node. */
    public static final class NodeEvidence {
        private final String anchorId;
        private final String label;
        private final String path;
        private final int depth;
        private final Status status;
        private final int passages;
        private final int distinctPassages;
        private final int sources;
        private final List<String> keyTerms;

        NodeEvidence(String anchorId, String label, String path, int depth, Status status, int passages,
                     int distinctPassages, int sources, List<String> keyTerms) {
            this.anchorId = anchorId;
            this.label = label;
            this.path = path;
            this.depth = depth;
            this.status = status;
            this.passages = passages;
            this.distinctPassages = distinctPassages;
            this.sources = sources;
            this.keyTerms = Collections.unmodifiableList(new ArrayList<String>(keyTerms));
        }

        public String getAnchorId() {
            return anchorId;
        }

        public String getLabel() {
            return label;
        }

        public String getPath() {
            return path;
        }

        public int getDepth() {
            return depth;
        }

        public Status getStatus() {
            return status;
        }

        public int getPassages() {
            return passages;
        }

        public int getDistinctPassages() {
            return distinctPassages;
        }

        public int getSources() {
            return sources;
        }

        /** Terms of its evidence; for a MISSING node the words of its label path. */
        public List<String> getKeyTerms() {
            return keyTerms;
        }
    }

    /** A significant research area without a concept node. */
    public static final class ResearchArea {
        private final String topicId;
        private final String title;
        private final int passages;
        private final int distinctPassages;
        private final int sources;
        private final List<String> keyTerms;

        ResearchArea(String topicId, String title, int passages, int distinctPassages, int sources,
                     List<String> keyTerms) {
            this.topicId = topicId;
            this.title = title;
            this.passages = passages;
            this.distinctPassages = distinctPassages;
            this.sources = sources;
            this.keyTerms = Collections.unmodifiableList(new ArrayList<String>(keyTerms));
        }

        public String getTopicId() {
            return topicId;
        }

        public String getTitle() {
            return title;
        }

        public int getPassages() {
            return passages;
        }

        public int getDistinctPassages() {
            return distinctPassages;
        }

        public int getSources() {
            return sources;
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
     * @param texts         passage id to text, over the analysed corpus (the key terms' corpus)
     * @param keyTermCount  key terms per node and area
     */
    public static EvidenceReport of(TopicAnalysis analysis, Map<String, String> texts, int keyTermCount) {
        KeyTerms terms = new KeyTerms(texts.values());
        int minimum = analysis.getSettings().getMinimumPassages();
        List<NodeEvidence> nodes = new ArrayList<NodeEvidence>();
        for (ConceptCoverage coverage : analysis.getCoverage()) {
            Status status;
            switch (coverage.getStatus()) {
                case GAP:
                    status = Status.MISSING;
                    break;
                case THIN:
                    status = Status.THIN;
                    break;
                default:
                    status = coverage.getDistinctPassageCount() < minimum ? Status.REDUNDANT : Status.OK;
            }
            List<String> keyTerms = status == Status.MISSING ? KeyTerms.labelWords(coverage.getPath())
                    : terms.of(textsOf(coverage.getPassageIds(), texts), keyTermCount);
            nodes.add(new NodeEvidence(coverage.getAnchorId(), coverage.getLabel(), coverage.getPath(),
                    coverage.getDepth(), status, coverage.getPassageIds().size(),
                    coverage.getDistinctPassageCount(), coverage.getSourceCount(), keyTerms));
        }
        List<ResearchArea> areas = new ArrayList<ResearchArea>();
        for (TopicProfile topic : analysis.getTopics()) {
            if (topic.isNovel()) {
                areas.add(new ResearchArea(topic.getTopicId(), topic.getTitle(), topic.getPassageCount(),
                        topic.getDistinctPassageCount(), topic.getSourceCount(),
                        terms.of(textsOf(topic.getPassageIds(), texts), keyTermCount)));
            }
        }
        return new EvidenceReport(nodes, areas);
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

    /** One entry per concept node, in concept order. */
    public List<NodeEvidence> getNodes() {
        return nodes;
    }

    /** Significant research areas without a concept node, largest distinct material first. */
    public List<ResearchArea> getNovelAreas() {
        return novelAreas;
    }
}
