package com.aresstack.askai.modelport;

import com.aresstack.askai.agent.model.port.ChatListener;
import com.aresstack.askai.agent.model.port.ChatRequest;
import com.aresstack.askai.agent.model.port.ChatResult;
import com.aresstack.askai.agent.model.port.ModelFailure;
import com.aresstack.askai.agent.model.port.ModelMessage;
import com.aresstack.askai.agent.model.port.ModelSlot;
import com.aresstack.askai.agent.model.port.resource.ModelEndpoint;
import com.aresstack.askai.config.AppConfigurationRepository;
import com.aresstack.askai.modelport.provider.OpenAiCompatibleModelProvider;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The productive model port with the OpenAI-compatible API chosen (the central directories over the provider
 * settings), against a scripted service: a model per task, different models side by side, several calls per model
 * by default, the per-model capacity override, nothing loaded or unloaded, and the named failures. Switching back
 * to Ollama routes every slot to Ollama again.
 */
public class OpenAiCompatiblePortTest {

    private static final ModelSlot PLANNER = ModelSlot.of("planner");
    private static final ModelSlot CONVERSATION = ModelSlot.of("conversation");
    private static final ModelSlot JUDGE = ModelSlot.of("judge"); // an app-wide helper slot
    private static final String KEY = "sk-port-test-42";

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private HttpServer server;
    private String baseUrl;
    private final Map<String, AtomicInteger> running = new ConcurrentHashMap<String, AtomicInteger>();
    private final Map<String, AtomicInteger> peak = new ConcurrentHashMap<String, AtomicInteger>();
    private final AtomicInteger runningAll = new AtomicInteger();
    private final List<String> unexpected = new CopyOnWriteArrayList<String>();
    private final List<String> authorizations = new CopyOnWriteArrayList<String>();
    private final List<String> calledModels = new CopyOnWriteArrayList<String>();
    private volatile CountDownLatch gate;
    private volatile int chatStatus = 200;

    private ModelPortSettingsRepository portSettings;
    private ModelProviderSettingsRepository providers;
    private AppConfigurationRepository central;
    private OllamaModelPort port;

