package com.aresstack.askai.ui;

import com.aresstack.askai.modelport.ModelPortSettings;
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

import javax.swing.JComboBox;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import java.io.File;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The model provider page: choosing the API, base URL and a typed key that is stored only encrypted and never
 * shown again, an empty key field that keeps the stored key, removing the key without touching unsaved edits, what
 * a save still lacks, a model per task picked from the API's own list, and the API fields switched off while Ollama
 * is chosen. Also which models a chat tab keeps loaded for the research slots under either provider.
 */
public class ModelProviderPanelTest {

    private static final String KEY = "sk-panel-test-0815";
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
            byte[] body = "{\"data\":[{\"id\":\"openai/gpt-oss-120b\"},{\"id\":\"gemma-4-31b\"}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            OutputStream out = exchange.getResponseBody();
            out.write(body);
            out.close();
            exchange.close();
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

    private ModelPortSettingsRepository portSettings() {
        return new ModelPortSettingsRepository(new File(folder.getRoot(), "model-port.properties"));
    }

    private ModelProviderPanel panel(ModelProviderSettingsRepository providers) {
        return new ModelProviderPanel(providers, portSettings(), OpenAiCompatibleModelProvider.PLAIN, DIRECT,
                DIRECT);
    }

    private static int row(JTable table, String slot) {
        for (int row = 0; row < table.getRowCount(); row++) {
            if (slot.equals(table.getModel().getValueAt(row, 0))) {
                return row;
            }
        }
        throw new AssertionError("no row for slot " + slot);
    }

    @SuppressWarnings("unchecked")
    private static List<String> modelOptions(JTable table, String slot) {
        assertTrue(table.editCellAt(row(table, slot), 1));
        JComboBox<String> combo = (JComboBox<String>) table.getEditorComponent();
        List<String> items = new ArrayList<String>();
        for (int i = 0; i < combo.getItemCount(); i++) {
            items.add(combo.getItemAt(i));
        }
        table.getCellEditor().cancelCellEditing();
        return items;
    }

    private static void onEdt(Runnable test) throws Exception {
        final Throwable[] failure = new Throwable[1];
        SwingUtilities.invokeAndWait(() -> {
            try {
                test.run();
            } catch (Throwable thrown) {
                failure[0] = thrown;
            }
        });
        if (failure[0] instanceof Error) {
            throw (Error) failure[0];
        }
        if (failure[0] != null) {
            throw new AssertionError(failure[0]);
        }
    }

    @Test
    public void choosingTheApiStoresTheKeyOnlyEncryptedAndClearsTheField() throws Exception {
        final ModelProviderSettingsRepository providers = providers();
        onEdt(() -> {
            ModelProviderPanel panel = panel(providers);
            assertEquals("Ollama is the first setting", ModelProviderKind.OLLAMA,
                    panel.providerChoice().getSelectedItem());
            assertFalse("API fields are off while Ollama is chosen", panel.baseUrlField().isEnabled());

            panel.providerChoice().setSelectedItem(ModelProviderKind.OPENAI_COMPATIBLE);
            assertTrue(panel.baseUrlField().isEnabled());
            panel.baseUrlField().setText(baseUrl);
            panel.apiKeyField().setText(KEY);
            JTable table = panel.slotTable();
            table.getModel().setValueAt("openai/gpt-oss-120b", row(table, ModelPortSettings.DEFAULT_SLOT), 1);
            table.getModel().setValueAt("gemma-4-31b", row(table, "phase.draft.writer"), 1);
            panel.save();

            assertEquals("the key is never shown again", "", new String(panel.apiKeyField().getPassword()));
            assertTrue(panel.statusText(), panel.statusText().startsWith("Saved"));
            assertTrue(panel.keyStateText(), panel.keyStateText().contains("stored"));
        });

        ModelProviderSettings saved = providers().load();
        assertTrue(saved.usesApi());
        assertEquals(baseUrl, saved.getApiBaseUrl());
        assertEquals("openai/gpt-oss-120b", saved.apiSlotModel(ModelPortSettings.DEFAULT_SLOT));
        assertEquals("gemma-4-31b", saved.resolveApiModel("phase.draft.writer"));
        assertEquals("the API's capacity default is on as soon as the API is chosen",
                ModelProviderSettings.DEFAULT_API_CAPACITY, saved.getApiCapacity());
        assertArrayEquals(KEY.toCharArray(), providers().apiKey());
        String fileText = new String(Files.readAllBytes(providers.getFile().toPath()), StandardCharsets.UTF_8);
        assertFalse("the key is never in the file's text", fileText.contains(KEY));
    }

