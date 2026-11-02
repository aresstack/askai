package com.aresstack.askai.browser.sidecar;

import com.aresstack.askai.browser.BrowserException;
import com.sun.net.httpserver.HttpServer;
import org.junit.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

/**
 * askai #31: the research browser inside the AskAI process. A real browser, started through
 * {@link InProcessBrowser} (no sidecar process, no MCP), renders a JavaScript page and serves the tool texts
 * the research loop parses. Environment-gated like {@link PlaywrightLiveBrowserTest}.
 */
public class InProcessBrowserLiveTest {

    private static final String PAGE = "<!doctype html><html><head><title>In Process</title></head>"
            + "<body><div id='c'></div><script>"
            + "document.getElementById('c').textContent='Rendered in process';"
            + "var a=document.createElement('a');a.href='/second';a.textContent='next page';"
            + "document.body.appendChild(a);</script></body></html>";

    @Test
    public void toolsRunDirectlyOnTheBrowserAndCloseEndsIt() throws Exception {
        String channel = System.getenv().getOrDefault("ASKAI_TEST_BROWSER_CHANNEL", "chrome");
        PlaywrightReadiness readiness = new PlaywrightCapabilityProbe().probe(channel);
        assumeTrue("SKIPPED (environment-gated live test): " + readiness.render(), readiness.isReady());

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = PAGE.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        InProcessBrowser browser = InProcessBrowser.start(channel, true, true, null);
        try {
            Map<String, Object> open = new HashMap<String, Object>();
            open.put("url", base + "/start");
            String page = browser.call("web_open", open);
            assertTrue(page, page.startsWith("URL: " + base + "/start\nTITLE: In Process"));
            assertTrue(page, page.contains("Rendered in process"));

            String links = browser.call("web_links", Collections.<String, Object>emptyMap());
            assertTrue(links, links.contains("next page — " + base + "/second"));

            try {
                browser.call("web_open", Collections.<String, Object>emptyMap());
                fail("a missing argument must fail the tool");
            } catch (BrowserException expected) {
                assertEquals("Missing required argument: url", expected.getMessage());
            }
        } finally {
            browser.close();
            server.stop(0);
        }
        assertFalse(browser.isAlive());
        try {
            browser.call("web_read", Collections.<String, Object>emptyMap());
            fail("a closed browser must refuse tools");
        } catch (BrowserException expected) {
            assertEquals("Browser session is closed.", expected.getMessage());
        }
    }
}
