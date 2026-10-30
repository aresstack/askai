package com.aresstack.askai.browser.playwright;

import com.aresstack.askai.browser.hud.ResearchHudState;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * The local channel between ONE browser's AskAI Research add-on and this runtime (docs/research-browser-addon.md).
 * A loopback-only HTTP server on a random port; every request must carry the random bearer token AskAI wrote into the
 * add-on's {@code bridge.json} and come from an extension origin, so a visited page can neither read the HUD state nor
 * send commands. The add-on long-polls {@code GET /v1/state?after=<version>} and posts the user's clicks to
 * {@code POST /v1/command}; commands land in the same {@link HudCommandInbox} the in-page overlay fed, so the runtime
 * cannot tell (and need not care) where a Skip came from.
 */
final class AddonBridge implements AutoCloseable {

    static final int PROTOCOL = 1;
    /** How long a state poll is held open when nothing changed. */
    static final long POLL_HOLD_MILLIS = 20_000L;
    /** The add-on counts as connected while its last request is at most this old (a poll returns every 20 s). */
    static final long CONNECTED_WINDOW_MILLIS = 30_000L;
    /** The HUD commands the overlay sends; anything else is refused before it reaches the inbox. */
    private static final Pattern COMMAND = Pattern.compile(
            "PAUSE|RESUME|SKIP|NEXT|SET_DELAY:[0-9]{1,3}|SET_RELEVANCE:(on|off)");
    private static final Gson GSON = new Gson();

    /** Set by the driver once it exists; the browser (and with it the add-on) starts only after that. */
    private volatile HudCommandInbox inbox;
    private final String token;
    private final HttpServer server;
    private final ExecutorService executor;
    private final Object lock = new Object();
    private long version;
    private ResearchHudState state;
    private volatile long lastContactMillis;
    private volatile String addonVersion;
    private volatile long servedVersion;
    private volatile boolean closed;

    private AddonBridge(String token, HttpServer server, ExecutorService executor) {
        this.token = token;
        this.server = server;
        this.executor = executor;
    }