    @Before
    public void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            unexpected.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            respond(exchange, 404, "text/plain", "not here");
        });
        server.createContext("/v1/models", exchange -> respond(exchange, 200, "application/json",
                "{\"data\":[{\"id\":\"model-a\"},{\"id\":\"model-b\"},{\"id\":\"openai/gpt-oss-120b\"}]}"));
        server.createContext("/v1/chat/completions", this::chat);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        portSettings = new ModelPortSettingsRepository(new File(folder.getRoot(), "model-port.properties"));
        providers = new ModelProviderSettingsRepository(new File(folder.getRoot(), "model-provider.properties"),
                new ApiKeyVault(new File(folder.getRoot(), "model-provider.key")), name -> null);
        central = new AppConfigurationRepository(folder.newFile("askai.properties"));
        OpenAiCompatibleModelProvider api = CentralModelDirectories.apiProvider(providers, null);
        port = new OllamaModelPort(CentralModelDirectories.endpoints(central, null, providers, api),
                CentralModelDirectories.slots(central, portSettings, providers), portSettings, null);
    }

    @After
    public void tearDown() {
        CountDownLatch latch = gate;
        if (latch != null) {
            latch.countDown();
        }
        port.close();
        if (server != null) {
            server.stop(0);
        }
    }

    private void useApi(Map<String, String> slotModels) throws IOException {
        providers.save(new ModelProviderSettings(ModelProviderKind.OPENAI_COMPATIBLE, baseUrl, "", 0, slotModels, 0,
                null, 5, 30, 0));
        providers.storeApiKey(KEY.toCharArray());
    }

    private static Map<String, String> models(String... slotAndModel) {
        Map<String, String> models = new HashMap<String, String>();
        for (int i = 0; i + 1 < slotAndModel.length; i += 2) {
            models.put(slotAndModel[i], slotAndModel[i + 1]);
        }
        return models;
    }

    // ------------------------------------------------------------------ tests

    @Test
    public void eachTaskGetsItsOwnModelAndDifferentModelsRunSideBySide() throws Exception {
        useApi(models("planner", "model-a", "conversation", "model-b"));
        gate = new CountDownLatch(1);
        List<Recorder> planner = new ArrayList<Recorder>();
        List<Recorder> conversation = new ArrayList<Recorder>();
        for (int i = 0; i < 3; i++) {
            planner.add(start(PLANNER));
            conversation.add(start(CONVERSATION));
        }

        assertTrue("six calls on two models run at the same time, no model switch",
                waitFor(() -> runningAll.get() == 6, 15000));
        gate.countDown();
        for (Recorder recorder : planner) {
            ChatResult result = recorder.await().result();
            assertEquals("ok from model-a", result.getContent());
            assertEquals(CentralModelDirectories.OPENAI_COMPATIBLE, result.getEndpointId());
        }
        for (Recorder recorder : conversation) {
            assertEquals("ok from model-b", recorder.await().result().getContent());
        }
        assertEquals(3, peak.get("model-a").get());
        assertEquals(3, peak.get("model-b").get());
        assertEquals("Bearer " + KEY, authorizations.get(0));
        assertTrue("nothing is loaded, warmed, listed as resident or unloaded: " + unexpected, unexpected.isEmpty());
    }

    @Test
    public void severalCallsPerModelByDefaultUpToTheApiCapacity() throws Exception {
        useApi(models(ModelPortSettings.DEFAULT_SLOT, "model-a"));
        gate = new CountDownLatch(1);
        List<Recorder> recorders = new ArrayList<Recorder>();
        for (int i = 0; i < ModelProviderSettings.DEFAULT_API_CAPACITY + 2; i++) {
            recorders.add(start(PLANNER));
        }

        assertTrue("the API's default capacity runs at once",
                waitFor(() -> runningAll.get() == ModelProviderSettings.DEFAULT_API_CAPACITY, 15000));
        Thread.sleep(300);
        assertEquals("the rest waits for a free place", ModelProviderSettings.DEFAULT_API_CAPACITY, runningAll.get());
        gate.countDown();
        for (Recorder recorder : recorders) {
            assertEquals("the default slot fills every task", "ok from model-a",
                    recorder.await().result().getContent());
        }
        assertEquals(ModelProviderSettings.DEFAULT_API_CAPACITY, peak.get("model-a").get());
    }

    @Test
    public void aPerModelCapacityOfTheModelResourcesWinsOnTheApiToo() throws Exception {
        portSettings.save(new ModelPortSettings(1, Collections.singletonMap("model-a", 2), null, null, null, null,
                null, "5m", 600, 5));
        useApi(models("planner", "model-a"));
        gate = new CountDownLatch(1);
        List<Recorder> recorders = new ArrayList<Recorder>();
        for (int i = 0; i < 5; i++) {
            recorders.add(start(PLANNER));
        }

        assertTrue(waitFor(() -> runningAll.get() == 2, 15000));
        Thread.sleep(300);
        assertEquals(2, runningAll.get());
        gate.countDown();
        for (Recorder recorder : recorders) {
            assertNotNull(recorder.await().result());
        }
        assertEquals(2, peak.get("model-a").get());
    }

    @Test
    public void aTaskWithoutAnApiModelSaysWhereToSetIt() throws Exception {
        useApi(Collections.<String, String>emptyMap());

        ModelFailure failure = start(PLANNER).await().failure();

        assertEquals(ModelFailure.Kind.NO_BINDING, failure.getKind());
        assertTrue(failure.getDetail(), failure.getDetail().contains("Model provider"));
        assertTrue("nothing reached the service", calledModels.isEmpty());
    }

    @Test
    public void aModelTheApiDoesNotOfferIsNamedBeforeTheCall() throws Exception {
        useApi(models("planner", "model-z"));

        ModelFailure failure = start(PLANNER).await().failure();

        assertTrue(failure.getDetail(), failure.getDetail().contains("not offered by the OpenAI-compatible API"));
        assertTrue(calledModels.isEmpty());
    }

    @Test
    public void helperSlotsRefuseALevelOnlyModelUnderAVendorPrefix() throws Exception {
        assertTrue(ModelPortSettings.isLevelOnlyModel("openai/gpt-oss-120b", null));
        useApi(models("judge", "openai/gpt-oss-120b"));

        ModelFailure failure = start(JUDGE).await().failure();

        assertTrue(failure.getDetail(), failure.getDetail().contains("cannot"));
        assertTrue(calledModels.isEmpty());
    }

    @Test
    public void aRefusalIsAProviderErrorAndADownServiceIsUnavailable() throws Exception {
        useApi(models("planner", "model-a"));
        chatStatus = 401;

        ModelFailure refused = start(PLANNER).await().failure();
        assertEquals(ModelFailure.Kind.PROVIDER_ERROR, refused.getKind());
        assertTrue(refused.getDetail(), refused.getDetail().contains("refused the API key"));

        server.stop(0);
        server = null;
        ModelFailure down = start(PLANNER).await().failure();
        assertEquals(down.getDetail(), ModelFailure.Kind.ENDPOINT_UNAVAILABLE, down.getKind());
        assertTrue("the key never appears in a failure", !down.getDetail().contains(KEY)
                && !refused.getDetail().contains(KEY));
    }

    @Test
    public void theApiEndpointReplacesOllamaOnlyWhileItIsChosen() throws Exception {
        OpenAiCompatibleModelProvider api = CentralModelDirectories.apiProvider(providers, null);
        OllamaModelPort.EndpointDirectory endpoints = CentralModelDirectories.endpoints(central, null, providers,
                api);
        OllamaModelPort.SlotDirectory slots = CentralModelDirectories.slots(central, portSettings, providers);
        assertEquals(Collections.singletonList(CentralModelDirectories.OLLAMA), endpoints.endpointIds());
        assertEquals(CentralModelDirectories.OLLAMA, slots.endpointFor("gemma4:e2b"));
        assertNull("Ollama keeps the port's own provider", endpoints.provider(CentralModelDirectories.OLLAMA));

        providers.save(new ModelProviderSettings(ModelProviderKind.OPENAI_COMPATIBLE, "", "", 0,
                models(ModelPortSettings.DEFAULT_SLOT, "model-a"), 0, null, 0, 0, 0));
        assertEquals(Collections.singletonList(CentralModelDirectories.OPENAI_COMPATIBLE), endpoints.endpointIds());
        assertEquals(ModelEndpoint.Kind.REMOTE, endpoints.kind(CentralModelDirectories.OPENAI_COMPATIBLE));
        assertEquals("OpenAI-compatible API", endpoints.label(CentralModelDirectories.OPENAI_COMPATIBLE));
        assertEquals(api, endpoints.provider(CentralModelDirectories.OPENAI_COMPATIBLE));
        assertEquals("model-a", slots.modelFor(PLANNER));
        assertEquals(CentralModelDirectories.OPENAI_COMPATIBLE, slots.endpointFor("model-a"));
        assertEquals("local models stay on the local runtime", CentralModelDirectories.LOCAL_RUNTIME,
                slots.endpointFor("local/acme/model"));
        assertNull("no base URL yet", endpoints.baseUrlIfRunning(CentralModelDirectories.OPENAI_COMPATIBLE));
        try {
            endpoints.baseUrl(CentralModelDirectories.OPENAI_COMPATIBLE);
            fail("a missing base URL is named");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("Model provider"));
        }

        providers.save(providers.load().withProvider(ModelProviderKind.OLLAMA));
        assertEquals("switching back routes to Ollama on the next call",
                Collections.singletonList(CentralModelDirectories.OLLAMA), endpoints.endpointIds());
        assertEquals(CentralModelDirectories.OLLAMA, slots.endpointFor("model-a"));
        assertEquals("the Ollama slot models apply again", portSettings.load().resolveModel("planner"),
                slots.modelFor(PLANNER));
    }

    // ------------------------------------------------------------------ fake service

    private void chat(HttpExchange exchange) throws IOException {
        try {
            String body = new String(drain(exchange.getRequestBody()), StandardCharsets.UTF_8);
            authorizations.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            String model = JsonParser.parseString(body).getAsJsonObject().get("model").getAsString();
            calledModels.add(model);
            if (chatStatus != 200) {
                respond(exchange, chatStatus, "application/json", "{\"error\":{\"message\":\"nope\"}}");
                return;
            }
            runningAll.incrementAndGet();
            AtomicInteger perModel = running.computeIfAbsent(model, name -> new AtomicInteger());
            int now = perModel.incrementAndGet();
            peak.computeIfAbsent(model, name -> new AtomicInteger()).accumulateAndGet(now, Math::max);
            try {
                CountDownLatch latch = gate;
                if (latch != null) {
                    latch.await(30, TimeUnit.SECONDS);
                }
                respond(exchange, 200, "text/event-stream", "data: {\"choices\":[{\"delta\":{\"content\":\"ok from "
                        + model + "\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                perModel.decrementAndGet();
                runningAll.decrementAndGet();
            }
        } finally {
            exchange.close();
        }
    }

    private static void respond(HttpExchange exchange, int status, String contentType, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        try {
            exchange.sendResponseHeaders(status, bytes.length);
            OutputStream out = exchange.getResponseBody();
            out.write(bytes);
            out.close();
        } catch (IOException closedByClient) {
            // a cancelled call closed the connection
        } finally {
            exchange.close();
        }
    }

    private static byte[] drain(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = in.read(chunk)) >= 0) {
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }

    // ------------------------------------------------------------------ helpers

    private Recorder start(ModelSlot slot) {
        Recorder recorder = new Recorder();
        port.chat(ChatRequest.builder(slot).message(ModelMessage.user("hi")).build(), recorder);
        return recorder;
    }

    private static boolean waitFor(BooleanSupplier condition, long timeoutMillis) throws InterruptedException {
        long end = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < end) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(20);
        }
        return condition.getAsBoolean();
    }

    private static final class Recorder implements ChatListener {
        private final AtomicReference<ChatResult> result = new AtomicReference<ChatResult>();
        private final AtomicReference<ModelFailure> failure = new AtomicReference<ModelFailure>();
        private final CountDownLatch done = new CountDownLatch(1);

        public void onThinkingDelta(String delta) {
        }

        public void onContentDelta(String delta) {
        }

        public void onCompleted(ChatResult completed) {
            result.set(completed);
            done.countDown();
        }

        public void onFailed(ModelFailure failed) {
            failure.set(failed);
            done.countDown();
        }

        Recorder await() throws InterruptedException {
            assertTrue("terminal callback arrived", done.await(30, TimeUnit.SECONDS));
            return this;
        }

        ChatResult result() {
            assertNull("unexpected failure: " + (failure.get() == null ? "" : failure.get().getDetail()),
                    failure.get());
            return result.get();
        }

        ModelFailure failure() {
            assertNotNull("the call should have failed", failure.get());
            return failure.get();
        }
    }
}
