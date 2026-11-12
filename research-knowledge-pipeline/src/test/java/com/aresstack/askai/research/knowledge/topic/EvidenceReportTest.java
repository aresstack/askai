package com.aresstack.askai.research.knowledge.topic;

import com.aresstack.askai.research.knowledge.ClusteringMonitor;
import com.aresstack.askai.research.knowledge.live.LiveOutlineProjectionBuilder;
import com.aresstack.askai.research.knowledge.live.LiveTopicTree;
import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Phase 2/3: the evidence report tells the research what is missing, thin, redundant or new, with key terms. */
public class EvidenceReportTest {

    @Test
    public void everyConceptNodeGetsAStatusAndUncoveredMaterialBecomesAResearchArea() {
        TopicCorpusFixture corpus = new TopicCorpusFixture();
        int helmets = corpus.plain("helmets", 8, 4, -1);
        int gloves = corpus.redundant("gloves", 12, 6);
        int boots = corpus.plain("boots", 2, 1, -1);
        int vests = corpus.plain("vests", 5, 1, -1);
        int missing = corpus.direction();
        corpus.plain("drones", 9, 3, -1);

        List<ConceptAnchor> anchors = Arrays.asList(
                new ConceptAnchor("n-helmets", "Helmets", "Book > Helmets", 1, "", corpus.query(helmets)),
                new ConceptAnchor("n-gloves", "Gloves", "Book > Gloves", 1, "", corpus.query(gloves)),
                new ConceptAnchor("n-boots", "Boots", "Book > Boots", 1, "", corpus.query(boots)),
                new ConceptAnchor("n-vests", "Vests", "Book > Vests", 1, "", corpus.query(vests)),
                new ConceptAnchor("n-exo", "Exoskeletons", "Book > Exoskeletons", 1, "", corpus.query(missing)));
        LiveTopicTree tree = new LiveOutlineProjectionBuilder().discoverTopicTree(corpus.passages, corpus.vectors,
                ClusteringMonitor.NONE);
        TopicAnalysis analysis = new TopicAnalyzer(TopicAnalysisSettings.defaults()).analyze(
                corpus.analysisPassages, corpus.vectors, tree, anchors, null, ClusteringMonitor.NONE);
        Map<String, String> texts = new LinkedHashMap<String, String>();
        for (AnalysisPassage passage : corpus.analysisPassages) {
            texts.put(passage.getPassageId(), passage.getText());
        }

        EvidenceReport report = EvidenceReport.of(analysis, texts, 3);

        assertEquals(5, report.getNodes().size());
        EvidenceReport.NodeEvidence helmetNode = node(report, "n-helmets");
        assertEquals(EvidenceReport.Status.OK, helmetNode.getStatus());
        assertEquals(8, helmetNode.getDistinctPassages());
        assertEquals(4, helmetNode.getSources());
        assertEquals("the node's own word ranks first", "helmets", helmetNode.getKeyTerms().get(0));
        assertEquals(3, helmetNode.getKeyTerms().size());

        EvidenceReport.NodeEvidence gloveNode = node(report, "n-gloves");
        assertEquals("twelve copies from six sources are no breadth", EvidenceReport.Status.REDUNDANT,
                gloveNode.getStatus());
        assertEquals(12, gloveNode.getPassages());
        assertEquals(1, gloveNode.getDistinctPassages());

        assertEquals("two passages of one source", EvidenceReport.Status.THIN, node(report, "n-boots").getStatus());
        EvidenceReport.NodeEvidence vestNode = node(report, "n-vests");
        assertEquals("five distinct passages from one site", EvidenceReport.Status.UNCONFIRMED,
                vestNode.getStatus());
        assertEquals(1, vestNode.getDomains());

        EvidenceReport.NodeEvidence exo = node(report, "n-exo");
        assertEquals(EvidenceReport.Status.MISSING, exo.getStatus());
        assertEquals("no material: the label words seed the follow-up query", Arrays.asList("Book", "Exoskeletons"),
                exo.getKeyTerms());

        assertEquals("only the drones topic is new", 1, report.getNovelAreas().size());
        EvidenceReport.ResearchArea area = report.getNovelAreas().get(0);
        assertEquals(9, area.getDistinctPassages());
        assertEquals(3, area.getSources());
        assertEquals("drones", area.getKeyTerms().get(0));
        assertEquals("no fence: unclassified, never OUT", ScopeAlignment.Verdict.UNCLASSIFIED,
                area.getScopeAlignment().getVerdict());
    }

    @Test
    public void keyTermsPreferWordsThatSetTheMaterialApart() {
        KeyTerms terms = new KeyTerms(Arrays.asList("Safety helmets protect heads on sites",
                "Safety gloves protect hands on sites", "Safety boots protect feet on sites 2024"));
        List<String> best = terms.of(Arrays.asList("Safety helmets protect heads on sites"), 2);
        assertEquals(Arrays.asList("heads", "helmets"), best);
        assertTrue("short words and numbers are no terms", !KeyTerms.terms("on 2024 sites").contains("2024"));
        assertEquals(Arrays.asList("sites"), KeyTerms.terms("on 2024 sites"));
        assertEquals(Arrays.asList("Book", "Safety", "Helmets"), KeyTerms.labelWords("Book > Safety > Helmets"));
    }

    /**
     * Live run 4: in a topic of 1681 passages the key terms were "werden, oder, eine". A word every topic uses alike
     * is no key term however often it occurs; a set holding the whole corpus has none.
     */
    @Test
    public void wordsSpreadEvenlyOverTheCorpusAreNoKeyTerms() {
        List<String> corpus = Arrays.asList(
                "eine Sensoren eine Daten eine werden eine oder",
                "eine Sensoren eine Akku eine werden eine oder",
                "eine Datenschutz eine Daten eine werden eine oder",
                "eine Datenschutz eine Recht eine werden eine oder");
        KeyTerms terms = new KeyTerms(corpus);
        List<String> best = terms.of(corpus.subList(0, 2), 5);
        assertEquals("\"daten\" is as common in the set as in the corpus", Arrays.asList("sensoren", "akku"), best);
        assertTrue("no word sets the whole corpus apart", terms.of(corpus, 5).isEmpty());
    }

    private static EvidenceReport.NodeEvidence node(EvidenceReport report, String id) {
        for (EvidenceReport.NodeEvidence node : report.getNodes()) {
            if (node.getAnchorId().equals(id)) {
                return node;
            }
        }
        throw new AssertionError("no node " + id);
    }
}
