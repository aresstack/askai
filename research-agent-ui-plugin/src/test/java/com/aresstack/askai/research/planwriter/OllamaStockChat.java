package com.aresstack.askai.research.planwriter;

import com.aresstack.askai.research.runtime.team.ChatMessage;
import com.aresstack.askai.research.runtime.team.MainModelChat;
import com.aresstack.askai.research.runtime.team.MainModelChatResult;
import com.aresstack.askai.research.runtime.team.ModelCallMetrics;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Test tooling (outside CI, not product code): a chat model straight on Ollama's {@code /api/chat}, for the stock test
 * of the writer. Unlike the copies of the other component stock tests it writes prose: {@link #complete} sends no
 * {@code format}, and an answer cut off at its budget stays a result flagged by its metrics ({@code done_reason}
 * length), as in the product's port, so the writer can continue it; {@link #completeJson} sends the schema as
 * {@code format}, and a cut-off JSON answer fails. Non-streaming, temperature and answer budget from the caller,
 * {@code think} as given ({@code null} leaves it out), {@code num_ctx} only when set (another value makes Ollama load
 * the model again), {@code truncate} false as in the product (an oversized prompt is refused, not cut). It counts
 * calls, failures, cut-off answers and the tokens Ollama reports, nothing of the text.
 */
final class OllamaStockChat implements MainModelChat {

    private final String url;
    private final String model;
    private final Integer numCtx;
    private final Boolean think;
    private final int timeoutMillis;
    final AtomicInteger calls = new AtomicInteger();
    final AtomicInteger failures = new AtomicInteger();
    final AtomicInteger cutOff = new AtomicInteger();
    final AtomicLong promptTokens = new AtomicLong();
    final AtomicLong evalTokens = new AtomicLong();
    final AtomicLong thinkingChars = new AtomicLong();
    final AtomicLong millis = new AtomicLong();

    OllamaStockChat(String url, String model, Integer numCtx, Boolean think, int timeoutMillis) {
        this.url = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        this.model = model;
        this.numCtx = numCtx;
        this.think = think;
        this.timeoutMillis = timeoutMillis;
    }

    @Override
    public MainModelChatResult complete(List<ChatMessage> messages, double temperature, int maxOutputTokens) {
        return call(messages, temperature, maxOutputTokens, null, false);
    }

    @Override
    public MainModelChatResult completeJson(List<ChatMessage> messages, double temperature, int maxOutputTokens,
                                            String schemaJson) {
        return call(messages, temperature, maxOutputTokens, schemaJson, true);
    }

    @Override
    public String modelName() {
        return model;
    }

    /** The body as sent; package-private for the sample test. */
    JsonObject body(List<ChatMessage> messages, double temperature, int maxOutputTokens, String schemaJson,
                    boolean json) {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("stream", false);
        if (think != null) {
            body.addProperty("think", think);
        }
        body.addProperty("truncate", false);
        JsonArray wire = new JsonArray();
        for (ChatMessage message : messages) {
            JsonObject entry = new JsonObject();
            entry.addProperty("role", message.getRole().name().toLowerCase(Locale.ROOT));
            entry.addProperty("content", message.getContent());
            wire.add(entry);
        }
        body.add("messages", wire);
        if (json) {
            body.add("format", schemaJson == null ? new JsonPrimitive("json") : JsonParser.parseString(schemaJson));
        }
        JsonObject options = new JsonObject();
        options.addProperty("temperature", temperature);
        options.addProperty("num_predict", maxOutputTokens);
        if (numCtx != null) {
            options.addProperty("num_ctx", numCtx);
        }
        body.add("options", options);
        return body;
    }

    private MainModelChatResult call(List<ChatMessage> messages, double temperature, int maxOutputTokens,
                                     String schemaJson, boolean json) {
        calls.incrementAndGet();
        long began = System.currentTimeMillis();
        try {
            HttpURLConnection connection = (HttpURLConnection) URI.create(url + "/api/chat").toURL().openConnection();
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setConnectTimeout(timeoutMillis);
            connection.setReadTimeout(timeoutMillis);
            connection.setRequestProperty("Content-Type", "application/json");
            try (OutputStream stream = connection.getOutputStream()) {
                stream.write(body(messages, temperature, maxOutputTokens, schemaJson, json).toString()
                        .getBytes(StandardCharsets.UTF_8));
            }
            int status = connection.getResponseCode();
            String text = read(status >= 400 ? connection.getErrorStream() : connection.getInputStream());
            if (status != 200) {
                failures.incrementAndGet();
                return MainModelChatResult.failure(MainModelChatResult.Status.PROVIDER_FAILURE,
                        "HTTP " + status + ": " + text);
            }
            return answer(JsonParser.parseString(text).getAsJsonObject(), maxOutputTokens, json);
        } catch (Exception failed) {
            failures.incrementAndGet();
            return MainModelChatResult.failure(MainModelChatResult.Status.PROVIDER_FAILURE,
                    failed.getClass().getSimpleName() + ": " + failed.getMessage());
        } finally {
            millis.addAndGet(System.currentTimeMillis() - began);
        }
    }

    /** Ollama's answer as the product's port reads it; package-private for the sample test. */
    MainModelChatResult answer(JsonObject answer, int maxOutputTokens, boolean json) {
        int prompt = number(answer, "prompt_eval_count");
        int eval = number(answer, "eval_count");
        promptTokens.addAndGet(prompt);
        evalTokens.addAndGet(eval);
        JsonObject message = answer.has("message") && answer.get("message").isJsonObject()
                ? answer.getAsJsonObject("message") : null;
        String thinking = message == null ? "" : string(message, "thinking");
        thinkingChars.addAndGet(thinking.length());
        String done = string(answer, "done_reason");
        String label = string(answer, "model");
        ModelCallMetrics metrics = ModelCallMetrics.builder()
                .modelLabel(label.isEmpty() ? model : label)
                .usage(prompt, eval)
                .doneReason(done)
                .thinkingChars(thinking.length())
                .durations(nanos(answer, "total_duration") / 1_000_000L, nanos(answer, "load_duration") / 1_000_000L,
                        nanos(answer, "prompt_eval_duration") / 1_000_000L)
                .contextTokens(numCtx == null ? 0 : numCtx)
                .answerBudget(maxOutputTokens)
                .build();
        boolean cut = "length".equals(done);
        if (cut) {
            cutOff.incrementAndGet();
        }
        if (cut && json) {
            failures.incrementAndGet();
            return MainModelChatResult.failure(MainModelChatResult.Status.INVALID_RESPONSE,
                    "the answer was cut off at its budget of " + maxOutputTokens + " tokens", metrics);
        }
        String content = message == null ? "" : string(message, "content");
        if (content.trim().isEmpty()) {
            failures.incrementAndGet();
            return MainModelChatResult.failure(MainModelChatResult.Status.INVALID_RESPONSE, "empty answer", metrics);
        }
        return MainModelChatResult.ok(content, metrics);
    }

    private static String string(JsonObject json, String key) {
        JsonElement value = json.get(key);
        return value == null || !value.isJsonPrimitive() ? "" : value.getAsString();
    }

    private static int number(JsonObject json, String key) {
        JsonElement value = json.get(key);
        return value == null || !value.isJsonPrimitive() ? 0 : (int) Math.min(Integer.MAX_VALUE, value.getAsLong());
    }

    private static long nanos(JsonObject json, String key) {
        JsonElement value = json.get(key);
        return value == null || !value.isJsonPrimitive() ? 0L : value.getAsLong();
    }

    private static String read(InputStream in) throws java.io.IOException {
        if (in == null) {
            return "";
        }
        try (InputStream stream = in) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while ((n = stream.read(chunk)) > 0) {
                buffer.write(chunk, 0, n);
            }
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
