package com.aresstack.askai.modelport.provider;

import com.aresstack.askai.agent.model.port.ModelMessage;
import com.aresstack.askai.agent.model.port.OutputFormat;
import com.aresstack.askai.agent.model.port.ThinkingMode;
import com.aresstack.askai.agent.model.port.ToolCall;
import com.aresstack.askai.agent.model.port.ToolDefinition;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Writes the JSON body of {@code POST /chat/completions} for one {@link ProviderCall}. Rules taken over from the
 * OpenAI-compatible chat adapter of Space Harvester, where each one was observed against a real service:
 * streaming only through {@code "stream": true} in the body, optional fields only when set, {@code n} never sent,
 * the {@code developer} role never used (one service answered it with HTTP 500).
 *
 * <p>Thinking: {@code reasoning_effort} carries Low, Medium and High as they are. Off and Auto send nothing (many
 * services do not know {@code "none"}), except for a model that cannot switch reasoning off, which gets
 * {@code "low"} and, when the caller asked for no reasoning, the same extra reasoning room in its answer budget as on
 * Ollama ({@link ProviderCall#LEVEL_ONLY_REASONING_TOKENS}). Such a model is {@code gpt-oss}, and any model whose
 * service demanded {@code max_completion_tokens} ({@link Relaxation#MAX_COMPLETION_TOKENS}): OpenAI asks that of its
 * reasoning models, which reason by default and count their reasoning in that budget, so a short answer budget
 * would otherwise end before the answer. On asks for the model's own default and sends nothing.</p>
 *
 * <p>Tool calls without an id (Ollama does not always give one) get {@code call_<n>}; a tool result without an id
 * answers the oldest open call of its tool name, so a conversation replayed from another provider stays valid.</p>
 *
 * <p>Token usage: a streamed answer reports it only when the request asks with
 * {@code "stream_options": {"include_usage": true}} (OpenAI, vLLM, Ollama's {@code /v1}, LiteLLM), so the request
 * always asks; the research token budget counts it.</p>
 *
 * <p>Services differ in the optional fields they accept. A field a service refuses with HTTP 400 is sent in a
 * weaker form ({@link Relaxation}) on the next try: OpenAI's reasoning models want {@code max_completion_tokens}
 * and only their default temperature, models without reasoning levels refuse {@code reasoning_effort}, LM Studio
 * accepts only {@code json_schema} as {@code response_format}.</p>
 */
final class OpenAiRequestWriter {

    /**
     * A weaker form of one optional field, for a service that refused the field (HTTP 400). Each one removes or
     * renames the field it answers, so the same refusal cannot be answered twice.
     */
    enum Relaxation {
        /** {@code max_completion_tokens} instead of {@code max_tokens} (OpenAI's reasoning models refuse the old name). */
        MAX_COMPLETION_TOKENS("max_tokens", "with max_completion_tokens instead"),
        /** No {@code temperature}: the service's default (reasoning models accept no other value). */
        NO_TEMPERATURE("temperature", "without it"),
        /** No {@code reasoning_effort}: a model without reasoning levels. */
        NO_REASONING_EFFORT("reasoning_effort", "without it"),
        /** No {@code stream_options}: the service does not know it, its token usage then stays unreported. */
        NO_STREAM_OPTIONS("stream_options", "without it (token usage stays unreported)"),
        /** The other JSON form: {@code json_schema} for a plain JSON answer, {@code json_object} for a schema answer. */
        OTHER_JSON_FORM("response_format", "in the other JSON form"),
        /** No {@code response_format}: the prompt alone asks for JSON. */
        NO_RESPONSE_FORMAT("response_format", "without it (the prompt still asks for JSON)");

        private final String field;
        private final String retry;

        Relaxation(String field, String retry) {
            this.field = field;
            this.retry = retry;
        }

        /** The request field this relaxation answers a refusal of. */
        String field() {
            return field;
        }

        /** How the next try sends the field, for the log line ("sending it again " + this). */
        String retry() {
            return retry;
        }
    }

    /** Words of a refusal that are about the field's VALUE (too large for the context), not about the field. */
    private static final String[] VALUE_PROBLEMS = {"too large", "too long", "maximum context", "context length",
            "context window", "exceed"};

    private OpenAiRequestWriter() {
    }

    static String write(ProviderCall call) {
        return write(call, Collections.<Relaxation>emptySet());
    }

    /** The body of {@code call}, with the weaker forms in {@code relaxations}. */
    static String write(ProviderCall call, Set<Relaxation> relaxations) {
        JsonObject body = new JsonObject();
        body.addProperty("model", call.getModel());
        body.add("messages", messages(call));
        if (!call.getTools().isEmpty()) {
            // only "tools"; tool_choice is never sent, the service decides
            JsonArray tools = new JsonArray();
            for (ToolDefinition tool : call.getTools()) {
                JsonObject function = new JsonObject();
                function.addProperty("name", tool.getName());
                function.addProperty("description", tool.getDescription());
                function.add("parameters", parse(tool.getParametersSchemaJson(), "parameter schema of tool "
                        + tool.getName()));
                JsonObject entry = new JsonObject();
                entry.addProperty("type", "function");
                entry.add("function", function);
                tools.add(entry);
            }
            body.add("tools", tools);
        }
        JsonObject responseFormat = responseFormat(call.getOutputFormat(), relaxations);
        if (responseFormat != null) {
            body.add("response_format", responseFormat);
        }
        body.addProperty("stream", true);
        if (!relaxations.contains(Relaxation.NO_STREAM_OPTIONS)) {
            JsonObject options = new JsonObject();
            options.addProperty("include_usage", true); // the last chunk then carries the token usage
            body.add("stream_options", options);
        }
        if (call.getTemperature() != null && !relaxations.contains(Relaxation.NO_TEMPERATURE)) {
            body.addProperty("temperature", call.getTemperature());
        }
        String effort = reasoningEffort(call, relaxations);
        int budget = call.getAnswerTokens();
        if (budget > 0 && call.getRequestedThinking() == ThinkingMode.OFF && "low".equals(effort)) {
            budget += ProviderCall.LEVEL_ONLY_REASONING_TOKENS; // as on Ollama (plan 4.1 rule 3)
        }
        if (budget > 0) {
            body.addProperty(relaxations.contains(Relaxation.MAX_COMPLETION_TOKENS) ? "max_completion_tokens"
                    : "max_tokens", budget);
        }
        if (effort != null && !relaxations.contains(Relaxation.NO_REASONING_EFFORT)) {
            body.addProperty("reasoning_effort", effort);
        }
        return body.toString();
    }

    /**
     * The answer form: {@code json_object} for a plain JSON answer and {@code json_schema} with the caller's schema
     * for a schema answer; {@link Relaxation#OTHER_JSON_FORM} swaps the two (a plain JSON answer then sends the
     * schema of any object), {@link Relaxation#NO_RESPONSE_FORMAT} sends none.
     */
    private static JsonObject responseFormat(OutputFormat format, Set<Relaxation> relaxations) {
        boolean json = format.getKind() == OutputFormat.Kind.JSON;
        if ((!json && format.getKind() != OutputFormat.Kind.JSON_SCHEMA)
                || relaxations.contains(Relaxation.NO_RESPONSE_FORMAT)) {
            return null;
        }
        boolean schemaForm = json == relaxations.contains(Relaxation.OTHER_JSON_FORM);
        JsonObject responseFormat = new JsonObject();
        if (!schemaForm) {
            responseFormat.addProperty("type", "json_object");
            return responseFormat;
        }
        JsonObject schema = new JsonObject();
        schema.addProperty("name", "answer");
        schema.add("schema", json ? anyObject() : parse(format.getSchemaJson(), "answer schema"));
        responseFormat.addProperty("type", "json_schema");
        responseFormat.add("json_schema", schema);
        return responseFormat;
    }

    private static JsonObject anyObject() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        return schema;
    }

    /**
     * The weaker form that answers a refusal (HTTP 400) of the request {@code sentBody}, written with
     * {@code applied}: the first optional field of the request that the error's {@code param} names, else the first
     * one its message names; null when the refusal names none of them, or names the answer budget's value (too
     * large for the context), which no weaker form fixes. Only the error's own parameter and message are read,
     * never a body that may echo the whole request.
     */
    static Relaxation relaxationFor(String sentBody, Set<Relaxation> applied, String param, String message) {
        JsonObject sent;
        try {
            sent = JsonParser.parseString(sentBody).getAsJsonObject();
        } catch (JsonParseException | IllegalStateException unreadable) {
            return null;
        }
        String text = message == null ? "" : message.toLowerCase(Locale.ROOT);
        Relaxation byParam = named(sent, applied, param == null ? "" : param.trim().toLowerCase(Locale.ROOT), true,
                text);
        return byParam != null ? byParam : named(sent, applied, text, false, text);
    }

    private static Relaxation named(JsonObject sent, Set<Relaxation> applied, String evidence, boolean isParam,
                                    String message) {
        if (evidence.isEmpty()) {
            return null;
        }
        if (sent.has("stream_options") && names(evidence, isParam, "stream_options")) {
            return Relaxation.NO_STREAM_OPTIONS;
        }
        if (sent.has("response_format") && names(evidence, isParam, "response_format")) {
            return applied.contains(Relaxation.OTHER_JSON_FORM) ? Relaxation.NO_RESPONSE_FORMAT
                    : Relaxation.OTHER_JSON_FORM;
        }
        if (sent.has("reasoning_effort") && (names(evidence, isParam, "reasoning_effort")
                || (!isParam && evidence.contains("thinking")))) { // Ollama's /v1: "... does not support thinking"
            return Relaxation.NO_REASONING_EFFORT;
        }
        if (sent.has("temperature") && names(evidence, isParam, "temperature")) {
            return Relaxation.NO_TEMPERATURE;
        }
        if (sent.has("max_tokens") && names(evidence, isParam, "max_tokens") && !isValueProblem(message)) {
            return Relaxation.MAX_COMPLETION_TOKENS;
        }
        return null;
    }

    /** A parameter names the field itself or a part of it ({@code response_format.type}); a message mentions it. */
    private static boolean names(String evidence, boolean isParam, String field) {
        return isParam ? evidence.equals(field) || evidence.startsWith(field + ".") || evidence.startsWith(field + "[")
                : evidence.contains(field);
    }

    private static boolean isValueProblem(String message) {
        for (String words : VALUE_PROBLEMS) {
            if (message.contains(words)) {
                return true;
            }
        }
        return false;
    }

    /** The {@code reasoning_effort} of a call written with {@code relaxations}, or null for none (class comment). */
    static String reasoningEffort(ProviderCall call, Set<Relaxation> relaxations) {
        switch (call.effectiveThinking()) {
            case LOW:
                return "low";
            case MEDIUM:
                return "medium";
            case HIGH:
                return "high";
            case ON:
                return null;
            default: // OFF and Auto
                return OpenAiCompatibleModelProvider.isLevelOnlyApiModel(call.getModel())
                        || relaxations.contains(Relaxation.MAX_COMPLETION_TOKENS) ? "low" : null;
        }
    }

    private static JsonArray messages(ProviderCall call) {
        JsonArray messages = new JsonArray();
        Map<String, Deque<String>> openCalls = new HashMap<String, Deque<String>>();
        int generated = 0;
        for (ModelMessage message : call.getMessages()) {
            JsonObject json = new JsonObject();
            switch (message.getRole()) {
                case SYSTEM:
                    json.addProperty("role", "system");
                    json.addProperty("content", message.getContent());
                    break;
                case ASSISTANT:
                    json.addProperty("role", "assistant");
                    if (message.getToolCalls().isEmpty()) {
                        json.addProperty("content", message.getContent());
                    } else {
                        if (message.getContent().isEmpty()) {
                            json.add("content", com.google.gson.JsonNull.INSTANCE);
                        } else {
                            json.addProperty("content", message.getContent());
                        }
                        JsonArray calls = new JsonArray();
                        for (ToolCall toolCall : message.getToolCalls()) {
                            String id = toolCall.getId();
                            if (id == null || id.trim().isEmpty()) {
                                id = "call_" + (++generated);
                            }
                            Deque<String> open = openCalls.get(toolCall.getName());
                            if (open == null) {
                                open = new ArrayDeque<String>();
                                openCalls.put(toolCall.getName(), open);
                            }
                            open.addLast(id);
                            JsonObject function = new JsonObject();
                            function.addProperty("name", toolCall.getName());
                            function.addProperty("arguments", toolCall.getArgumentsJson());
                            JsonObject entry = new JsonObject();
                            entry.addProperty("id", id);
                            entry.addProperty("type", "function");
                            entry.add("function", function);
                            calls.add(entry);
                        }
                        json.add("tool_calls", calls);
                    }
                    break;
                case TOOL:
                    json.addProperty("role", "tool");
                    String id = message.getToolCallId();
                    Deque<String> open = openCalls.get(message.getToolName());
                    if (id == null || id.trim().isEmpty()) {
                        id = open == null || open.isEmpty() ? "call_" + (++generated) : open.pollFirst();
                    } else if (open != null) {
                        open.remove(id);
                    }
                    json.addProperty("tool_call_id", id);
                    json.addProperty("content", message.getContent());
                    break;
                default:
                    json.addProperty("role", "user");
                    json.addProperty("content", message.getContent());
            }
            messages.add(json);
        }
        return messages;
    }

    private static JsonElement parse(String json, String what) {
        try {
            return JsonParser.parseString(json == null || json.trim().isEmpty() ? "{}" : json);
        } catch (JsonParseException invalid) {
            throw new IllegalArgumentException("the " + what + " is not valid JSON");
        }
    }
}
