package com.aresstack.askai.research.benchmark;

import com.sun.net.httpserver.HttpServer;
import org.junit.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class OllamaProbeTest {

    private static final String TAGS = "{\"models\":[{\"name\":\"nomic-embed-text:latest\"},"
            + "{\"name\":\"gemma4:e2b\"},{\"name\":\"bge-reranker:latest\"},{\"name\":\"gpt-oss:20b\"}]}";

    @Test
    public void anUnreachableOllamaGivesAReadableSkipReason() throws Exception {
        String url = "http://127.0.0.1:1"; // never served here
        OllamaProbe probe = OllamaProbe.probe(url);
        assertFalse(probe.isReachable());
        assertTrue(probe.modelsToRun(Collections.<String>emptyList()).isEmpty());
        String reason = probe.modelProblem(Collections.<String>emptyList());
        assertTrue(reason, reason.startsWith("no Ollama reachable at " + url));
        assertTrue(reason, reason.contains("askai.benchmark.ollamaUrl"));
    }

    @Test
    public void chatModelsLeaveOutEmbeddingAndRerankerModels() {
        assertEquals(Arrays.asList("gemma4:e2b", "gpt-oss:20b"), OllamaProbe.chatModels(TAGS));
        assertTrue(OllamaProbe.chatModels("not json").isEmpty());
    }

    @Test
    public void requestedModelsMustBeInstalled() throws Exception {
        HttpServer ollama = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ollama.createContext("/api/tags", exchange -> {
            byte[] body = TAGS.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        ollama.createContext("/api/version", exchange -> {
            byte[] body = "{\"version\":\"0.12.6\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        ollama.start();
        try {
            OllamaProbe probe = OllamaProbe.probe("http://127.0.0.1:" + ollama.getAddress().getPort());
            assertTrue(probe.isReachable());
            assertEquals("0.12.6", probe.getVersion());
            assertEquals(Collections.singletonList("gemma4:e2b"),
                    probe.modelsToRun(Collections.<String>emptyList()));
            assertEquals(Arrays.asList("gpt-oss:20b", "gemma4:e2b"),
                    probe.modelsToRun(Arrays.asList("gpt-oss:20b", "gemma4:e2b")));
            assertTrue(probe.modelsToRun(Arrays.asList("gemma4:e2b", "llama9:70b")).isEmpty());
            assertTrue(probe.modelProblem(Arrays.asList("gemma4:e2b", "llama9:70b")).contains("[llama9:70b]"));
            assertEquals("", probe.modelProblem(Collections.<String>emptyList()));
        } finally {
            ollama.stop(0);
        }
    }
}
