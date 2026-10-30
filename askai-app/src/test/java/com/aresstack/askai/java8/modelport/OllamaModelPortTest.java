package com.aresstack.askai.java8.modelport;

import com.aresstack.askai.agent.model.port.ChatListener;
import com.aresstack.askai.agent.model.port.ChatRequest;
import com.aresstack.askai.agent.model.port.ChatResult;
import com.aresstack.askai.agent.model.port.ModelCall;
import com.aresstack.askai.agent.model.port.ModelFailure;
import com.aresstack.askai.agent.model.port.ModelMessage;
import com.aresstack.askai.agent.model.port.ModelSlot;
import com.aresstack.askai.agent.model.port.OutputFormat;
import com.aresstack.askai.agent.model.port.ThinkingMode;
import com.aresstack.askai.agent.model.port.ToolCall;
import com.aresstack.askai.agent.model.port.ToolDefinition;
import com.aresstack.askai.agent.model.port.resource.ResourceSnapshot;
import com.aresstack.askai.agent.model.port.resource.SlotRequest;
import com.aresstack.askai.agent.model.port.resource.SlotReservation;
import com.aresstack.askai.agent.model.port.resource.SlotUnavailableException;
import com.aresstack.askai.agent.model.port.resource.ModelEndpoint;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The model port on ollama4j against a scripted {@code /api/chat} and {@code /api/ps}: what reaches the wire
 * per slot and call, what comes back (streamed deltas, thinking, tool calls with ids, usage), the named
 * failures, cancellation, time-outs and the capacity the scheduler enforces on real calls.
 */
public class OllamaModelPortTest {

    private static final ModelSlot JUDGE = ModelSlot.of("judge");
    private static final ModelSlot CONVERSATION = ModelSlot.of("conversation");

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private HttpServer server;
    private final List<String> bodies = new CopyOnWriteArrayList<String>();
    private final ConcurrentLinkedDeque<String> answers = new ConcurrentLinkedDeque<String>();
    private final AtomicInteger concurrent = new AtomicInteger();
    private final AtomicInteger maxConcurrent = new AtomicInteger();
    private volatile CountDownLatch gate;
    private volatile long answerDelayMillis;
    private final Map<String, String> slotModels = new HashMap<String, String>();
    private ModelPortSettingsRepository settings;
    private OllamaModelPort port;
    private String mainModel = "gemma4:e2b";

