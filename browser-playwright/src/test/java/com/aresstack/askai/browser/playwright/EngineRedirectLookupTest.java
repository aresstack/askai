package com.aresstack.askai.browser.playwright;

import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Test;

import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The lookup of an engine's redirect answers ({@link EngineRedirectLookup}) against a local stand-in engine: only the
 * {@code Location} of a 3xx counts, the redirect is never followed, all wrappers are asked at once and share one
 * deadline, so a stalling answer costs that deadline once, not once per wrapper.
 */
public class EngineRedirectLookupTest {

    private HttpServer server;

    @After
    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    public void onlyRedirectAnswersCountAndTheTargetIsNeverRequested() throws Exception {
        final AtomicInteger targetRequests = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/goto", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            if (query.endsWith("=moved")) {
                exchange.getResponseHeaders().add("Location", "/target");
                exchange.sendResponseHeaders(302, -1);
            } else if (query.endsWith("=page")) {
                exchange.sendResponseHeaders(200, -1);
            } else {
                try {
                    Thread.sleep(3_000);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                exchange.getResponseHeaders().add("Location", "/late");
                exchange.sendResponseHeaders(302, -1);
            }
            exchange.close();
        });
        server.createContext("/target", exchange -> {
            targetRequests.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/goto?url=";

        long started = System.currentTimeMillis();
        Map<String, String> answers = EngineRedirectLookup.locations(Arrays.asList(
                base + "moved", base + "page", base + "stall1", base + "stall2", base + "stall3"), 1_000);
        long took = System.currentTimeMillis() - started;

        assertEquals("/target", answers.get(base + "moved"));
        assertFalse("a page is no redirect answer", answers.containsKey(base + "page"));
        assertFalse(answers.containsKey(base + "stall1"));
        assertTrue("three stalling answers share one deadline, took " + took + " ms", took < 2_500);
        assertEquals("the redirect is never followed", 0, targetRequests.get());
    }
}
