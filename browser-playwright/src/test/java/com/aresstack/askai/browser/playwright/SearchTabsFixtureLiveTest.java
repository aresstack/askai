package com.aresstack.askai.browser.playwright;

import com.aresstack.askai.browser.search.LegacyBrowserSearchDefaults;
import com.aresstack.askai.browser.search.LegacyBrowserSearchSettings;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.After;
import org.junit.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * Issue #22, Argos' web_search in AskAI, against three LOCAL "engines" in a real browser: every start page opens in a
 * background tab of its own, the query is typed into all three search fields at the same time and Enter pressed in
 * each, with real input only. Each start page records what its field receives (key events with their isTrusted flag,
 * blur and visibility changes) into a hidden form field that Enter submits with the query, so the result page shows
 * the query exactly as the engine got it and the page's own view of the input.
 *
 * <p>Environment-gated like the other live tests: {@code ASKAI_TEST_BROWSER_CHANNEL} (default chrome) names the
 * browser; for Camoufox set {@code ASKAI_TEST_BROWSER_CHANNEL=camoufox ASKAI_LIVE_CAMOUFOX=1}.</p>
 */
public class SearchTabsFixtureLiveTest {

    private static final String QUERY = "Kaninchen Ernährung Futter";

    private HttpServer server;
    private Playwright4jDriver driver;
    /** Per engine: when its result request arrived (ms since the epoch). */
    private final Map<String, Long> arrivals = new ConcurrentHashMap<String, Long>();

    @After
    public void stop() {
        if (driver != null) {
            driver.close();
        }
        if (server != null) {
            server.stop(0);
        }
    }

    /** A start page whose own script logs the input its field gets; nothing of AskAI runs in it. */
    private static String startPage(String engine) {
        return "<!doctype html><html><head><title>" + engine + " start</title></head><body>"
                + "<form action='/" + engine + "/results' method='get'>"
                + "<input name='q' id='q' style='width:400px;font-size:18px' autocomplete='off'>"
                + "<input type='hidden' name='log' id='log'></form>"
                + "<script>const log=[];const f=document.getElementById('q');"
                + "const put=(s)=>{log.push(s);document.getElementById('log').value=log.join(' ');};"
                + "for(const t of ['keydown','keyup']){f.addEventListener(t,(e)=>put(t+':'+e.key+':'+e.isTrusted));}"
                + "f.addEventListener('input',(e)=>put('input:'+e.isTrusted));"
                + "f.addEventListener('blur',()=>put('blur'));"
                + "document.addEventListener('visibilitychange',()=>put('visibility:'+document.visibilityState));"
                + "</script></body></html>";
    }

    @Test
    public void allEngineTabsAreTypedIntoAtTheSameTimeWithRealInput() throws Exception {
        String channel = System.getenv().getOrDefault("ASKAI_TEST_BROWSER_CHANNEL", "chrome");
        boolean camoufox = CamoufoxProvisioning.CHANNEL.equals(channel);
        if (camoufox) {
            assumeTrue("set ASKAI_LIVE_CAMOUFOX=1 for the real Camoufox", "1".equals(System.getenv("ASKAI_LIVE_CAMOUFOX")));
        } else {
            PlaywrightReadiness readiness = new PlaywrightCapabilityProbe().probe(channel);
            assumeTrue("SKIPPED (environment-gated live test): " + readiness.render(), readiness.isReady());
        }
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final List<String> engines = Arrays.asList("one", "two", "three");
        for (final String engine : engines) {
            server.createContext("/" + engine + "/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                if (path.endsWith("/results")) {
                    arrivals.put(engine, System.currentTimeMillis());
                    String raw = exchange.getRequestURI().getRawQuery();
                    String q = "";
                    String log = "";
                    for (String pair : raw == null ? new String[0] : raw.split("&")) {
                        int eq = pair.indexOf('=');
                        String value = URLDecoder.decode(pair.substring(eq + 1), "UTF-8");
                        if (pair.startsWith("q=")) {
                            q = value;
                        } else if (pair.startsWith("log=")) {
                            log = value;
                        }
                    }
                    respond(exchange, "<!doctype html><html><head><title>" + engine + " results</title></head>"
                            + "<body><p id='q'>QUERY[" + q + "]</p><p id='log'>LOG[" + log + "]</p></body></html>");
                } else {
                    respond(exchange, startPage(engine));
                }
            });
        }
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();

        LegacyBrowserSearchSettings settings = LegacyBrowserSearchDefaults.create();
        boolean headless = !"1".equals(System.getenv("ASKAI_LIVE_CAMOUFOX_HEADED"));
        driver = Playwright4jDriver.launch(channel, headless, 30_000, null, null, settings.consent, settings.captcha);
        PlaywrightPageState front = driver.open(base + "/one/front");

        List<String> homes = new ArrayList<String>();
        List<List<String>> fields = new ArrayList<List<String>>();
        for (String engine : engines) {
            homes.add(base + "/" + engine + "/");
            fields.add(Arrays.asList("textarea[name=q]", "input[name=q]"));
        }
        long started = System.currentTimeMillis();
        List<String> loaded = driver.openSearchTabs(homes);
        assertEquals(Arrays.asList(null, null, null), loaded);
        List<String> typed = driver.submitSearchInTabs(fields, QUERY);
        long took = System.currentTimeMillis() - started;
        assertEquals(Arrays.asList("submitted", "submitted", "submitted"), typed);

        for (int i = 0; i < engines.size(); i++) {
            PlaywrightPageState results = driver.useSearchTab(i);
            System.err.println("[search-tabs] " + engines.get(i) + ": " + results.text);
            assertTrue("each engine got exactly its query: " + results.text,
                    results.text.contains("QUERY[" + QUERY + "]"));
            String log = results.text.substring(results.text.indexOf("LOG["));
            assertTrue("keys arrived as key events: " + log, log.contains("keydown:K:true"));
            assertTrue("Enter was a real key: " + log, log.contains("keydown:Enter:true") || log.contains("keyup:"));
            assertFalse("no untrusted (script-made) input: " + log, log.contains(":false"));
            assertFalse("the tab never lost focus or visibility: " + log,
                    log.contains("blur") || log.contains("visibility:hidden"));
        }
        long first = Collections.min(arrivals.values());
        long last = Collections.max(arrivals.values());
        System.err.println("[search-tabs] typing and submit took " + took + " ms, results arrived within "
                + (last - first) + " ms");
        // Typed one after another, the result requests would be a whole typing duration apart.
        assertTrue("the three tabs were typed into side by side, results within " + (last - first) + " ms of "
                + took + " ms", last - first < took / 3);

        driver.closeSearchTabs();
        assertEquals("the page before the search is current again", front.url, driver.current().url);
    }

    private static void respond(HttpExchange exchange, String html) throws IOException {
        byte[] body = html.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(200, body.length);
        OutputStream out = exchange.getResponseBody();
        out.write(body);
        out.close();
    }
}
