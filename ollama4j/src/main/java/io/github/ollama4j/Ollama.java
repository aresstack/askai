package io.github.ollama4j;

import io.github.ollama4j.exceptions.OllamaException;
import io.github.ollama4j.http.OllamaHttpClient;
import io.github.ollama4j.http.OllamaHttpResponse;
import io.github.ollama4j.http.OllamaLineListener;
import io.github.ollama4j.json.OllamaJson;
import io.github.ollama4j.models.ChatCompletion;
import io.github.ollama4j.models.ChatMessage;
import io.github.ollama4j.models.ChatStreamListener;
import io.github.ollama4j.models.ChatTokenListener;
import io.github.ollama4j.models.ToolCall;
import io.github.ollama4j.models.EmbeddingResult;
import io.github.ollama4j.models.Model;
import io.github.ollama4j.models.ModelDetails;
import io.github.ollama4j.models.ModelInfo;
import io.github.ollama4j.models.PullProgress;
import io.github.ollama4j.models.PullProgressListener;
import io.github.ollama4j.models.RunningModel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Ollama {

    private final OllamaHttpClient httpClient;

    public Ollama() {
        this("http://127.0.0.1:11434");
    }

    public Ollama(String baseUrl) {
        this.httpClient = new OllamaHttpClient(baseUrl);
    }

    public void setRequestTimeoutSeconds(long seconds) {
        httpClient.setRequestTimeoutSeconds(seconds);
    }

    public boolean ping() throws OllamaException {
        return httpClient.get("/api/tags").isSuccessful();
    }

    public String getVersion() throws OllamaException {
        Map map = object(httpClient.get("/api/version"));
        return string(map, "version");
    }

    public List<Model> listModels() throws OllamaException {
        Map map = object(httpClient.get("/api/tags"));
        List result = list(map.get("models"));
        List<Model> models = new ArrayList<Model>();
        for (int i = 0; i < result.size(); i++) {
            Object item = result.get(i);
            if (item instanceof Map) {
                models.add(toModel((Map) item));
            }
        }
        return models;
    }

    public List<RunningModel> ps() throws OllamaException {
        Map map = object(httpClient.get("/api/ps"));
        List result = list(map.get("models"));
        List<RunningModel> models = new ArrayList<RunningModel>();
        for (int i = 0; i < result.size(); i++) {
            Object item = result.get(i);
            if (item instanceof Map) {
                Map itemMap = (Map) item;
                models.add(new RunningModel(
                        string(itemMap, "name"),
                        number(itemMap, "size"),
                        number(itemMap, "size_vram"),
                        string(itemMap, "expires_at")));
            }
        }
        return models;
    }

    public ModelInfo getModelDetails(String modelName) throws OllamaException {
        Map body = new LinkedHashMap();
        body.put("model", modelName);
        Map map = object(httpClient.post("/api/show", OllamaJson.toJson(body)));
        return new ModelInfo(
                string(map, "modelfile"),
                string(map, "parameters"),
                string(map, "template"),
                details(map.get("details")),
                stringList(map.get("capabilities")));
    }

    /** Map a JSON array of strings to a List; anything else yields an empty list. */
    private static List<String> stringList(Object value) {
        List<String> result = new ArrayList<String>();
        if (value instanceof List) {
            List values = (List) value;
            for (int i = 0; i < values.size(); i++) {
                Object entry = values.get(i);
                if (entry != null) {
                    result.add(String.valueOf(entry));
                }
            }
        }
        return result;
    }

    public void deleteModel(String modelName) throws OllamaException {
        Map body = new LinkedHashMap();
        body.put("name", modelName);
        require(httpClient.delete("/api/delete", OllamaJson.toJson(body)));
    }

    public void unloadModel(String modelName) throws OllamaException {
        Map body = new LinkedHashMap();
        body.put("model", modelName);
        body.put("prompt", "");
        body.put("stream", Boolean.FALSE);
        body.put("keep_alive", Integer.valueOf(0));
        require(httpClient.post("/api/generate", OllamaJson.toJson(body)));
    }

    public String generate(String modelName, String text) throws OllamaException {
        Map body = new LinkedHashMap();
        body.put("model", modelName);
        body.put("prompt", text == null ? "" : text);
        body.put("stream", Boolean.FALSE);
        Map map = object(httpClient.post("/api/generate", OllamaJson.toJson(body)));
        return string(map, "response");
    }

    public EmbeddingResult embed(String modelName, List<String> inputs) throws OllamaException {
        Map body = new LinkedHashMap();
        body.put("model", modelName);
        body.put("input", inputs == null ? new ArrayList<String>() : inputs);
        Map map = object(httpClient.post("/api/embed", OllamaJson.toJson(body)));
        List embeddings = list(map.get("embeddings"));
        List<List<Double>> vectors = new ArrayList<List<Double>>();
        for (int i = 0; i < embeddings.size(); i++) {
            vectors.add(doubleList(list(embeddings.get(i))));
        }
        return new EmbeddingResult(vectors);
    }

    public ChatCompletion streamChat(String modelName, List<ChatMessage> messages, String keepAlive,
                                     final ChatTokenListener listener) throws OllamaException {
        return streamChat(modelName, messages, keepAlive, (Object) null, tokenAdapter(listener));
    }

    public ChatCompletion streamChat(String modelName, List<ChatMessage> messages, String keepAlive,
                                     String think, final ChatTokenListener listener) throws OllamaException {
        return streamChat(modelName, messages, keepAlive, (Object) think, tokenAdapter(listener));
    }

    public ChatCompletion streamChat(String modelName, List<ChatMessage> messages, String keepAlive,
                                     ChatStreamListener listener) throws OllamaException {
        return streamChat(modelName, messages, keepAlive, (Object) null, listener);
    }

    /**
     * Streams {@code /api/chat}, keeping reasoning ({@code message.thinking}), the answer
     * ({@code message.content}) and tool calls ({@code message.tool_calls}) strictly separate. The three
     * are accumulated independently and delivered both as live deltas and, aggregated, in the final
     * {@link ChatCompletion}.
     *
     * @param think {@code null} to omit the field, a {@link Boolean} for {@code true}/{@code false}, or a
     *              level string ({@code "low"}/{@code "medium"}/{@code "high"}/{@code "max"}).
     */
    public ChatCompletion streamChat(String modelName, List<ChatMessage> messages, String keepAlive,
                                     Object think, final ChatStreamListener listener) throws OllamaException {
        return streamChat(modelName, messages, keepAlive, think, null, listener);
    }

    /**
     * Same as the {@code (think, listener)} variant, but also sends {@code tools} definitions so the model
     * can request tool calls. Each tool is a raw definition map (as Ollama expects under {@code tools}).
     */
    public ChatCompletion streamChat(String modelName, List<ChatMessage> messages, String keepAlive,
                                     Object think, List<Map<String, Object>> tools,
                                     final ChatStreamListener listener) throws OllamaException {
        Map body = buildChatBody(modelName, messages, keepAlive, think, tools);
        final StringBuilder thinking = new StringBuilder();
        final StringBuilder content = new StringBuilder();
        final List<ToolCall> toolCalls = new ArrayList<ToolCall>();
        final ChatCompletion[] completion = new ChatCompletion[] { null };
        httpClient.postLines("/api/chat", OllamaJson.toJson(body), new OllamaLineListener() {
            public void onLine(String line) {
                Object parsed = OllamaJson.parse(line);
                if (!(parsed instanceof Map)) {
                    return;
                }
                Map map = (Map) parsed;
                Map message = map.get("message") instanceof Map ? (Map) map.get("message") : null;
                if (message != null) {
                    String thinkingDelta = string(message, "thinking");
                    if (thinkingDelta.length() > 0) {
                        thinking.append(thinkingDelta);
                        if (listener != null) {
                            listener.onThinkingDelta(thinkingDelta);
                        }
                    }
                    String contentDelta = string(message, "content");
                    if (contentDelta.length() > 0) {
                        content.append(contentDelta);
                        if (listener != null) {
                            listener.onContentDelta(contentDelta);
                        }
                    }
                    List<ToolCall> calls = parseToolCalls(message.get("tool_calls"));
                    if (!calls.isEmpty()) {
                        toolCalls.addAll(calls);
                        if (listener != null) {
                            listener.onToolCalls(calls);
                        }
                    }
                }
                if (Boolean.TRUE.equals(map.get("done"))) {
                    completion[0] = new ChatCompletion(thinking.toString(), content.toString(),
                            new ArrayList<ToolCall>(toolCalls), number(map, "eval_count"),
                            number(map, "eval_duration"));
                }
            }
        });
        ChatCompletion result = completion[0] != null ? completion[0]
                : new ChatCompletion(thinking.toString(), content.toString(),
                        new ArrayList<ToolCall>(toolCalls), 0L, 0L);
        if (listener != null) {
            listener.onComplete(result);
        }
        return result;
    }

    private Map buildChatBody(String modelName, List<ChatMessage> messages, String keepAlive, Object think,
                             List<Map<String, Object>> tools) {
        Map body = new LinkedHashMap();
        body.put("model", modelName);
        body.put("messages", toMessageMaps(messages));
        body.put("stream", Boolean.TRUE);
        if (keepAlive != null && keepAlive.trim().length() > 0) {
            body.put("keep_alive", keepAlive.trim());
        }
        // Ollama accepts think as a boolean, or a level ("low"/"medium"/"high"/"max") for models with
        // reasoning effort; null omits the field entirely (thinking stays off).
        if (think instanceof Boolean) {
            body.put("think", think);
        } else if (think instanceof String && ((String) think).trim().length() > 0) {
            body.put("think", ((String) think).trim());
        }
        if (tools != null && !tools.isEmpty()) {
            body.put("tools", new ArrayList<Object>(tools));
        }
        return body;
    }

    private static ChatStreamListener tokenAdapter(final ChatTokenListener listener) {
        return new ChatStreamListener() {
            public void onThinkingDelta(String delta) {
            }

            public void onContentDelta(String delta) {
                if (listener != null) {
                    listener.onToken(delta);
                }
            }

            public void onToolCalls(List<ToolCall> toolCalls) {
            }

            public void onComplete(ChatCompletion completion) {
            }
        };
    }

    private List<ToolCall> parseToolCalls(Object value) {
        List<ToolCall> calls = new ArrayList<ToolCall>();
        if (!(value instanceof List)) {
            return calls;
        }
        List items = (List) value;
        for (int i = 0; i < items.size(); i++) {
            Object item = items.get(i);
            if (!(item instanceof Map)) {
                continue;
            }
            Map call = (Map) item;
            Object function = call.get("function");
            String name;
            Object arguments;
            if (function instanceof Map) {
                Map functionMap = (Map) function;
                name = string(functionMap, "name");
                arguments = functionMap.get("arguments");
            } else {
                name = string(call, "name");
                arguments = call.get("arguments");
            }
            if (name.length() > 0) {
                Map argumentMap = arguments instanceof Map ? (Map) arguments : null;
                calls.add(new ToolCall(name, argumentMap));
            }
        }
        return calls;
    }

    public void pullModel(String modelName, final PullProgressListener listener) throws OllamaException {
        Map body = new LinkedHashMap();
        body.put("name", modelName);
        body.put("stream", Boolean.TRUE);
        httpClient.postLines("/api/pull", OllamaJson.toJson(body), new OllamaLineListener() {
            public void onLine(String line) {
                Map map = (Map) OllamaJson.parse(line);
                if (listener != null) {
                    listener.onProgress(new PullProgress(
                            string(map, "status"),
                            number(map, "completed"),
                            number(map, "total")));
                }
            }
        });
    }

    private void require(OllamaHttpResponse response) throws OllamaException {
        if (!response.isSuccessful()) {
            throw new OllamaException("HTTP " + response.getStatusCode() + ": " + response.getBody());
        }
    }

    private Map object(OllamaHttpResponse response) throws OllamaException {
        require(response);
        Object parsed = OllamaJson.parse(response.getBody());
        if (!(parsed instanceof Map)) {
            throw new OllamaException("Expected JSON object response.");
        }
        return (Map) parsed;
    }

    private List toMessageMaps(List<ChatMessage> messages) {
        List result = new ArrayList();
        if (messages == null) {
            return result;
        }
        for (int i = 0; i < messages.size(); i++) {
            ChatMessage message = messages.get(i);
            Map map = new LinkedHashMap();
            map.put("role", message.getRole());
            map.put("content", message.getContent());
            // Send back the full turn unchanged, so Ollama keeps thinking/tool_calls/tool results in
            // context for the next request (required for streaming tool loops).
            if (message.getThinking().length() > 0) {
                map.put("thinking", message.getThinking());
            }
            if (message.getToolName().length() > 0) {
                map.put("tool_name", message.getToolName());
            }
            if (!message.getToolCalls().isEmpty()) {
                map.put("tool_calls", toolCallMaps(message.getToolCalls()));
            }
            // Ollama's /api/chat expects images as an array of raw base64 strings on the message.
            if (!message.getImages().isEmpty()) {
                map.put("images", new ArrayList<String>(message.getImages()));
            }
            result.add(map);
        }
        return result;
    }

    private List toolCallMaps(List<ToolCall> toolCalls) {
        List result = new ArrayList();
        for (int i = 0; i < toolCalls.size(); i++) {
            ToolCall call = toolCalls.get(i);
            Map function = new LinkedHashMap();
            function.put("name", call.getName());
            function.put("arguments", call.getArguments());
            Map wrapper = new LinkedHashMap();
            wrapper.put("function", function);
            result.add(wrapper);
        }
        return result;
    }

    private Model toModel(Map map) {
        return new Model(
                firstNonEmpty(string(map, "name"), string(map, "model")),
                string(map, "modified_at"),
                number(map, "size"),
                string(map, "digest"),
                details(map.get("details")));
    }

    private ModelDetails details(Object value) {
        if (!(value instanceof Map)) {
            return ModelDetails.empty();
        }
        Map map = (Map) value;
        return new ModelDetails(
                string(map, "format"),
                string(map, "family"),
                string(map, "families"),
                string(map, "parameter_size"),
                string(map, "quantization_level"));
    }

    private List list(Object value) {
        return value instanceof List ? (List) value : new ArrayList();
    }

    private List<Double> doubleList(List values) {
        List<Double> result = new ArrayList<Double>();
        for (int i = 0; i < values.size(); i++) {
            Object value = values.get(i);
            if (value instanceof Number) {
                result.add(Double.valueOf(((Number) value).doubleValue()));
            }
        }
        return result;
    }

    private static String string(Map map, String key) {
        Object value = map.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private static long number(Map map, String key) {
        Object value = map.get(key);
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }

    private static String firstNonEmpty(String first, String second) {
        return first != null && first.length() > 0 ? first : second;
    }
}
