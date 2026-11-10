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
