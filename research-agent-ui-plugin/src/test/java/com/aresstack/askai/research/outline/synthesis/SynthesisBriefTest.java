package com.aresstack.askai.research.outline.synthesis;

import com.aresstack.askai.research.domain.scope.ScopeAnchor;
import com.aresstack.askai.research.domain.scope.ScopeFenceEvaluator;
import com.aresstack.askai.research.knowledge.topic.ScopeFence;
import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Phase 3b: the brief lists significant topics with short keys; OUT topics stay out, new ones stay in. */
public class SynthesisBriefTest {

    @Test
    public void keysResolveToTopicsSubtopicsAndRequirements() {
        SynthesisBrief brief = OutlineSynthesizerTest.fourAreas().brief();
        assertEquals("T1", brief.getTopics().get(0).getKey());
        assertEquals("T1.1", brief.getTopics().get(0).getSubtopics().get(0).getKey());
        assertEquals("T1", brief.topicKeyOf("T1.2"));
        assertEquals(4, brief.passagesOf("T1.1").size());
        assertEquals(8, brief.passagesOf("T1").size());
        assertEquals(5, brief.requirementKeys().size());
        assertEquals("four independent areas, four groups", 4, brief.getGroupTopicKeys().size());
        assertTrue(brief.isMaterial("T4.2"));
        assertTrue(!brief.isMaterial("C1"));
    }

    @Test
    public void aTopicOutsideTheFenceIsKeptOutAndANovelOneStaysIn() {
        SynthesisFixture corpus = new SynthesisFixture();
        int inside = corpus.plain("inside", 6, 3);
        int fitness = corpus.plain("fitness", 6, 3);
        corpus.plain("drones", 6, 3);
        corpus.concept("c-in", "Inside", inside);
        ScopeFence fence = new ScopeFence(java.util.Arrays.asList(
                new ScopeFenceEvaluator.AnchorVector("out-1", ScopeAnchor.Membership.OUT, axis(fitness)),
                new ScopeFenceEvaluator.AnchorVector("in-1", ScopeAnchor.Membership.IN, axis(inside))),
                Collections.singletonMap("out-1", "Consumer fitness"), new ScopeFenceEvaluator.Thresholds(0.7, 0.05),
                3L);
        corpus.fence = fence;

        SynthesisBrief brief = corpus.brief();

        assertEquals(1, brief.getOutOfScopeTopics().size());
        assertTrue(brief.getOutOfScopeTopics().get(0).getTitle().startsWith("fitness"));
        assertEquals("inside and the new drones topic", 2, brief.getTopics().size());
    }

    private static float[] axis(int direction) {
        float[] values = new float[SynthesisFixture.DIMENSION];
        values[direction] = 1f;
        return values;
    }
}
