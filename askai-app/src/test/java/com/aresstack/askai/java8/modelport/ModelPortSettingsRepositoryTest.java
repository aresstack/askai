package com.aresstack.askai.java8.modelport;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;

/** The model-port settings: defaults without a file, a full round trip, and a save visible at once. */
public class ModelPortSettingsRepositoryTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void withoutAFileEverythingIsTheDocumentedDefault() {
        ModelPortSettings settings = new ModelPortSettingsRepository(
                new File(folder.getRoot(), "absent.properties")).load();
        assertEquals(1, settings.capacityOf("gemma4:e2b"));
        assertEquals(-1L, settings.memoryBudgetBytes("ollama"));
        assertEquals("", settings.slotModel("judge"));
        assertEquals(0, settings.slotContextTokens("judge"));
        assertEquals("5m", settings.getKeepAlive());
        assertEquals(ModelPortSettings.DEFAULT_SLOTS, settings.knownSlotNames());
    }

    @Test
    public void everySettingSurvivesARoundTrip() throws Exception {
        ModelPortSettingsRepository repository = new ModelPortSettingsRepository(
                new File(folder.getRoot(), "model-port.properties"));
        Map<String, String> slotModels = new LinkedHashMap<String, String>();
        slotModels.put("judge", "gemma4:e2b");
        slotModels.put("narration", "gpt-oss:20b");
        Map<String, Integer> contexts = new LinkedHashMap<String, Integer>();
        contexts.put("judge", 4096);
        repository.save(new ModelPortSettings(2, Collections.singletonMap("gemma4:e2b", 4),
                Collections.singletonMap("ollama", 16384L), slotModels, contexts, "30m", 120, 7));

        ModelPortSettings loaded = new ModelPortSettingsRepository(repository.getFile()).load();
        assertEquals(2, loaded.getDefaultCapacity());
        assertEquals(4, loaded.capacityOf("gemma4:e2b"));
        assertEquals(2, loaded.capacityOf("gpt-oss:20b"));
        assertEquals(16384L * 1024L * 1024L, loaded.memoryBudgetBytes("ollama"));
        assertEquals("gpt-oss:20b", loaded.slotModel("narration"));
        assertEquals(4096, loaded.slotContextTokens("judge"));
        assertEquals("30m", loaded.getKeepAlive());
        assertEquals(120, loaded.getImplicitWaitSeconds());
        assertEquals(7, loaded.getResidencyRefreshSeconds());
        assertEquals("the extra slot is listed after the defaults", "narration",
                loaded.knownSlotNames().get(ModelPortSettings.DEFAULT_SLOTS.size()));

        repository.save(new ModelPortSettings(3, null, null, null, null, null, 600, 5));
        assertEquals("a save is visible to the next read at once", 3, repository.load().getDefaultCapacity());
    }
}
