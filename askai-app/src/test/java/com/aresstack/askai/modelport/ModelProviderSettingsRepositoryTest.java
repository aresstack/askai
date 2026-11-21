package com.aresstack.askai.modelport;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The provider settings: Ollama when nothing is saved, a round trip of the API settings with a model per task, the
 * API key stored only encrypted (never in the file's text or in {@code toString}), the environment variable as the
 * fallback, and the inheritance of the API's slot models.
 */
public class ModelProviderSettingsRepositoryTest {

    private static final String KEY = "sk-live-secret-0123456789";

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private final Map<String, String> environment = new HashMap<String, String>();

    private ModelProviderSettingsRepository repository() {
        File file = new File(folder.getRoot(), ModelProviderSettingsRepository.FILE_NAME);
        return new ModelProviderSettingsRepository(file, new ApiKeyVault(new File(folder.getRoot(),
                ModelProviderSettingsRepository.KEY_FILE_NAME)), environment::get);
    }

    private static ModelProviderSettings api(String baseUrl, Map<String, String> slotModels) {
        return new ModelProviderSettings(ModelProviderKind.OPENAI_COMPATIBLE, baseUrl, "", 12, slotModels, 65536,
                Collections.singletonMap("openai/gpt-oss-120b", 131072), 10, 900, 3);
    }

    @Test
    public void withoutAFileOllamaFillsTheSlots() throws IOException {
        ModelProviderSettings settings = repository().load();

        assertEquals(ModelProviderKind.OLLAMA, settings.getProvider());
        assertFalse(settings.usesApi());
        assertEquals("", settings.getApiBaseUrl());
        assertFalse(settings.hasStoredApiKey());
        assertEquals("several calls per model by default once the API is chosen",
                ModelProviderSettings.DEFAULT_API_CAPACITY, settings.getApiCapacity());
        assertNull("no key anywhere", repository().apiKey());
    }

    @Test
    public void theApiSettingsAndAModelPerTaskSurviveASave() throws IOException {
        Map<String, String> models = new LinkedHashMap<String, String>();
        models.put(ModelPortSettings.DEFAULT_SLOT, "openai/gpt-oss-120b");
        models.put("phase.draft.writer", "gemma-4-31b");
        models.put("judge", " ");
        ModelProviderSettingsRepository repository = repository();

        repository.save(api("https://models.example.test/v1", models));
        ModelProviderSettings loaded = repository().load();

        assertTrue(loaded.usesApi());
        assertEquals("https://models.example.test/v1", loaded.getApiBaseUrl());
        assertEquals(12, loaded.getApiCapacity());
        assertEquals(65536, loaded.getApiContextTokens());
        assertEquals(131072, loaded.apiContextOf("openai/gpt-oss-120b"));
        assertEquals(65536, loaded.apiContextOf("gemma-4-31b"));
        assertEquals(10, loaded.getConnectTimeoutSeconds());
        assertEquals(900, loaded.getReadTimeoutSeconds());
        assertEquals(3, loaded.getRetries());
        assertEquals("gemma-4-31b", loaded.apiSlotModel("phase.draft.writer"));
        assertFalse("a blank model is not stored", loaded.getApiSlotModels().containsKey("judge"));
    }

    @Test
    public void aSlotInheritsItsParentsThenTheDefaultApiModel() {
        Map<String, String> models = new LinkedHashMap<String, String>();
        models.put(ModelPortSettings.DEFAULT_SLOT, "openai/gpt-oss-120b");
        models.put("phase.draft", "gemma-4-31b");
        ModelProviderSettings settings = api("https://x/v1", models);

        assertEquals("gemma-4-31b", settings.resolveApiModel("phase.draft.writer"));
        assertEquals("openai/gpt-oss-120b", settings.resolveApiModel("phase.outline.labels"));
        assertEquals("the API's model replaces Ollama's while the API is chosen", "gemma-4-31b",
                settings.resolveModel("phase.draft.writer", ModelPortSettings.defaults()));
        assertEquals("with Ollama chosen the Ollama slot models apply",
                ModelPortSettings.defaults().resolveModel("phase.draft.writer"),
                settings.withProvider(ModelProviderKind.OLLAMA).resolveModel("phase.draft.writer",
                        ModelPortSettings.defaults()));
        assertEquals("nothing is shipped for the API", "",
                api("https://x/v1", null).resolveApiModel("phase.draft.writer"));
    }

    @Test
    public void theApiKeyIsStoredOnlyEncryptedAndReadBack() throws IOException {
        ModelProviderSettingsRepository repository = repository();
        repository.save(api("https://x/v1", null));

        repository.storeApiKey(KEY.toCharArray());

        String fileText = new String(Files.readAllBytes(repository.getFile().toPath()), StandardCharsets.UTF_8);
        assertFalse("the key is never in the settings file's text", fileText.contains(KEY));
        assertTrue(fileText, fileText.contains("openai.apiKey=v1\\:"));
        assertArrayEquals(KEY.toCharArray(), repository().apiKey());
        ModelProviderSettings loaded = repository().load();
        assertTrue(loaded.hasStoredApiKey());
        assertFalse("toString never shows the key", loaded.toString().contains(KEY));
        assertFalse(loaded.toString().contains(loaded.getEncryptedApiKey()));
        assertTrue("other settings stay", loaded.usesApi());
    }

    @Test
    public void withoutAStoredKeyTheEnvironmentVariableIsRead() throws IOException {
        environment.put(ModelProviderSettings.API_KEY_ENVIRONMENT_VARIABLE, "  " + KEY + "  ");
        ModelProviderSettingsRepository repository = repository();

        assertArrayEquals(KEY.toCharArray(), repository.apiKey());

        repository.storeApiKey("sk-stored".toCharArray());
        assertArrayEquals("a stored key wins", "sk-stored".toCharArray(), repository.apiKey());
        repository.removeApiKey();
        assertArrayEquals("removing the stored key falls back to the variable", KEY.toCharArray(),
                repository.apiKey());
        assertFalse(repository.load().hasStoredApiKey());
    }

    @Test
    public void aStoredKeyWithoutItsKeyFileAsksToEnterItAgain() throws IOException {
        ModelProviderSettingsRepository repository = repository();
        repository.storeApiKey(KEY.toCharArray());
        assertTrue(new File(folder.getRoot(), ModelProviderSettingsRepository.KEY_FILE_NAME).delete());

        try {
            repository().apiKey();
            fail("a key without its key file cannot be read");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("enter the API key again"));
            assertFalse(expected.getMessage().contains(KEY));
        }
    }

    @Test
    public void theVaultRefusesABlankKeyAndForeignValues() throws IOException {
        ApiKeyVault vault = new ApiKeyVault(new File(folder.getRoot(), "vault.key"));
        try {
            vault.encrypt("   ".toCharArray());
            fail("a blank key is refused");
        } catch (IllegalArgumentException expected) {
            // expected
        }
        String first = vault.encrypt(KEY.toCharArray());
        String second = vault.encrypt(KEY.toCharArray());
        assertFalse("a fresh IV per encryption", first.equals(second));
        assertArrayEquals(KEY.toCharArray(), vault.decrypt(second));
        assertNull("nothing stored", vault.decrypt(""));
        try {
            vault.decrypt("plain-text-key");
            fail("an unknown format is refused");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("enter it again"));
        }
    }

    @Test
    public void unknownProviderValuesAndBrokenNumbersFallBackToDefaults() throws IOException {
        File file = new File(folder.getRoot(), ModelProviderSettingsRepository.FILE_NAME);
        Files.write(file.toPath(), ("provider=something-else\nopenai.capacity.default=many\n"
                + "openai.retries=-4\n").getBytes(StandardCharsets.UTF_8));

        ModelProviderSettings loaded = repository().load();

        assertEquals(ModelProviderKind.OLLAMA, loaded.getProvider());
        assertEquals(ModelProviderSettings.DEFAULT_API_CAPACITY, loaded.getApiCapacity());
        assertEquals(ModelProviderSettings.DEFAULT_RETRIES, loaded.getRetries());
        assertEquals(ModelProviderKind.OPENAI_COMPATIBLE, ModelProviderKind.fromKey(" OpenAI-Compatible "));
    }
}
