package com.aresstack.askai.research.benchmark.retrieval;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Direct Ollama and {@code nvidia-smi} probes of the measurement protocol (plan 5.4 items 2, 3, 4 and 9). Only the
 * opt-in benchmark task uses them; the normal test run never reaches a model. Loopback Ollama only, so no proxy and
 * no download is involved.
 */
public final class ProtocolProbes {

    /** One HTTP answer: status and body. */
    public static final class Answer {
        public final int status;
        public final String body;

        Answer(int status, String body) {
            this.status = status;
            this.body = body;
        }

        public JsonObject json() {
            JsonElement parsed = JsonParser.parseString(body);
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
        }
    }

    private final String baseUrl;
    private final int timeoutMillis;

    public ProtocolProbes(String baseUrl, int timeoutMillis) {
        this.baseUrl = baseUrl;
        this.timeoutMillis = timeoutMillis;
    }

    public Answer post(String path, JsonObject body) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(baseUrl + path).openConnection(java.net.Proxy.NO_PROXY);
        c.setConnectTimeout(3000);
        c.setReadTimeout(timeoutMillis);
        c.setDoOutput(true);
        c.setRequestMethod("POST");
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        try (OutputStream out = c.getOutputStream()) {
            out.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }
        int status = c.getResponseCode();
        InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
        return new Answer(status, in == null ? "" : read(in));
    }

    /** Length of the vector the model returns for a raw probe text. */
    public int embeddingDimension(String model) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        JsonArray input = new JsonArray();
        input.add("dimension probe");
        body.add("input", input);
        Answer answer = post("/api/embed", body);
        if (answer.status != 200) {
            throw new IOException("embed probe of " + model + " failed: HTTP " + answer.status + " " + answer.body);
        }
        return answer.json().getAsJsonArray("embeddings").get(0).getAsJsonArray().size();
    }

    /** {@code prompt_eval_count} of embedding one text (its length in model tokens), or -1. */
    public long embeddingTokens(String model, String text) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        JsonArray input = new JsonArray();
        input.add(text);
        body.add("input", input);
        Answer answer = post("/api/embed", body);
        JsonElement count = answer.status == 200 ? answer.json().get("prompt_eval_count") : null;
        return count == null ? -1 : count.getAsLong();
    }

    /** The model's trained context from {@code /api/show} ({@code <arch>.context_length}), or -1. */
    public long contextLength(String model) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        Answer answer = post("/api/show", body);
        if (answer.status != 200) {
            return -1;
        }
        JsonElement info = answer.json().get("model_info");
        if (info == null || !info.isJsonObject()) {
            return -1;
        }
        for (Map.Entry<String, JsonElement> e : info.getAsJsonObject().entrySet()) {
            if (e.getKey().endsWith(".context_length") && e.getValue().isJsonPrimitive()) {
                return e.getValue().getAsLong();
            }
        }
        return -1;
    }

    /** Ollama's version string, or "unknown". */
    public String version() {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(baseUrl + "/api/version")
                    .openConnection(java.net.Proxy.NO_PROXY);
            c.setConnectTimeout(3000);
            c.setReadTimeout(3000);
            JsonElement v = JsonParser.parseString(read(c.getInputStream())).getAsJsonObject().get("version");
            return v == null ? "unknown" : v.getAsString();
        } catch (IOException | RuntimeException unreachable) {
            return "unknown";
        }
    }

    /**
     * Item 9: an overlong input with {@code truncate=false} must fail instead of answering. Returns the HTTP status
     * and the first part of the answer, so the report states what the installed Ollama did.
     */
    public Answer overlongEmbed(String model, int words) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        JsonArray input = new JsonArray();
        input.add(repeat(words));
        body.add("input", input);
        body.addProperty("truncate", false);
        return post("/api/embed", body);
    }

    /** Item 9 for chat: a prompt longer than {@code num_ctx} with {@code truncate=false}. */
    public Answer overlongChat(String model, int numCtx) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("stream", false);
        body.addProperty("truncate", false);
        JsonObject options = new JsonObject();
        options.addProperty("num_ctx", numCtx);
        options.addProperty("num_predict", 8);
        body.add("options", options);
        JsonArray messages = new JsonArray();
        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        user.addProperty("content", repeat(numCtx * 2) + " Answer with one word.");
        messages.add(user);
        body.add("messages", messages);
        return post("/api/chat", body);
    }

    /**
     * Item 2: prompt tokens of a text, measured as {@code prompt_eval_count} of a one-token generation minus that of
     * an empty baseline prompt (the template's own tokens). Returns -1 when Ollama does not report the counts.
     */
    public long promptTokens(String model, String text, int numCtx) throws IOException {
        long full = generateCount(model, text, numCtx);
        long empty = generateCount(model, ".", numCtx);
        return full < 0 || empty < 0 ? -1 : Math.max(0, full - empty);
    }

    private long generateCount(String model, String prompt, int numCtx) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("prompt", prompt);
        body.addProperty("stream", false);
        body.addProperty("raw", true);
        JsonObject options = new JsonObject();
        options.addProperty("num_ctx", numCtx);
        options.addProperty("num_predict", 1);
        body.add("options", options);
        Answer answer = post("/api/generate", body);
        JsonElement count = answer.status == 200 ? answer.json().get("prompt_eval_count") : null;
        return count == null ? -1 : count.getAsLong();
    }

    /** Item 4: {@code load_duration} (ms) of a one-token generation, i.e. the switch cost when another model ran. */
    public long loadDurationMillis(String model, int numCtx) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("prompt", "ok");
        body.addProperty("stream", false);
        JsonObject options = new JsonObject();
        options.addProperty("num_ctx", numCtx);
        options.addProperty("num_predict", 1);
        body.add("options", options);
        Answer answer = post("/api/generate", body);
        JsonElement load = answer.status == 200 ? answer.json().get("load_duration") : null;
        return load == null ? -1 : TimeUnit.NANOSECONDS.toMillis(load.getAsLong());
    }

    /** Item 3: used GPU memory (MiB) summed over all GPUs from {@code nvidia-smi}, or -1 when it is unavailable. */
    public static long gpuMemoryUsedMiB() {
        try {
            Process p = new ProcessBuilder("nvidia-smi", "--query-gpu=memory.used", "--format=csv,noheader,nounits")
                    .redirectErrorStream(true).start();
            String out = read(p.getInputStream());
            if (!p.waitFor(10, TimeUnit.SECONDS) || p.exitValue() != 0) {
                return -1;
            }
            long sum = 0;
            for (String line : out.split("\\R")) {
                if (!line.trim().isEmpty()) {
                    sum += Long.parseLong(line.trim());
                }
            }
            return sum;
        } catch (IOException | RuntimeException | InterruptedException unavailable) {
            if (unavailable instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return -1;
        }
    }

    static String repeat(int words) {
        StringBuilder sb = new StringBuilder();
        String[] vocabulary = {"copper", "Kupfer", "market", "Markt", "price", "Preis", "mine", "Grube"};
        for (int i = 0; i < words; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(vocabulary[i % vocabulary.length]);
        }
        return sb.toString();
    }

    static List<String> models(String csv) {
        List<String> out = new ArrayList<String>();
        for (String part : (csv == null ? "" : csv).split(",")) {
            if (!part.trim().isEmpty()) {
                out.add(part.trim());
            }
        }
        return out;
    }

    private static String read(InputStream in) throws IOException {
        try (InputStream input = in) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while ((n = input.read(chunk)) > 0) {
                buffer.write(chunk, 0, n);
            }
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