    @Before
    public void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat", this::chat);
        server.createContext("/api/ps", exchange -> respond(exchange, "{\"models\":[{\"name\":\"gemma4:e2b\","
                + "\"model\":\"gemma4:e2b\",\"size\":7500000000,\"size_vram\":7000000000,"
                + "\"expires_at\":\"2026-10-30T03:00:00.5+01:00\",\"context_length\":8192}]}"));
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        settings = new ModelPortSettingsRepository(folder.newFile("model-port.properties"));
        port = newPort();
    }

    @After
    public void tearDown() {
        CountDownLatch latch = gate;
        if (latch != null) {
            latch.countDown();
        }
        port.close();
        server.stop(0);
    }

    private OllamaModelPort newPort() {
        final String url = "http://127.0.0.1:" + server.getAddress().getPort();
        return new OllamaModelPort(new OllamaModelPort.EndpointDirectory() {
            public List<String> endpointIds() {
                return Collections.singletonList("ollama");
            }

            public ModelEndpoint.Kind kind(String endpointId) {
                return ModelEndpoint.Kind.LOCAL;
            }

            public String label(String endpointId) {
                return "Ollama";
            }

            public String baseUrl(String endpointId) {
                return url;
            }

            public String baseUrlIfRunning(String endpointId) {
                return url;
            }
        }, new OllamaModelPort.SlotDirectory() {
            public String modelFor(ModelSlot slot) {
                String configured = slotModels.get(slot.getName());
                return configured != null ? configured : mainModel;
            }

            public String endpointFor(String model) {
                return "ollama";
            }

            public List<ModelSlot> knownSlots() {
                return Arrays.asList(CONVERSATION, JUDGE);
            }

            public Duration defaultTimeout() {
                return Duration.ofSeconds(60);
            }
        }, settings, null);
    }

    private void writeSettings(int capacity, String keepAlive, int judgeContext) throws IOException {
        Map<String, Integer> contexts = new HashMap<String, Integer>();
        contexts.put("judge", judgeContext);
        settings.save(new ModelPortSettings(capacity, null, null, null, contexts, keepAlive, 600, 5));
    }

    // ------------------------------------------------------------------ fake Ollama

    private void chat(HttpExchange exchange) throws IOException {
        int now = concurrent.incrementAndGet();
        maxConcurrent.accumulateAndGet(now, Math::max);
        try {
            bodies.add(new String(drain(exchange.getRequestBody()), StandardCharsets.UTF_8));
            CountDownLatch latch = gate;
            if (latch != null) {
                try {
                    latch.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            if (answerDelayMillis > 0) {
                try {
                    Thread.sleep(answerDelayMillis);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            String answer = answers.isEmpty() ? done("ok", "") : answers.poll();
            respond(exchange, answer);
        } finally {
            concurrent.decrementAndGet();
        }
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        try {
            exchange.sendResponseHeaders(200, bytes.length);
            OutputStream out = exchange.getResponseBody();
            out.write(bytes);
            out.close();
        } catch (IOException closedByClient) {
            // a cancelled call closed the connection
        } finally {
            exchange.close();
        }
    }

    private static String done(String content, String thinking) {
        return "{\"message\":{\"role\":\"assistant\",\"content\":\"" + content + "\",\"thinking\":\"" + thinking
                + "\"},\"done\":true,\"done_reason\":\"stop\",\"prompt_eval_count\":12,\"eval_count\":3,"
                + "\"eval_duration\":1}\n";
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

    private static class Recorder implements ChatListener {
        final StringBuilder thinking = new StringBuilder();
        final StringBuilder content = new StringBuilder();
        final AtomicReference<ChatResult> result = new AtomicReference<ChatResult>();
        final AtomicReference<ModelFailure> failure = new AtomicReference<ModelFailure>();
        final AtomicInteger terminals = new AtomicInteger();
        final CountDownLatch done = new CountDownLatch(1);
        volatile String callbackThread;

        public void onThinkingDelta(String delta) {
            thinking.append(delta);
        }

        public void onContentDelta(String delta) {
            content.append(delta);
        }

        public void onCompleted(ChatResult completed) {
            callbackThread = Thread.currentThread().getName();
            result.set(completed);
            terminals.incrementAndGet();
            done.countDown();
        }

        public void onFailed(ModelFailure failed) {
            callbackThread = Thread.currentThread().getName();
            failure.set(failed);
            terminals.incrementAndGet();
            done.countDown();
        }

        /** Starts the call on the port and waits for its terminal callback. */
        Recorder await0(OllamaModelPort port, ChatRequest request) throws InterruptedException {
            port.chat(request, this);
            return await();
        }

        Recorder await() throws InterruptedException {
            assertTrue("terminal callback arrived", done.await(20, TimeUnit.SECONDS));
            return this;
        }
    }

    private static ChatRequest.Builder request(ModelSlot slot) {
        return ChatRequest.builder(slot).message(ModelMessage.system("be brief")).message(ModelMessage.user("hi"));
    }

    private JsonNode body(int index) throws IOException {
        return new ObjectMapper().readTree(bodies.get(index));
    }

    // ------------------------------------------------------------------ tests

    @Test
    public void aSlotCallReachesOllamaWithTheResolvedModelAndTheSettings() throws Exception {
        writeSettings(1, "15m", 4096);
        slotModels.put("judge", "qwen3:4b");
        answers.add(done("yes", "short reasoning"));
        Recorder recorder = new Recorder();
        port.chat(request(JUDGE).thinking(ThinkingMode.LOW).temperature(0.0d).maxOutputTokens(24)
                .outputFormat(OutputFormat.jsonSchema("{\"type\":\"object\"}")).build(), recorder);
        recorder.await();

        assertNull(recorder.failure.get());
        ChatResult result = recorder.result.get();
        assertEquals("yes", result.getContent());
        assertEquals("short reasoning", result.getThinking());
        assertEquals("yes", recorder.content.toString());
        assertEquals("short reasoning", recorder.thinking.toString());
        assertEquals(ChatResult.FinishReason.STOP, result.getFinishReason());
        assertEquals(12, result.getUsage().getPromptTokens());
        assertEquals(3, result.getUsage().getOutputTokens());
        assertEquals("ollama", result.getEndpointId());
        assertFalse("callbacks never run on the caller's thread",
                Thread.currentThread().getName().equals(recorder.callbackThread));

        JsonNode body = body(0);
        assertEquals("the slot's model, never named by the caller", "qwen3:4b", body.get("model").asText());
        assertEquals("keep_alive from the settings keeps the model warm", "15m", body.get("keep_alive").asText());
        assertEquals("low", body.get("think").asText());
        assertEquals("the slot's context setting", 4096, body.get("options").get("num_ctx").asInt());
        assertEquals(0.0d, body.get("options").get("temperature").asDouble(), 0.0d);
        assertEquals(24, body.get("options").get("num_predict").asInt());
        assertEquals("object", body.get("format").get("type").asText());
    }

    @Test
    public void toolsGoOutVerbatimAndToolCallsComeBackWithIdsAndRawArguments() throws Exception {
        answers.add("{\"message\":{\"role\":\"assistant\",\"content\":\"\",\"tool_calls\":[{\"id\":\"call_1\","
                + "\"function\":{\"name\":\"open\",\"arguments\":{\"url\":\"https://x\",\"n\":2}}}]},"
                + "\"done\":false}\n" + done("", ""));
        Recorder recorder = new Recorder();
        port.chat(request(CONVERSATION)
                .tool(new ToolDefinition("open", "Open a page.",
                        "{\"type\":\"object\",\"properties\":{\"url\":{\"type\":\"string\"}}}"))
                .message(ModelMessage.assistant("", "", Collections.singletonList(
                        new ToolCall("call_0", "search", "{\"q\":\"pf4j\"}"))))
                .message(ModelMessage.toolResult("search", "call_0", "3 hits"))
                .build(), recorder);
        recorder.await();

        ChatResult result = recorder.result.get();
        assertNotNull("an empty content WITH tool calls is a regular result: " + recorder.failure.get(), result);
        assertEquals(ChatResult.FinishReason.TOOL_CALLS, result.getFinishReason());
        assertEquals("call_1", result.getToolCalls().get(0).getId());
        assertEquals("open", result.getToolCalls().get(0).getName());
        JsonNode arguments = new ObjectMapper().readTree(result.getToolCalls().get(0).getArgumentsJson());
        assertEquals("https://x", arguments.get("url").asText());
        assertEquals(2, arguments.get("n").asInt());

        JsonNode body = body(0);
        assertEquals("open", body.get("tools").get(0).get("function").get("name").asText());
        assertEquals("string", body.get("tools").get(0).get("function").get("parameters").get("properties")
                .get("url").get("type").asText());
        JsonNode messages = body.get("messages");
        assertEquals("call_0", messages.get(2).get("tool_calls").get(0).get("id").asText());
        assertEquals("pf4j", messages.get(2).get("tool_calls").get(0).get("function").get("arguments")
                .get("q").asText());
        assertEquals("search", messages.get(3).get("tool_name").asText());
        assertNull("no thinking setting = no think field", body.get("think"));
    }

    @Test
    public void reasoningWithoutAnAnswerIsANamedInvalidResponseNeverASuccess() throws Exception {
        answers.add(done("", "I thought about it at length"));
        Recorder recorder = new Recorder().await0(port, request(JUDGE).build());
        assertEquals(ModelFailure.Kind.INVALID_RESPONSE, recorder.failure.get().getKind());
        assertTrue(recorder.failure.get().getDetail().contains("reasoning only"));
    }

    @Test
    public void aSlotWithoutAModelFailsAsNoBinding() throws Exception {
        mainModel = "";
        Recorder recorder = new Recorder().await0(port, request(JUDGE).build());
        assertEquals(ModelFailure.Kind.NO_BINDING, recorder.failure.get().getKind());
        try {
            port.reserve(new SlotRequest(JUDGE, 0, Duration.ZERO, "t")).get(5, TimeUnit.SECONDS);
            fail("expected NO_BINDING");
        } catch (ExecutionException expected) {
            assertEquals(SlotUnavailableException.Reason.NO_BINDING,
                    ((SlotUnavailableException) expected.getCause()).getReason());
        }
    }

    @Test
    public void anUnreachableEndpointIsNamedAsSuch() throws Exception {
        server.stop(0);
        Recorder recorder = new Recorder().await0(port, request(JUDGE).build());
        assertEquals(ModelFailure.Kind.ENDPOINT_UNAVAILABLE, recorder.failure.get().getKind());
    }

    @Test
    public void capacityOneSerialisesConcurrentCallsAndCapacityTwoRunsThemSideBySide() throws Exception {
        answerDelayMillis = 300;
        runConcurrently(3);
        assertEquals("capacity 1 (the default): one call at a time on the wire", 1, maxConcurrent.get());

        writeSettings(2, "5m", 0);
        maxConcurrent.set(0);
        runConcurrently(4);
        assertEquals("capacity 2: two calls at a time, never more", 2, maxConcurrent.get());
    }

    private void runConcurrently(int calls) throws InterruptedException {
        List<Recorder> recorders = new ArrayList<Recorder>();
        for (int i = 0; i < calls; i++) {
            Recorder recorder = new Recorder();
            recorders.add(recorder);
            port.chat(request(JUDGE).build(), recorder);
        }
        for (Recorder recorder : recorders) {
            recorder.await();
            assertNull(String.valueOf(recorder.failure.get()), recorder.failure.get());
        }
    }

    @Test
    public void cancellingDeliversCancelledAtOnceAndFreesTheCapacity() throws Exception {
        gate = new CountDownLatch(1);
        Recorder blocked = new Recorder();
        ModelCall call = port.chat(request(JUDGE).build(), blocked);
        waitForRequests(1);
        long start = System.currentTimeMillis();
        call.cancel();
        blocked.await();
        assertTrue("prompt", System.currentTimeMillis() - start < 3000);
        assertEquals(ModelFailure.Kind.CANCELLED, blocked.failure.get().getKind());
        assertTrue(call.isDone());
        assertEquals("exactly one terminal callback", 1, blocked.terminals.get());

        gate.countDown();
        gate = null;
        Recorder next = new Recorder().await0(port, request(JUDGE).build());
        assertNull("the place was freed for the next call", next.failure.get());
    }

    @Test
    public void aCancelledCallKeepsItsPlaceUntilItsStreamHasReallyStopped() throws Exception {
        answers.add("{\"message\":{\"role\":\"assistant\",\"content\":\"he\"},\"done\":false}\n"
                + done("llo", ""));
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch hold = new CountDownLatch(1);
        Recorder first = new Recorder() {
            @Override
            public void onContentDelta(String delta) {
                entered.countDown();
                // the transport is still busy (like a slow prefill): an interrupt does not end it
                while (hold.getCount() > 0) {
                    try {
                        hold.await();
                    } catch (InterruptedException ignored) {
                        // keep holding the stream
                    }
                }
            }
        };
        ModelCall call = port.chat(request(JUDGE).build(), first);
        assertTrue(entered.await(10, TimeUnit.SECONDS));
        call.cancel();
        first.await();
        assertEquals("the cancel is reported at once", ModelFailure.Kind.CANCELLED, first.failure.get().getKind());

        Recorder next = new Recorder();
        port.chat(request(JUDGE).build(), next);
        Thread.sleep(300);
        assertEquals("capacity 1: the next call waits while the aborted stream still runs", 1, bodies.size());
        assertEquals(1, next.done.getCount());

        hold.countDown();
        next.await();
        assertNull("the place was handed on once the stream stopped", next.failure.get());
        assertEquals(2, bodies.size());
        assertEquals(1, maxConcurrent.get());
    }

    @Test
    public void aCallOverItsTimeLimitEndsAsTimeout() throws Exception {
        gate = new CountDownLatch(1);
        Recorder recorder = new Recorder().await0(port, request(JUDGE).timeout(Duration.ofMillis(300)).build());
        assertEquals(ModelFailure.Kind.TIMEOUT, recorder.failure.get().getKind());
    }

    @Test
    public void explicitReservationsPinTheSlotAndRunSequentialCalls() throws Exception {
        writeSettings(1, "5m", 2048);
        SlotReservation reservation = port.reserve(new SlotRequest(JUDGE, 0, Duration.ofSeconds(5), "task-1"))
                .get(5, TimeUnit.SECONDS);
        assertTrue(reservation.isActive());
        assertEquals("ollama", reservation.getEndpointId());
        assertEquals("the slot default context", 2048, reservation.getContextTokens());
        assertEquals(1, port.resources().capacityOf(JUDGE).get().getActiveReservations());

        // A call for another slot, or with a bigger context than reserved, is refused by name.
        Recorder wrongSlot = new Recorder();
        port.chat(reservation, request(CONVERSATION).build(), wrongSlot);
        assertEquals(ModelFailure.Kind.RESERVATION_INVALID, wrongSlot.await().failure.get().getKind());
        Recorder tooLarge = new Recorder();
        port.chat(reservation, request(JUDGE).contextTokens(4096).build(), tooLarge);
        assertEquals(ModelFailure.Kind.CONTEXT_TOO_LARGE, tooLarge.await().failure.get().getKind());

        // Two sequential calls under one reservation (one worker's tool loop).
        Recorder first = new Recorder();
        port.chat(reservation, request(JUDGE).build(), first);
        assertNull(first.await().failure.get());
        Recorder second = new Recorder();
        port.chat(reservation, request(JUDGE).build(), second);
        assertNull(second.await().failure.get());
        assertEquals(2048, body(1).get("options").get("num_ctx").asInt());

        // While held, an implicit call for the same model waits; closing the reservation lets it run.
        Recorder waiting = new Recorder();
        port.chat(request(JUDGE).build(), waiting);
        assertFalse(waiting.done.await(300, TimeUnit.MILLISECONDS));
        reservation.close();
        reservation.close(); // idempotent
        assertNull(waiting.await().failure.get());
        assertFalse(reservation.isActive());
        Recorder afterClose = new Recorder();
        port.chat(reservation, request(JUDGE).build(), afterClose);
        assertEquals(ModelFailure.Kind.RESERVATION_INVALID, afterClose.await().failure.get().getKind());
    }

    @Test
    public void theResourceViewShowsBindingsCapacitiesAndResidencyWithoutModelNames() throws Exception {
        writeSettings(4, "5m", 0);
        port.resources(); // triggers the first background probe
        ResourceSnapshot snapshot = null;
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            snapshot = port.resources();
            if (snapshot.isResidencyKnown()) {
                break;
            }
            Thread.sleep(50);
        }
        assertNotNull(snapshot);
        assertTrue("residency probed via /api/ps", snapshot.isResidencyKnown());
        assertEquals(1, snapshot.getEndpoints().size());
        assertTrue(snapshot.isResident(JUDGE));
        assertEquals(8192, snapshot.getResidents().get(0).getContextLength());
        assertEquals(7000000000L, snapshot.getResidents().get(0).getVramBytes());
        assertNotNull(snapshot.getResidents().get(0).getExpiresAt());
        assertEquals("both slots share one model and so one capacity",
                snapshot.bindingOf(JUDGE).get().getModel(), snapshot.bindingOf(CONVERSATION).get().getModel());
        assertEquals(4, snapshot.capacityOf(JUDGE).get().getMaxConcurrentCalls());
        assertFalse("handles are opaque", snapshot.bindingOf(JUDGE).get().getModel().getToken().contains("gemma"));
    }

    @Test
    public void aClosedPortRefusesCalls() throws Exception {
        port.close();
        Recorder recorder = new Recorder().await0(port, request(JUDGE).build());
        assertEquals(ModelFailure.Kind.PORT_CLOSED, recorder.failure.get().getKind());
    }

    private void waitForRequests(int count) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (bodies.size() < count && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertEquals(count, bodies.size());
    }
}
