package com.aresstack.askai.modelport.provider;

import com.aresstack.askai.agent.model.port.ModelMessage;
import com.aresstack.askai.agent.model.port.OutputFormat;
import com.aresstack.askai.agent.model.port.ThinkingMode;
import com.aresstack.askai.agent.model.port.ToolCall;
import com.aresstack.askai.agent.model.port.ToolDefinition;
import com.aresstack.askai.modelport.ModelPortSettings;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * Writes the JSON body of {@code POST /chat/completions} for one {@link ProviderCall}. Rules taken over from the
 * OpenAI-compatible chat adapter of Space Harvester, where each one was observed against a real service:
 * streaming only through {@code "stream": true} in the body, optional fields only when set, {@code n} never sent,
 * the {@code developer} role never used (one service answered it with HTTP 500).
 *
 * <p>Thinking: {@code reasoning_effort} carries Low, Medium and High as they are. Off and Auto send nothing (many
 * services do not know {@code "none"}), except for a model that cannot switch reasoning off ({@code gpt-oss}),
 * which gets {@code "low"} and, when the caller asked for no reasoning, the same extra reasoning room in its answer
 * budget as on Ollama ({@link ProviderCall#LEVEL_ONLY_REASONING_TOKENS}). On asks for the model's own default and
 * sends nothing.</p>
 *
 * <p>Tool calls without an id (Ollama does not always give one) get {@code call_<n>}; a tool result without an id
 * answers the oldest open call of its tool name, so a conversation replayed from another provider stays valid.</p>
 */
final class OpenAiRequestWriter {

    private OpenAiRequestWriter() {
    }

    static String write(ProviderCall call) {
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
        OutputFormat format = call.getOutputFormat();
        if (format.getKind() == OutputFormat.Kind.JSON) {
            JsonObject responseFormat = new JsonObject();
            responseFormat.addProperty("type", "json_object");
            body.add("response_format", responseFormat);
        } else if (format.getKind() == OutputFormat.Kind.JSON_SCHEMA) {
            JsonObject schema = new JsonObject();
            schema.addProperty("name", "answer");
            schema.add("schema", parse(format.getSchemaJson(), "answer schema"));
            JsonObject responseFormat = new JsonObject();
            responseFormat.addProperty("type", "json_schema");
            responseFormat.add("json_schema", schema);
            body.add("response_format", responseFormat);
        }
        body.addProperty("stream", true);
        if (call.getTemperature() != null) {
            body.addProperty("temperature", call.getTemperature());
        }
        String effort = reasoningEffort(call);
        int budget = call.getAnswerTokens();
        if (budget > 0 && call.getRequestedThinking() == ThinkingMode.OFF && "low".equals(effort)) {
            budget += ProviderCall.LEVEL_ONLY_REASONING_TOKENS; // as on Ollama (plan 4.1 rule 3)
        }
        if (budget > 0) {
            body.addProperty("max_tokens", budget);
        }
        if (effort != null) {
            body.addProperty("reasoning_effort", effort);
        }
        return body.toString();
    }

    /** The {@code reasoning_effort} of a call, or null for none (see the class comment). */
    static String reasoningEffort(ProviderCall call) {
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
                return ModelPortSettings.isLevelOnlyModel(call.getModel(), null) ? "low" : null;
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
