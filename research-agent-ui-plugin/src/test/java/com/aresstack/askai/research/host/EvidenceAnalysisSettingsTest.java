package com.aresstack.askai.research.host;

import com.aresstack.askai.plugin.api.service.WorkspaceStateStore;
import com.aresstack.askai.research.knowledge.topic.TopicAnalysisSettings;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;

/** D6: every knob of the evidence analysis is a stored setting with a default; inconsistent values fall back. */
public class EvidenceAnalysisSettingsTest {

    private static final class MemoryStore implements WorkspaceStateStore {
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

    @Test
    public void anEmptyStoreGivesTheDefaults() {
        EvidenceAnalysisSettings loaded = EvidenceAnalysisSettings.load(new MemoryStore());
        assertEquals(TopicAnalysisSettings.DEFAULT_MINIMUM_DOMAINS, loaded.getAnalysis().getMinimumDomains());
        assertEquals(TopicAnalysisSettings.DEFAULT_MAXIMUM_DOMINANT_SHARE,
                loaded.getAnalysis().getMaximumDominantShare(), 0.0);
        assertEquals(EvidenceAnalysisSettings.DEFAULT_MINIMUM_CORPUS, loaded.getMinimumCorpusPassages());
        assertEquals(EvidenceAnalysisSettings.DEFAULT_EVERY_NEW_PASSAGES, loaded.getEveryNewPassages());
        assertEquals(TopicAnalysisSettings.DEFAULT_SUBTOPIC_DISTANCE,
                loaded.getAnalysis().getSubtopicDistance(), 0.0);
    }

    @Test
    public void storedValuesAreRead() {
        MemoryStore store = new MemoryStore();
        ResearchRuntimeSettings.saveDouble(store, EvidenceAnalysisSettings.KEY_SUBTOPIC_DISTANCE, 0.3);
        store.putInt(EvidenceAnalysisSettings.KEY_MINIMUM_CORPUS, 20);
        store.putInt(EvidenceAnalysisSettings.KEY_EVERY_NEW_PASSAGES, 40);
        store.putInt(EvidenceAnalysisSettings.KEY_MINIMUM_DOMAINS, 3);
        ResearchRuntimeSettings.saveDouble(store, EvidenceAnalysisSettings.KEY_MINIMUM_CHILD_COVERAGE, 0.8);
        EvidenceAnalysisSettings loaded = EvidenceAnalysisSettings.load(store);
        assertEquals(0.3, loaded.getAnalysis().getSubtopicDistance(), 1e-9);
        assertEquals(20, loaded.getMinimumCorpusPassages());
        assertEquals(40, loaded.getEveryNewPassages());
        assertEquals(3, loaded.getAnalysis().getMinimumDomains());
        assertEquals(0.8, loaded.getAnalysis().getMinimumChildCoverage(), 1e-9);
    }

    @Test
    public void distancesThatDoNotGrowFallBackToTheDefaults() {
        MemoryStore store = new MemoryStore();
        ResearchRuntimeSettings.saveDouble(store, EvidenceAnalysisSettings.KEY_SUBTOPIC_DISTANCE, 0.9);
        EvidenceAnalysisSettings loaded = EvidenceAnalysisSettings.load(store);
        assertEquals(TopicAnalysisSettings.DEFAULT_SUBTOPIC_DISTANCE,
                loaded.getAnalysis().getSubtopicDistance(), 0.0);
    }
}
