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
