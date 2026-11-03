package com.aresstack.askai.research.plugin.spike.langchain4j;

import com.aresstack.askai.agent.model.port.ChatListener;
import com.aresstack.askai.agent.model.port.ChatResult;
import com.aresstack.askai.agent.model.port.ModelCall;
import com.aresstack.askai.agent.model.port.ModelFailure;
import com.aresstack.askai.agent.model.port.ModelMessage;
import com.aresstack.askai.agent.model.port.ModelPort;
import com.aresstack.askai.agent.model.port.ModelSlot;
import com.aresstack.askai.agent.model.port.OutputFormat;
import com.aresstack.askai.agent.model.port.ToolCall;
import com.aresstack.askai.agent.model.port.ToolDefinition;
import com.google.gson.Gson;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.internal.JsonSchemaElementUtils;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonRawSchema;
import dev.langchain4j.model.chat.request.json.JsonSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The LangChain4j {@link ChatModel} over the host's MODEL PORT v2 (ADR 0001, 3.4: "through a ChatModel adapter
 * over the host model port"). One {@link #chat} is one port call on ONE model slot: the platform keeps model
 * choice, endpoint, scheduling and residency; LangChain4j only sees a chat model without a name. Tool
 * specifications become port {@link ToolDefinition}s, a JSON-schema response format becomes
 * {@link OutputFormat#jsonSchema}; the schema is written with Gson from LangChain4j's own map form, so no
 * Jackson type crosses into our code. The port never executes a tool; LangChain4j's tool loop does, which is
 * exactly what the spike measures.
 *
 * <p>Failures of the port (no binding, timeout, cancel) are thrown as {@link ModelPortCallException}, the
 * LangChain4j convention for a failed model call. The thread blocks on the call; an interrupt cancels it.</p>
 */
public final class ModelPortChatModel implements ChatModel {

    private static final Gson GSON = new Gson();

    private final ModelPort port;
    private final ModelSlot slot;
    private final int contextTokens;
    private final Duration timeout;
    private final AtomicLong tokens;

    /**
     * @param contextTokens the role's context per call, 0 for the slot default
     * @param timeout       the per-call limit, null for the platform default
     * @param tokens        receives prompt + output tokens of every completed call (never null)
     */
    public ModelPortChatModel(ModelPort port, ModelSlot slot, int contextTokens, Duration timeout, AtomicLong tokens) {
        if (port == null || slot == null || tokens == null) {
            throw new IllegalArgumentException("port, slot and token counter are required");
        }
        this.port = port;
        this.slot = slot;
        this.contextTokens = Math.max(0, contextTokens);
        this.timeout = timeout;
        this.tokens = tokens;
    }

    @Override
    public Set<Capability> supportedCapabilities() {
        return EnumSet.of(Capability.RESPONSE_FORMAT_JSON_SCHEMA);
    }

    @Override
    public ChatResponse doChat(ChatRequest request) {
        com.aresstack.askai.agent.model.port.ChatRequest portRequest = toPortRequest(request);
        final CompletableFuture<ChatResult> done = new CompletableFuture<ChatResult>();
        ModelCall call = port.chat(portRequest, new ChatListener() {
            @Override
            public void onCompleted(ChatResult result) {
                done.complete(result);
            }

            @Override
            public void onFailed(ModelFailure failure) {
                done.completeExceptionally(new ModelPortCallException(failure));
            }
        });
        ChatResult result;
        try {
            result = done.get();
        } catch (InterruptedException interrupted) {
            call.cancel();
            Thread.currentThread().interrupt();
            throw new ModelPortCallException(new ModelFailure(ModelFailure.Kind.CANCELLED, "interrupted"));
        } catch (ExecutionException failed) {
            throw (ModelPortCallException) failed.getCause();
        }
        tokens.addAndGet(Math.max(0, result.getUsage().getPromptTokens())
                + (long) Math.max(0, result.getUsage().getOutputTokens()));
        return toResponse(result);
    }

    com.aresstack.askai.agent.model.port.ChatRequest toPortRequest(ChatRequest request) {
        ChatRequestParameters parameters = request.parameters();
        com.aresstack.askai.agent.model.port.ChatRequest.Builder port =
                com.aresstack.askai.agent.model.port.ChatRequest.builder(slot)
                        .outputFormat(outputFormat(parameters.responseFormat()))
                        .temperature(parameters.temperature())
                        .maxOutputTokens(parameters.maxOutputTokens() == null ? 0 : parameters.maxOutputTokens())
                        .contextTokens(contextTokens)
                        .timeout(timeout);
        for (ToolSpecification tool : parameters.toolSpecifications() == null
                ? Collections.<ToolSpecification>emptyList() : parameters.toolSpecifications()) {
            port.tool(new ToolDefinition(tool.name(), tool.description(), schemaJson(tool.parameters())));
        }
        for (ChatMessage message : request.messages()) {
            port.message(toModelMessage(message));
        }
        return port.build();
    }

    static OutputFormat outputFormat(ResponseFormat format) {
        if (format == null || format.type() != ResponseFormatType.JSON) {
            return OutputFormat.text();
        }
        JsonSchema schema = format.jsonSchema();
        if (schema == null || schema.rootElement() == null) {
            return OutputFormat.json();
        }
        // A raw schema passes verbatim: LangChain4j's schema model has no minItems/maxItems/minLength/maxLength,
        // so a role whose schema carries limits from its settings hands the schema text through unchanged.
        if (schema.rootElement() instanceof JsonRawSchema) {
            return OutputFormat.jsonSchema(((JsonRawSchema) schema.rootElement()).schema());
        }
        return OutputFormat.jsonSchema(GSON.toJson(JsonSchemaElementUtils.toMap(schema.rootElement())));
    }

    static String schemaJson(JsonObjectSchema parameters) {
        if (parameters == null) {
            Map<String, Object> empty = new LinkedHashMap<String, Object>();
            empty.put("type", "object");
            empty.put("properties", new LinkedHashMap<String, Object>());
            return GSON.toJson(empty);
        }
        return GSON.toJson(JsonSchemaElementUtils.toMap(parameters));
    }

    static ModelMessage toModelMessage(ChatMessage message) {
        if (message instanceof SystemMessage) {
            return ModelMessage.system(((SystemMessage) message).text());
        }
        if (message instanceof UserMessage) {
            return ModelMessage.user(((UserMessage) message).singleText());
        }
        if (message instanceof ToolExecutionResultMessage) {
            ToolExecutionResultMessage result = (ToolExecutionResultMessage) message;
            return ModelMessage.toolResult(result.toolName(), result.id(), result.text());
        }
        if (message instanceof AiMessage) {
            AiMessage ai = (AiMessage) message;
            List<ToolCall> calls = new ArrayList<ToolCall>();
            if (ai.hasToolExecutionRequests()) {
                for (ToolExecutionRequest request : ai.toolExecutionRequests()) {
                    calls.add(new ToolCall(request.id(), request.name(), request.arguments()));
                }
            }
            return ModelMessage.assistant(ai.text() == null ? "" : ai.text(), ai.thinking(), calls);
        }
        throw new IllegalArgumentException("unsupported message type " + message.type());
    }

    static ChatResponse toResponse(ChatResult result) {
        List<ToolExecutionRequest> requests = new ArrayList<ToolExecutionRequest>();
        for (ToolCall call : result.getToolCalls()) {
            requests.add(ToolExecutionRequest.builder().id(call.getId()).name(call.getName())
                    .arguments(call.getArgumentsJson()).build());
        }
        AiMessage.Builder ai = AiMessage.builder().text(result.getContent().isEmpty() && !requests.isEmpty()
                ? null : result.getContent());
        if (!result.getThinking().isEmpty()) {
            ai.thinking(result.getThinking());
        }
        if (!requests.isEmpty()) {
            ai.toolExecutionRequests(requests);
        }
        return ChatResponse.builder()
                .aiMessage(ai.build())
                .tokenUsage(new TokenUsage(Math.max(0, result.getUsage().getPromptTokens()),
                        Math.max(0, result.getUsage().getOutputTokens())))
                .finishReason(finishReason(result.getFinishReason()))
                .build();
    }

    static FinishReason finishReason(ChatResult.FinishReason reason) {
        switch (reason) {
            case STOP:
                return FinishReason.STOP;
            case LENGTH:
                return FinishReason.LENGTH;
            case TOOL_CALLS:
                return FinishReason.TOOL_EXECUTION;
            default:
                return FinishReason.OTHER;
        }
    }

    /** A port call that ended without a result; carries the port's failure kind. */
    public static final class ModelPortCallException extends RuntimeException {

        private final ModelFailure failure;

        public ModelPortCallException(ModelFailure failure) {
            super("model port call failed: " + failure);
            this.failure = failure;
        }

        public ModelFailure getFailure() {
            return failure;
        }
    }
}
