package com.aresstack.askai.research.knowledge;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Ported from the enterprise AI client's ReciprocalRankFusionTest (plan section 2.5). */
public class ReciprocalRankFusionTest {

    private static final List<String> NONE = Collections.<String>emptyList();

    @Test
    public void anIdFoundByBothPathsOutranksIdsFoundByOne() {
        ReciprocalRankFusion.Result fused = new ReciprocalRankFusion().fuse(
                Arrays.asList("p1", "p2"), Arrays.asList("p3", "p2"));
        assertEquals(3, fused.getEntries().size());
        assertEquals("p2", fused.getEntries().get(0).getId());
        assertEquals(1.0 / 62 + 1.0 / 62, fused.getEntries().get(0).getScore(), 1e-12);
        assertEquals("", fused.getWarning());
    }

    @Test
    public void deduplicatesPerIdAndKeepsBothRanks() {
        ReciprocalRankFusion.Result fused = new ReciprocalRankFusion().fuse(
                Arrays.asList("p7", "p7"), Collections.singletonList("p7"));
        assertEquals(1, fused.getEntries().size());
        assertEquals(1, fused.getEntries().get(0).getKeywordRank());
        assertEquals(1, fused.getEntries().get(0).getSemanticRank());
    }

    @Test
    public void aMissingPathLeavesItsRankEmpty() {
        ReciprocalRankFusion.Result fused = new ReciprocalRankFusion().fuse(Collections.singletonList("p1"), NONE);
        assertEquals(0, fused.getEntries().get(0).getSemanticRank());
    }

    @Test
    public void onlyTheRankMattersAndTiesGoById() {
        ReciprocalRankFusion.Result fused = new ReciprocalRankFusion().fuse(
                Collections.singletonList("p2"), Collections.singletonList("p1"));
        assertEquals(fused.getEntries().get(0).getScore(), fused.getEntries().get(1).getScore(), 0.0);
        assertEquals("p1", fused.getEntries().get(0).getId());
    }

    @Test
    public void weightsShiftThePreference() {
        ReciprocalRankFusion.Result fused = new ReciprocalRankFusion(0.5, 2.0, 60).fuse(
                Collections.singletonList("p1"), Collections.singletonList("p2"));
        assertEquals("p2", fused.getEntries().get(0).getId());
        assertEquals(2.0 / 61, fused.getEntries().get(0).getScore(), 1e-12);
        assertEquals(0.5 / 61, fused.getEntries().get(1).getScore(), 1e-12);
    }

    @Test
    public void rankConstantIsConfigurable() {
        ReciprocalRankFusion.Result fused = new ReciprocalRankFusion(1.0, 1.0, 1).fuse(
                Arrays.asList("p1", "p2"), NONE);
        assertEquals(1.0 / 2, fused.getEntries().get(0).getScore(), 1e-12);
        assertEquals(1.0 / 3, fused.getEntries().get(1).getScore(), 1e-12);
    }

    @Test
    public void aFailedPathLetsTheOtherStandWithAWarning() {
        ReciprocalRankFusion.Result fused = new ReciprocalRankFusion().fuse(null, Arrays.asList("p2", "p1"));
        assertEquals(Arrays.asList("p2", "p1"), fused.ids());
        assertTrue(fused.getWarning().startsWith("keyword ranking failed"));
    }

    @Test
    public void emptyInputsGiveAnEmptyResult() {
        assertTrue(new ReciprocalRankFusion().fuse(NONE, NONE).getEntries().isEmpty());
    }

    @Test
    public void settingsRejectNonsense() {
        assertRejected(0, 0, 60);
        assertRejected(Double.NaN, 1, 60);
        assertRejected(1, 1, 0);
        assertRejected(-1, 1, 60);
    }

    private static void assertRejected(double k, double s, int c) {
        try {
            new ReciprocalRankFusion(k, s, c);
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("IllegalArgumentException expected");
    }
}
