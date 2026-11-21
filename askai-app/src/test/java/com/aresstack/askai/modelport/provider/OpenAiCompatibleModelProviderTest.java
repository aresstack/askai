package com.aresstack.askai.modelport.provider;

import com.aresstack.askai.agent.model.port.ModelMessage;
import com.aresstack.askai.agent.model.port.ThinkingMode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The OpenAI-compatible provider against a scripted service on {@code /v1}: what reaches the wire (path, key only
 * in the header, no event-stream Accept), the streamed and the one-piece answer, named failures without the key,
 * retries, refused fields sent again in a weaker form, a broken stream, the abort, the model list, and that nothing
 * is ever loaded or unloaded.
 */
public class OpenAiCompatibleModelProviderTest {

    private static final String KEY = "sk-test-0123456789abcdef";
    private static final String DONE = "data: [DONE]\n\n";

    private interface Script {
        void answer(HttpExchange exchange) throws IOException;
    }

    private HttpServer server;
    private String baseUrl;
    private final AtomicInteger requests = new AtomicInteger();
    private final List<String> paths = new CopyOnWriteArrayList<String>();
    private final List<String> authorizations = new CopyOnWriteArrayList<String>();
    private final List<String> accepts = new CopyOnWriteArrayList<String>();
    private final List<String> bodies = new CopyOnWriteArrayList<String>();
    private final CountDownLatch release = new CountDownLatch(1);
    private volatile Script chat = exchange -> sse(exchange, data("{\"choices\":[{\"delta\":{\"content\":\"ok\"},"
            + "\"finish_reason\":\"stop\"}]}") + DONE);
    private volatile Script models = exchange -> respond(exchange, 200, "application/json", "{\"data\":[]}");
    private volatile char[] key = KEY.toCharArray();
    private volatile IOException keyFailure;
    private volatile int retries = 2;

