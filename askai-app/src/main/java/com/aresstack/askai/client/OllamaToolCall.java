package com.aresstack.askai.client;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One tool call the model requested, in AskAI domain terms. Immutable. Carries only the tool name and
 * arguments — the human-facing explanation shown in the UI is derived elsewhere, never from this object
 * directly.
 */
public final class OllamaToolCall {

    private final String name;
    private final Map<String, Object> arguments;

    public OllamaToolCall(String name, Map<String, Object> arguments) {
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
}