    @Test
    public void anEmptyKeyFieldKeepsTheStoredKey() throws Exception {
        final ModelProviderSettingsRepository providers = providers();
        providers.save(new ModelProviderSettings(ModelProviderKind.OPENAI_COMPATIBLE, baseUrl, "", 0, null, 0, null,
                0, 0, 0));
        providers.storeApiKey(KEY.toCharArray());
        onEdt(() -> {
            ModelProviderPanel panel = panel(providers);
            assertEquals(ModelProviderKind.OPENAI_COMPATIBLE, panel.providerChoice().getSelectedItem());
            assertEquals(baseUrl, panel.baseUrlField().getText());
            assertEquals("", new String(panel.apiKeyField().getPassword()));
            panel.save();
            assertTrue("without a default model the page says what is missing",
                    panel.statusText().contains("default slot"));
        });

        assertArrayEquals(KEY.toCharArray(), providers().apiKey());
    }

    @Test
    public void theTaskDropdownOffersTheModelsOfTheApi() throws Exception {
        final ModelProviderSettingsRepository providers = providers();
        onEdt(() -> {
            ModelProviderPanel panel = panel(providers);
            panel.providerChoice().setSelectedItem(ModelProviderKind.OPENAI_COMPATIBLE);
            panel.baseUrlField().setText(baseUrl + "/");
            panel.apiKeyField().setText(KEY);
            assertEquals("before listing only the inherit entry", Collections.singletonList(""),
                    modelOptions(panel.slotTable(), "phase.draft.writer"));

            panel.listModels();

            assertTrue(panel.statusText(), panel.statusText().contains("2 models"));
            List<String> options = modelOptions(panel.slotTable(), "phase.draft.writer");
            assertTrue(options.toString(), options.contains("gemma-4-31b"));
            assertTrue(options.toString(), options.contains("openai/gpt-oss-120b"));
            assertEquals("the typed key stays in the field until saved", KEY,
                    new String(panel.apiKeyField().getPassword()));
        });
        assertEquals("the typed key was used for the list", "Bearer " + KEY, authorizations.get(0));
        assertFalse("listing stores nothing", providers().load().usesApi());
    }

    @Test
    public void theSlotListIsTheModelResourcesSlotsPlusEveryApiSlot() {
        List<String> names = ModelProviderPanel.slotNames(ModelPortSettings.defaults(),
                new ModelProviderSettings(ModelProviderKind.OPENAI_COMPATIBLE, "", "", 0,
                        Collections.singletonMap("custom.task", "m"), 0, null, 0, 0, 0));
        assertTrue(names.contains(ModelPortSettings.DEFAULT_SLOT));
        assertTrue(names.contains("phase.draft.writer"));
        assertEquals("custom.task", names.get(names.size() - 1));
    }

