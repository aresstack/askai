package com.aresstack.askai.research.benchmark;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A transparent, recording pass-through in front of Ollama. The subject's inference descriptor points at this
 * meter instead of Ollama, so the benchmark counts model calls, failed calls, prompt and output tokens and
 * model time WITHOUT touching the subject's code: today's baseline has no token accounting of its own, and
 * the model port v2 (lane C) does not exist yet.
 *
 * <p>Requests and responses are forwarded byte for byte; streamed responses are relayed chunk by chunk, so
 * the subject sees the same streaming behaviour. Tokens come from Ollama's own counters in the final
 * {@code "done": true} object ({@code prompt_eval_count}, {@code eval_count}, {@code total_duration}).</p>
 */
public final class OllamaTokenMeter implements AutoCloseable {

    private static final int CONNECT_TIMEOUT_MILLIS = 10_000;

    private final String upstream;
    private final int readTimeoutMillis;
    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "benchmark-token-meter");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicLong calls = new AtomicLong();
    private final AtomicLong failedCalls = new AtomicLong();
    private final AtomicLong promptTokens = new AtomicLong();
    private final AtomicLong outputTokens = new AtomicLong();
    private final AtomicLong modelNanos = new AtomicLong();
    /** Requests being forwarded right now; a run's usage is final only when this is back to zero. */
    private final java.util.concurrent.atomic.AtomicInteger inFlight =
            new java.util.concurrent.atomic.AtomicInteger();
    private final Object idle = new Object();

    private OllamaTokenMeter(String upstream, int readTimeoutMillis) throws IOException {
        this.upstream = upstream.endsWith("/") ? upstream.substring(0, upstream.length() - 1) : upstream;
        this.readTimeoutMillis = readTimeoutMillis;
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        server.createContext("/", this::forward);
        server.start();
    }

    /**
     * Start a meter that forwards to {@code upstreamBaseUrl} (for example {@code http://127.0.0.1:11434}).
     *
     * @param readTimeoutMillis upper bound of one forwarded call (the subject's model-call timeout), so a call
     *                          its subject abandoned cannot stay in flight forever
     */
    public static OllamaTokenMeter start(String upstreamBaseUrl, long readTimeoutMillis) throws IOException {
        return new OllamaTokenMeter(upstreamBaseUrl, (int) Math.min(Integer.MAX_VALUE, readTimeoutMillis));
    }

    /**
     * Wait until no request is being forwarded. A run that ends (timeout, cancel) while a model call is still
     * streaming would otherwise charge that call to the NEXT run. @return false when calls are still in flight
     */
    public boolean awaitIdle(long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        synchronized (idle) {
            while (inFlight.get() > 0) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) {
                    return false;
                }
                idle.wait(left);
            }
            return true;
        }
    }

    /** The base URL the subject must use instead of Ollama's. */
    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** The counters so far; a run takes one snapshot before and one after and reports the difference. */
    public Usage snapshot() {
        return new Usage(calls.get(), failedCalls.get(), promptTokens.get(), outputTokens.get(),
                modelNanos.get() / 1_000_000L);
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    private void forward(HttpExchange exchange) throws IOException {
        inFlight.incrementAndGet();
        try {
            forwardCounted(exchange);
        } finally {
            synchronized (idle) {
                inFlight.decrementAndGet();
                idle.notifyAll();
            }
        }
    }

    private void forwardCounted(HttpExchange exchange) throws IOException {
        boolean modelCall = isModelCall(exchange.getRequestURI().getPath());
        HttpURLConnection connection = null;
        try {
            URL target = new URL(upstream + exchange.getRequestURI().toString());
            // Ollama is local; never route it through a JVM-wide HTTP proxy.
            connection = (HttpURLConnection) target.openConnection(Proxy.NO_PROXY);
            // Connecting is fast or never; reading is bounded by the subject's own model-call timeout.
            connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
            connection.setReadTimeout(readTimeoutMillis);
            connection.setRequestMethod(exchange.getRequestMethod());
            String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
            if (contentType != null) {
                connection.setRequestProperty("Content-Type", contentType);
            }
            byte[] requestBody = readAll(exchange.getRequestBody());
            if (requestBody.length > 0) {
                connection.setDoOutput(true);
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(requestBody);
                }
            }
            int status = connection.getResponseCode();
            InputStream upstreamBody = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String responseType = connection.getContentType();
            if (responseType != null) {
                exchange.getResponseHeaders().add("Content-Type", responseType);
            }
            exchange.sendResponseHeaders(status, 0); // chunked: relay as it arrives
            ByteArrayOutputStream copy = new ByteArrayOutputStream();
            try (OutputStream out = exchange.getResponseBody()) {
                if (upstreamBody != null) {
                    byte[] buffer = new byte[8192];
                    int n;
                    while ((n = upstreamBody.read(buffer)) > 0) {
                        out.write(buffer, 0, n);
                        out.flush();
                        if (modelCall) {
                            copy.write(buffer, 0, n);
                        }
                    }
                }
            }
            if (modelCall) {
                calls.incrementAndGet();
                if (status >= 400) {
                    failedCalls.incrementAndGet();
                } else {
                    record(copy.toString(StandardCharsets.UTF_8));
                }
            }
        } catch (IOException upstreamFailure) {
            if (modelCall) {
                calls.incrementAndGet();
                failedCalls.incrementAndGet();
            }
            byte[] body = ("{\"error\":\"benchmark token meter: upstream failed: "
                    + upstreamFailure.getMessage() + "\"}").getBytes(StandardCharsets.UTF_8);
            try {
                exchange.sendResponseHeaders(502, body.length);
                exchange.getResponseBody().write(body);
            } catch (IOException alreadyStarted) {
                // the response was already under way; the subject sees the truncated stream
            }
        } finally {
            exchange.close();
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /** Parse a single JSON body or an NDJSON stream; the {@code done} object carries the counters. */
    void record(String body) {
        for (String line : body.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || !trimmed.startsWith("{")) {
                continue;
            }
            JsonObject object;
            try {
                JsonElement element = JsonParser.parseString(trimmed);
                if (!element.isJsonObject()) {
                    continue;
                }
                object = element.getAsJsonObject();
            } catch (RuntimeException notJson) {
                continue;
            }
            if (object.has("done") && !object.get("done").getAsBoolean()) {
                continue;
            }
            promptTokens.addAndGet(longOf(object, "prompt_eval_count"));
            outputTokens.addAndGet(longOf(object, "eval_count"));
            modelNanos.addAndGet(longOf(object, "total_duration"));
        }
    }

    private static boolean isModelCall(String path) {
        return path.endsWith("/api/chat") || path.endsWith("/api/generate");
    }

    private static long longOf(JsonObject object, String key) {
        JsonElement value = object.get(key);
        try {
            return value == null || value.isJsonNull() ? 0L : value.getAsLong();
        } catch (RuntimeException notANumber) {
            return 0L;
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        return in == null ? new byte[0] : in.readAllBytes();
    }

    /** Model usage counters; {@link #minus} gives the usage of one run. */
    public static final class Usage {
        public final long calls;
        public final long failedCalls;
        public final long promptTokens;
        public final long outputTokens;
        public final long modelMillis;

        public Usage(long calls, long failedCalls, long promptTokens, long outputTokens, long modelMillis) {
            this.calls = calls;
            this.failedCalls = failedCalls;
            this.promptTokens = promptTokens;
            this.outputTokens = outputTokens;
            this.modelMillis = modelMillis;
        }

        public Usage minus(Usage earlier) {
            return new Usage(calls - earlier.calls, failedCalls - earlier.failedCalls,
                    promptTokens - earlier.promptTokens, outputTokens - earlier.outputTokens,
                    modelMillis - earlier.modelMillis);
        }

        public long totalTokens() {
            return promptTokens + outputTokens;
        }
    }
}
