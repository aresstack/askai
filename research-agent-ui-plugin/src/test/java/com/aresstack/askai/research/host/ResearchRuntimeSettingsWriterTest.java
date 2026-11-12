package com.aresstack.askai.research.host;

import com.aresstack.askai.plugin.api.service.WorkspaceStateStore;
import com.aresstack.askai.research.runtime.writer.ProsePassageFilter;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;

/** The Document writer's thresholds are settings with defaults, read from the workspace store. */
public class ResearchRuntimeSettingsWriterTest {

    @Test
    public void theProseCriteriaDefaultToTheFilterDefaults() {
        assertEquals(ProsePassageFilter.Criteria.defaults(),
                ResearchRuntimeSettings.loadWriterProseCriteria(new MemoryStore()));
    }

    @Test
    public void storedProseCriteriaWin() {
        MemoryStore store = new MemoryStore();
        store.put(ResearchRuntimeSettings.KEY_WRITER_PROSE_MIN_WORDS, "12");
        store.put(ResearchRuntimeSettings.KEY_WRITER_PROSE_MIN_SENTENCES, "0");
        store.put(ResearchRuntimeSettings.KEY_WRITER_PROSE_MAX_RUN, "40");
        assertEquals(new ProsePassageFilter.Criteria(12, 0, 40),
                ResearchRuntimeSettings.loadWriterProseCriteria(store));
    }

    @Test
    public void theCarryCriteriaDefaultAndAStoredOverlapWins() {
        assertEquals(com.aresstack.askai.research.runtime.writer.ClaimCarryCheck.Criteria.defaults(),
                ResearchRuntimeSettings.loadWriterCarryCriteria(new MemoryStore()));
        MemoryStore store = new MemoryStore();
        store.put(ResearchRuntimeSettings.KEY_WRITER_CARRY_MIN_OVERLAP, "0,45");
        store.put(ResearchRuntimeSettings.KEY_WRITER_PROSE_MIN_WORDS, "12");
        com.aresstack.askai.research.runtime.writer.ClaimCarryCheck.Criteria c =
                ResearchRuntimeSettings.loadWriterCarryCriteria(store);
        assertEquals(0.45, c.getMinOverlap(), 1e-9);
        assertEquals("the prose criteria are the evidence pack's", 12, c.getProse().getMinWords());
        store.put(ResearchRuntimeSettings.KEY_WRITER_CARRY_MIN_OVERLAP, "7");
        assertEquals("out of range: the default", 0.3,
                ResearchRuntimeSettings.loadWriterCarryCriteria(store).getMinOverlap(), 1e-9);
    }

    @Test
    public void theWriterTargetsDefaultToThePresetAndThreePartsForASectionOf1100Words() {
        com.aresstack.askai.research.drafting.EvidenceAssignments.Targets t =
                ResearchRuntimeSettings.loadWriterTargets(new MemoryStore());
        assertEquals(450, t.getWordsPerCall());
        assertEquals(3072, t.getAnswerTokens());
        assertEquals(8, t.getPack().getPassageCount());
        assertEquals(300, t.getPack().getPassageTokens());
        assertEquals(2, t.getPack().getPassagesPerSource());
        assertEquals("brief relevance calibrated for bge-m3",
                com.aresstack.askai.research.writer.WriterPackSettings.BriefRelevance.defaults(),
                t.getPack().getRelevance());
    }

    @Test
    public void storedBriefRelevanceWinsAndOutOfRangeFallsBack() {
        MemoryStore store = new MemoryStore();
        store.put(ResearchRuntimeSettings.KEY_WRITER_MIN_BRIEF_SIMILARITY, "0,6");
        store.put(ResearchRuntimeSettings.KEY_WRITER_BRIEF_SIMILARITY_MARGIN, "0");
        com.aresstack.askai.research.writer.WriterPackSettings.BriefRelevance r =
                ResearchRuntimeSettings.loadWriterTargets(store).getPack().getRelevance();
        assertEquals(0.6, r.getMinSimilarity(), 1e-9);
        assertEquals("0 switches the margin off", 0, r.getMargin(), 1e-9);
        store.put(ResearchRuntimeSettings.KEY_WRITER_MIN_BRIEF_SIMILARITY, "1.5");
        store.put(ResearchRuntimeSettings.KEY_WRITER_BRIEF_SIMILARITY_MARGIN, "-1");
        assertEquals(com.aresstack.askai.research.writer.WriterPackSettings.BriefRelevance.defaults(),
                ResearchRuntimeSettings.loadWriterBriefRelevance(store));
    }

    @Test
    public void storedWriterTargetsWin() {
        MemoryStore store = new MemoryStore();
        store.put(ResearchRuntimeSettings.KEY_WRITER_WORDS_PER_CALL, "900");
        store.put(ResearchRuntimeSettings.KEY_WRITER_ANSWER_TOKENS, "2500");
        store.put(ResearchRuntimeSettings.KEY_WRITER_PASSAGES, "14");
        store.put(ResearchRuntimeSettings.KEY_WRITER_PASSAGE_TOKENS, "5");
        store.put(ResearchRuntimeSettings.KEY_WRITER_PASSAGES_PER_SOURCE, "3");
        com.aresstack.askai.research.drafting.EvidenceAssignments.Targets t =
                ResearchRuntimeSettings.loadWriterTargets(store);
        assertEquals(900, t.getWordsPerCall());
        assertEquals(2500, t.getAnswerTokens());
        assertEquals(14, t.getPack().getPassageCount());
        assertEquals("below the minimum: the default", 300, t.getPack().getPassageTokens());
        assertEquals(3, t.getPack().getPassagesPerSource());
    }

    @Test
    public void theTopicDriftCriteriaDefaultAndFollowTheStore() {
        assertEquals(com.aresstack.askai.research.assembly.TopicDriftCheck.Criteria.defaults(),
                ResearchRuntimeSettings.loadTopicDriftCriteria(new MemoryStore()));
        MemoryStore store = new MemoryStore();
        store.put(ResearchRuntimeSettings.KEY_DRIFT_MIN_SENTENCES, "3");
        store.putBoolean(ResearchRuntimeSettings.KEY_DRIFT_NOUNS_ONLY, false);
        assertEquals(new com.aresstack.askai.research.assembly.TopicDriftCheck.Criteria(10, 3, 5, false),
                ResearchRuntimeSettings.loadTopicDriftCriteria(store));
    }

    static final class MemoryStore implements WorkspaceStateStore {
        final Map<String, String> values = new HashMap<String, String>();

        public String get(String key, String defaultValue) {
            return values.containsKey(key) ? values.get(key) : defaultValue;
        }

        public boolean getBoolean(String key, boolean defaultValue) {
            return values.containsKey(key) ? Boolean.parseBoolean(values.get(key)) : defaultValue;
        }

        public int getInt(String key, int defaultValue) {
            try {
                return values.containsKey(key) ? Integer.parseInt(values.get(key)) : defaultValue;
            } catch (NumberFormatException invalid) {
                return defaultValue;
            }
        }

        public void put(String key, String value) {
            values.put(key, value);
        }

        public void putBoolean(String key, boolean value) {
            values.put(key, String.valueOf(value));
        }

        public void putInt(String key, int value) {
            values.put(key, String.valueOf(value));
        }
    }
}