    /** Start the bridge on a random loopback port; commands from the add-on go to the inbox of {@link #deliverTo}. */
    static AddonBridge start() throws IOException {
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        // Explicitly IPv4: the add-on connects to 127.0.0.1 (and only has that host permission), while the loopback
        // address of a JVM with java.net.preferIPv6Addresses=true would be ::1.
        HttpServer server = HttpServer.create(new InetSocketAddress(
                InetAddress.getByAddress("localhost", new byte[]{127, 0, 0, 1}), 0), 0);
        AtomicInteger threads = new AtomicInteger();
        // A few threads: one held state poll, a command and a hello may overlap.
        ExecutorService executor = Executors.newFixedThreadPool(4, runnable -> {
            Thread thread = new Thread(runnable, "askai-addon-bridge-" + threads.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        AddonBridge bridge = new AddonBridge(HexFormat.of().formatHex(secret), server, executor);
        server.createContext("/v1/", bridge::handle);
        server.setExecutor(executor);
        server.start();
        return bridge;
    }

    /** Where the add-on's commands go: the driver's HUD inbox. */
    void deliverTo(HudCommandInbox target) {
        this.inbox = target;
    }

    /** The bound address: always 127.0.0.1, the only host the add-on may reach. */
    InetAddress address() {
        return server.getAddress().getAddress();
    }

    int port() {
        return server.getAddress().getPort();
    }

    String token() {
        return token;
    }

    /** The {@code bridge.json} the add-on reads at its start. */
    String clientConfig() {
        JsonObject config = new JsonObject();
        config.addProperty("protocol", PROTOCOL);
        config.addProperty("port", port());
        config.addProperty("token", token);
        return GSON.toJson(config);
    }

    /** Publish a new HUD state; a held poll returns it at once. */
    void publish(ResearchHudState next) {
        synchronized (lock) {
            state = next;
            version++;
            lock.notifyAll();
        }
    }

    /** True while the add-on is polling: the HUD is visible in the browser UI. */
    boolean addonConnected() {
        return !closed && lastContactMillis != 0L
                && System.currentTimeMillis() - lastContactMillis <= CONNECTED_WINDOW_MILLIS;
    }

    /** The add-on's manifest version from its hello, or null before the first hello. */
    String addonVersion() {
        return addonVersion;
    }

    /** The newest HUD state version the add-on has fetched (0 = none yet). */
    long servedVersion() {
        return servedVersion;
    }

    /** The version of the newest published state. */
    long publishedVersion() {
        synchronized (lock) {
            return version;
        }
    }

    @Override
    public void close() {
        closed = true;
        synchronized (lock) {
            lock.notifyAll();
        }
        server.stop(0);
        executor.shutdownNow();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!authorized(exchange)) {
                respond(exchange, 403, null);
                return;
            }
            lastContactMillis = System.currentTimeMillis();
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            if ("GET".equals(method) && "/v1/state".equals(path)) {
                state(exchange);
            } else if ("POST".equals(method) && "/v1/command".equals(path)) {
                String command = body(exchange, 64).trim();
                if (!COMMAND.matcher(command).matches()) {
                    respond(exchange, 400, null);
                    return;
                }
                HudCommandInbox target = inbox;
                if (target != null) {
                    target.add(command);
                }
                respond(exchange, 204, null);
            } else if ("POST".equals(method) && "/v1/hello".equals(path)) {
                hello(body(exchange, 1024));
                respond(exchange, 204, null);
            } else {
                respond(exchange, 404, null);
            }
        }
    }

    /**
     * The bearer token, compared in constant time. A page cannot send it: an Authorization header forces a CORS
     * request, which carries the page's own origin; such a request is refused even before the token is checked.
     */
    private boolean authorized(HttpExchange exchange) {
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        if (origin != null && !origin.startsWith("moz-extension://")) {
            return false;
        }
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        String expected = "Bearer " + token;
        return header != null && MessageDigest.isEqual(
                header.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
    }

    private void state(HttpExchange exchange) throws IOException {
        long after = afterParameter(exchange.getRequestURI());
        long deadline = System.currentTimeMillis() + POLL_HOLD_MILLIS;
        long currentVersion;
        ResearchHudState current;
        synchronized (lock) {
            while (!closed && (state == null || version <= after)) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0L) {
                    break;
                }
                try {
                    lock.wait(remaining);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            currentVersion = version;
            current = state;
        }
        lastContactMillis = System.currentTimeMillis();
        if (current == null || currentVersion <= after) {
            respond(exchange, 204, null);
            return;
        }
        servedVersion = currentVersion;
        JsonObject body = new JsonObject();
        body.addProperty("version", currentVersion);
        body.add("state", toJson(current));
        respond(exchange, 200, GSON.toJson(body));
    }

    private void hello(String body) {
        try {
            JsonObject hello = GSON.fromJson(body, JsonObject.class);
            if (hello != null && hello.has("addonVersion")) {
                addonVersion = hello.get("addonVersion").getAsString();
            }
        } catch (RuntimeException ignored) {
            // The version is informational; an unreadable hello still counts as contact.
        }
    }

    static JsonObject toJson(ResearchHudState state) {
        JsonObject json = new JsonObject();
        json.addProperty("phase", state.phase);
        json.addProperty("status", state.statusText);
        json.addProperty("waiting", state.waitingForUser);
        json.addProperty("countdown", state.countdownSeconds);
        json.addProperty("paused", state.paused);
        json.addProperty("delay", state.delaySeconds);
        json.addProperty("relevant", state.relevant);
        json.addProperty("terminal", state.terminal);
        return json;
    }

    private static long afterParameter(URI uri) {
        String query = uri.getRawQuery();
        if (query != null) {
            for (String part : query.split("&")) {
                if (part.startsWith("after=")) {
                    try {
                        return Long.parseLong(part.substring("after=".length()));
                    } catch (NumberFormatException ignored) {
                        return 0L;
                    }
                }
            }
        }
        return 0L;
    }

    private static String body(HttpExchange exchange, int limit) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            byte[] bytes = in.readNBytes(limit + 1);
            if (bytes.length > limit) {
                return "";
            }
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private static void respond(HttpExchange exchange, int status, String json) throws IOException {
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        if (json == null) {
            exchange.sendResponseHeaders(status, -1);
            return;
        }
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
