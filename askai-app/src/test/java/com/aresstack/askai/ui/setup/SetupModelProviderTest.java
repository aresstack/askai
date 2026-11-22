package com.aresstack.askai.ui.setup;

import com.aresstack.askai.modelport.ModelProviderKind;
import com.aresstack.askai.modelport.ModelProviderSettings;
import com.aresstack.askai.modelport.ModelProviderSettingsRepository;
import com.aresstack.askai.modelport.provider.OpenAiCompatibleModelProvider;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The wizard's model provider part against a temporary settings file and a fake OpenAI-compatible service. */
public class SetupModelProviderTest {

    private static final String STORED_KEY = "sk-wizard-stored";
    private static final String TYPED_KEY = "sk-wizard-typed";

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private HttpServer server;
    private String baseUrl;
    private ModelProviderSettingsRepository repository;
    private SetupModelProvider provider;

    @Before
    public void startService() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", exchange -> {
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            boolean known = ("Bearer " + STORED_KEY).equals(auth) || ("Bearer " + TYPED_KEY).equals(auth);
            byte[] body = (known ? "{\"data\":[{\"id\":\"model-b\"},{\"id\":\"model-a\"}]}"
                    : "{\"error\":{\"message\":\"Incorrect API key provided: sk-****oops.\"}}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(known ? 200 : 401, body.length);
            OutputStream out = exchange.getResponseBody();
            out.write(body);
            out.close();
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        repository = new ModelProviderSettingsRepository(new File(folder.getRoot(), "model-provider.properties"));
        provider = new SetupModelProvider(repository, OpenAiCompatibleModelProvider.PLAIN);
    }

    @After
    public void stopService() {
        server.stop(0);
    }

    @Test
    public void savingStoresTheChoiceUrlDefaultModelAndTypedKeyAndKeepsTheOtherTasks() throws Exception {
        repository.save(new ModelProviderSettings(ModelProviderKind.OLLAMA, "", "", 8,
                Collections.singletonMap("research.writer", "model-w"), 0, null, 0, 0, -1));
        char[] typed = ("  " + TYPED_KEY + " ").toCharArray();

        provider.save(api("model-a"), typed);

        ModelProviderSettings saved = repository.load();
        assertTrue(saved.usesApi());
        assertEquals(baseUrl, saved.getApiBaseUrl());
        assertEquals("model-a", saved.apiSlotModel("default"));
        assertEquals("another task keeps its own model", "model-w", saved.apiSlotModel("research.writer"));
        assertArrayEquals("the key is stored trimmed", TYPED_KEY.toCharArray(), repository.apiKey());
        assertArrayEquals("the typed key is cleared", new char[typed.length], typed);
        assertFalse("the file holds no plain key", new String(java.nio.file.Files.readAllBytes(
                repository.getFile().toPath()), StandardCharsets.UTF_8).contains(TYPED_KEY));
    }

    @Test
    public void anEmptyKeyKeepsTheStoredOneAndOllamaCanBeChosenAgain() throws Exception {
        repository.storeApiKey(STORED_KEY.toCharArray());
        provider.save(api("model-a"), new char[0]);
        assertArrayEquals(STORED_KEY.toCharArray(), repository.apiKey());

        provider.save(SetupDraft.builder().api(false, baseUrl, "model-a").build(), null);

        assertFalse(repository.load().usesApi());
        assertEquals("the API values stay for a later switch", baseUrl, repository.load().getApiBaseUrl());
        assertArrayEquals(STORED_KEY.toCharArray(), repository.apiKey());
        SetupDraft filled = provider.fill(SetupDraft.builder()).build();
        assertFalse(filled.useApi());
        assertTrue(filled.apiKeyStored());
        assertEquals("model-a", filled.apiDefaultModel());
    }

    @Test
    public void theCheckListsTheModelsWithTheTypedKeyElseTheStoredOne() throws Exception {
        char[] typed = TYPED_KEY.toCharArray();
        SetupActions.ApiStatus withTyped = provider.check(api(""), typed);
        assertTrue(withTyped.answered());
        assertEquals(Arrays.asList("model-a", "model-b"), withTyped.models());
        assertArrayEquals(new char[typed.length], typed);

        SetupActions.ApiStatus noKey = provider.check(api(""), new char[0]);
        assertFalse(noKey.answered());
        assertTrue(noKey.problem(), noKey.problem().contains("401"));
        assertFalse("a masked echo of a key is not shown", noKey.problem().contains("****"));

        repository.storeApiKey(STORED_KEY.toCharArray());
        assertTrue(provider.check(api(""), new char[0]).answered());
        assertFalse(provider.check(SetupDraft.builder().api(true, "", "").build(), null).answered());
    }

