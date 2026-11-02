package com.aresstack.askai.client;

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
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** The adapter keeps thinking, content and tool calls separate up to the app layer. */
public class AskAiOllamaChatStreamTest {

    private HttpServer server;
    private final AtomicReference<String> ndjson = new AtomicReference<String>("");

    @Before
    public void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat", new HttpHandler() {
            public void handle(HttpExchange exchange) throws java.io.IOException {
                drain(exchange.getRequestBody());
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

    private AskAiOllamaClient client() {
        return new AskAiOllamaClient("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @Test
    public void keepsThinkingContentAndToolCallsSeparate() throws Exception {
        ndjson.set("{\"message\":{\"role\":\"assistant\",\"thinking\":\"reason\",\"content\":\"\"}}\n"
                + "{\"message\":{\"role\":\"assistant\",\"content\":\"answer\"}}\n"
                + "{\"message\":{\"role\":\"assistant\",\"content\":\"\",\"tool_calls\":"
                + "[{\"function\":{\"name\":\"open\",\"arguments\":{\"url\":\"x\"}}}]}}\n"
                + "{\"message\":{\"role\":\"assistant\",\"content\":\"\"},\"done\":true,"
                + "\"eval_count\":7,\"eval_duration\":1000}\n");

        final StringBuilder thinking = new StringBuilder();
        final StringBuilder content = new StringBuilder();
        final List<OllamaToolCall> toolCalls = new ArrayList<OllamaToolCall>();
        final AtomicReference<OllamaChatCompletion> completion = new AtomicReference<OllamaChatCompletion>();

        client().streamChat("m", Collections.singletonList(OllamaChatTurn.user("hi")), null, null,
                new OllamaChatStreamListener() {
                    public void onThinkingDelta(String delta) {
                        thinking.append(delta);
                    }

                    public void onContent(String c) {
                        content.append(c);
                    }

                    public void onToolCalls(List<OllamaToolCall> calls) {
                        toolCalls.addAll(calls);
                    }

                    public void onStatus(String status) {
                    }

                    public void onComplete(OllamaChatCompletion c) {
                        completion.set(c);
                    }
                });

        assertEquals("reason", thinking.toString());
        assertEquals("answer", content.toString());
        assertEquals(1, toolCalls.size());
        assertEquals("open", toolCalls.get(0).getName());
        assertEquals("x", toolCalls.get(0).getArguments().get("url"));

        OllamaChatCompletion result = completion.get();
        assertEquals("reason", result.getThinking());
        assertEquals("answer", result.getContent());
        assertEquals(1, result.getToolCalls().size());
        assertEquals(7, result.getEvalCount());
        assertTrue(result.hasMetrics());
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
