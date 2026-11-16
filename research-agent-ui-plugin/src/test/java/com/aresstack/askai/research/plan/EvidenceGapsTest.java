package com.aresstack.askai.research.plan;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class EvidenceGapsTest {

    @Test
    public void thinAndMissingNodesBecomeGapsWithWhatIsThereAndWhatFoundNothing() {
        SearchPlanEntry nothing = SearchPlanEntry.submit("e1", "research", "a", "A", "alpha leer",
                SearchPlanEntry.Origin.BASE, 1L).started("r1", 2L).done(3L);
        EvidenceGaps.Gaps gaps = EvidenceGaps.of(SearchPlanQueueTest.analysis("s1", true, Arrays.asList(
                new EvidenceGaps.NodeEvidence("a", EvidenceGaps.Status.THIN, 3, 2, 1, Arrays.asList("x", "y")),
                new EvidenceGaps.NodeEvidence("b", EvidenceGaps.Status.MISSING, 0, 0, 0, Arrays.asList("beta")),
                new EvidenceGaps.NodeEvidence("z", EvidenceGaps.Status.THIN, 1, 1, 1, Arrays.asList("z")),
                new EvidenceGaps.NodeEvidence("c", EvidenceGaps.Status.OK, 9, 9, 4, Arrays.asList("c"))),
                Collections.<EvidenceGaps.NewTopic>emptyList()),
                Arrays.asList("a", "b", "c"), Collections.singletonList(nothing),
                Collections.<String>emptyList(), 3, 3);
        assertEquals("s1", gaps.getAnalysisId());
        assertEquals(Arrays.asList("a", "b"), new java.util.ArrayList<String>(gaps.getEvidenceByNode().keySet()));
        assertEquals(Arrays.asList("THIN: 2 distinct passages from 1 sources",
                "the material so far is about: x, y", "found nothing: alpha leer"),
                gaps.getEvidenceByNode().get("a"));
        assertEquals("a missing node names no key terms of material it does not have",
                Collections.singletonList("MISSING: no material for this node yet"), gaps.getEvidenceByNode().get("b"));
        assertEquals(Collections.singletonList("c"), gaps.getCoveredNodes());
    }

    @Test
    public void unconfirmedAndOneSidedNodesAskForOtherSourcesAndTheMissingSubTopics() {
        EvidenceGaps.Gaps gaps = EvidenceGaps.of(SearchPlanQueueTest.analysis("s4", true, Arrays.asList(
                new EvidenceGaps.NodeEvidence("a", EvidenceGaps.Status.UNCONFIRMED, 12, 10, 1, 1, null),
                new EvidenceGaps.NodeEvidence("b", EvidenceGaps.Status.ONE_SIDED, 12, 10, 4, 3, null)),
                Collections.<EvidenceGaps.NewTopic>emptyList()),
                Arrays.asList("a", "b"), Collections.<SearchPlanEntry>emptyList(),
                Collections.<String>emptyList(), 3, 3);
        assertEquals("UNCONFIRMED: 10 distinct passages but only from 1 sources on 1 domains: find other publishers "
                + "that confirm it", gaps.getEvidenceByNode().get("a").get(0));
        assertTrue(gaps.getEvidenceByNode().get("b").get(0).startsWith("ONE_SIDED: "));
    }

    @Test
    public void newTopicsOutsideTheScopeOrOnTheBlacklistAreDroppedWithTheirReason() {
        List<EvidenceGaps.NewTopic> topics = Arrays.asList(
                new EvidenceGaps.NewTopic("Small", Collections.<String>emptyList(), 2, EvidenceGaps.Scope.IN, ""),
                new EvidenceGaps.NewTopic("Big", Arrays.asList("b1"), 9, EvidenceGaps.Scope.UNCLASSIFIED, ""),
                new EvidenceGaps.NewTopic("Gaming", Collections.<String>emptyList(), 8, EvidenceGaps.Scope.OUT,
                        "Video games"),
                new EvidenceGaps.NewTopic("Crypto mining", Collections.<String>emptyList(), 7,
                        EvidenceGaps.Scope.IN, ""),
                new EvidenceGaps.NewTopic("Third", Collections.<String>emptyList(), 1, EvidenceGaps.Scope.IN, ""));
        EvidenceGaps.Gaps gaps = EvidenceGaps.of(SearchPlanQueueTest.analysis("s2", true,
                Collections.<EvidenceGaps.NodeEvidence>emptyList(), topics),
                Collections.<String>emptyList(), Collections.<SearchPlanEntry>emptyList(),
                Collections.singletonList("crypto"), 2, 3);
        assertEquals("the most distinct material first, at most the setting; unclassified is no exclusion",
                Arrays.asList("Big: b1", "Small"), gaps.getNewTopics());
        assertTrue(gaps.getDropped().contains("topic \"Gaming\" dropped: outside scope (nearest exclusion: Video games)"));
        assertTrue(gaps.getDropped().contains("topic \"Crypto mining\" dropped: blacklist \"crypto\""));
    }

    @Test
    public void noAnalysisOrAnUnsettledOneHasNoGaps() {
        assertTrue(EvidenceGaps.of(null, Collections.<String>emptyList(), Collections.<SearchPlanEntry>emptyList(),
                Collections.<String>emptyList(), 3, 3).isEmpty());
        assertEquals("", EvidenceGaps.of(SearchPlanQueueTest.analysis("s3", false,
                Collections.singletonList(new EvidenceGaps.NodeEvidence("a", EvidenceGaps.Status.MISSING, 0, 0, 0,
                        null)), Collections.<EvidenceGaps.NewTopic>emptyList()),
                Collections.singletonList("a"), Collections.<SearchPlanEntry>emptyList(),
                Collections.<String>emptyList(), 3, 3).getAnalysisId());
    }

    @Test
    public void aNodeTheAnalysisCallsOkIsWeakBelowThePlansOwnBar() {
        SearchPlanEntry vendorQuery = SearchPlanEntry.submit("e1", "research", "impl", "Implementierung",
                "Wearables Kosten Grenzen", SearchPlanEntry.Origin.BASE, 1L).started("r1", 2L).done(3L);
        CoverageQuality quality = CoverageQuality.of(16, 0.2, CoverageQualityTest.run4Preference());
        EvidenceGaps.Gaps gaps = EvidenceGaps.of(SearchPlanQueueTest.analysis("run4", true, Arrays.asList(
                new EvidenceGaps.NodeEvidence("impl", EvidenceGaps.Status.OK, 12, 11, 6, 4,
                        Arrays.asList("kosten", "akku"), CoverageQualityTest.run4Implementation()),
                new EvidenceGaps.NodeEvidence("law", EvidenceGaps.Status.OK, 30, 25, 12, 6,
                        Arrays.asList("dsgvo"), CoverageQualityTest.run4Law()),
                new EvidenceGaps.NodeEvidence("thin", EvidenceGaps.Status.THIN, 2, 2, 1, 1,
                        Arrays.asList("x"), CoverageQualityTest.run4Implementation())),
                Collections.<EvidenceGaps.NewTopic>emptyList()),
                Arrays.asList("impl", "law", "thin"), Collections.<SearchPlanEntry>emptyList(),
                Collections.<String>emptyList(), 3, 3, quality);
        assertEquals(Arrays.asList("impl", "thin"), new java.util.ArrayList<String>(gaps.getEvidenceByNode().keySet()));
        assertEquals(Collections.singletonList("impl"), gaps.getWeakNodes());
        assertEquals(Collections.singletonList("law"), gaps.getCoveredNodes());
        List<String> impl = gaps.getEvidenceByNode().get("impl");
        assertTrue(impl.get(0), impl.get(0).startsWith("WEAK: only 11 distinct passages"));
        assertTrue(impl.get(0), impl.get(0).contains("0 of 12 passages (0 %) from the preferred sources"));
        assertTrue(impl.get(0), impl.get(0).contains("site:dguv.de"));
        assertEquals("the material so far is about: kosten, akku", impl.get(1));
        assertEquals("a thin node keeps its own status line", "THIN: 2 distinct passages from 1 sources",
                gaps.getEvidenceByNode().get("thin").get(0));
        assertTrue(EvidenceGaps.of(SearchPlanQueueTest.analysis("run4", true, Collections.singletonList(
                new EvidenceGaps.NodeEvidence("impl", EvidenceGaps.Status.OK, 12, 11, 6, 4,
                        Arrays.asList("kosten"), CoverageQualityTest.run4Implementation())),
                Collections.<EvidenceGaps.NewTopic>emptyList()),
                Collections.singletonList("impl"), Collections.singletonList(vendorQuery),
                Collections.<String>emptyList(), 3, 3).getEvidenceByNode().isEmpty());
    }

    @Test
    public void withoutAnAnalysisNodesCoveredByVendorSourcesAreWeak() {
        Map<String, SearchPlanCoverage.NodeCoverage> coverage = new java.util.LinkedHashMap<String,
                SearchPlanCoverage.NodeCoverage>();
        coverage.put("impl", new SearchPlanCoverage.NodeCoverage("impl", SearchPlanCoverage.State.COVERED,
                new java.util.LinkedHashSet<String>(Arrays.asList("s1", "s2", "s3")),
                new java.util.HashSet<String>(Arrays.asList("vendor.example", "linkedin.com")), 3));
        coverage.put("law", new SearchPlanCoverage.NodeCoverage("law", SearchPlanCoverage.State.COVERED,
                new java.util.LinkedHashSet<String>(Arrays.asList("s4", "s5")),
                new java.util.HashSet<String>(Arrays.asList("dguv.de", "e-recht24.de")), 3));
        coverage.put("open", new SearchPlanCoverage.NodeCoverage("open", SearchPlanCoverage.State.EXHAUSTED,
                new java.util.LinkedHashSet<String>(), new java.util.HashSet<String>(), 3));
        final Map<String, String> domains = new java.util.HashMap<String, String>();
        domains.put("s1", "vendor.example");
        domains.put("s2", "linkedin.com");
        domains.put("s3", "vendor.example");
        domains.put("s4", "dguv.de");
        domains.put("s5", "e-recht24.de");
        SearchPlanCoverage.Domains resolver = new SearchPlanCoverage.Domains() {
            public String of(String sourceId) {
                return domains.get(sourceId);
            }
        };
        EvidenceGaps.Gaps gaps = EvidenceGaps.ofSources(coverage, resolver,
                CoverageQuality.of(16, 0.2, CoverageQualityTest.run4Preference()));
        assertEquals(EvidenceGaps.SOURCE_COUNTS, gaps.getAnalysisId());
        assertEquals(Collections.singletonList("impl"), gaps.getWeakNodes());
        assertEquals(Collections.singletonList("law"), gaps.getCoveredNodes());
        assertTrue(gaps.getEvidenceByNode().get("impl").get(0),
                gaps.getEvidenceByNode().get("impl").get(0).startsWith("WEAK: 0 of 3 sources (0 %)"));
        assertTrue("without a mission preference the source counts decide alone", EvidenceGaps.ofSources(coverage,
                resolver, CoverageQuality.of(16, 0.2, SourcePreference.NONE)).isEmpty());
    }
}
