package com.aresstack.askai.research.knowledge.topic;

import com.aresstack.askai.research.knowledge.ClusteringMonitor;
import com.aresstack.askai.research.knowledge.live.LiveOutlineProjectionBuilder;
import com.aresstack.askai.research.knowledge.live.LiveTopicProjection;
import com.aresstack.askai.research.knowledge.live.LiveTopicTree;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Phase 3a: the topic analysis measures material and diversity apart, and relates topics and concept. */
public class TopicAnalyzerTest {

    private static TopicAnalysis analyse(TopicCorpusFixture corpus, List<ConceptAnchor> anchors) {
        LiveTopicTree tree = new LiveOutlineProjectionBuilder().discoverTopicTree(corpus.passages, corpus.vectors,
                ClusteringMonitor.NONE);
        return new TopicAnalyzer(TopicAnalysisSettings.defaults()).analyze(corpus.analysisPassages,
                corpus.vectors, tree, anchors, ClusteringMonitor.NONE);
    }

    /** The topic whose passages carry {@code name} in their text. */
    private static TopicProfile topicNamed(TopicCorpusFixture corpus, TopicAnalysis analysis, String name) {
        TopicProfile found = null;
        for (TopicProfile topic : analysis.getTopics()) {
            String first = topic.getPassageIds().get(0);
            for (AnalysisPassage passage : corpus.analysisPassages) {
                if (passage.getPassageId().equals(first) && passage.getText().startsWith(name + " ")) {
                    assertTrue("one topic per name: " + name, found == null);
                    found = topic;
                }
            }
        }
        assertNotNull("no topic for " + name, found);
        return found;
    }

    @Test
    public void manyCopiesOfOneStatementAreMuchMaterialButNoDiversity() {
        TopicCorpusFixture corpus = new TopicCorpusFixture();
        corpus.redundant("copies", 20, 10);
        corpus.diverse("broad", 4, 5, 6);
        TopicAnalysis analysis = analyse(corpus, Collections.<ConceptAnchor>emptyList());

        TopicProfile copies = topicNamed(corpus, analysis, "copies");
        assertEquals(20, copies.getPassageCount());
        assertEquals("twenty copies count once", 1, copies.getDistinctPassageCount());
        assertEquals(0.95, copies.getRedundancyShare(), 1e-9);
        assertEquals(10, copies.getSourceCount());
        assertTrue("one statement has no sub-topics", copies.getSubtopics().isEmpty());
        assertFalse("not a major area despite twenty passages", copies.isMajor());

        TopicProfile broad = topicNamed(corpus, analysis, "broad");
        assertEquals(20, broad.getPassageCount());
        assertEquals(20, broad.getDistinctPassageCount());
        assertEquals("four aspects", 4, broad.getSubtopics().size());
        for (Subtopic subtopic : broad.getSubtopics()) {
            assertEquals(5, subtopic.getPassageIds().size());
            assertTrue(subtopic.getSubtopicId().startsWith("subtopic-"));
        }
        assertTrue(broad.isMajor());
        assertTrue("diversity shows in the dispersion", broad.getDispersion() > copies.getDispersion());
        assertEquals("the largest distinct material comes first", broad.getTopicId(),
                analysis.getTopics().get(0).getTopicId());
        assertEquals(1, analysis.distinctCount(copies.getPassageIds()));
        assertEquals(10, analysis.sourceCount(copies.getPassageIds()));
    }

    @Test
    public void relatedTopicsShareAGroupAndIndependentOnesDoNot() {
        TopicCorpusFixture corpus = new TopicCorpusFixture();
        int area = corpus.direction();
        corpus.plain("alpha", 6, 3, area);
        corpus.plain("beta", 6, 3, area);
        corpus.plain("gamma", 6, 3, -1);
        TopicAnalysis analysis = analyse(corpus, Collections.<ConceptAnchor>emptyList());

        TopicProfile alpha = topicNamed(corpus, analysis, "alpha");
        TopicProfile beta = topicNamed(corpus, analysis, "beta");
        TopicProfile gamma = topicNamed(corpus, analysis, "gamma");
        assertTrue(alpha.getSubtopics().isEmpty());
        assertEquals("alpha and beta form one area", alpha.getGroupId(), beta.getGroupId());
        assertFalse("gamma is independent", alpha.getGroupId().equals(gamma.getGroupId()));
        assertEquals(1, alpha.getRelated().size());
        assertEquals(beta.getTopicId(), alpha.getRelated().get(0).getTopicId());
        assertTrue(alpha.getRelated().get(0).getJoinDistance() < 0.75);
        assertTrue(gamma.getRelated().isEmpty());
        assertEquals(2, analysis.getGroups().size());
    }

