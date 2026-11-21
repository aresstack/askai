package com.aresstack.askai.modelport.provider;

import com.aresstack.askai.agent.model.port.ChatResult;
import com.aresstack.askai.agent.model.port.TokenUsage;
import com.aresstack.askai.agent.model.port.ToolCall;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Reads the answer of {@code POST /chat/completions}: the server-sent events of a streamed answer
 * ({@code data: {...}} lines ending with {@code data: [DONE]}) or, from a service that ignores
 * {@code "stream": true}, one complete JSON answer. Observed behaviour taken over from Space Harvester: chunks may be
 * heavily batched, chunks without text are normal, {@code delta.content} may be null, a service may send
 * {@code message} instead of {@code delta} inside the stream, and a usage block of zeros means "not reported".
 *
 * <p>Reasoning arrives as {@code delta.reasoning_content} (vLLM, DeepSeek) or {@code delta.reasoning} (Ollama's
 * {@code /v1}, newer vLLM); both become thinking deltas. Tool calls arrive in pieces that share an {@code index}
 * and are put together here.</p>
 */
final class OpenAiResponseReader {

    private final ProviderStream stream;
    private final StringBuilder content = new StringBuilder();
    private final StringBuilder thinking = new StringBuilder();
    private final Map<Integer, String[]> toolCalls = new TreeMap<Integer, String[]>();
    private String finishReason;
    private TokenUsage usage = TokenUsage.unknown();
    private boolean done;

    OpenAiResponseReader(ProviderStream stream) {
        this.stream = stream;
    }

    /**
     * Reads server-sent events until {@code [DONE]}, the end of the stream or an abort.
     *
     * @throws ProviderException when the service reports an error inside the stream or sends unreadable data
     */
    void readEvents(BufferedReader reader, AbortSignal abort) throws IOException, ProviderException {
        String line;
        while (!abort.isAborted() && (line = reader.readLine()) != null) {
            String payload = payloadOf(line);
            if (payload == null) {
                continue;
            }
            if ("[DONE]".equals(payload)) {
                done = true;
                return;
            }
            JsonObject chunk = object(payload);
            failOnError(chunk);
            JsonObject choice = firstChoice(chunk);
            if (choice != null) {
                JsonObject delta = child(choice, "delta");
                if (delta == null) {
                    delta = child(choice, "message"); // some services send "message" inside the stream too
                }
                if (delta != null) {
                    read(delta);
                }
                String reason = text(choice, "finish_reason");
                if (reason != null) {
                    finishReason = reason;
                }
            }
            readUsage(chunk);
        }
    }

    /** Reads one complete (non-streamed) answer. */
    void readCompletion(String json) throws ProviderException {
        JsonObject root = object(json);
        failOnError(root);
        JsonObject choice = firstChoice(root);
        if (choice == null) {
            throw new ProviderException("the answer contains no choices", false);
        }
        JsonObject message = child(choice, "message");
        if (message != null) {
            read(message);
        }
        finishReason = text(choice, "finish_reason");
        readUsage(root);
        done = true;
    }

    /** True when the answer ended properly: {@code [DONE]}, a finish reason or a complete JSON answer. */
    boolean isComplete() {
        return done || finishReason != null;
    }

    ProviderCompletion completion() {
        List<ToolCall> calls = new ArrayList<ToolCall>();
        for (String[] call : toolCalls.values()) {
            if (!call[1].isEmpty()) {
                calls.add(new ToolCall(call[0].isEmpty() ? null : call[0], call[1],
                        call[2].trim().isEmpty() ? "{}" : call[2]));
            }
        }
        return new ProviderCompletion(content.toString(), thinking.toString(), calls,
                finishReason(finishReason, !calls.isEmpty()), usage, null, null);
    }

    static ChatResult.FinishReason finishReason(String value, boolean toolCalls) {
        if (toolCalls || "tool_calls".equals(value) || "function_call".equals(value)) {
            return toolCalls ? ChatResult.FinishReason.TOOL_CALLS : ChatResult.FinishReason.OTHER;
        }
        if (value == null || value.isEmpty() || "stop".equals(value)) {
            return ChatResult.FinishReason.STOP;
        }
        if ("length".equals(value)) {
            return ChatResult.FinishReason.LENGTH;
        }
        return ChatResult.FinishReason.OTHER; // content_filter and anything unknown
    }

