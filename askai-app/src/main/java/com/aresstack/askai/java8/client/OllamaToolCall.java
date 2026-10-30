package com.aresstack.askai.java8.client;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One tool call the model requested, in AskAI domain terms. Immutable. Carries only the tool name and
 * arguments — the human-facing explanation shown in the UI is derived elsewhere, never from this object
 * directly.
 */
public final class OllamaToolCall {

    private final String id;
    private final String name;
    private final Map<String, Object> arguments;

    public OllamaToolCall(String name, Map<String, Object> arguments) {
        this(null, name, arguments);
    }

    /** @param id the provider's call id, or null/"" when it assigned none */
    public OllamaToolCall(String id, String name, Map<String, Object> arguments) {
        this.id = id == null ? "" : id;
        this.name = name == null ? "" : name;
        this.arguments = arguments == null
                ? Collections.<String, Object>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, Object>(arguments));
    }

    /** The provider's call id, or "" when none was assigned. */
    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public Map<String, Object> getArguments() {
        return arguments;
    }
}
