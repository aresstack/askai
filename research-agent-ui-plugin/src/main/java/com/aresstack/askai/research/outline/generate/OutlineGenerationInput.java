package com.aresstack.askai.research.outline.generate;

import com.aresstack.askai.research.knowledge.EmbeddingPort;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Plan PR 22: everything {@link ConceptSeededOutlineBuilder} reads. The caller has already applied the source
 * rule of D4 ({@link OutlineSourceRule}): only passages of eligible sources are in {@link #passages}, with their
 * STORED vectors; nothing here is embedded again.
 */
public final class OutlineGenerationInput {

    /** One stored passage with its source and its stored document vector. */
    public static final class CorpusPassage {
        final String passageId;
        final String sourceId;
        final String text;
        final EmbeddingPort.EmbeddingVector vector;

        public CorpusPassage(String passageId, String sourceId, String text, EmbeddingPort.EmbeddingVector vector) {
            this.passageId = passageId == null ? "" : passageId;
            this.sourceId = sourceId == null ? "" : sourceId;
            this.text = text == null ? "" : text;
            this.vector = vector;
        }
    }

    /** One source cluster of the eligible passages (the PR 20 clustering). */
    public static final class Cluster {
        final String clusterId;
        final String title;
        final List<String> memberPassageIds;
        final List<String> representativePassageIds;

        public Cluster(String clusterId, String title, List<String> memberPassageIds,
                       List<String> representativePassageIds) {
            this.clusterId = clusterId == null ? "" : clusterId;
            this.title = title == null ? "" : title;
            this.memberPassageIds = copy(memberPassageIds);
            this.representativePassageIds = copy(representativePassageIds);
        }
    }

    /** The calibration and topic minimums. */
    public static final class Settings {
        /**
         * Provisional defaults, settings in the research runtime ("Outline anchors"). Live run 3 (2026-11-10,
         * "Wearables auf Baustellen", 2068 passages, 1 chapter with 4 sections): every passage's best anchor
         * similarity lay far above 0.35 (median 0.645), while the lead over the nearest sibling section had a
         * median of 0.011, because sibling anchors share the whole path but their last label. A margin of 0.02
         * therefore left three quarters of the corpus unassigned; 0.01 is the measured midpoint of that corpus.
         */
        public static final double DEFAULT_THRESHOLD = 0.35;
        public static final double DEFAULT_MARGIN = 0.01;

        final double threshold;
        final double margin;
        final int minimumPassagesPerTopic;
        final int minimumDistinctSourcesPerTopic;

        public Settings(double threshold, double margin, int minimumPassagesPerTopic,
                        int minimumDistinctSourcesPerTopic) {
            this.threshold = threshold;
            this.margin = margin;
            this.minimumPassagesPerTopic = Math.max(1, minimumPassagesPerTopic);
            this.minimumDistinctSourcesPerTopic = Math.max(1, minimumDistinctSourcesPerTopic);
        }

        /** Minimum cosine between a passage and its nearest anchor. */
        public double getThreshold() {
            return threshold;
        }

        /** Minimum lead of the nearest anchor over the second-nearest. */
        public double getMargin() {
            return margin;
        }

        public int getMinimumPassagesPerTopic() {
            return minimumPassagesPerTopic;
        }

        public int getMinimumDistinctSourcesPerTopic() {
            return minimumDistinctSourcesPerTopic;
        }
    }

    final String bookTitle;
    final String mission;
    final String deliverable;
    final List<ConceptOutlineNode> chapters;
    final List<CorpusPassage> passages;
    final List<Cluster> clusters;
    final Settings settings;

    /**
     * @param bookTitle   the first part of every anchor's label path
     * @param mission     the mission of the Phase-1 record (default purposes)
     * @param deliverable the deliverable of the Phase-1 record ("120 pages", "20000-30000 words" or "")
     * @param chapters    the effective first-level concept nodes of the approved revision, in concept order
     */
    public OutlineGenerationInput(String bookTitle, String mission, String deliverable,
                                  List<ConceptOutlineNode> chapters, List<CorpusPassage> passages,
                                  List<Cluster> clusters, Settings settings) {
        this.bookTitle = bookTitle == null ? "" : bookTitle.trim();
        this.mission = mission == null ? "" : mission.trim();
        this.deliverable = deliverable == null ? "" : deliverable.trim();
        this.chapters = Collections.unmodifiableList(new ArrayList<ConceptOutlineNode>(
                chapters == null ? Collections.<ConceptOutlineNode>emptyList() : chapters));
        this.passages = Collections.unmodifiableList(new ArrayList<CorpusPassage>(
                passages == null ? Collections.<CorpusPassage>emptyList() : passages));
        this.clusters = Collections.unmodifiableList(new ArrayList<Cluster>(
                clusters == null ? Collections.<Cluster>emptyList() : clusters));
        this.settings = settings == null ? new Settings(Settings.DEFAULT_THRESHOLD, Settings.DEFAULT_MARGIN, 3, 2)
                : settings;
    }

    private static List<String> copy(List<String> values) {
        return Collections.unmodifiableList(new ArrayList<String>(
                values == null ? Collections.<String>emptyList() : values));
    }

    /** The first part of every anchor's label path (the quoted book title, or empty). */
    public String getBookTitle() {
        return bookTitle;
    }
}
