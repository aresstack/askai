package com.aresstack.askai.modelport;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The API's roles beside the research slots (chat window, embeddings, reranker) with AskAI's preferred models for the
 * 14 models the API offers, the preselection taken from Space Harvester, and the key that only the API's own URLs get.
 */
public class ApiRolesAndAuthorizationTest {

    /** The models Angelo's API offers (2026-11-22). */
    static final List<String> API_MODELS = Arrays.asList("mistralai/Mistral-Small-3.2-24B-Instruct-2506",
            "google/gemma-4-31B-it", "nvidia/llama-nemotron-rerank-1b-v2", "Qwen/Qwen3-TTS-12Hz-1.7B-CustomVoice",
            "danielheinz/e5-base-sts-en-de", "Qwen/Qwen3-Embedding-8B", "mixedbread-ai/mxbai-embed-large-v1",
            "orpheus-tts-3b-de", "google/vit-base-patch16-224-in21k", "ellamind/sui-1-24b", "BAAI/bge-m3",
            "ibm-granite/granite-docling-258M", "openai/gpt-oss-120b", "opendatalab/MinerU2.5-Pro-2604-1.2B");

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void everyPreferredModelIsOneTheApiOffers() {
        ApiModelPreferences preferences = ApiModelPreferences.bundled();
        for (String role : Arrays.asList("chat", "embedding", "rerank", "vision")) {
            assertTrue(role + " -> " + preferences.model(role), API_MODELS.contains(preferences.model(role)));
            assertEquals(preferences.model(role), preferences.choose(role, API_MODELS, ""));
        }
        assertEquals("a chosen model stays", "openai/gpt-oss-120b",
                preferences.choose("chat", API_MODELS, "openai/gpt-oss-120b"));
        assertEquals("the one offered model with the same last part", "local/gemma-4-31B-it",
                preferences.choose("chat", Arrays.asList("a-model", "local/gemma-4-31B-it"), ""));
        assertEquals("else the first offered", "a-model", preferences.choose("chat", Arrays.asList("a-model"), ""));
    }

    @Test
    public void rolesAreStoredAndAnEmptyRoleUsesThePreference() throws Exception {
        ModelProviderSettingsRepository repository = new ModelProviderSettingsRepository(
                new File(folder.getRoot(), "model-provider.properties"));
        ModelProviderSettings settings = repository.load().withProvider(ModelProviderKind.OPENAI_COMPATIBLE)
                .withApi("https://api.example.org/v1", "google/gemma-4-31B-it")
                .withApiRoleModel(ModelProviderSettings.ROLE_EMBEDDING, "danielheinz/e5-base-sts-en-de")
                .withKeePassEntry("AI API");
        repository.save(settings);

        ModelProviderSettings loaded = repository.load();
        assertEquals("danielheinz/e5-base-sts-en-de", loaded.apiRoleModel(ModelProviderSettings.ROLE_EMBEDDING));
        assertEquals("nvidia/llama-nemotron-rerank-1b-v2", loaded.apiRoleModel(ModelProviderSettings.ROLE_RERANK));
        assertEquals("AI API", loaded.getKeePassEntry());
        assertEquals("google/gemma-4-31B-it", loaded.resolveApiModel("research.writer"));
        assertEquals("BAAI/bge-m3", loaded.withApiRoleModel(ModelProviderSettings.ROLE_EMBEDDING, "")
                .apiRoleModel(ModelProviderSettings.ROLE_EMBEDDING));
        assertEquals("other copies keep the roles", "danielheinz/e5-base-sts-en-de",
                loaded.withProvider(ModelProviderKind.OLLAMA).withEncryptedApiKey("")
                        .apiRoleModel(ModelProviderSettings.ROLE_EMBEDDING));
    }

    @Test
    public void onlyTheApisOwnUrlsGetTheKeyAndOnlyWhileTheApiIsChosen() throws Exception {
        ModelProviderSettingsRepository repository = new ModelProviderSettingsRepository(
                new File(folder.getRoot(), "model-provider.properties"));
        repository.save(repository.load().withProvider(ModelProviderKind.OPENAI_COMPATIBLE)
                .withApi("https://api.example.org/v1/", ""));
        repository.storeApiKey("sk-only-for-the-api".toCharArray());
        ApiEndpointAuthorization authorization = new ApiEndpointAuthorization(repository);

        assertArrayEquals("sk-only-for-the-api".toCharArray(),
                authorization.bearerFor("https://api.example.org/v1/embeddings"));
        assertNull(authorization.bearerFor("http://localhost:11434/api/embed"));
        assertNull("another path of the same host", authorization.bearerFor("https://api.example.org/v10/rerank"));
        assertNull(authorization.bearerFor("https://api.example.org.evil.test/v1/rerank"));

        repository.save(repository.load().withProvider(ModelProviderKind.OLLAMA));
        assertNull(authorization.bearerFor("https://api.example.org/v1/embeddings"));
        assertFalse(ApiEndpointAuthorization.belowBase("https://x/v1", ""));
    }

    @Test
    public void onlyTheApiUrlsUseTheConfiguredConnections() throws Exception {
        ModelProviderSettingsRepository repository =
                new ModelProviderSettingsRepository(new File(folder.getRoot(), "conn.properties"));
        repository.save(repository.load().withProvider(ModelProviderKind.OPENAI_COMPATIBLE)
                .withApi("http://127.0.0.1:9/v1", "x"));
        final java.util.List<String> opened = new java.util.ArrayList<String>();
        ApiEndpointAuthorization authorization = new ApiEndpointAuthorization(repository, url -> {
            opened.add(url);
            return (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        });
        authorization.open("http://127.0.0.1:9/v1/embeddings");
        authorization.open("http://127.0.0.1:11434/api/embed");
        assertEquals(Arrays.asList("http://127.0.0.1:9/v1/embeddings"), opened);
    }
}
