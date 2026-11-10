package com.aresstack.askai.modelport;

import com.aresstack.askai.agent.model.port.ThinkingMode;
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
        assertEquals("parallel page workers need parallel model calls (plan T5)", 4,
                settings.capacityOf("gemma4:e2b"));
        assertEquals(-1L, settings.memoryBudgetBytes("ollama"));
        assertEquals("", settings.slotModel("judge"));
        assertEquals("presets of plan 4.1", 32768, settings.contextOf("gemma4:e2b"));
        assertEquals(16384, settings.contextOf("llama3.1:8b"));
        assertEquals(16384, settings.contextOf("gpt-oss:20b"));
        assertEquals(8192, settings.contextOf("some-other:7b"));
        assertEquals(ThinkingMode.DEFAULT, settings.slotThinking("judge"));
        assertEquals(0, settings.slotAnswerTokens("judge"));
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
        contexts.put("llama3.1:8b", 24576);
        Map<String, ThinkingMode> thinking = new LinkedHashMap<String, ThinkingMode>();
        thinking.put("judge", ThinkingMode.OFF);
        thinking.put("synthesis", ThinkingMode.HIGH);
        Map<String, Integer> answers = new LinkedHashMap<String, Integer>();
        answers.put("conversation", 1536);
        repository.save(new ModelPortSettings(2, Collections.singletonMap("gemma4:e2b", 4),
                Collections.singletonMap("ollama", 16384L), slotModels, contexts, thinking, answers, "30m", 120,
                7));

        ModelPortSettings loaded = new ModelPortSettingsRepository(repository.getFile()).load();
        assertEquals(2, loaded.getDefaultCapacity());
        assertEquals(4, loaded.capacityOf("gemma4:e2b"));
        assertEquals(2, loaded.capacityOf("gpt-oss:20b"));
        assertEquals(16384L * 1024L * 1024L, loaded.memoryBudgetBytes("ollama"));
        assertEquals("gpt-oss:20b", loaded.slotModel("narration"));
        assertEquals("a model name with a colon survives as a key", 24576, loaded.contextOf("llama3.1:8b"));
        assertEquals(32768, loaded.contextOf("gemma4:e2b"));
        assertEquals(ThinkingMode.OFF, loaded.slotThinking("judge"));
        assertEquals(ThinkingMode.HIGH, loaded.slotThinking("synthesis"));
        assertEquals(1536, loaded.slotAnswerTokens("conversation"));
        assertEquals("30m", loaded.getKeepAlive());
        assertEquals(120, loaded.getImplicitWaitSeconds());
        assertEquals(7, loaded.getResidencyRefreshSeconds());
        assertEquals("the extra slot is listed after the defaults", "narration",
                loaded.knownSlotNames().get(ModelPortSettings.DEFAULT_SLOTS.size()));

        repository.save(new ModelPortSettings(3, null, null, null, null, null, null, null, 600, 5));
        assertEquals("a save is visible to the next read at once", 3, repository.load().getDefaultCapacity());
    }

    @Test
    public void theOldShippedDefaultOfOneIsMigratedButAChosenOneIsKept() throws Exception {
        File file = new File(folder.getRoot(), "model-port.properties");
        java.nio.file.Files.write(file.toPath(), "capacity.default=1\n".getBytes("UTF-8"));
        assertEquals("a file of the old default carries 1 without a revision: the old default, not a choice",
                ModelPortSettings.DEFAULT_CAPACITY, new ModelPortSettingsRepository(file).load().getDefaultCapacity());

        ModelPortSettingsRepository repository = new ModelPortSettingsRepository(file);
        repository.save(new ModelPortSettings(1, null, null, null, null, null, null, null, 600, 5));
        assertEquals("1 saved with the current revision is the user's choice", 1,
                new ModelPortSettingsRepository(file).load().getDefaultCapacity());
    }
}
