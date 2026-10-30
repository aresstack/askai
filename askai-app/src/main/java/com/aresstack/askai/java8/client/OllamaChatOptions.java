package com.aresstack.askai.java8.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * The per-call extras of a chat request beyond model, conversation, keep-alive and thinking, in AskAI domain
 * terms (the model port's call shape, ADR 0001, 3.2): the tools offered to the model with their raw JSON
 * schemas, the answer format (generic JSON or a raw JSON schema), the model options ({@code num_ctx},
 * {@code temperature}, {@code num_predict}) and an abort signal checked between streamed chunks.
 * Immutable; {@link #none()} is a plain chat.
 */
public final class OllamaChatOptions {

    /** One tool offered to the model; the schema travels verbatim. */
    public static final class Tool {
        private final String name;
        private final String description;
        private final String parametersSchemaJson;

        public Tool(String name, String description, String parametersSchemaJson) {
            this.name = name == null ? "" : name;
            this.description = description == null ? "" : description;
            this.parametersSchemaJson = parametersSchemaJson == null || parametersSchemaJson.trim().isEmpty()
                    ? "{\"type\":\"object\",\"properties\":{}}" : parametersSchemaJson;
        }

        public String getName() {
            return name;
        }

        public String getDescription() {
            return description;
        }

        public String getParametersSchemaJson() {
            return parametersSchemaJson;
        }
    }

    private static final OllamaChatOptions NONE = new OllamaChatOptions(null, null, null, false, null);

    private final List<Tool> tools;
    private final String schemaJson;
    private final Map<String, Object> modelOptions;
    private final boolean jsonMode;
    private final BooleanSupplier abort;

    /**
     * @param tools        tools offered to the model; null/empty = none
     * @param schemaJson   a raw JSON schema the answer must match, or null
     * @param modelOptions Ollama model options ({@code num_ctx}, {@code temperature}, ...); null = none
     * @param jsonMode     generic JSON answer format (ignored when a schema is given)
     * @param abort        checked between streamed chunks; true aborts the call; null = never
     */
    public OllamaChatOptions(List<Tool> tools, String schemaJson, Map<String, Object> modelOptions,
                             boolean jsonMode, BooleanSupplier abort) {
        this.tools = tools == null ? Collections.<Tool>emptyList()
                : Collections.unmodifiableList(new ArrayList<Tool>(tools));
        this.schemaJson = schemaJson == null || schemaJson.trim().isEmpty() ? null : schemaJson;
        this.modelOptions = modelOptions == null ? Collections.<String, Object>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, Object>(modelOptions));
        this.jsonMode = jsonMode;
        this.abort = abort;
    }

    public static OllamaChatOptions none() {
        return NONE;
    }

    public List<Tool> getTools() {
        return tools;
    }

    /** The raw answer schema, or null. */
    public String getSchemaJson() {
        return schemaJson;
    }

    public boolean isJsonMode() {
        return jsonMode;
    }

    public Map<String, Object> getModelOptions() {
        return modelOptions;
    }

    boolean isAborted() {
        return abort != null && abort.getAsBoolean();
    }
}
