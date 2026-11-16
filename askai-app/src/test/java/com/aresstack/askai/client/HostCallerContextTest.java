package com.aresstack.askai.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

import static org.junit.Assert.assertEquals;

/**
 * Plan PR 03, rule 1 of section 4.1: every host caller of {@link AskAiOllamaClient} sends the same
 * {@code num_ctx} for the same model, so the main chat, the party bot, the warm-up, the Models page Load button,
 * the Actions page and the setup test question never make Ollama reload the runner.
 */
public class HostCallerContextTest {

    private HttpServer server;
    private final List<String> bodies = new CopyOnWriteArrayList<String>();
    private AskAiOllamaClient client;

    @Before
    public void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat", exchange -> answer(exchange,
                "{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"done\":true,\"done_reason\":\"stop\"}\n"));
        server.createContext("/api/generate", exchange -> answer(exchange,
                "{\"model\":\"gemma4:e2b\",\"response\":\"ok\",\"done\":true}"));
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        client = new AskAiOllamaClient("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @After
    public void tearDown() {
        ModelContextSizes.install(null);
        server.stop(0);
    }

    private void answer(HttpExchange exchange, String body) throws IOException {
        bodies.add(new String(drain(exchange.getRequestBody()), StandardCharsets.UTF_8));
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        OutputStream out = exchange.getResponseBody();
        out.write(bytes);
        out.close();
    }

    private int numCtx(int index) throws IOException {
        JsonNode body = new ObjectMapper().readTree(bodies.get(index));
        return body.get("options").get("num_ctx").asInt();
    }

    @Test
    public void mainChatWarmUpLoadAndGenerateSendTheModelsContext() throws Exception {
        client.streamChat("gemma4:e2b", Collections.singletonList(OllamaChatTurn.user("hi")), "5m", null);
        client.loadModel("gemma4:e2b", "5m");
        client.generate("gemma4:e2b", "Question: hi");
        assertEquals("main chat and party bot", 32768, numCtx(0));
        assertEquals("warm-up and the Models page Load button", 32768, numCtx(1));
        assertEquals("the Actions page and the setup test question", 32768, numCtx(2));
    }

    @Test
    public void theInstalledSettingsDecideAndACallersOwnValueStays() throws Exception {
        ModelContextSizes.install(model -> "llama3.1:8b".equals(model) ? 24576 : 0);
        client.loadModel("llama3.1:8b", "5m");
        client.streamChat("other:1b", Collections.singletonList(OllamaChatTurn.user("hi")), "5m", null);
        client.streamChat("gemma4:e2b", Collections.singletonList(OllamaChatTurn.user("hi")), "5m", null,
                new OllamaChatOptions(null, null, Collections.<String, Object>singletonMap("num_ctx", 12288), false,
                        null), null);
        assertEquals(24576, numCtx(0));
        assertEquals("a non-positive lookup falls back to the preset", 8192, numCtx(1));
        assertEquals("the model port names the value from the same settings", 12288, numCtx(2));
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
