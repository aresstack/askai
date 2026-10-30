package com.aresstack.askai.browser.playwright;

import com.aresstack.askai.browser.hud.ResearchHudState;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The add-on bridge: only the add-on (token, extension origin) reads the HUD and sends commands. */
public class AddonBridgeTest {

    private static final String EXTENSION_ORIGIN = "moz-extension://3f1c2b7e-0000-4000-8000-000000000001";
    private final HttpClient http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();
    private final HudCommandInbox inbox = new HudCommandInbox();
    private AddonBridge bridge;

    @Before
    public void start() throws Exception {
        bridge = AddonBridge.start();
        bridge.deliverTo(inbox);
    }

    @After
    public void stop() {
        bridge.close();
    }

    @Test
    public void clientConfigNamesPortTokenAndProtocol() {
        JsonObject config = JsonParser.parseString(bridge.clientConfig()).getAsJsonObject();
        assertEquals(AddonBridge.PROTOCOL, config.get("protocol").getAsInt());
        assertEquals(bridge.port(), config.get("port").getAsInt());
        assertEquals(64, config.get("token").getAsString().length());
        // The add-on connects to 127.0.0.1, also on a JVM that prefers IPv6 (where the loopback address is ::1).
        assertEquals("127.0.0.1", bridge.address().getHostAddress());
    }

    @Test
    public void theAddonReadsThePublishedStateAndBecomesConnected() throws Exception {
        assertFalse(bridge.addonConnected());
        bridge.publish(new ResearchHudState("WAITING_FOR_USER", "Solve the check", true, 42, false, 3, true));
        HttpResponse<String> response = send(get("/v1/state?after=0").header("Origin", EXTENSION_ORIGIN)
                .header("Authorization", "Bearer " + bridge.token()));
        assertEquals(200, response.statusCode());
        JsonObject body = JsonParser.parseString(response.body()).getAsJsonObject();
        assertEquals(1L, body.get("version").getAsLong());
        JsonObject state = body.getAsJsonObject("state");
        assertEquals("WAITING_FOR_USER", state.get("phase").getAsString());
        assertEquals("Solve the check", state.get("status").getAsString());
        assertTrue(state.get("waiting").getAsBoolean());
        assertEquals(42, state.get("countdown").getAsInt());
        assertEquals(3, state.get("delay").getAsInt());
        assertTrue(state.get("relevant").getAsBoolean());
        assertFalse(state.get("terminal").getAsBoolean());
        assertTrue(bridge.addonConnected());
    }

    @Test
    public void aHeldPollReturnsAsSoonAsANewStateIsPublished() throws Exception {
        bridge.publish(new ResearchHudState("READABLE", "first", false, -1, false));
        CompletableFuture<HttpResponse<String>> held = http.sendAsync(
                get("/v1/state?after=1").header("Authorization", "Bearer " + bridge.token()).build(),
                HttpResponse.BodyHandlers.ofString());
        Thread.sleep(300);
        assertFalse("nothing new yet", held.isDone());
        bridge.publish(new ResearchHudState("DELAY", "second", false, -1, false));
        HttpResponse<String> response = held.get(5, TimeUnit.SECONDS);
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"second\""));
    }

    @Test
    public void commandsFromTheAddonReachTheInbox() throws Exception {
        assertEquals(204, send(post("/v1/command", "SKIP")).statusCode());
        assertEquals(204, send(post("/v1/command", "SET_DELAY:12")).statusCode());
        assertEquals(204, send(post("/v1/command", "SET_RELEVANCE:on")).statusCode());
        assertEquals("SKIP\nSET_DELAY:12\nSET_RELEVANCE:on", inbox.drain());
    }

    @Test
    public void unknownCommandsAreRefused() throws Exception {
        assertEquals(400, send(post("/v1/command", "SET_DELAY:12\nSKIP")).statusCode());
        assertEquals(400, send(post("/v1/command", "DELETE_EVERYTHING")).statusCode());
        assertEquals("", inbox.drain());
    }

    @Test
    public void aPageCannotUseTheBridge() throws Exception {
        bridge.publish(new ResearchHudState("READABLE", "secret status", false, -1, false));
        // A page's fetch carries the page origin, even when it somehow knew the token.
        assertEquals(403, send(get("/v1/state?after=0").header("Origin", "https://www.bing.com")
                .header("Authorization", "Bearer " + bridge.token())).statusCode());
        // Without the token nothing is answered.
        assertEquals(403, send(get("/v1/state?after=0").header("Origin", EXTENSION_ORIGIN)).statusCode());
        assertEquals(403, send(get("/v1/state?after=0").header("Origin", EXTENSION_ORIGIN)
                .header("Authorization", "Bearer " + "0".repeat(64))).statusCode());
        assertEquals(403, send(HttpRequest.newBuilder(uri("/v1/command")).timeout(Duration.ofSeconds(5))
                .POST(HttpRequest.BodyPublishers.ofString("SKIP"))).statusCode());
        assertEquals("", inbox.drain());
        assertFalse("refused requests are no contact", bridge.addonConnected());
    }

    @Test
    public void helloRecordsTheAddonVersion() throws Exception {
        assertEquals(204, send(HttpRequest.newBuilder(uri("/v1/hello")).timeout(Duration.ofSeconds(5))
                .header("Origin", EXTENSION_ORIGIN).header("Authorization", "Bearer " + bridge.token())
                .POST(HttpRequest.BodyPublishers.ofString("{\"protocol\":1,\"addonVersion\":\"0.1.0\"}")))
                .statusCode());
        assertEquals("0.1.0", bridge.addonVersion());
        assertTrue(bridge.addonConnected());
    }

    @Test
    public void closingEndsTheConnection() throws Exception {
        send(post("/v1/command", "PAUSE"));
        assertTrue(bridge.addonConnected());
        bridge.close();
        assertFalse(bridge.addonConnected());
    }

    private HttpRequest.Builder get(String path) {
        return HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(30)).GET();
    }

    private HttpRequest.Builder post(String path, String body) {
        return HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(5))
                .header("Origin", EXTENSION_ORIGIN).header("Authorization", "Bearer " + bridge.token())
                .POST(HttpRequest.BodyPublishers.ofString(body));
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + bridge.port() + path);
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
