package io.github.ollama4j.models;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One tool call requested by the model in an {@code /api/chat} response ({@code message.tool_calls[].function}).
 * Immutable. Carries only the tool name and its arguments — the human-facing explanation is a UI concern,
 * never derived here.
 */
public final class ToolCall {

    private final String name;
    private final Map<String, Object> arguments;

    public ToolCall(String name, Map<String, Object> arguments) {
        this.name = name == null ? "" : name;
        this.arguments = arguments == null
                ? Collections.<String, Object>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, Object>(arguments));
    }

    public String getName() {
        return name;
    }

    public Map<String, Object> getArguments() {
        return arguments;
    }

    @Override
    public String toString() {
        return "ToolCall{" + name + " " + arguments + "}";
    }
}