    @Test
    public void theConceptCoverageShowsAlignedNovelAndMissingTopics() {
        TopicCorpusFixture corpus = new TopicCorpusFixture();
        int known = corpus.plain("known", 6, 3, -1);
        corpus.plain("novel", 6, 3, -1);
        int missing = corpus.direction();
        List<ConceptAnchor> anchors = Arrays.asList(
                new ConceptAnchor("c-known", "Known", "Book > Known", 1, "", corpus.query(known)),
                new ConceptAnchor("c-missing", "Missing", "Book > Missing", 1, "", corpus.query(missing)));
        TopicAnalysis analysis = analyse(corpus, anchors);

        TopicProfile knownTopic = topicNamed(corpus, analysis, "known");
        assertTrue(knownTopic.isConceptAligned());
        assertEquals("c-known", knownTopic.getConceptAnchorId());
        assertFalse(knownTopic.isNovel());
        TopicProfile novel = topicNamed(corpus, analysis, "novel");
        assertTrue("a significant topic without a node is new from the research", novel.isNovel());

        ConceptCoverage coverKnown = analysis.coverage("c-known");
        assertEquals(ConceptCoverage.Status.COVERED, coverKnown.getStatus());
        assertEquals(6, coverKnown.getPassageIds().size());
        assertEquals(Collections.singletonList(knownTopic.getTopicId()), coverKnown.getTopicIds());
        ConceptCoverage coverMissing = analysis.coverage("c-missing");
        assertEquals("no evidence stays a visible gap", ConceptCoverage.Status.GAP, coverMissing.getStatus());
        assertTrue(coverMissing.getPassageIds().isEmpty());
    }

    @Test
    public void passagesRollUpToTheParentNode() {
        TopicCorpusFixture corpus = new TopicCorpusFixture();
        int child = corpus.plain("child", 6, 3, -1);
        int parentOnly = corpus.direction();
        List<ConceptAnchor> anchors = Arrays.asList(
                new ConceptAnchor("c-parent", "Parent", "Book > Parent", 1, "", corpus.query(parentOnly)),
                new ConceptAnchor("c-child", "Child", "Book > Parent > Child", 2, "c-parent", corpus.query(child)));
        TopicAnalysis analysis = analyse(corpus, anchors);
        assertEquals(6, analysis.coverage("c-child").getPassageIds().size());
        assertEquals("the parent counts its children's evidence", 6,
                analysis.coverage("c-parent").getPassageIds().size());
    }

    @Test
    public void theSameInputGivesTheSameAnalysis() {
        TopicCorpusFixture corpus = new TopicCorpusFixture();
        corpus.diverse("broad", 3, 4, 4);
        corpus.redundant("copies", 8, 4);
        corpus.plain("plain", 5, 3, -1);
        TopicAnalysis first = analyse(corpus, Collections.<ConceptAnchor>emptyList());
        TopicAnalysis second = analyse(corpus, Collections.<ConceptAnchor>emptyList());
        List<String> a = new ArrayList<String>();
        List<String> b = new ArrayList<String>();
        for (TopicProfile topic : first.getTopics()) {
            a.add(topic.getTopicId() + "/" + topic.getDistinctPassageCount() + "/" + topic.getSubtopics().size());
        }
        for (TopicProfile topic : second.getTopics()) {
            b.add(topic.getTopicId() + "/" + topic.getDistinctPassageCount() + "/" + topic.getSubtopics().size());
        }
        assertEquals(a, b);
    }

    @Test
    public void smallTopicsAreNotSignificant() {
        TopicCorpusFixture corpus = new TopicCorpusFixture();
        corpus.plain("tiny", 2, 2, -1);
        corpus.plain("single-source", 5, 1, -1);
        corpus.plain("enough", 5, 2, -1);
        TopicAnalysis analysis = analyse(corpus, Collections.<ConceptAnchor>emptyList());
        assertFalse(topicNamed(corpus, analysis, "tiny").isSignificant());
        assertFalse(topicNamed(corpus, analysis, "single-source").isSignificant());
        assertTrue(topicNamed(corpus, analysis, "enough").isSignificant());
        assertEquals(1, analysis.significantTopics().size());
        assertEquals(0, topicNamed(corpus, analysis, "tiny").getEvidenceShare(), 0);
    }

    @Test
    public void theTreeTopicsEqualTheDiscoveredTopics() {
        TopicCorpusFixture corpus = new TopicCorpusFixture();
        corpus.diverse("broad", 3, 4, 4);
        corpus.plain("plain", 5, 3, -1);
        LiveOutlineProjectionBuilder builder = new LiveOutlineProjectionBuilder();
        List<LiveTopicProjection> flat = builder.discoverTopics(corpus.passages, corpus.vectors);
        LiveTopicTree tree = builder.discoverTopicTree(corpus.passages, corpus.vectors, null);
        assertEquals(flat.size(), tree.getTopics().size());
        for (int i = 0; i < flat.size(); i++) {
            assertEquals(flat.get(i).getClusterId(), tree.getTopics().get(i).getClusterId());
            assertEquals(flat.get(i).getRepresentativePassageIds(),
                    tree.getTopics().get(i).getRepresentativePassageIds());
        }
    }
}
