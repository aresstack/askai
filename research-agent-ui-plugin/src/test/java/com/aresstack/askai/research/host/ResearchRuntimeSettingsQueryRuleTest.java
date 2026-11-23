package com.aresstack.askai.research.host;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Component 4: the old similarity rule for queries is off unless a comparison switches it on. */
public class ResearchRuntimeSettingsQueryRuleTest {

    @Test
    public void theOldSimilarityRuleIsOffByDefault() {
        assertFalse(ResearchRuntimeSettings.loadPlanQueryNeedsConceptNearness(null));
        assertFalse(ResearchRuntimeSettings.loadPlanQueryNeedsConceptNearness(
                new ResearchRuntimeSettingsWriterTest.MemoryStore()));
    }

    @Test
    public void aComparisonCanSwitchItOn() {
        ResearchRuntimeSettingsWriterTest.MemoryStore store = new ResearchRuntimeSettingsWriterTest.MemoryStore();
        store.putBoolean(ResearchRuntimeSettings.KEY_PLAN_QUERY_NEEDS_CONCEPT_NEARNESS, true);
        assertTrue(ResearchRuntimeSettings.loadPlanQueryNeedsConceptNearness(store));
        store.putBoolean(ResearchRuntimeSettings.KEY_PLAN_QUERY_NEEDS_CONCEPT_NEARNESS, false);
        assertFalse(ResearchRuntimeSettings.loadPlanQueryNeedsConceptNearness(store));
    }
}