    /** The data part of one SSE line; null for blank lines, comments and other fields. */
    static String payloadOf(String line) {
        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.startsWith(":") || trimmed.startsWith("event:")
                || trimmed.startsWith("id:") || trimmed.startsWith("retry:")) {
            return null;
        }
        if (trimmed.startsWith("data:")) {
            trimmed = trimmed.substring("data:".length()).trim();
        }
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** The message of an error body ({@code {"error":{"message":...}}} or {@code {"error":"..."}}), or null. */
    static String errorMessage(String body) {
        try {
            JsonElement element = JsonParser.parseString(body == null ? "" : body);
            if (!element.isJsonObject()) {
                return null;
            }
            JsonElement error = element.getAsJsonObject().get("error");
            if (error == null || error.isJsonNull()) {
                return null;
            }
            if (error.isJsonPrimitive()) {
                return error.getAsString();
            }
            if (error.isJsonObject()) {
                return text(error.getAsJsonObject(), "message");
            }
            return null;
        } catch (JsonParseException | IllegalStateException unreadable) {
            return null;
        }
    }

    private void read(JsonObject delta) {
        String reasoning = text(delta, "reasoning_content");
        if (reasoning == null) {
            reasoning = text(delta, "reasoning");
        }
        if (reasoning != null && !reasoning.isEmpty()) {
            thinking.append(reasoning);
            stream.onThinkingDelta(reasoning);
        }
        String text = text(delta, "content");
        if (text != null && !text.isEmpty()) {
            content.append(text);
            stream.onContentDelta(text);
        }
        JsonElement calls = delta.get("tool_calls");
        if (calls != null && calls.isJsonArray()) {
            JsonArray array = calls.getAsJsonArray();
            for (int i = 0; i < array.size(); i++) {
                if (!array.get(i).isJsonObject()) {
                    continue;
                }
                JsonObject call = array.get(i).getAsJsonObject();
                int index = integer(call, "index");
                Integer key = Integer.valueOf(index >= 0 ? index : i);
                String[] parts = toolCalls.get(key);
                if (parts == null) {
                    parts = new String[]{"", "", ""};
                    toolCalls.put(key, parts);
                }
                String id = text(call, "id");
                if (id != null && !id.isEmpty()) {
                    parts[0] = id;
                }
                JsonObject function = child(call, "function");
                if (function != null) {
                    String name = text(function, "name");
                    if (name != null) {
                        parts[1] = parts[1] + name;
                    }
                    JsonElement arguments = function.get("arguments");
                    if (arguments != null && !arguments.isJsonNull()) {
                        parts[2] = parts[2] + (arguments.isJsonPrimitive() ? arguments.getAsString()
                                : arguments.toString());
                    }
                }
            }
        }
    }

    private void readUsage(JsonObject root) {
        JsonObject block = child(root, "usage");
        if (block == null) {
            return;
        }
        int prompt = integer(block, "prompt_tokens");
        int completion = integer(block, "completion_tokens");
        if (prompt > 0 || completion > 0) {
            usage = new TokenUsage(prompt > 0 ? prompt : -1, completion > 0 ? completion : -1);
        }
    }

    private static void failOnError(JsonObject root) throws ProviderException {
        JsonElement error = root.get("error");
        if (error != null && !error.isJsonNull()) {
            String message = errorMessage(root.toString());
            throw new ProviderException("the service reported an error: "
                    + OpenAiCompatibleModelProvider.shorten(message == null ? "unknown" : message), false);
        }
    }

    private static JsonObject object(String json) throws ProviderException {
        try {
            JsonElement element = JsonParser.parseString(json);
            if (!element.isJsonObject()) {
                throw new ProviderException("the service sent an unreadable answer (no JSON object)", false);
            }
            return element.getAsJsonObject();
        } catch (JsonParseException invalid) {
            throw new ProviderException("the service sent an unreadable answer (invalid JSON)", false);
        }
    }

    private static JsonObject firstChoice(JsonObject root) {
        JsonElement choices = root.get("choices");
        if (choices == null || !choices.isJsonArray() || choices.getAsJsonArray().size() == 0
                || !choices.getAsJsonArray().get(0).isJsonObject()) {
            return null;
        }
        return choices.getAsJsonArray().get(0).getAsJsonObject();
    }

    private static JsonObject child(JsonObject parent, String name) {
        JsonElement element = parent.get(name);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    private static String text(JsonObject parent, String name) {
        JsonElement element = parent.get(name);
        return element == null || element.isJsonNull() || !element.isJsonPrimitive() ? null : element.getAsString();
    }

    private static int integer(JsonObject parent, String name) {
        JsonElement element = parent.get(name);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return -1;
        }
        try {
            return element.getAsInt();
        } catch (NumberFormatException notANumber) {
            return -1;
        }
    }
}
