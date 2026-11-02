package io.github.ollama4j;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.github.ollama4j.json.OllamaJson;
import io.github.ollama4j.models.ChatCompletion;
import io.github.ollama4j.models.ChatMessage;
import io.github.ollama4j.models.ChatStreamListener;
import io.github.ollama4j.models.ToolCall;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Structured /api/chat streaming against a local fake Ollama: thinking / content / tool_calls stay separate. */
public class OllamaChatStreamTest {

    private HttpServer server;
    private final AtomicReference<String> requestBody = new AtomicReference<String>();
    private final AtomicReference<String> ndjson = new AtomicReference<String>("");

    @Before
    public void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat", new HttpHandler() {
            public void handle(HttpExchange exchange) throws java.io.IOException {
                requestBody.set(new String(drain(exchange.getRequestBody()), StandardCharsets.UTF_8));
                byte[] response = ndjson.get().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, response.length);
                OutputStream out = exchange.getResponseBody();
                out.write(response);
                out.close();
            }
        });
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
    }

    @After
    public void tearDown() {
        server.stop(0);
    }

    private Ollama ollama() {
        return new Ollama("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private Recorder streamUser(String text) throws Exception {
        return stream(Collections.singletonList(ChatMessage.user(text)), null, null);
    }

    private Recorder stream(List<ChatMessage> messages, Object think, List<Map<String, Object>> tools)
            throws Exception {
        Recorder recorder = new Recorder();
        ollama().streamChat("m", messages, null, think, tools, recorder);
        return recorder;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> sentBody() {
        return (Map<String, Object>) OllamaJson.parse(requestBody.get());
    }

    // ------------------------------------------------------------------ streaming shapes

    @Test
    public void contentOnly() throws Exception {
        ndjson.set(line("", "Hello", null, false)
                + line("", " world", null, false)
                + done(5, 1000));
        Recorder r = streamUser("hi");
        assertEquals("", r.thinking.toString());
        assertEquals("Hello world", r.content.toString());
        assertTrue(r.toolCalls.isEmpty());
        assertEquals("Hello world", r.completion.getContent());
        assertEquals("", r.completion.getThinking());
    }

    @Test
    public void thinkingOnly() throws Exception {
        ndjson.set(line("Let me think", "", null, false) + done(3, 900));
        Recorder r = streamUser("hi");
        assertEquals("Let me think", r.thinking.toString());
        assertEquals("", r.content.toString());
        assertEquals("Let me think", r.completion.getThinking());
    }

    @Test
    public void thinkingThenContent() throws Exception {
        ndjson.set(line("reason", "", null, false)
                + line("", "answer", null, false)
                + done(4, 800));
        Recorder r = streamUser("hi");
        assertEquals("reason", r.thinking.toString());
        assertEquals("answer", r.content.toString());
        // Order preserved and never merged into one field.
        assertEquals(Arrays.asList("T:reason", "C:answer"), r.order);
    }

    @Test
    public void multipleThinkingDeltas() throws Exception {
        ndjson.set(line("a", "", null, false) + line("b", "", null, false)
                + line("c", "", null, false) + done(1, 100));
        Recorder r = streamUser("hi");
        assertEquals("abc", r.thinking.toString());
        assertEquals(3, r.thinkingDeltas);
    }

    @Test
    public void multipleContentDeltas() throws Exception {
        ndjson.set(line("", "x", null, false) + line("", "y", null, false) + done(1, 100));
        Recorder r = streamUser("hi");
        assertEquals("xy", r.content.toString());
        assertEquals(2, r.contentDeltas);
    }

    @Test
    public void streamedToolCall() throws Exception {
        ndjson.set(line("", "", "[{\"function\":{\"name\":\"open_page\",\"arguments\":{\"url\":\"x\"}}}]", false)
                + done(2, 200));
        Recorder r = streamUser("hi");
        assertEquals(1, r.toolCalls.size());
        assertEquals("open_page", r.toolCalls.get(0).getName());
        assertEquals("x", r.toolCalls.get(0).getArguments().get("url"));
        assertEquals(1, r.completion.getToolCalls().size());
    }

    @Test
    public void multipleParallelToolCalls() throws Exception {
        ndjson.set(line("", "", "[{\"function\":{\"name\":\"a\",\"arguments\":{}}},"
                + "{\"function\":{\"name\":\"b\",\"arguments\":{}}}]", false) + done(2, 200));
        Recorder r = streamUser("hi");
        assertEquals(2, r.toolCalls.size());
        assertEquals("a", r.toolCalls.get(0).getName());
        assertEquals("b", r.toolCalls.get(1).getName());
    }

    @Test
    public void finalMetricsInLastChunk() throws Exception {
        ndjson.set(line("", "hi", null, false) + done(42, 2000000000L));
        Recorder r = streamUser("hi");
        assertEquals(42, r.completion.getEvalCount());
        assertEquals(2000000000L, r.completion.getEvalDurationNanos());
        assertTrue(r.completion.hasMetrics());
    }

    // ------------------------------------------------------------------ request shape

    @Test
    public void requestWithThinkTrue() throws Exception {
        ndjson.set(done(1, 100));
        stream(Collections.singletonList(ChatMessage.user("hi")), Boolean.TRUE, null);
        assertEquals(Boolean.TRUE, sentBody().get("think"));
    }

    @Test
    public void requestWithThinkLevel() throws Exception {
        ndjson.set(done(1, 100));
        stream(Collections.singletonList(ChatMessage.user("hi")), "medium", null);
        assertEquals("medium", sentBody().get("think"));
    }

    @Test
    public void requestWithTools() throws Exception {
        ndjson.set(done(1, 100));
        Map<String, Object> tool = new LinkedHashMap<String, Object>();
        tool.put("type", "function");
        stream(Collections.singletonList(ChatMessage.user("hi")), null, Collections.singletonList(tool));
        Object tools = sentBody().get("tools");
        assertTrue(tools instanceof List);
        assertEquals(1, ((List) tools).size());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void fullHistoryRoundTripsThinkingToolCallAndToolResult() throws Exception {
        ndjson.set(done(1, 100));
        List<ToolCall> calls = new ArrayList<ToolCall>();
        Map<String, Object> args = new LinkedHashMap<String, Object>();
        args.put("q", "spec");
        calls.add(new ToolCall("search", args));
        List<ChatMessage> history = Arrays.asList(
                ChatMessage.user("check the spec"),
                ChatMessage.assistant("thinking hard", "", calls),
                ChatMessage.tool("search", "found it"),
                ChatMessage.assistant("all set"));
        stream(history, null, null);

        List<Object> messages = (List<Object>) sentBody().get("messages");
        assertEquals(4, messages.size());
        Map<String, Object> assistant = (Map<String, Object>) messages.get(1);
        assertEquals("assistant", assistant.get("role"));
        assertEquals("thinking hard", assistant.get("thinking"));
        assertTrue(assistant.get("tool_calls") instanceof List);
        Map<String, Object> toolResult = (Map<String, Object>) messages.get(2);
        assertEquals("tool", toolResult.get("role"));
        assertEquals("search", toolResult.get("tool_name"));
        assertEquals("found it", toolResult.get("content"));
        // A plain assistant turn carries neither thinking nor tool_calls.
        Map<String, Object> plain = (Map<String, Object>) messages.get(3);
        assertFalse(plain.containsKey("thinking"));
        assertFalse(plain.containsKey("tool_calls"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void userMessageCarriesBase64Images() throws Exception {
        ndjson.set(done(1, 100));
        stream(Collections.singletonList(
                ChatMessage.user("what is this", Arrays.asList("QUJD", "ZEVG"))), null, null);
        List<Object> messages = (List<Object>) sentBody().get("messages");
        Map<String, Object> user = (Map<String, Object>) messages.get(0);
        assertEquals("user", user.get("role"));
        assertEquals("what is this", user.get("content"));
        assertTrue(user.get("images") instanceof List);
        assertEquals(Arrays.asList("QUJD", "ZEVG"), user.get("images"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void textOnlyUserMessageOmitsImages() throws Exception {
        ndjson.set(done(1, 100));
        streamUser("hi");
        List<Object> messages = (List<Object>) sentBody().get("messages");
        Map<String, Object> user = (Map<String, Object>) messages.get(0);
        assertFalse(user.containsKey("images"));
    }

    // ------------------------------------------------------------------ helpers

    /** One NDJSON chat chunk. {@code toolCallsJson} is a raw JSON array string, or null for none. */
    private static String line(String thinking, String content, String toolCallsJson, boolean done) {
        StringBuilder message = new StringBuilder("{\"role\":\"assistant\"");
        if (thinking != null && thinking.length() > 0) {
            message.append(",\"thinking\":\"").append(thinking).append("\"");
        }
        message.append(",\"content\":\"").append(content == null ? "" : content).append("\"");
        if (toolCallsJson != null) {
            message.append(",\"tool_calls\":").append(toolCallsJson);
        }
        message.append("}");
        return "{\"message\":" + message + ",\"done\":" + done + "}\n";
    }

    private static String done(long evalCount, long evalDuration) {
        return "{\"message\":{\"role\":\"assistant\",\"content\":\"\"},\"done\":true,"
                + "\"eval_count\":" + evalCount + ",\"eval_duration\":" + evalDuration + "}\n";
    }

    private static byte[] drain(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = in.read(chunk)) >= 0) {
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }

    /** Records the structured callbacks so a test can assert the separation. */
    private static final class Recorder implements ChatStreamListener {
        final StringBuilder thinking = new StringBuilder();
        final StringBuilder content = new StringBuilder();
        final List<ToolCall> toolCalls = new ArrayList<ToolCall>();
        final List<String> order = new ArrayList<String>();
        int thinkingDeltas;
        int contentDeltas;
        ChatCompletion completion;

        public void onThinkingDelta(String delta) {
            thinking.append(delta);
            thinkingDeltas++;
            order.add("T:" + delta);
        }

        public void onContentDelta(String delta) {
            content.append(delta);
            contentDeltas++;
            order.add("C:" + delta);
        }

        public void onToolCalls(List<ToolCall> calls) {
            toolCalls.addAll(calls);
        }

        public void onComplete(ChatCompletion c) {
            completion = c;
        }
    }
}
