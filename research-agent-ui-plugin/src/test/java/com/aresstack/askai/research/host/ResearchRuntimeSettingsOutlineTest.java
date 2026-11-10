package com.aresstack.askai.research.host;

import com.aresstack.askai.plugin.api.service.WorkspaceStateStore;
import com.aresstack.askai.research.knowledge.processing.KnowledgeProcessingSettings;
import com.aresstack.askai.research.outline.generate.OutlineGenerationInput;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;

/**
 * The outline calibration is a setting (threshold and margin per workspace store, read at each start), never a
 * code constant: the live calibration on a real corpus happens in the Settings tab, not in a release.
 */
public class ResearchRuntimeSettingsOutlineTest {

    @Test
    public void theDefaultsAreTheProvisionalOnesAndTheMinimumsComeFromTheKnowledgeSettings() {
        OutlineGenerationInput.Settings settings = ResearchRuntimeSettings.loadOutlineSettings(new MemoryStore(),
                KnowledgeProcessingSettings.defaults());
        assertEquals(OutlineGenerationInput.Settings.DEFAULT_THRESHOLD, settings.getThreshold(), 1e-9);
        assertEquals(OutlineGenerationInput.Settings.DEFAULT_MARGIN, settings.getMargin(), 1e-9);
        assertEquals(KnowledgeProcessingSettings.defaults().minimumPassagesPerTopic,
                settings.getMinimumPassagesPerTopic());
        assertEquals(KnowledgeProcessingSettings.defaults().minimumDistinctSourcesPerTopic,
                settings.getMinimumDistinctSourcesPerTopic());
    }

    @Test
    public void aStoredThresholdAndMarginWinAndACommaDecimalIsRead() {
        MemoryStore store = new MemoryStore();
        store.put(ResearchRuntimeSettings.KEY_OUTLINE_ASSIGNMENT_THRESHOLD, "0,25");
        store.put(ResearchRuntimeSettings.KEY_OUTLINE_ASSIGNMENT_MARGIN, "0.005");
        OutlineGenerationInput.Settings settings = ResearchRuntimeSettings.loadOutlineSettings(store, null);
        assertEquals(0.25, settings.getThreshold(), 1e-9);
        assertEquals(0.005, settings.getMargin(), 1e-9);
        assertEquals("no knowledge settings: the shared defaults", KnowledgeProcessingSettings.defaults()
                .minimumPassagesPerTopic, settings.getMinimumPassagesPerTopic());
    }

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
}
