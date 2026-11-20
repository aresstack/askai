package com.aresstack.askai.research.benchmark.writer;

import com.aresstack.askai.research.runtime.writer.SentenceVectors;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * The sentence-embedding slot of the writer A/B: Ollama's {@code /api/embed} with one model (the product's sentence
 * slot falls back to its embedding model, {@code bge-m3}), so the harness runs the writer checks and the section plan
 * with the vectors the product gives them.
 */
final class OllamaSentenceVectors implements SentenceVectors {

    private static final int BATCH = 64;

    private final String baseUrl;
    private final String model;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    OllamaSentenceVectors(String baseUrl, String model) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.model = model;
    }

    public List<float[]> embed(List<String> sentences) throws Exception {
        List<float[]> out = new ArrayList<float[]>(sentences.size());
        for (int from = 0; from < sentences.size(); from += BATCH) {
            JsonObject body = new JsonObject();
            body.addProperty("model", model);
            JsonArray input = new JsonArray();
            for (String sentence : sentences.subList(from, Math.min(sentences.size(), from + BATCH))) {
                input.add(sentence);
            }
            body.add("input", input);
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(baseUrl + "/api/embed"))
                            .timeout(Duration.ofMinutes(5)).header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)).build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                throw new IllegalStateException("Ollama embed answered " + response.statusCode());
            }
            for (JsonElement vector : JsonParser.parseString(response.body()).getAsJsonObject()
                    .getAsJsonArray("embeddings")) {
                JsonArray values = vector.getAsJsonArray();
                float[] floats = new float[values.size()];
                for (int i = 0; i < floats.length; i++) {
                    floats[i] = values.get(i).getAsFloat();
                }
                out.add(floats);
            }
        }
        return out;
    }
}
