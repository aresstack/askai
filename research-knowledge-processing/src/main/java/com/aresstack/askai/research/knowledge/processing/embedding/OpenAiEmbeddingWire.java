package com.aresstack.askai.research.knowledge.processing.embedding;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The OpenAI-compatible {@code POST /embeddings} wire, taken over from Space Harvester (enterprise-ai-client's
 * {@code EmbeddingRequestWriter}, {@code EmbeddingResponseParser} and {@code EmbeddingErrorMapper}, package
 * {@code com.aresstack.enterpriseai.embedding.openai}) with AskAI's {@link EmbeddingException}:
 * <ul>
 *   <li>a request carries only {@code model} and {@code input}; one text per request ({@code "input": "<text>"}) is
 *   the only form the OpenAPI type definition covers, so that is what is sent;</li>
 *   <li>the response is read strictly: {@code data[].embedding} float arrays (base64 is refused), the count must match,
 *   {@code index} sorts when every entry has one, a mix is invalid, {@code usage} is ignored;</li>
 *   <li>an error names only the status and a machine-readable error code, never the response's free text, because a
 *   validation error may echo the input texts.</li>
 * </ul>
 */
final class OpenAiEmbeddingWire {

    private static final Pattern ERROR_CODE = Pattern.compile("[A-Za-z0-9_.:-]{1,64}");

    private OpenAiEmbeddingWire() {
    }

    /** {@code {"model": "...", "input": "<text>"}}. */
    static String singleInput(String modelId, String text) {
        JsonObject request = new JsonObject();
        request.addProperty("model", modelId);
        request.addProperty("input", text);
        return request.toString();
    }

    static List<float[]> parse(String json, int expectedCount) {
        JsonObject root = parseObject(json);
        JsonElement dataElement = root.get("data");
        if (dataElement == null || !dataElement.isJsonArray()) {
            throw new EmbeddingException("embedding response has no 'data' array");
        }
        JsonArray data = dataElement.getAsJsonArray();
        if (data.size() != expectedCount) {
            throw new EmbeddingException("embedding count " + data.size() + " does not match input count "
                    + expectedCount);
        }
        float[][] ordered = new float[expectedCount][];
        Boolean indexed = null;
        for (int position = 0; position < data.size(); position++) {
            JsonElement entryElement = data.get(position);
            if (!entryElement.isJsonObject()) {
                throw new EmbeddingException("data[" + position + "] is not an object");
            }
            JsonObject entry = entryElement.getAsJsonObject();
            boolean hasIndex = entry.has("index") && !entry.get("index").isJsonNull();
            if (indexed == null) {
                indexed = hasIndex;
            } else if (indexed != hasIndex) {
                throw new EmbeddingException("data entries mix explicit and missing 'index'");
            }
            int slot = hasIndex ? readIndex(entry, position) : position;
            if (slot < 0 || slot >= expectedCount) {
                throw new EmbeddingException("data[" + position + "].index " + slot + " is out of range 0.."
                        + (expectedCount - 1));
            }
            if (ordered[slot] != null) {
                throw new EmbeddingException("data index " + slot + " occurs twice");
            }
            ordered[slot] = readVector(entry, position);
        }
        List<float[]> result = new ArrayList<float[]>(expectedCount);
        for (float[] vector : ordered) {
            result.add(vector);
        }
        return result;
    }

    /** The message of a failed call: endpoint path, status and the server's error code, nothing else. */
    static String failure(String endpointPath, int status, String body) {
        StringBuilder message = new StringBuilder("embedding endpoint ").append(endpointPath)
                .append(" returned HTTP ").append(status);
        String code = errorCode(body);
        if (code != null) {
            message.append(" (").append(code).append(')');
        }
        return message.toString();
    }

    /** {@code error.code}, else {@code error.type}, else a plain {@code error} token; {@code null} for free text. */
    static String errorCode(String json) {
        try {
            JsonObject root = parseObject(json);
            JsonElement error = root.get("error");
            if (error != null && error.isJsonObject()) {
                String code = token(error.getAsJsonObject().get("code"));
                return code != null ? code : token(error.getAsJsonObject().get("type"));
            }
            return token(error);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String token(JsonElement element) {
        if (element == null || !element.isJsonPrimitive()) {
            return null;
        }
        String value = element.getAsString();
        return ERROR_CODE.matcher(value).matches() ? value : null;
    }

    private static JsonObject parseObject(String json) {
        if (json == null || json.trim().isEmpty()) {
            throw new EmbeddingException("empty embedding response");
        }
        try {
            JsonElement element = JsonParser.parseString(json);
            if (!element.isJsonObject()) {
                throw new EmbeddingException("embedding response is not a JSON object");
            }
            return element.getAsJsonObject();
        } catch (JsonParseException | IllegalStateException ex) {
            throw new EmbeddingException("malformed embedding response", ex);
        }
    }

    private static int readIndex(JsonObject entry, int position) {
        JsonElement index = entry.get("index");
        if (!index.isJsonPrimitive() || !index.getAsJsonPrimitive().isNumber()) {
            throw new EmbeddingException("data[" + position + "].index is not a number");
        }
        double value = index.getAsDouble();
        if (value != Math.rint(value)) {
            throw new EmbeddingException("data[" + position + "].index is not an integer");
        }
        return (int) value;
    }

    private static float[] readVector(JsonObject entry, int position) {
        JsonElement embedding = entry.get("embedding");
        if (embedding == null || embedding.isJsonNull()) {
            throw new EmbeddingException("data[" + position + "] has no 'embedding'");
        }
        if (!embedding.isJsonArray()) {
            throw new EmbeddingException("data[" + position + "].embedding is not a float array (base64 is not "
                    + "supported)");
        }
        JsonArray array = embedding.getAsJsonArray();
        float[] values = new float[array.size()];
        for (int i = 0; i < values.length; i++) {
            JsonElement component = array.get(i);
            if (!component.isJsonPrimitive() || !component.getAsJsonPrimitive().isNumber()) {
                throw new EmbeddingException("data[" + position + "].embedding[" + i + "] is not a number");
            }
            values[i] = component.getAsFloat();
        }
        return values;
    }
}
