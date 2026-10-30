package com.aresstack.askai.research.plugin.spike.langchain4j;

import com.aresstack.askai.agent.model.port.ChatListener;
import com.aresstack.askai.agent.model.port.ChatRequest;
import com.aresstack.askai.agent.model.port.ChatResult;
import com.aresstack.askai.agent.model.port.ModelCall;
import com.aresstack.askai.agent.model.port.ModelFailure;
import com.aresstack.askai.agent.model.port.ModelMessage;
import com.aresstack.askai.agent.model.port.ModelPort;
import com.aresstack.askai.agent.model.port.TokenUsage;
import com.aresstack.askai.agent.model.port.ToolCall;
import com.aresstack.askai.agent.model.port.resource.ResourceSnapshot;
import com.aresstack.askai.agent.model.port.resource.SlotRequest;
import com.aresstack.askai.agent.model.port.resource.SlotReservation;
import com.aresstack.askai.agent.model.port.resource.SlotUnavailableException;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * The spike's model port: answers scripted turns in order (content, tool calls or a failure), records every
 * request, and answers on a separate thread as the real port does. Both twins run against an identical script,
 * so their recorded requests can be compared one to one.
 */
public final class ScriptedModelPort implements ModelPort {

    private final Deque<Object> script = new ArrayDeque<Object>();
    private final List<ChatRequest> requests = Collections.synchronizedList(new ArrayList<ChatRequest>());

    public ScriptedModelPort answer(String content) {
        script.add(new ChatResult(content, "", null, ChatResult.FinishReason.STOP, new TokenUsage(100, 20),
                "scripted", Duration.ZERO));
        return this;
    }

    public ScriptedModelPort toolCall(String id, String name, String argumentsJson) {
        script.add(new ChatResult("", "", Collections.singletonList(new ToolCall(id, name, argumentsJson)),
                ChatResult.FinishReason.TOOL_CALLS, new TokenUsage(100, 10), "scripted", Duration.ZERO));
        return this;
    }

    public ScriptedModelPort fail(ModelFailure.Kind kind) {
        script.add(new ModelFailure(kind, "scripted " + kind));
        return this;
    }

    public List<ChatRequest> requests() {
        synchronized (requests) {
            return new ArrayList<ChatRequest>(requests);
        }
    }

    /** One request as comparable text: slot, format, temperature, limits, tools and every message. */
    public static String describe(ChatRequest request) {
        StringBuilder sb = new StringBuilder("slot=").append(request.getSlot().getName())
                .append(" format=").append(request.getOutputFormat().getKind())
                .append(' ').append(request.getOutputFormat().getSchemaJson())
                .append(" temperature=").append(request.getTemperature())
                .append(" maxOutput=").append(request.getMaxOutputTokens())
                .append(" context=").append(request.getContextTokens())
                .append(" tools=").append(request.getTools().size()).append('\n');
        for (ModelMessage message : request.getMessages()) {
            sb.append(message.getRole()).append(": ").append(message.getContent()).append('\n');
        }
        return sb.toString();
    }

    @Override
    public CompletableFuture<SlotReservation> reserve(SlotRequest request) {
        CompletableFuture<SlotReservation> none = new CompletableFuture<SlotReservation>();
        none.completeExceptionally(new SlotUnavailableException(request.getSlot(), SlotUnavailableException.Reason.NO_BINDING,
                "the scripted port takes implicit reservations only"));
        return none;
    }

    @Override
    public ModelCall chat(SlotReservation reservation, ChatRequest request, ChatListener listener) {
        return chat(request, listener);
    }

    @Override
    public ModelCall chat(final ChatRequest request, final ChatListener listener) {
        requests.add(request);
        final Object next;
        synchronized (script) {
            next = script.isEmpty() ? new ModelFailure(ModelFailure.Kind.INVALID_RESPONSE, "script exhausted")
                    : script.poll();
        }
        Thread answer = new Thread(() -> {
            if (next instanceof ChatResult) {
                listener.onCompleted((ChatResult) next);
            } else {
                listener.onFailed((ModelFailure) next);
            }
        }, "scripted-model-port");
        answer.setDaemon(true);
        answer.start();
        return new ModelCall() {
            public void cancel() {
            }

            public boolean isDone() {
                return !answer.isAlive();
            }
        };
    }

    @Override
    public ResourceSnapshot resources() {
        return new ResourceSnapshot(Instant.now(), null, null, null, null, false);
    }
}
