package com.aresstack.askai.research.pagenoise;

import com.aresstack.askai.research.runtime.team.ChatMessage;
import com.aresstack.askai.research.runtime.team.MainModelChat;
import com.aresstack.askai.research.runtime.team.MainModelChatResult;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Test tooling (outside CI, not product code): a chat model straight on Ollama's {@code /api/chat}, for the stock test
 * of the page-noise check (the other component stock tests carry the same one). Non-streaming, the schema as
 * {@code format}, temperature and answer budget from the caller, {@code think} as given ({@code null} leaves it out;
 * the check's role does not think, so its stock test sends {@code false}), {@code num_ctx} only when set (another value
 * makes Ollama load the model again). An answer cut off at the budget fails, as in the product's port; the budget
 * counts the thinking too. It counts the tokens Ollama reports and the characters of the thinking, nothing of the text.
 */
final class OllamaStockChat implements MainModelChat {

    private final String url;
    private final String model;
    private final Integer numCtx;
    private final Boolean think;
    private final int timeoutMillis;
    final AtomicInteger calls = new AtomicInteger();
    final AtomicInteger failures = new AtomicInteger();
    final AtomicLong evalTokens = new AtomicLong();
    final AtomicLong thinkingChars = new AtomicLong();

    OllamaStockChat(String url, String model, Integer numCtx, Boolean think, int timeoutMillis) {
        this.url = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        this.model = model;
        this.numCtx = numCtx;
        this.think = think;
        this.timeoutMillis = timeoutMillis;
    }

    @Override
    public MainModelChatResult complete(List<ChatMessage> messages, double temperature, int maxOutputTokens) {
        return completeJson(messages, temperature, maxOutputTokens, null);
    }

    @Override
    public MainModelChatResult completeJson(List<ChatMessage> messages, double temperature, int maxOutputTokens,
                                            String schemaJson) {
        calls.incrementAndGet();
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("stream", false);
        if (think != null) {
            body.addProperty("think", think);
        }
        JsonArray wire = new JsonArray();
        for (ChatMessage message : messages) {
            JsonObject entry = new JsonObject();
            entry.addProperty("role", message.getRole().name().toLowerCase(Locale.ROOT));
            entry.addProperty("content", message.getContent());
            wire.add(entry);
        }
        body.add("messages", wire);
        body.add("format", schemaJson == null ? new JsonPrimitive("json") : JsonParser.parseString(schemaJson));
        JsonObject options = new JsonObject();
        options.addProperty("temperature", temperature);
        options.addProperty("num_predict", maxOutputTokens);
        if (numCtx != null) {
            options.addProperty("num_ctx", numCtx);
        }
        body.add("options", options);
        try {
            HttpURLConnection connection = (HttpURLConnection) new URL(url + "/api/chat").openConnection();
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setConnectTimeout(timeoutMillis);
            connection.setReadTimeout(timeoutMillis);
            connection.setRequestProperty("Content-Type", "application/json");
            try (OutputStream stream = connection.getOutputStream()) {
                stream.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            int status = connection.getResponseCode();
            String text = read(status >= 400 ? connection.getErrorStream() : connection.getInputStream());
            if (status != 200) {
                failures.incrementAndGet();
                return MainModelChatResult.failure(MainModelChatResult.Status.PROVIDER_FAILURE,
                        "HTTP " + status + ": " + text);
            }
            JsonObject answer = JsonParser.parseString(text).getAsJsonObject();
            JsonElement eval = answer.get("eval_count");
            if (eval != null && eval.isJsonPrimitive()) {
                evalTokens.addAndGet(eval.getAsLong());
            }
            JsonElement done = answer.get("done_reason");
            if (done != null && done.isJsonPrimitive() && "length".equals(done.getAsString())) {
                failures.incrementAndGet();
                return MainModelChatResult.failure(MainModelChatResult.Status.INVALID_RESPONSE,
                        "answer cut off at " + maxOutputTokens + " tokens");
            }
            JsonObject message = answer.getAsJsonObject("message");
            if (message != null && message.get("thinking") != null && message.get("thinking").isJsonPrimitive()) {
                thinkingChars.addAndGet(message.get("thinking").getAsString().length());
            }
            String content = message == null || message.get("content") == null ? ""
                    : message.get("content").getAsString();
            if (content.trim().isEmpty()) {
                failures.incrementAndGet();
                return MainModelChatResult.failure(MainModelChatResult.Status.INVALID_RESPONSE, "empty answer");
            }
            return MainModelChatResult.ok(content);
        } catch (Exception failed) {
            failures.incrementAndGet();
            return MainModelChatResult.failure(MainModelChatResult.Status.PROVIDER_FAILURE,
                    failed.getClass().getSimpleName() + ": " + failed.getMessage());
        }
    }

    @Override
    public String modelName() {
        return model;
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
