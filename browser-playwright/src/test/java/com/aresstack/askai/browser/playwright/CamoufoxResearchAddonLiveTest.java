package com.aresstack.askai.browser.playwright;

import com.aresstack.askai.browser.hud.ResearchHudState;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * The AskAI Research add-on in a REAL Camoufox (downloaded on first use like in the app): it loads at the start,
 * connects to its bridge although loopback requests of pages are blocked, fetches the HUD state, and the page
 * never sees a HUD element or binding. Without the add-on there is no HUD in the browser, and still nothing in the page.
 *
 * <p>Opt-in, because it needs the Camoufox download: {@code ASKAI_LIVE_CAMOUFOX=1 gradlew :browser-playwright:test
 * --tests '*CamoufoxResearchAddonLiveTest'}. Headed with {@code ASKAI_LIVE_CAMOUFOX_HEADED=1} to see the toolbar
 * button and the sidebar.</p>
 */
public class CamoufoxResearchAddonLiveTest {

    /** The page reports in its title whether it can see anything of the HUD. */
    private static final String PAGE = "<!doctype html><html><head><title>probe</title></head><body><p>AskAI add-on"
            + " probe page</p><script>setInterval(function(){document.title='hud='"
            + "+(document.getElementById('__askai-research-hud')!==null)+';binding='+(typeof window.__askaiHudCommand)"
            + "+';root='+(typeof window.__askaiResearchHudRoot);},100);</script></body></html>";

    private HttpServer server;
    private String base;
    private Playwright4jDriver driver;

    @Before
    public void start() throws Exception {
        assumeTrue("set ASKAI_LIVE_CAMOUFOX=1 to run against a real Camoufox",
                "1".equals(System.getenv("ASKAI_LIVE_CAMOUFOX")));
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = PAGE.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
    }

    @After
    public void stop() {
        if (driver != null) {
            driver.close();
        }
        if (server != null) {
            server.stop(0);
        }
        System.clearProperty(Playwright4jDriver.ADDON_PROPERTY);
    }

    @Test
    public void theAddonShowsTheHudAndThePageSeesNothing() throws Exception {
        // Like PrivateTargetRequestFilter: page requests to loopback are aborted, only the test page is allowed.
        driver = launch(url -> !url.startsWith("http://127.0.0.1") || url.startsWith(base));
        AddonBridge bridge = driver.addonBridge();
        assertNotNull("Camoufox gets the add-on", bridge);
        driver.open(base);
        String result = "";
        long deadline = System.currentTimeMillis() + 30_000L;
        while (System.currentTimeMillis() < deadline) {
            result = driver.renderHud(new ResearchHudState("WAITING_FOR_USER", "Solve the check", true,
                    (int) ((deadline - System.currentTimeMillis()) / 1000L), false).render());
            if ("rendered-addon".equals(result) && bridge.servedVersion() > 0) {
                break;
            }
            driver.pumpEvents(() -> false, 500L);
        }
        System.err.println("[addon-live] renderHud=" + result + " addonVersion=" + bridge.addonVersion()
                + " served=" + bridge.servedVersion() + "/" + bridge.publishedVersion());
        assertEquals("the add-on connected and took the HUD", "rendered-addon", result);
        assertEquals("0.1.0", bridge.addonVersion());
        assertTrue("the add-on fetched the HUD state", bridge.servedVersion() > 0);
        driver.pumpEvents(() -> false, 500L);
        String seen = driver.current().title;
        System.err.println("[addon-live] page sees: " + seen);
        assertEquals("hud=false;binding=undefined;root=undefined", seen);
        // ASKAI_LIVE_CAMOUFOX_HOLD_SECONDS=n keeps the browser open with a running countdown, to look at the
        // toolbar badge and the sidebar (headed) and to try the buttons: clicked commands are printed.
        int hold = Integer.parseInt(System.getenv().getOrDefault("ASKAI_LIVE_CAMOUFOX_HOLD_SECONDS", "0"));
        for (int left = hold; left > 0; left--) {
            driver.renderHud(new ResearchHudState("WAITING_FOR_USER", "Solve the check in this tab", true, left,
                    false, 5, false).render());
            driver.pumpEvents(() -> false, 1000L);
            String commands = driver.pollHudCommands();
            if (!commands.isEmpty()) {
                System.err.println("[addon-live] commands from the add-on: " + commands.replace('\n', ' '));
            }
        }
    }

    @Test
    public void withoutTheAddonNothingIsPutIntoThePage() throws Exception {
        // Issue #22: Camoufox never falls back to the in-page overlay or the binding; only the log says the HUD is off.
        System.setProperty(Playwright4jDriver.ADDON_PROPERTY, "false");
        driver = launch(null);
        assertEquals(null, driver.addonBridge());
        driver.open(base);
        assertEquals("addon-missing", driver.renderHud(new ResearchHudState("READABLE", "no add-on", false, -1,
                false).render()));
        driver.pumpEvents(() -> false, 500L);
        String seen = driver.current().title;
        System.err.println("[addon-live] without add-on the page sees: " + seen);
        assertEquals("hud=false;binding=undefined;root=undefined", seen);
    }

    private Playwright4jDriver launch(java.util.function.Predicate<String> requestAllowed) throws Exception {
        boolean headless = !"1".equals(System.getenv("ASKAI_LIVE_CAMOUFOX_HEADED"));
        com.aresstack.askai.browser.search.LegacyBrowserSearchSettings settings =
                com.aresstack.askai.browser.search.LegacyBrowserSearchDefaults.create();
        return Playwright4jDriver.launch(CamoufoxProvisioning.CHANNEL, headless, 60_000, null, requestAllowed,
                settings.consent, settings.captcha);
    }
}
