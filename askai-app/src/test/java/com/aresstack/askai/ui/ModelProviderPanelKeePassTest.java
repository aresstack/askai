package com.aresstack.askai.ui;

import com.aresstack.askai.credentials.KeePassAccess;
import com.aresstack.askai.modelport.ModelPortSettingsRepository;
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
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The KeePass key source of the model provider page: connecting lists the entries, picking one takes its URL into the
 * base URL, saving stores only the entry title (never the key), the key then comes from KeePass for every call, and a
 * missing KeePass (no plugin) is reported in words without breaking the page.
 */
public class ModelProviderPanelKeePassTest {

    private static final String KEY = "sk-from-keepass-4711";
    private static final Executor DIRECT = Runnable::run;

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private HttpServer server;
    private String baseUrl;
    private final List<String> authorizations = new CopyOnWriteArrayList<String>();

    @Before
    public void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", exchange -> {
            authorizations.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            byte[] body = "{\"data\":[{\"id\":\"openai/gpt-oss-120b\"}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            OutputStream out = exchange.getResponseBody();
            out.write(body);
            out.close();
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    @After
    public void tearDown() {
        server.stop(0);
    }

    private ModelProviderSettingsRepository providers() {
        return new ModelProviderSettingsRepository(new File(folder.getRoot(), "model-provider.properties"));
    }

    private ModelProviderPanel panel(ModelProviderSettingsRepository providers, KeePassAccess keePass) {
        return new ModelProviderPanel(providers,
                new ModelPortSettingsRepository(new File(folder.getRoot(), "model-port.properties")),
                OpenAiCompatibleModelProvider.PLAIN, DIRECT, DIRECT, keePass);
    }

    @Test
    public void connectingListsTheEntriesAndTakesTheUrlOfTheChosenOne() {
        ModelProviderPanel panel = panel(providers(), new FakeKeePass());
        panel.providerChoice().setSelectedItem(ModelProviderKind.OPENAI_COMPATIBLE);
        panel.keySourceChoice().setSelectedItem(ModelProviderPanel.KEY_IN_KEEPASS);

        panel.connectKeePass();

        assertEquals(2, panel.keePassEntryChoice().getItemCount());
        assertEquals("AI API", panel.keePassEntryChoice().getSelectedItem());
        assertEquals(baseUrl, panel.baseUrlField().getText());
        assertFalse("the key field is off while KeePass holds the key", panel.apiKeyField().isEnabled());
    }

    @Test
    public void savingStoresOnlyTheEntryAndTheKeyComesFromKeePass() throws Exception {
        ModelProviderSettingsRepository providers = providers();
        FakeKeePass keePass = new FakeKeePass();
        providers.useKeePass(keePass::apiKey);
        ModelProviderPanel panel = panel(providers, keePass);
        panel.providerChoice().setSelectedItem(ModelProviderKind.OPENAI_COMPATIBLE);
        panel.keySourceChoice().setSelectedItem(ModelProviderPanel.KEY_IN_KEEPASS);
        panel.connectKeePass();
        panel.apiKeyField().setText("typed-but-ignored");

        panel.save();

        ModelProviderSettings saved = providers.load();
        assertEquals("AI API", saved.getKeePassEntry());
        assertFalse("no key is stored in AskAI", saved.hasStoredApiKey());
        assertArrayEquals(KEY.toCharArray(), providers.apiKey());
        String file = new String(Files.readAllBytes(providers.getFile().toPath()), StandardCharsets.UTF_8);
        assertFalse(file.contains(KEY));
        assertFalse(file.contains("typed-but-ignored"));
        assertTrue(panel.keyStateText().contains("AI API"));

        panel.listModels();
        assertEquals("Bearer " + KEY, authorizations.get(0));
    }

    @Test
    public void anEmptyEntryIsNotSavedAndAskAiKeyCanBeChosenAgain() throws Exception {
        ModelProviderSettingsRepository providers = providers();
        ModelProviderPanel panel = panel(providers, new FakeKeePass());
        panel.providerChoice().setSelectedItem(ModelProviderKind.OPENAI_COMPATIBLE);
        panel.keySourceChoice().setSelectedItem(ModelProviderPanel.KEY_IN_KEEPASS);
        panel.baseUrlField().setText(baseUrl);
        panel.save();
        assertTrue(panel.statusText(), panel.statusText().startsWith("Not saved"));
        assertFalse(providers.getFile().exists());

        panel.connectKeePass();
        panel.save();
        assertTrue(providers.load().usesKeePass());
        panel.keySourceChoice().setSelectedItem(ModelProviderPanel.KEY_IN_ASKAI);
        panel.save();
        assertFalse(providers.load().usesKeePass());
    }

    @Test
    public void withoutThePluginThePageSaysWhatIsMissing() throws Exception {
        ModelProviderSettingsRepository providers = providers();
        ModelProviderPanel panel = panel(providers, KeePassAccess.NONE);
        panel.providerChoice().setSelectedItem(ModelProviderKind.OPENAI_COMPATIBLE);
        panel.keySourceChoice().setSelectedItem(ModelProviderPanel.KEY_IN_KEEPASS);

        panel.connectKeePass();

        assertTrue(panel.statusText(), panel.statusText().startsWith("KeePass: "));
        assertEquals(0, panel.keePassEntryChoice().getItemCount());

        providers.save(providers.load().withProvider(ModelProviderKind.OPENAI_COMPATIBLE).withKeePassEntry("AI API"));
        try {
            providers.apiKey();
            throw new AssertionError("a KeePass key without KeePass must fail");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("KeePass"));
        }
    }

    @Test
    public void theRolesBesideTheResearchAreSavedAndEmptyMeansAskAisChoice() throws Exception {
        ModelProviderSettingsRepository providers = providers();
        ModelProviderPanel panel = panel(providers, KeePassAccess.NONE);
        panel.providerChoice().setSelectedItem(ModelProviderKind.OPENAI_COMPATIBLE);
        panel.baseUrlField().setText(baseUrl);
        panel.listModels();
        assertEquals("the listed models are offered", 2,
                panel.roleChoice(ModelProviderSettings.ROLE_CHAT).getItemCount());
        panel.roleChoice(ModelProviderSettings.ROLE_CHAT).getEditor().setItem("openai/gpt-oss-120b");

        panel.save();

        ModelProviderSettings saved = providers.load();
        assertEquals("openai/gpt-oss-120b", saved.apiRoleModel(ModelProviderSettings.ROLE_CHAT));
        assertEquals("BAAI/bge-m3", saved.apiRoleModel(ModelProviderSettings.ROLE_EMBEDDING));
        assertFalse(saved.getApiRoleModels().containsKey(ModelProviderSettings.ROLE_EMBEDDING));
    }

    private final class FakeKeePass implements KeePassAccess {
        @Override
        public boolean paired() {
            return true;
        }

        @Override
        public List<String> titles() {
            return Arrays.asList("AI API", "Mail");
        }

        @Override
        public String location(String title) throws IOException {
            if (!"AI API".equals(title)) {
                return "";
            }
            return baseUrl;
        }

        @Override
        public char[] apiKey(String title) throws IOException {
            if (!"AI API".equals(title)) {
                throw new IOException("KeePass has no entry with this name");
            }
            return KEY.toCharArray();
        }

        @Override
        public void unpair() {
        }
    }
}