    @Test
    public void anAnsweringApiPassesTheRuntimeCheckWithoutOllama() throws Exception {
        repository.storeApiKey(STORED_KEY.toCharArray());

        List<SetupCheck> checks = provider.apiChecks(api("model-a"), "");

        assertEquals(5, checks.size());
        assertTrue(checks.get(0).toString(), checks.get(0).isOk());
        assertEquals(SetupModelProvider.RUNTIME_CHECK, checks.get(0).label());
        assertTrue(checks.get(0).detail().contains("Ollama does not answer"));
        assertEquals(SetupModelProvider.CHAT_CHECK, checks.get(2).label());
        assertTrue("the preferred API models stand in while none is chosen",
                checks.get(2).detail().startsWith("google/gemma-4-31B-it"));
        assertFalse("model-a and model-b only: the preferred chat model is not offered", checks.get(2).isOk());
        assertEquals("BAAI/bge-m3", repository.load().apiRoleModel(ModelProviderSettings.ROLE_EMBEDDING));
        assertTrue(checks.get(1).isOk());
        assertTrue(provider.apiChecks(api("model-a"), "Ollama 0.9.0").get(0).detail().contains("Ollama 0.9.0"));
    }

    @Test
    public void aSilentApiOrAModelItDoesNotOfferFailsTheCheck() throws Exception {
        List<SetupCheck> noKey = provider.apiChecks(api("model-a"), "Ollama 0.9.0");
        assertFalse("Ollama answering does not stand in for the API", noKey.get(0).isOk());

        repository.storeApiKey(STORED_KEY.toCharArray());
        assertFalse(provider.apiChecks(api("model-z"), "").get(1).isOk());
        assertFalse(provider.apiChecks(api(""), "").get(1).isOk());
    }

    @Test
    public void aKeePassEntryIsSavedInsteadOfAKeyAndTheCheckUsesItsPassword() throws Exception {
        FakeKeePass keePass = new FakeKeePass();
        repository.useKeePass(keePass::apiKey);
        SetupModelProvider withKeePass = new SetupModelProvider(repository, OpenAiCompatibleModelProvider.PLAIN,
                keePass);
        SetupDraft draft = SetupDraft.builder().api(true, baseUrl, "model-a").apiKeePassEntry("AI API").build();

        assertTrue("the entry's password opens the API", withKeePass.check(draft, null).answered());
        withKeePass.save(draft, "sk-typed-ignored".toCharArray());

        ModelProviderSettings saved = repository.load();
        assertEquals("AI API", saved.getKeePassEntry());
        assertFalse("no key is stored when KeePass holds it", saved.hasStoredApiKey());
        assertArrayEquals(STORED_KEY.toCharArray(), repository.apiKey());
        assertEquals("AI API", withKeePass.fill(SetupDraft.builder()).build().apiKeePassEntry());

        SetupActions.KeePassStatus entries = withKeePass.keePassEntries("");
        assertEquals(Arrays.asList("AI API", "Mail"), entries.titles());
        assertEquals("the first entry's URL", baseUrl, entries.url());
        assertEquals("", withKeePass.keePassEntries("Mail").url());
        assertFalse(provider.keePassEntries("").answered());
    }

    private final class FakeKeePass implements com.aresstack.askai.credentials.KeePassAccess {
        @Override
        public boolean paired() {
            return true;
        }

        @Override
        public List<String> titles() {
            return Arrays.asList("AI API", "Mail");
        }

        @Override
        public String location(String title) {
            return "AI API".equals(title) ? baseUrl : "";
        }

        @Override
        public char[] apiKey(String title) throws java.io.IOException {
            if (!"AI API".equals(title)) {
                throw new java.io.IOException("KeePass has no entry with this name");
            }
            return STORED_KEY.toCharArray();
        }

        @Override
        public void unpair() {
        }
    }

    private SetupDraft api(String model) {
        return SetupDraft.builder().api(true, baseUrl, model).build();
    }
}
