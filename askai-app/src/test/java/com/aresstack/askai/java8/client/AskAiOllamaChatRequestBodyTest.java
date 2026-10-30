package com.aresstack.askai.java8.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
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
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * What AskAI really puts on the wire for {@code /api/chat} through the official ollama4j client: the
 * {@code think} value verbatim (the library's own enum stops at "high"), {@code tool_name} on tool-result
 * turns (the library's message has no such field), the assistant's reasoning and tool calls, base64
 * images — and none of the library-internal fields ({@code useTools}, an empty {@code tools} list).
 */
public class AskAiOllamaChatRequestBodyTest {

    private static final String DONE = "{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"done\":true,"
            + "\"eval_count\":1,\"eval_duration\":1}\n";

    private HttpServer server;
    private final AtomicReference<String> requestBody = new AtomicReference<String>("");

    @Before
    public void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat", new HttpHandler() {
            public void handle(HttpExchange exchange) throws java.io.IOException {
                requestBody.set(new String(drain(exchange.getRequestBody()), StandardCharsets.UTF_8));
                byte[] response = DONE.getBytes(StandardCharsets.UTF_8);
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

    private AskAiOllamaClient client() {
        return new AskAiOllamaClient("http://127.0.0.1:" + server.getAddress().getPort() + "/");
    }

    private JsonNode send(Object think, List<OllamaChatTurn> turns) throws Exception {
        client().streamChat("m", turns, "5m", think, null);
        return new ObjectMapper().readTree(requestBody.get());
    }

    @Test
    public void thinkLevelsTravelVerbatimIncludingMax() throws Exception {
        JsonNode body = send("max", Collections.singletonList(OllamaChatTurn.user("hi")));
        assertEquals("max", body.get("think").asText());
        assertEquals("m", body.get("model").asText());
        assertEquals("5m", body.get("keep_alive").asText());
        assertTrue(body.get("stream").asBoolean());

        assertTrue(send(Boolean.TRUE, Collections.singletonList(OllamaChatTurn.user("hi"))).get("think").asBoolean());
        assertEquals("low", send("low", Collections.singletonList(OllamaChatTurn.user("hi"))).get("think").asText());
        assertNull("no think field when thinking is not requested",
                send(null, Collections.singletonList(OllamaChatTurn.user("hi"))).get("think"));
    }

    @Test
    public void libraryInternalFieldsNeverReachOllama() throws Exception {
        JsonNode body = send(null, Collections.singletonList(OllamaChatTurn.user("hi")));
        assertNull(body.get("useTools"));
        assertNull("an empty tools list is not sent", body.get("tools"));
    }

    @Test
    public void everyTurnKindIsEncodedTheWayOllamaExpects() throws Exception {
        List<OllamaToolCall> calls = Collections.singletonList(new OllamaToolCall("open",
                Collections.<String, Object>singletonMap("url", "https://x")));
        List<OllamaChatTurn> turns = new ArrayList<OllamaChatTurn>(Arrays.asList(
                OllamaChatTurn.system("be brief"),
                OllamaChatTurn.user("look", Collections.singletonList("AQID")),
                OllamaChatTurn.assistant("let me open it", "", calls),
                OllamaChatTurn.tool("open", "page text")));
        JsonNode messages = send(null, turns).get("messages");
        assertEquals(4, messages.size());

        assertEquals("system", messages.get(0).get("role").asText());
        assertEquals("be brief", messages.get(0).get("content").asText());
        assertNull(messages.get(0).get("tool_name"));

        assertEquals("user", messages.get(1).get("role").asText());
        assertEquals("AQID", messages.get(1).get("images").get(0).asText());

        JsonNode assistant = messages.get(2);
        assertEquals("assistant", assistant.get("role").asText());
        assertEquals("let me open it", assistant.get("thinking").asText());
        JsonNode function = assistant.get("tool_calls").get(0).get("function");
        assertEquals("open", function.get("name").asText());
        assertEquals("https://x", function.get("arguments").get("url").asText());

        JsonNode tool = messages.get(3);
        assertEquals("tool", tool.get("role").asText());
        assertEquals("open", tool.get("tool_name").asText());
        assertEquals("page text", tool.get("content").asText());
        assertFalse(requestBody.get().contains("useTools"));
    }

    @Test
    public void modelPortCallsCarryRawToolSchemasFormatOptionsAndKeepAlive() throws Exception {
        String toolSchema = "{\"type\":\"object\",\"properties\":{\"url\":{\"type\":\"string\","
                + "\"pattern\":\"^https://\"}},\"required\":[\"url\"],\"additionalProperties\":false}";
        java.util.Map<String, Object> options = new java.util.LinkedHashMap<String, Object>();
        options.put("num_ctx", 8192);
        options.put("temperature", 0.2d);
        options.put("num_predict", 256);
        OllamaChatOptions extras = new OllamaChatOptions(
                Collections.singletonList(new OllamaChatOptions.Tool("open", "Open a page.", toolSchema)),
                "{\"type\":\"object\",\"properties\":{\"verdict\":{\"enum\":[\"yes\",\"no\"]}}}",
                options, false, null);
        client().streamChat("m", Collections.singletonList(OllamaChatTurn.user("hi")), "30m", Boolean.FALSE,
                extras, null);
        JsonNode body = new ObjectMapper().readTree(requestBody.get());

        assertEquals("keep_alive is the per-call value", "30m", body.get("keep_alive").asText());
        assertFalse(body.get("think").asBoolean());
        JsonNode tool = body.get("tools").get(0);
        assertEquals("function", tool.get("type").asText());
        assertEquals("open", tool.get("function").get("name").asText());
        assertEquals("Open a page.", tool.get("function").get("description").asText());
        // The schema travels VERBATIM: keywords the library's typed tool spec has no field for survive.
        assertEquals(new ObjectMapper().readTree(toolSchema), tool.get("function").get("parameters"));
        assertEquals("^https://", tool.get("function").get("parameters").get("properties").get("url")
                .get("pattern").asText());
        assertEquals("yes", body.get("format").get("properties").get("verdict").get("enum").get(0).asText());
        assertEquals(8192, body.get("options").get("num_ctx").asInt());
        assertEquals(0.2d, body.get("options").get("temperature").asDouble(), 0.0d);
        assertEquals(256, body.get("options").get("num_predict").asInt());
        assertNull(body.get("useTools"));
    }

    @Test
    public void genericJsonModeIsTheJsonFormatString() throws Exception {
        client().streamChat("m", Collections.singletonList(OllamaChatTurn.user("hi")), "5m", null,
                new OllamaChatOptions(null, null, null, true, null), null);
        JsonNode body = new ObjectMapper().readTree(requestBody.get());
        assertEquals("json", body.get("format").asText());
        assertNull("no tools offered, none sent", body.get("tools"));
        assertNull("no options set, none sent", body.get("options"));
    }

    @Test
    public void replayedToolCallsKeepTheProviderCallId() throws Exception {
        List<OllamaToolCall> calls = Collections.singletonList(new OllamaToolCall("call_7", "open",
                Collections.<String, Object>singletonMap("url", "https://x")));
        JsonNode messages = send(null, Arrays.asList(OllamaChatTurn.user("go"),
                OllamaChatTurn.assistant("", "", calls), OllamaChatTurn.tool("open", "page text"))).get("messages");
        assertEquals("call_7", messages.get(1).get("tool_calls").get(0).get("id").asText());
        assertEquals("open", messages.get(2).get("tool_name").asText());
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
}
