package com.aresstack.askai.research.agent.narration;

import com.aresstack.askai.agent.model.inference.AgentInferencePort;
import com.aresstack.askai.agent.model.port.ChatListener;
import com.aresstack.askai.agent.model.port.ChatRequest;
import com.aresstack.askai.agent.model.port.ChatResult;
import com.aresstack.askai.agent.model.port.ModelCall;
import com.aresstack.askai.agent.model.port.ModelFailure;
import com.aresstack.askai.agent.model.port.ModelMessage;
import com.aresstack.askai.agent.model.port.ModelPort;
import com.aresstack.askai.research.runtime.inference.ResearchModelSlots;

import java.util.function.Supplier;

/**
 * The narrator's and the visualizer's inference over the host's model port on the ACTIVE phase's slot
 * ({@code phase.<id>}, plan 5.1): research sessions no longer stream over the chat-model inference port, so a
 * chat model change never reaches them. The phase is read per call.
 */
public final class PhaseSlotInferencePort implements AgentInferencePort {

    private final ModelPort port;
    private final Supplier<String> phaseId;

    public PhaseSlotInferencePort(ModelPort port, Supplier<String> phaseId) {
        if (port == null || phaseId == null) {
            throw new IllegalArgumentException("port and phaseId must not be null");
        }
        this.port = port;
        this.phaseId = phaseId;
    }

    @Override
    public Cancellable generate(InferenceRequest request, final Listener listener) {
        ChatRequest.Builder chat = ChatRequest.builder(ResearchModelSlots.phase(phaseId.get()));
        if (!request.getSystemPrompt().isEmpty()) {
            chat.message(ModelMessage.system(request.getSystemPrompt()));
        }
        chat.message(ModelMessage.user(request.getUserPrompt()));
        final ModelCall call = port.chat(chat.build(), new ChatListener() {
            public void onThinkingDelta(String delta) {
                listener.onThinkingDelta(delta);
            }

            public void onContentDelta(String delta) {
                listener.onContentDelta(delta);
            }

            public void onCompleted(ChatResult result) {
                listener.onCompleted(result.getContent());
            }

            public void onFailed(ModelFailure failure) {
                listener.onFailed(failure.getDetail());
            }
        });
        return new Cancellable() {
            public void cancel() {
                call.cancel();
            }
        };
    }
}
