package com.aresstack.askai.party;

import com.aresstack.askai.groupchat.GroupChatMessage;
import com.aresstack.askai.groupchat.Participant;

import java.util.List;
import java.util.Map;

/**
 * Port through which the party bot obtains a model response, decoupling group chat from the
 * concrete chat runtime (Ollama today).  Implementations must be asynchronous and call back
 * exactly once.
 */
public interface BotResponder {

    /** Delivered exactly once per {@link #respond} call (thinking deltas may precede it). */
    interface Callback {
        /** Streaming thinking output from the model, for local visualization on the bot host. */
        void onThinkingDelta(String delta);

        void onResponse(String markdown);

        /** The model deliberately chose not to answer (always-policy silence). */
        void onNoAnswer();

        void onFailure(Exception error);
    }

    /** @return {@code true} when this peer could answer right now (model selected + reachable). */
    boolean isReady();

    /**
     * Model names that may be @-mentioned directly (e.g. {@code @gemma4:e2b}) to address the bot
     * with a specific model.  Empty when model mentions are disabled or unknown.
     */
    List<String> modelMentionHandles();

    /**
     * Produce the bot answer for {@code addressed} given the recent room context.
     *
     * @param context        recent room messages, oldest first (including {@code addressed})
     * @param addressed      the bot-mentioning message to answer
     * @param profiles       participantId → profile, for labelling senders in the prompt
     * @param requestedModel a specific model mentioned by name, or {@code null} for the default
     */
    void respond(List<GroupChatMessage> context, GroupChatMessage addressed,
                 Map<String, Participant> profiles, String requestedModel, Callback callback);
}
