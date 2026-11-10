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
