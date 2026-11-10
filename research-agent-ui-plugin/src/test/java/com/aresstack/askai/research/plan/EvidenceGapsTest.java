package com.aresstack.askai.research.plan;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

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
}
