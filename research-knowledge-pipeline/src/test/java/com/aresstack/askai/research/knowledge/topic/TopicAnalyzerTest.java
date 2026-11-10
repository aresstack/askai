package com.aresstack.askai.research.knowledge.topic;

import com.aresstack.askai.research.domain.scope.ScopeAnchor;
import com.aresstack.askai.research.domain.scope.ScopeFenceEvaluator;
import com.aresstack.askai.research.knowledge.ClusteringMonitor;
import com.aresstack.askai.research.knowledge.live.LiveOutlineProjectionBuilder;
import com.aresstack.askai.research.knowledge.live.LiveTopicProjection;
import com.aresstack.askai.research.knowledge.live.LiveTopicTree;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Phase 3a: the topic analysis measures material and diversity apart, and relates topics and concept. */
public class TopicAnalyzerTest {

    private static TopicAnalysis analyse(TopicCorpusFixture corpus, List<ConceptAnchor> anchors) {
        return analyse(corpus, anchors, null);
    }

    private static TopicAnalysis analyse(TopicCorpusFixture corpus, List<ConceptAnchor> anchors, ScopeFence fence) {
        LiveTopicTree tree = new LiveOutlineProjectionBuilder().discoverTopicTree(corpus.passages, corpus.vectors,
                ClusteringMonitor.NONE);
        return new TopicAnalyzer(TopicAnalysisSettings.defaults()).analyze(corpus.analysisPassages,
                corpus.vectors, tree, anchors, fence, ClusteringMonitor.NONE);
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

    @Test
    public void manyCopiesFromManySourcesAreConfirmationButNotCoverage() {
        TopicCorpusFixture corpus = new TopicCorpusFixture();
        int copies = corpus.redundant("copies", 20, 10);
        corpus.plain("other", 6, 3, -1);
        TopicAnalysis analysis = analyse(corpus, Collections.singletonList(
                new ConceptAnchor("c-copies", "Copies", "Book > Copies", 1, "", corpus.query(copies))));

        TopicProfile topic = topicNamed(corpus, analysis, "copies");
        assertFalse("one distinct statement is too little material", topic.isMaterialCovered());
        assertTrue("ten sources confirm it", topic.isSourceConfirmed());
        assertFalse(topic.isSignificant());

        ConceptCoverage coverage = analysis.coverage("c-copies");
        assertEquals(20, coverage.getPassageIds().size());
        assertEquals(1, coverage.getDistinctPassageCount());
        assertFalse(coverage.isMaterialCovered());
        assertTrue(coverage.isSourceConfirmed());
        assertEquals("twenty near duplicates never make a node COVERED", ConceptCoverage.Status.THIN,
                coverage.getStatus());
    }

    @Test
    public void pagesOfOneSiteConfirmOnlyOnce() {
        TopicCorpusFixture corpus = new TopicCorpusFixture();
        int base = corpus.plain("site", 6, 3, -1);
        List<AnalysisPassage> oneDomain = new ArrayList<AnalysisPassage>();
        for (AnalysisPassage passage : corpus.analysisPassages) {
            oneDomain.add(new AnalysisPassage(passage.getPassageId(), passage.getSourceId(), "same.example",
                    passage.getText()));
        }
        LiveTopicTree tree = new LiveOutlineProjectionBuilder().discoverTopicTree(corpus.passages, corpus.vectors,
                ClusteringMonitor.NONE);
        TopicAnalysis analysis = new TopicAnalyzer(TopicAnalysisSettings.defaults()).analyze(oneDomain,
                corpus.vectors, tree, Collections.singletonList(
                        new ConceptAnchor("c", "Site", "Book > Site", 1, "", corpus.query(base))), null,
                ClusteringMonitor.NONE);
        ConceptCoverage coverage = analysis.coverage("c");
        assertTrue(coverage.isMaterialCovered());
        assertEquals(3, coverage.getSourceCount());
        assertEquals(1, coverage.getDomainCount());
        assertFalse("three pages of one site are one confirmation", coverage.isSourceConfirmed());
        assertEquals(ConceptCoverage.Status.THIN, coverage.getStatus());
    }

    @Test
    public void evidenceConcentratedInOneChildLeavesTheParentThin() {
        TopicCorpusFixture corpus = new TopicCorpusFixture();
        int watches = corpus.plain("smartwatches", 25, 10, -1);
        int helmets = corpus.plain("helmets", 3, 2, -1);
        int vests = corpus.direction();
        int parent = corpus.direction();
        List<ConceptAnchor> anchors = Arrays.asList(
                new ConceptAnchor("c-wear", "Wearables", "Book > Wearables", 1, "", corpus.query(parent)),
                new ConceptAnchor("c-watch", "Smartwatches", "Book > Wearables > Smartwatches", 2, "c-wear",
                        corpus.query(watches)),
                new ConceptAnchor("c-helmet", "Helmets", "Book > Wearables > Helmets", 2, "c-wear",
                        corpus.query(helmets)),
                new ConceptAnchor("c-vest", "Vests", "Book > Wearables > Vests", 2, "c-wear", corpus.query(vests)));
        TopicAnalysis analysis = analyse(corpus, anchors);

        ConceptCoverage wearables = analysis.coverage("c-wear");
        assertEquals(28, wearables.getDistinctPassageCount());
        assertTrue("quality alone would pass", wearables.isMaterialCovered() && wearables.isSourceConfirmed());
        assertEquals(3, wearables.getChildCount());
        assertEquals(2, wearables.getCoveredChildCount());
        assertTrue("two of three children are covered", wearables.isBroad());
        assertEquals(25.0 / 28, wearables.getDominantChildShare(), 1e-9);
        assertFalse("smartwatches hold most of the material", wearables.isBalanced());
        assertEquals(ConceptCoverage.Status.THIN, wearables.getStatus());
        assertEquals(ConceptCoverage.Status.GAP, analysis.coverage("c-vest").getStatus());
    }

    @Test
    public void evidenceSpreadOverTheChildrenCoversTheParent() {
        TopicCorpusFixture corpus = new TopicCorpusFixture();
        int watches = corpus.plain("smartwatches", 6, 3, -1);
        int helmets = corpus.plain("helmets", 6, 3, -1);
        int vests = corpus.plain("vests", 6, 3, -1);
        int parent = corpus.direction();
        List<ConceptAnchor> anchors = Arrays.asList(
                new ConceptAnchor("c-wear", "Wearables", "Book > Wearables", 1, "", corpus.query(parent)),
                new ConceptAnchor("c-watch", "Smartwatches", "Book > Wearables > Smartwatches", 2, "c-wear",
                        corpus.query(watches)),
                new ConceptAnchor("c-helmet", "Helmets", "Book > Wearables > Helmets", 2, "c-wear",
                        corpus.query(helmets)),
                new ConceptAnchor("c-vest", "Vests", "Book > Wearables > Vests", 2, "c-wear", corpus.query(vests)));
        ConceptCoverage wearables = analyse(corpus, anchors).coverage("c-wear");
        assertTrue(wearables.isBroad());
        assertTrue(wearables.isBalanced());
        assertEquals(3, wearables.getTopicCount());
        assertEquals(ConceptCoverage.Status.COVERED, wearables.getStatus());
    }

    @Test
    public void theFenceIsASeparateReadingAndANovelTopicIsNotOutByItself() {
        TopicCorpusFixture corpus = new TopicCorpusFixture();
        int inside = corpus.plain("inside", 6, 3, -1);
        int outside = corpus.plain("outside", 6, 3, -1);
        corpus.plain("unknown", 6, 3, -1);
        Map<String, String> labels = new LinkedHashMap<String, String>();
        labels.put("in-1", "Site safety");
        labels.put("out-1", "Consumer fitness");
        ScopeFence fence = new ScopeFence(Arrays.asList(
                new ScopeFenceEvaluator.AnchorVector("in-1", ScopeAnchor.Membership.IN,
                        corpus.query(inside).getValues()),
                new ScopeFenceEvaluator.AnchorVector("out-1", ScopeAnchor.Membership.OUT,
                        corpus.query(outside).getValues())),
                labels, new ScopeFenceEvaluator.Thresholds(0.7, 0.05), 4L, "fp");
        TopicAnalysis analysis = analyse(corpus, Collections.singletonList(
                new ConceptAnchor("c-in", "Inside", "Book > Inside", 1, "", corpus.query(inside))), fence);

        ScopeAlignment in = topicNamed(corpus, analysis, "inside").getScopeAlignment();
        assertEquals(ScopeAlignment.Verdict.IN, in.getVerdict());
        assertEquals("Site safety", in.getNearestInLabel());
        TopicProfile out = topicNamed(corpus, analysis, "outside");
        assertEquals(ScopeAlignment.Verdict.OUT, out.getScopeAlignment().getVerdict());
        assertEquals("Consumer fitness", out.getScopeAlignment().getNearestOutLabel());
        TopicProfile unknown = topicNamed(corpus, analysis, "unknown");
        assertTrue("new to the concept", unknown.isNovel());
        assertEquals("but not outside the fence", ScopeAlignment.Verdict.UNCLASSIFIED,
                unknown.getScopeAlignment().getVerdict());
        assertEquals(ScopeFenceEvaluator.Hint.NOVEL, unknown.getScopeAlignment().getHint());
    }

    @Test
    public void anchorsOfAnotherVectorWorldGiveNoReading() {
        TopicCorpusFixture corpus = new TopicCorpusFixture();
        corpus.plain("topic", 6, 3, -1);
        ScopeFence fence = new ScopeFence(Collections.singletonList(new ScopeFenceEvaluator.AnchorVector("in-1",
                ScopeAnchor.Membership.IN, new float[]{1f, 0f, 0f})), null,
                new ScopeFenceEvaluator.Thresholds(0.7, 0.05), 1L, "fp");
        TopicProfile topic = topicNamed(corpus, analyse(corpus, Collections.<ConceptAnchor>emptyList(), fence),
                "topic");
        assertEquals(ScopeAlignment.Verdict.UNCLASSIFIED, topic.getScopeAlignment().getVerdict());
        assertEquals(null, topic.getScopeAlignment().getHint());
    }

    @Test
    public void anchorsOfAnotherEmbeddingRoleGiveNoReadingEvenWithTheSameDimension() {
        TopicCorpusFixture corpus = new TopicCorpusFixture();
        int inside = corpus.plain("inside", 6, 3, -1);
        // same model and dimension, but embedded raw instead of as documents (a prompted model's other role)
        ScopeFence fence = new ScopeFence(Collections.singletonList(new ScopeFenceEvaluator.AnchorVector("in-1",
                ScopeAnchor.Membership.IN, corpus.query(inside).getValues())), null,
                new ScopeFenceEvaluator.Thresholds(0.7, 0.05), 1L, "fp|raw");
        TopicProfile topic = topicNamed(corpus, analyse(corpus, Collections.<ConceptAnchor>emptyList(), fence),
                "inside");
        assertEquals(ScopeAlignment.Verdict.UNCLASSIFIED, topic.getScopeAlignment().getVerdict());
        assertEquals(null, topic.getScopeAlignment().getHint());
    }

    @Test
    public void theRepresentativeSettingReachesTheTopicDiscovery() {
        TopicCorpusFixture corpus = new TopicCorpusFixture();
        corpus.diverse("broad", 4, 5, 6);
        LiveOutlineProjectionBuilder defaults = new LiveOutlineProjectionBuilder();
        LiveTopicTree tree = defaults.withRepresentatives(4).discoverTopicTree(corpus.passages, corpus.vectors,
                ClusteringMonitor.NONE);
        assertEquals(4, tree.getTopics().get(0).getRepresentativePassageIds().size());
        assertTrue("the default count is the same builder", defaults.withRepresentatives(2) == defaults);
    }
}