    @Before
    public void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> handle(exchange, chat));
        server.createContext("/v1/models", exchange -> handle(exchange, models));
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    @After
    public void tearDown() {
        release.countDown();
        server.stop(0);
    }

    private void handle(HttpExchange exchange, Script script) throws IOException {
        requests.incrementAndGet();
        paths.add(exchange.getRequestURI().getPath());
        authorizations.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
        accepts.add(String.valueOf(exchange.getRequestHeaders().getFirst("Accept")));
        bodies.add(new String(drain(exchange.getRequestBody()), StandardCharsets.UTF_8));
        try {
            script.answer(exchange);
        } catch (IOException closedByClient) {
            // an aborted call closed the connection
        } finally {
            exchange.close();
        }
    }

    private OpenAiCompatibleModelProvider provider() {
        return new OpenAiCompatibleModelProvider(new OpenAiCompatibleModelProvider.Settings() {
            public int defaultCapacity() {
                return 8;
            }

            public int contextTokens(String model) {
                return 65536;
            }

            public int connectTimeoutMillis() {
                return 5000;
            }

            public int readTimeoutMillis() {
                return 30000;
            }

            public int retries() {
                return retries;
            }

            public char[] apiKey() throws IOException {
                if (keyFailure != null) {
                    throw keyFailure;
                }
                return key == null ? null : key.clone();
            }
        }, OpenAiCompatibleModelProvider.PLAIN);
    }

    private static ProviderCall call(String model, AbortSignal abort) {
        return new ProviderCall("planner", model, Collections.singletonList(ModelMessage.user("question")),
                null, null, ThinkingMode.DEFAULT, ThinkingMode.DEFAULT, null, 500, 65536, null, "30m", abort);
    }

    /** A call with a temperature and a reasoning level, as the research roles send them. */
    private static ProviderCall tunedCall(String model) {
        return new ProviderCall("planner", model, Collections.singletonList(ModelMessage.user("question")),
                null, null, ThinkingMode.LOW, ThinkingMode.DEFAULT, 0.2d, 500, 65536, null, "30m", null);
    }

    private static final String OK = "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}\n\n"
            + DONE;

    private static final class Deltas implements ProviderStream {
        final List<String> seen = new CopyOnWriteArrayList<String>();

        public void onThinkingDelta(String delta) {
            seen.add("T:" + delta);
        }

        public void onContentDelta(String delta) {
            seen.add("C:" + delta);
        }
    }

    /** An abort signal like the port's: actions registered by the provider run when the call is aborted. */
    private static final class Abort implements AbortSignal {
        final AtomicBoolean aborted = new AtomicBoolean();
        final List<Runnable> actions = new CopyOnWriteArrayList<Runnable>();

        public boolean isAborted() {
            return aborted.get();
        }

        public void onAbort(Runnable action) {
            actions.add(action);
            if (aborted.get()) {
                action.run();
            }
        }

        /** Aborts like a cancel from another thread; returns how long the aborting thread was held. */
        long abort() {
            long start = System.nanoTime();
            aborted.set(true);
            for (Runnable action : actions) {
                action.run();
            }
            return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        }
    }

    // ------------------------------------------------------------------ answers

    @Test
    public void streamsTheAnswerAndSendsTheKeyOnlyInTheAuthorizationHeader() throws Exception {
        chat = exchange -> sse(exchange, data("{\"choices\":[{\"delta\":{\"reasoning_content\":\"hm\"}}]}")
                + data("{\"choices\":[{\"delta\":{\"content\":\"Hel\"}}]}")
                + data("{\"choices\":[{\"delta\":{\"content\":\"lo\"},\"finish_reason\":\"stop\"}]}") + DONE);
        Deltas deltas = new Deltas();

        ProviderCompletion completion = provider().chat(baseUrl + "/", call("gemma-4-31b", null), deltas);

        assertEquals("Hello", completion.getContent());
        assertEquals("hm", completion.getThinking());
        assertEquals("[T:hm, C:Hel, C:lo]", deltas.seen.toString());
        assertEquals("/v1/chat/completions", paths.get(0));
        assertEquals("Bearer " + KEY, authorizations.get(0));
        assertFalse("one service refused the event-stream Accept", accepts.get(0).contains("text/event-stream"));
        assertTrue(bodies.get(0), bodies.get(0).contains("\"stream\":true"));
        assertTrue("the token usage is asked for", bodies.get(0).contains("\"include_usage\":true"));
        assertTrue(bodies.get(0).contains("\"model\":\"gemma-4-31b\""));
        assertFalse("the key never travels in the body", bodies.get(0).contains(KEY));
    }

    @Test
    public void aServiceThatIgnoresStreamingIsReadInOnePiece() throws Exception {
        chat = exchange -> respond(exchange, 200, "application/json; charset=utf-8", "{\"choices\":[{\"message\":"
                + "{\"role\":\"assistant\",\"content\":\"whole\"},\"finish_reason\":\"stop\"}],\"usage\":"
                + "{\"prompt_tokens\":5,\"completion_tokens\":1}}");

        ProviderCompletion completion = provider().chat(baseUrl, call("m", null), new Deltas());

        assertEquals("whole", completion.getContent());
        assertEquals(5, completion.getUsage().getPromptTokens());
    }

    // ------------------------------------------------------------------ failures

    @Test
    public void withoutAKeyNoAuthorizationIsSentAndTheRefusalSaysWhereToEnterIt() throws Exception {
        key = null;
        chat = exchange -> respond(exchange, 401, "application/json", "{\"error\":{\"message\":\"missing bearer\"}}");

        ProviderException failure = failure(provider(), call("m", null));

        assertEquals("null", authorizations.get(0));
        assertTrue(failure.getMessage(), failure.getMessage().contains("no API key is set"));
        assertTrue(failure.getMessage(), failure.getMessage().contains("Model provider"));
        assertTrue("the service's own words are kept", failure.getMessage().contains("missing bearer"));
        assertFalse(failure.isUnreachable());
        assertEquals("a refusal is not retried", 1, requests.get());
    }

    @Test
    public void aRefusedKeyIsNamedAndNeverEchoed() throws Exception {
        chat = exchange -> respond(exchange, 401, "application/json",
                "{\"error\":{\"message\":\"Incorrect API key provided: " + KEY + "\"}}");

        ProviderException failure = failure(provider(), call("m", null));

        assertTrue(failure.getMessage(), failure.getMessage().contains("refused the API key"));
        assertTrue(failure.getMessage(), failure.getMessage().contains("***"));
        assertFalse("the key never appears in a message", failure.getMessage().contains(KEY));
    }

    @Test
    public void aRateLimitIsRetriedHonouringRetryAfter() throws Exception {
        final AtomicInteger attempts = new AtomicInteger();
        chat = exchange -> {
            if (attempts.incrementAndGet() == 1) {
                exchange.getResponseHeaders().set("Retry-After", "0");
                respond(exchange, 429, "application/json", "{\"error\":{\"message\":\"slow down\"}}");
            } else {
                sse(exchange, data("{\"choices\":[{\"delta\":{\"content\":\"second\"},\"finish_reason\":\"stop\"}]}")
                        + DONE);
            }
        };

        ProviderCompletion completion = provider().chat(baseUrl, call("m", null), new Deltas());

        assertEquals("second", completion.getContent());
        assertEquals(2, requests.get());
    }

    @Test
    public void aRateLimitBeyondTheRetriesIsNamed() throws Exception {
        retries = 1;
        chat = exchange -> {
            exchange.getResponseHeaders().set("Retry-After", "0");
            respond(exchange, 429, "application/json", "{\"error\":{\"message\":\"slow down\"}}");
        };

        ProviderException failure = failure(provider(), call("m", null));

        assertTrue(failure.getMessage(), failure.getMessage().contains("rate limiting"));
        assertTrue(failure.getMessage(), failure.getMessage().contains("after 1 retries"));
        assertEquals("one try and one retry", 2, requests.get());
    }

    @Test
    public void notFoundPointsAtTheBaseUrl() throws Exception {
        chat = exchange -> respond(exchange, 404, "text/html", "<html><body>Not Found</body></html>");

        ProviderException failure = failure(provider(), call("m", null));

        assertTrue(failure.getMessage(), failure.getMessage().contains("HTTP 404"));
        assertTrue(failure.getMessage(), failure.getMessage().contains("/v1"));
    }

    @Test
    public void aStreamThatBreaksOffBeforeDoneFails() throws Exception {
        chat = exchange -> sse(exchange, data("{\"choices\":[{\"delta\":{\"content\":\"half an ans\"}}]}"));

        ProviderException failure = failure(provider(), call("m", null));

        assertTrue(failure.getMessage(), failure.getMessage().contains("ended before [DONE]"));
    }

    @Test
    public void anUnreadableStoredKeyEndsTheCallBeforeAnythingIsSent() throws Exception {
        keyFailure = new IOException("the stored API key cannot be read (AEADBadTagException); enter it again");

        ProviderException failure = failure(provider(), call("m", null));

        assertTrue(failure.getMessage(), failure.getMessage().contains("enter it again"));
        assertEquals(0, requests.get());
    }

    @Test
    public void aKeyWithALineBreakIsRefusedWithoutQuotingIt() throws Exception {
        key = "sk-SECRET123\nsk-other".toCharArray(); // an environment variable read from a two-line file

        ProviderException chatFailure = failure(provider(), call("m", null));
        assertEquals(OpenAiCompatibleModelProvider.KEY_WITH_CONTROL_CHARACTERS, chatFailure.getMessage());
        try {
            provider().modelNames(baseUrl);
            fail("a key the header cannot carry lists nothing");
        } catch (ProviderException expected) {
            assertFalse(expected.getMessage(), expected.getMessage().contains("SECRET"));
        }
        assertEquals("nothing was sent", 0, requests.get());
    }

    // ------------------------------------------------------------------ refused fields

    @Test
    public void aRefusedFieldIsSentAgainInAWeakerFormThatIsKeptForTheModel() throws Exception {
        chat = exchange -> {
            String body = bodies.get(bodies.size() - 1);
            if (body.contains("\"max_tokens\"")) {
                respond(exchange, 400, "application/json", "{\"error\":{\"message\":\"Unsupported parameter: "
                        + "'max_tokens' is not supported with this model. Use 'max_completion_tokens' instead.\","
                        + "\"type\":\"invalid_request_error\",\"param\":\"max_tokens\",\"code\":\"unsupported_parameter\"}}");
            } else {
                sse(exchange, OK);
            }
        };
        OpenAiCompatibleModelProvider provider = provider();

        assertEquals("ok", provider.chat(baseUrl, call("gpt-5-mini", null), new Deltas()).getContent());
        assertEquals("the refusal and the weaker request", 2, requests.get());
        assertTrue(bodies.get(1), bodies.get(1).contains("\"max_completion_tokens\":500"));
        assertFalse(bodies.get(1).contains("\"max_tokens\""));

        assertEquals("ok", provider.chat(baseUrl + "/", call("gpt-5-mini", null), new Deltas()).getContent());
        assertEquals("the next call of the model starts in the accepted form", 3, requests.get());
        assertTrue(bodies.get(2).contains("\"max_completion_tokens\""));

        provider.chat(baseUrl, call("gpt-4o-mini", null), new Deltas());
        assertTrue("another model starts with the full request again", bodies.get(3).contains("\"max_tokens\""));
    }

    @Test
    public void refusalsOneAfterAnotherAreAnsweredOneByOneAndAreNoRetries() throws Exception {
        retries = 0;
        chat = exchange -> {
            String body = bodies.get(bodies.size() - 1);
            if (body.contains("\"temperature\"")) {
                respond(exchange, 400, "application/json", "{\"error\":{\"message\":\"Unsupported value: "
                        + "'temperature' does not support 0.2 with this model.\",\"param\":\"temperature\"}}");
            } else if (body.contains("\"reasoning_effort\"")) {
                respond(exchange, 400, "application/json",
                        "{\"error\":{\"message\":\"m does not support thinking\",\"param\":null}}");
            } else {
                sse(exchange, OK);
            }
        };

        assertEquals("ok", provider().chat(baseUrl, tunedCall("m"), new Deltas()).getContent());
        assertEquals(3, requests.get());
        assertFalse(bodies.get(2).contains("temperature"));
        assertFalse(bodies.get(2).contains("reasoning_effort"));
        assertTrue("everything else is still sent", bodies.get(2).contains("\"max_tokens\":500"));
    }

    @Test
    public void aStrictServersValidationRefusalDropsTheUnknownField() throws Exception {
        chat = exchange -> {
            if (bodies.get(bodies.size() - 1).contains("stream_options")) {
                respond(exchange, 422, "application/json", "{\"detail\":[{\"type\":\"extra_forbidden\","
                        + "\"loc\":[\"body\",\"stream_options\"],\"msg\":\"Extra inputs are not permitted\","
                        + "\"input\":{\"include_usage\":true}}]}");
            } else {
                sse(exchange, OK);
            }
        };

        assertEquals("ok", provider().chat(baseUrl, call("m", null), new Deltas()).getContent());
        assertEquals(2, requests.get());
        assertFalse(bodies.get(1).contains("stream_options"));
    }

    @Test
    public void aRefusalNamingNoFieldIsReportedAfterOneRequest() throws Exception {
        chat = exchange -> respond(exchange, 400, "application/json", "{\"error\":{\"message\":\"Invalid "
                + "'messages[0].content': string too long\",\"param\":\"messages\"}}");

        ProviderException failure = failure(provider(), call("m", null));

        assertTrue(failure.getMessage(), failure.getMessage().contains("HTTP 400"));
        assertTrue("the service's words are kept", failure.getMessage().contains("string too long"));
        assertEquals(1, requests.get());
    }

    @Test
    public void aWeakerFormThatIsRefusedAsWellIsNotKept() throws Exception {
        chat = exchange -> {
            if (bodies.get(bodies.size() - 1).contains("\"temperature\"")) {
                respond(exchange, 400, "application/json",
                        "{\"error\":{\"message\":\"bad temperature\",\"param\":\"temperature\"}}");
            } else {
                respond(exchange, 400, "application/json", "{\"error\":{\"message\":\"model is busy elsewhere\"}}");
            }
        };
        OpenAiCompatibleModelProvider provider = provider();

        ProviderException failure = failure(provider, tunedCall("m"));
        assertTrue(failure.getMessage(), failure.getMessage().contains("model is busy elsewhere"));
        assertEquals(2, requests.get());

        failure(provider, tunedCall("m"));
        assertTrue("an unhelpful weaker form is not remembered", bodies.get(2).contains("\"temperature\""));
    }

    @Test
    public void anUnreachableServiceIsReportedAsUnreachable() throws Exception {
        int closedPort;
        ServerSocket probe = new ServerSocket(0);
        try {
            closedPort = probe.getLocalPort();
        } finally {
            probe.close();
        }
        String nowhere = "http://127.0.0.1:" + closedPort + "/v1";

        ProviderException chatFailure = failure(provider(), nowhere, call("m", null));
        assertTrue(chatFailure.getMessage(), chatFailure.isUnreachable());
        assertFalse(chatFailure.getMessage().contains(KEY));
        try {
            provider().modelNames(nowhere);
            fail("an unreachable service has no model list");
        } catch (ProviderException expected) {
            assertTrue(expected.isUnreachable());
        }
    }

    // ------------------------------------------------------------------ abort

    @Test
    public void anAbortBeforeTheServiceAnswersClosesTheConnectionAtOnce() throws Exception {
        chat = exchange -> await(release); // the service never answers while the test runs
        final Abort abort = new Abort();
        final AtomicLong held = new AtomicLong(-1L);
        Thread canceller = new Thread(() -> {
            sleep(300);
            held.set(abort.abort());
        });
        canceller.start();
        long start = System.nanoTime();

        ProviderException failure = failure(provider(), call("m", abort));

        long tookMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        canceller.join(5000);
        assertTrue(failure.getMessage(), failure.getMessage().contains("aborted"));
        assertTrue("the call ends at once, not after the read time-out: " + tookMillis + " ms", tookMillis < 5000);
        assertTrue("the cancelling thread is not held: " + held.get() + " ms", held.get() >= 0 && held.get() < 1000);
    }

    @Test
    public void anAbortDuringTheStreamEndsItAtTheNextChunkWithoutHoldingTheCanceller() throws Exception {
        final CountDownLatch clientGone = new CountDownLatch(1);
        chat = exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            OutputStream out = exchange.getResponseBody();
            for (int i = 0; i < 300; i++) { // a token every 100 ms, as a generating model sends them
                try {
                    out.write(data("{\"choices\":[{\"delta\":{\"content\":\"t" + i + " \"}}]}")
                            .getBytes(StandardCharsets.UTF_8));
                    out.flush();
                } catch (IOException closed) {
                    clientGone.countDown();
                    return;
                }
                sleep(100);
            }
        };
        final Abort abort = new Abort();
        final AtomicLong held = new AtomicLong(-1L);
        final AtomicBoolean started = new AtomicBoolean();
        ProviderStream stream = new ProviderStream() {
            public void onThinkingDelta(String delta) {
            }

            public void onContentDelta(String delta) {
                if (started.compareAndSet(false, true)) {
                    Thread canceller = new Thread(() -> {
                        sleep(150);
                        held.set(abort.abort());
                    });
                    canceller.start();
                }
            }
        };
        long start = System.nanoTime();

        try {
            provider().chat(baseUrl, call("m", abort), stream);
            fail("an aborted call has no completion");
        } catch (ProviderException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("aborted"));
        }

        long tookMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertTrue("the stream ends at the next chunk: " + tookMillis + " ms", tookMillis < 5000);
        assertTrue("the cancelling thread is not held: " + held.get() + " ms", held.get() >= 0 && held.get() < 1000);
        assertTrue("the service sees the connection closed, so it can stop generating",
                clientGone.await(10, TimeUnit.SECONDS));
    }

    // ------------------------------------------------------------------ models and resources

    @Test
    public void listsTheModelsSortedAndOffersOnlyExactNames() throws Exception {
        models = exchange -> respond(exchange, 200, "application/json", "{\"object\":\"list\",\"data\":["
                + "{\"id\":\"openai/gpt-oss-120b\",\"object\":\"model\"},{\"id\":\"gemma-4-31b\"},"
                + "{\"id\":\"gemma-4-31b\"},{\"object\":\"model\"}]}");
        OpenAiCompatibleModelProvider provider = provider();

        List<String> names = provider.modelNames(baseUrl);

        assertEquals(Arrays.asList("gemma-4-31b", "openai/gpt-oss-120b"), names);
        assertEquals("/v1/models", paths.get(0));
        assertEquals("Bearer " + KEY, authorizations.get(0));
        assertTrue(provider.offers(names, "gemma-4-31b"));
        assertFalse("no tag is implied on an API", provider.offers(Collections.singletonList("gemma4"),
                "gemma4:latest"));
        assertTrue(provider.missingModelMessage("x", "planner").contains("Model provider"));
    }

    @Test
    public void aRefusedModelListIsNamed() throws Exception {
        key = null;
        models = exchange -> respond(exchange, 403, "application/json", "{\"error\":\"forbidden\"}");
        try {
            provider().modelNames(baseUrl);
            fail("a refused list must fail");
        } catch (ProviderException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("no API key is set"));
        }
    }

    @Test
    public void theServiceSharesNoMemoryAndAskAiNeverLoadsOrUnloadsThere() throws Exception {
        OpenAiCompatibleModelProvider provider = provider();

        provider.load(baseUrl, "gemma-4-31b", 65536, "30m");
        provider.unload(baseUrl, "gemma-4-31b");

        assertFalse(provider.sharesMemory());
        assertEquals(8, provider.defaultCapacity());
        assertEquals(65536, provider.contextTokens("gemma-4-31b"));
        assertNull("no residency to report", provider.residentModels(baseUrl));
        assertEquals("nothing reached the service", 0, requests.get());
    }

    @Test
    public void gptOssUnderAVendorPrefixCannotTurnThinkingOffOnTheApiOnly() {
        assertTrue(provider().isLevelOnlyModel("openai/gpt-oss-120b"));
        assertTrue(provider().isLevelOnlyModel("gpt-oss-20b"));
        assertFalse(provider().isLevelOnlyModel("gemma-4-31b"));
        assertFalse("Ollama reads its names as before (a namespaced GGUF passes there)",
                new OllamaModelProvider(null).isLevelOnlyModel("hf.co/unsloth/gpt-oss-20b-GGUF:Q4_K_M"));
        assertTrue(provider().cannotTurnThinkingOffMessage("openai/gpt-oss-120b", "judge")
                .contains("Model provider"));
    }

    @Test
    public void messagesAreShortRedactedAndTheUrlIsJoinedCleanly() {
        assertEquals("http://h/v1/models", OpenAiCompatibleModelProvider.join("http://h/v1//", "/models"));
        assertEquals("token *** leaked", OpenAiCompatibleModelProvider.redact("token " + KEY + " leaked", KEY));
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            longText.append("line ").append(i).append('\n');
        }
        String shortened = OpenAiCompatibleModelProvider.shorten(longText.toString());
        assertEquals(OpenAiCompatibleModelProvider.MAX_DETAIL_LENGTH + 3, shortened.length());
        assertFalse(shortened.contains("\n"));
        assertTrue(OpenAiCompatibleModelProvider.statusMessage(503, "busy", 2, true).contains("after 2 retries"));
        assertTrue(OpenAiCompatibleModelProvider.statusMessage(403, "", 0, false).contains("no API key is set"));
        assertTrue(OpenAiCompatibleModelProvider.statusMessage(403, "", 0, true).contains("refused the API key"));
    }

    // ------------------------------------------------------------------ helpers

    private ProviderException failure(OpenAiCompatibleModelProvider provider, ProviderCall call) {
        return failure(provider, baseUrl, call);
    }

    private static ProviderException failure(OpenAiCompatibleModelProvider provider, String url, ProviderCall call) {
        try {
            provider.chat(url, call, new Deltas());
        } catch (ProviderException expected) {
            return expected;
        }
        throw new AssertionError("the call should have failed");
    }

    private static String data(String json) {
        return "data: " + json + "\n\n";
    }

    private static void sse(HttpExchange exchange, String events) throws IOException {
        respond(exchange, 200, "text/event-stream", events);
    }

    private static void respond(HttpExchange exchange, int status, String contentType, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        if (contentType != null) {
            exchange.getResponseHeaders().set("Content-Type", contentType);
        }
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            OutputStream out = exchange.getResponseBody();
            out.write(bytes);
            out.close();
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

    private static void await(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
