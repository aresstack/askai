package com.aresstack.askai.research.benchmark;

import com.sun.net.httpserver.HttpServer;
import org.junit.Test;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class OllamaTokenMeterTest {

    private static final String STREAM = "{\"message\":{\"content\":\"Hel\"},\"done\":false}\n"
            + "{\"message\":{\"content\":\"lo\"},\"done\":false}\n"
            + "{\"done\":true,\"prompt_eval_count\":120,\"eval_count\":7,\"total_duration\":2500000000}\n";
    private static final String SINGLE = "{\"message\":{\"content\":\"{}\"},\"done\":true,"
            + "\"prompt_eval_count\":30,\"eval_count\":3,\"total_duration\":500000000}";

    @Test
    public void forwardsUnchangedAndCountsStreamedAndSingleResponses() throws Exception {
        HttpServer upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final String[] seenBody = {""};
        upstream.createContext("/api/chat", exchange -> {
            seenBody[0] = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] body = (seenBody[0].contains("\"stream\":true") ? STREAM : SINGLE)
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/x-ndjson");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        upstream.createContext("/api/tags", exchange -> {
            byte[] body = "{\"models\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        upstream.start();
        try (OllamaTokenMeter meter = OllamaTokenMeter.start("http://127.0.0.1:" + upstream.getAddress().getPort(),
                10_000L)) {
            OllamaTokenMeter.Usage before = meter.snapshot();
            assertEquals(STREAM, post(meter.baseUrl() + "/api/chat", "{\"stream\":true}", 200));
            assertEquals("{\"stream\":true}", seenBody[0]);
            assertEquals(SINGLE, post(meter.baseUrl() + "/api/chat", "{\"stream\":false}", 200));
            post(meter.baseUrl() + "/api/tags", "", 200);
            OllamaTokenMeter.Usage used = meter.snapshot().minus(before);
            assertEquals("only model calls are counted", 2, used.calls);
            assertEquals(0, used.failedCalls);
            assertEquals(150, used.promptTokens);
            assertEquals(10, used.outputTokens);
            assertEquals(160, used.totalTokens());
            assertEquals(3000, used.modelMillis);
            assertTrue("nothing in flight after the calls returned", meter.awaitIdle(1000));
        } finally {
            upstream.stop(0);
        }
    }

    @Test
    public void anUnreachableUpstreamIsAFailedCall() throws Exception {
        // Port 1 (tcpmux) is never served here. A freshly freed ephemeral port would not do: the meter itself
        // may bind it and then forward to itself.
        try (OllamaTokenMeter meter = OllamaTokenMeter.start("http://127.0.0.1:1", 10_000L)) {
            post(meter.baseUrl() + "/api/chat", "{}", 502);
            assertEquals(1, meter.snapshot().calls);
            assertEquals(1, meter.snapshot().failedCalls);
        }
    }

    @Test
    public void aRunsUsageIsSettledOnlyAfterItsSlowCallFinished() throws Exception {
        HttpServer upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        upstream.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        upstream.createContext("/api/chat", exchange -> {
            exchange.getRequestBody().readAllBytes();
            try {
                release.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            byte[] body = SINGLE.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        upstream.start();
        try (OllamaTokenMeter meter = OllamaTokenMeter.start("http://127.0.0.1:" + upstream.getAddress().getPort(),
                10_000L)) {
            Thread caller = new Thread(() -> {
                try {
                    post(meter.baseUrl() + "/api/chat", "{\"stream\":false}", 200);
                } catch (Exception ignored) {
                    // the assertion below is what matters
                }
            });
            caller.start();
            Thread.sleep(300);
            assertFalse("the slow call is still in flight", meter.awaitIdle(200));
            release.countDown();
            assertTrue(meter.awaitIdle(5000));
            assertEquals(30, meter.snapshot().promptTokens);
            caller.join(5000);
        } finally {
            upstream.stop(0);
        }
    }

    private static String post(String url, String body, int expectedStatus) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection(Proxy.NO_PROXY);
        try {
            if (!body.isEmpty()) {
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json");
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(body.getBytes(StandardCharsets.UTF_8));
                }
            }
            assertEquals(expectedStatus, connection.getResponseCode());
            java.io.InputStream in = expectedStatus >= 400 ? connection.getErrorStream() : connection.getInputStream();
            return in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } finally {
            connection.disconnect();
        }
    }
}
