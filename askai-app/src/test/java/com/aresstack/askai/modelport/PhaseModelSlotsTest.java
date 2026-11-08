package com.aresstack.askai.modelport;

import com.aresstack.askai.agent.model.port.ChatListener;
import com.aresstack.askai.agent.model.port.ChatRequest;
import com.aresstack.askai.agent.model.port.ChatResult;
import com.aresstack.askai.agent.model.port.ModelFailure;
import com.aresstack.askai.agent.model.port.ModelMessage;
import com.aresstack.askai.agent.model.port.ModelSlot;
import com.aresstack.askai.agent.model.port.ThinkingMode;
import com.aresstack.askai.agent.model.port.resource.ModelEndpoint;
import com.aresstack.askai.config.AppConfiguration;
import com.aresstack.askai.config.AppConfigurationRepository;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * PR 08 (plan 5.1): one research model per phase, independent of the chat model. The productive slot
 * directory resolves exact -> parent -> default, never {@code ai.mainModel}; a model that is not installed
 * and a helper model that cannot turn thinking off fail before the call is sent.
 */
public class PhaseModelSlotsTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private HttpServer server;
    private final List<String> chatBodies = new CopyOnWriteArrayList<String>();
    private volatile String installed = "gemma4:e2b,llama3.1:8b,gpt-oss:20b";
    private ModelPortSettingsRepository settings;
    private AppConfigurationRepository central;
    private OllamaModelPort port;

    @Before
    public void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat", exchange -> {
            chatBodies.add(new String(drain(exchange.getRequestBody()), StandardCharsets.UTF_8));
            respond(exchange, "{\"message\":{\"role\":\"assistant\",\"content\":\"ok\",\"thinking\":\"\"},"
                    + "\"done\":true,\"done_reason\":\"stop\",\"prompt_eval_count\":12,\"eval_count\":3,"
                    + "\"eval_duration\":1}\n");
        });
        server.createContext("/api/show", exchange -> {
            String request = new String(drain(exchange.getRequestBody()), StandardCharsets.UTF_8);
            String family = request.contains("gpt-oss") ? "gptoss" : request.contains("llama") ? "llama" : "gemma4";
            respond(exchange, "{\"modelfile\":\"\",\"parameters\":\"\",\"template\":\"\",\"details\":{\"family\":\""
                    + family + "\"},\"capabilities\":[\"completion\"]}");
        });
        server.createContext("/api/tags", exchange -> {
            drain(exchange.getRequestBody());
            StringBuilder body = new StringBuilder("{\"models\":[");
            String[] names = installed.isEmpty() ? new String[0] : installed.split(",");
            for (int i = 0; i < names.length; i++) {
                body.append(i == 0 ? "" : ",").append("{\"name\":\"").append(names[i]).append("\",\"model\":\"")
                        .append(names[i]).append("\",\"modified_at\":\"2026-01-01T00:00:00.000000+01:00\","
                        + "\"size\":1000,\"digest\":\"abc\",\"details\":{\"format\":\"gguf\",\"family\":\"x\","
                        + "\"parameter_size\":\"2B\",\"quantization_level\":\"Q4_K_M\"}}");
            }
            respond(exchange, body.append("]}").toString());
        });
        server.createContext("/api/ps", exchange -> respond(exchange, "{\"models\":[]}"));
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        settings = new ModelPortSettingsRepository(folder.newFile("model-port.properties"));
        central = new AppConfigurationRepository(folder.newFile("askai.properties"));
        chatModel("llama3.1:8b");
        port = new OllamaModelPort(endpoints(), CentralModelDirectories.slots(central, settings), settings, null);
    }

    @After
    public void tearDown() {
        port.close();
        server.stop(0);
    }

    // ------------------------------------------------------------------ resolution

    @Test
    public void onlyTheDocumentPhaseSetUsesItsModelAndEveryOtherPhaseTheDefault() throws Exception {
        slots("phase.draft", "llama3.1:8b");
        chatModel("gpt-oss:20b");
        assertEquals("llama3.1:8b", call("phase.draft"));
        assertEquals("the writer sub-slot inherits its phase", "llama3.1:8b", call("phase.draft.writer"));
        assertEquals("gemma4:e2b", call("phase.scoping"));
        assertEquals("gemma4:e2b", call("phase.research"));
        assertEquals("gemma4:e2b", call("phase.research.planner"));
        assertEquals("gemma4:e2b", call("phase.outline"));
    }

    @Test
    public void onlyTheOutlinePhaseSetUsesItForAnOutlineTurn() throws Exception {
        slots("phase.outline", "llama3.1:8b");
        assertEquals("llama3.1:8b", call("phase.outline"));
        assertEquals("llama3.1:8b", call("phase.outline.briefs"));
        assertEquals("gemma4:e2b", call("phase.draft"));
    }

    @Test
    public void changingTheChatModelChangesNoResearchRequest() throws Exception {
        chatModel("llama3.1:8b");
        call("phase.scoping");
        call("judge");
        chatModel("gpt-oss:20b");
        call("phase.scoping");
        call("judge");
        assertEquals(4, chatBodies.size());
        for (String body : chatBodies) {
            assertTrue(body, body.contains("\"model\":\"gemma4:e2b\""));
        }
    }

    @Test
    public void researchRolesWorkWithoutAChatModel() throws Exception {
        chatModel("");
        assertEquals("gemma4:e2b", call("phase.scoping"));
        assertEquals("gemma4:e2b", call("scope"));
    }

    @Test
    public void thinkingIsInheritedFromThePhaseButNotTheAnswerBudget() throws Exception {
        Map<String, ThinkingMode> thinking = new HashMap<String, ThinkingMode>();
        thinking.put("phase.draft", ThinkingMode.HIGH);
        Map<String, Integer> answers = new HashMap<String, Integer>();
        answers.put("phase.draft", 1536);
        settings.save(new ModelPortSettings(1, null, null, null, null, thinking, answers, "5m", 600, 5));
        ModelPortSettings loaded = settings.load();
        assertEquals(ThinkingMode.HIGH, loaded.resolveThinking("phase.draft.writer"));
        assertEquals(ThinkingMode.DEFAULT, loaded.resolveThinking("phase.scoping"));
        assertEquals(0, loaded.slotAnswerTokens("phase.draft.writer"));
    }

    // ------------------------------------------------------------------ fails before the call

    @Test
    public void aSlotWhoseModelIsNotInstalledFailsBeforeTheCallWithTheInstallHint() throws Exception {
        installed = "gemma4:e2b";
        slots("phase.draft", "llama3.1:8b");
        ModelFailure failure = fail("phase.draft");
        assertTrue(failure.getDetail(), failure.getDetail().endsWith("Model llama3.1:8b for the Document phase is "
                + "not installed. Install it under Models -> Setup."));
        assertTrue("nothing was sent", chatBodies.isEmpty());
    }

    @Test
    public void aHelperWithADefaultThatCannotTurnThinkingOffFailsBeforeTheCall() throws Exception {
        slots("default", "gpt-oss:20b");
        ModelFailure failure = fail("judge");
        assertTrue(failure.getDetail(),
                failure.getDetail().endsWith("The page judge needs a model that can turn thinking off; gpt-oss:20b "
                        + "cannot. Set a model for it under Research settings -> Models."));
        assertTrue("nothing was sent", chatBodies.isEmpty());
        assertEquals("a phase slot may use a reasoning model", "gpt-oss:20b", call("phase.research"));
    }

    @Test
    public void theInferenceDescriptorNamesTheDefaultSlotNotTheChatModel() throws Exception {
        chatModel("llama3.1:8b");
        assertEquals("gemma4:e2b", settings.load().resolveModel(ModelPortSettings.DEFAULT_SLOT));
        slots("default", "llama3.1:8b");
        assertEquals("llama3.1:8b", settings.load().resolveModel("phase.outline.labels"));
        assertFalse(ModelPortSettings.DEFAULT_SLOTS.contains("conversation"));
        assertTrue(ModelPortSettings.DEFAULT_SLOTS.containsAll(java.util.Arrays.asList("default",
                "phase.scoping", "phase.research", "phase.outline", "phase.draft")));
    }

    @Test
    public void theDocumentWriterSettingsArePresetByModel() throws Exception {
        assertEquals(450, settings.load().documentWriterPreset().getWordsPerCall());
        assertEquals(1200, settings.load().documentWriterPreset().getAnswerTokens());
        slots("phase.draft", "llama3.1:8b");
        ModelPortSettings.WriterPreset llama = settings.load().documentWriterPreset();
        assertEquals(1100, llama.getWordsPerCall());
        assertEquals(8, llama.getEvidencePassages());
        assertEquals(300, llama.getPassageTokens());
        assertEquals(3072, llama.getAnswerTokens());
    }

    // ------------------------------------------------------------------ helpers

    private void slots(String slot, String model) throws IOException {
        Map<String, String> models = new HashMap<String, String>(settings.load().getSlotModels());
        models.put(slot, model);
        settings.save(new ModelPortSettings(1, null, null, models, null, null, null, "5m", 600, 5));
    }

    private void chatModel(String model) {
        AppConfiguration current = central.load();
        central.save(current.withAiModelSelections(current.getAiModelSelections().withMainModel(model)));
    }

    /** One call on {@code slot}; returns the model of the request that reached the wire. */
    private String call(String slot) throws Exception {
        int before = chatBodies.size();
        Outcome outcome = run(slot);
        assertTrue("call on " + slot + " failed: " + outcome.failure, outcome.failure == null);
        String body = chatBodies.get(before);
        int start = body.indexOf("\"model\":\"") + 9;
        return body.substring(start, body.indexOf('"', start));
    }

    private ModelFailure fail(String slot) throws Exception {
        Outcome outcome = run(slot);
        assertTrue("expected a failure on " + slot, outcome.failure != null);
        return outcome.failure;
    }

    private Outcome run(String slot) throws Exception {
        final CompletableFuture<Outcome> done = new CompletableFuture<Outcome>();
        port.chat(ChatRequest.builder(ModelSlot.of(slot)).message(ModelMessage.user("hi")).build(),
                new ChatListener() {
                    public void onCompleted(ChatResult result) {
                        done.complete(new Outcome(null));
                    }

                    public void onFailed(ModelFailure failure) {
                        done.complete(new Outcome(failure));
                    }
                });
        return done.get(30, TimeUnit.SECONDS);
    }

    private static final class Outcome {
        final ModelFailure failure;

        Outcome(ModelFailure failure) {
            this.failure = failure;
        }
    }

    private String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private OllamaModelPort.EndpointDirectory endpoints() {
        return new OllamaModelPort.EndpointDirectory() {
            public List<String> endpointIds() {
                return Collections.singletonList(CentralModelDirectories.OLLAMA);
            }

            public ModelEndpoint.Kind kind(String endpointId) {
                return ModelEndpoint.Kind.LOCAL;
            }

            public String label(String endpointId) {
                return "Ollama";
            }

            public String baseUrl(String endpointId) {
                return url();
            }

            public String baseUrlIfRunning(String endpointId) {
                return url();
            }
        };
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        try {
            exchange.sendResponseHeaders(200, bytes.length);
            OutputStream out = exchange.getResponseBody();
            out.write(bytes);
            out.close();
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
}