    @Test
    public void removingTheStoredKeyKeepsTheUnsavedEditsOfThePage() throws Exception {
        final ModelProviderSettingsRepository providers = providers();
        providers.storeApiKey(KEY.toCharArray());
        onEdt(() -> {
            ModelProviderPanel panel = panel(providers);
            panel.providerChoice().setSelectedItem(ModelProviderKind.OPENAI_COMPATIBLE);
            panel.baseUrlField().setText(baseUrl);
            JTable table = panel.slotTable();
            table.getModel().setValueAt("gemma-4-31b", row(table, "phase.draft.writer"), 1);

            panel.removeKey();

            assertEquals("still the typed choice", ModelProviderKind.OPENAI_COMPATIBLE,
                    panel.providerChoice().getSelectedItem());
            assertEquals("still the typed URL", baseUrl, panel.baseUrlField().getText());
            assertEquals("still the typed model", "gemma-4-31b",
                    table.getModel().getValueAt(row(table, "phase.draft.writer"), 1));
            assertTrue(panel.keyStateText(), panel.keyStateText().startsWith("No key stored"));
        });
        org.junit.Assert.assertNull("the key is gone", providers().apiKey());
        assertFalse("the edits stay unsaved until Save", providers().load().usesApi());
    }

    @Test
    public void aSaveSaysWhatTheApiStillLacks() {
        ModelProviderSettings noUrl = new ModelProviderSettings(ModelProviderKind.OPENAI_COMPATIBLE, "", "", 0,
                Collections.singletonMap(ModelPortSettings.DEFAULT_SLOT, "m"), 0, null, 0, 0, 0);
        assertTrue(ModelProviderPanel.savedMessage(noUrl), ModelProviderPanel.savedMessage(noUrl)
                .contains("base URL"));
        ModelProviderSettings noScheme = new ModelProviderSettings(ModelProviderKind.OPENAI_COMPATIBLE,
                "localhost:8000/v1", "", 0, Collections.singletonMap(ModelPortSettings.DEFAULT_SLOT, "m"), 0, null,
                0, 0, 0);
        assertTrue(ModelProviderPanel.savedMessage(noScheme).contains("http://"));
        ModelProviderSettings noModel = new ModelProviderSettings(ModelProviderKind.OPENAI_COMPATIBLE, baseUrl, "",
                0, null, 0, null, 0, 0, 0);
        assertTrue(ModelProviderPanel.savedMessage(noModel).contains("default slot"));
        ModelProviderSettings complete = new ModelProviderSettings(ModelProviderKind.OPENAI_COMPATIBLE, baseUrl,
                "", 0, Collections.singletonMap(ModelPortSettings.DEFAULT_SLOT, "m"), 0, null, 0, 0, 0);
        assertTrue(ModelProviderPanel.savedMessage(complete).contains("next model call"));
        assertTrue("Ollama needs nothing here", ModelProviderPanel.savedMessage(ModelProviderSettings.defaults())
                .contains("next model call"));
    }

    @Test
    public void aChatTabKeepsOnlyTheModelsTheChosenProviderUsesForResearch() {
        ModelPortSettings ollama = ModelPortSettings.defaults();
        String shipped = ollama.resolveModel(ModelPortSettings.DEFAULT_SLOT);
        assertTrue("Ollama serves the research slots: its slot model stays loaded",
                OllamaChatPanel.isResearchSlotModel(shipped, ollama, ModelProviderSettings.defaults()));

        java.util.Map<String, String> apiModels = new java.util.HashMap<String, String>();
        apiModels.put(ModelPortSettings.DEFAULT_SLOT, "model-a");
        apiModels.put("custom.task", "model-x");
        ModelProviderSettings api = new ModelProviderSettings(ModelProviderKind.OPENAI_COMPATIBLE, baseUrl, "", 0,
                apiModels, 0, null, 0, 0, 0);
        assertFalse("the API serves them: a chat tab may unload Ollama's slot model again",
                OllamaChatPanel.isResearchSlotModel(shipped, ollama, api));
        assertTrue("an API model with the same name as a chat model is kept",
                OllamaChatPanel.isResearchSlotModel("model-a", ollama, api));
        assertTrue("a task only the provider page knows", OllamaChatPanel.isResearchSlotModel("model-x", ollama,
                api));
    }
}
