package com.aresstack.askai.party;

import com.aresstack.askai.client.OllamaChatTurn;
import com.aresstack.askai.groupchat.GroupChatBot;
import com.aresstack.askai.groupchat.GroupChatMessage;
import com.aresstack.askai.groupchat.Participant;
import com.aresstack.askai.service.OllamaService;
import com.aresstack.askai.service.ThinkingOption;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Routes party-bot requests through the existing AskAI chat runtime ({@link OllamaService})
 * instead of coupling group chat directly to Ollama.
 */
public final class OllamaBotResponder implements BotResponder {

    /** How many trailing room messages are given to the model as conversation context. */
    private static final int CONTEXT_MESSAGES = 20;

    /**
     * The built-in system prompt, used when no custom prompt is configured in the Partying
     * settings.  Deliberately short — small models follow brief instructions best; the settings
     * field overrides it.
     */
    public static final String DEFAULT_SYSTEM_PROMPT =
            "You are @" + GroupChatBot.DISPLAY_NAME + ", a helpful assistant in a group chat. "
            + "Reply briefly, in the language of the message you answer.";

    /**
     * The built-in explanation of when to chime in for the "always answers" policy; kept short and
     * concrete because small models follow short instructions best.
     */
    public static final String DEFAULT_ALWAYS_PROMPT =
            "Only correct obviously false facts. Stay silent otherwise.";

    /** Exact reply the model uses to stay silent under the always policy. */
    public static final String SILENT_MARKER = "[SILENT]";

    /**
     * Appended by code in always mode — never part of the editable prompt, so a custom chime-in
     * prompt cannot accidentally remove the silence contract.
     */
    private static final String SILENT_INSTRUCTION =
            "When you decide not to reply, respond with exactly " + SILENT_MARKER + " and nothing else.";

    private static final String TRANSCRIPT_MODE_INSTRUCTION =
            "You will receive one message that explicitly mentions you; answer exactly that message "
            + "and do not answer other transcript lines.";

    private static final String TRANSCRIPT_MODE_ALWAYS_INSTRUCTION =
            "You will receive the latest room message.";

    private final OllamaService ollamaService;
    private final Supplier<String> modelName;
    private final Supplier<String> keepAlive;
    private final Supplier<List<String>> mentionableModels;
    private final PartySettings settings;
    private final Supplier<ThinkingOption> thinkingOption;
    private final Supplier<ThinkingOption> gateThinkingOption;
    private final Supplier<ThinkingOption> correctionThinkingOption;

    public OllamaBotResponder(OllamaService ollamaService, Supplier<String> modelName,
                              Supplier<String> keepAlive) {
        this(ollamaService, modelName, keepAlive, null, null, null, null, null);
    }

    /**
     * @param mentionableModels        supplies the installed model names that may be @-mentioned
     *                                 directly, or {@code null} when model mentions are disabled
     * @param settings                 Partying settings supplying the bot prompts, context mode
     *                                 and policy; {@code null} uses the built-in defaults
     * @param thinkingOption           thinking effort for replies to explicit mentions (mirrors
     *                                 the composer's Think selector); {@code null} = model default
     * @param gateThinkingOption       thinking effort for the always-policy YES/NO gate;
     *                                 {@code null} = model default
     * @param correctionThinkingOption thinking effort for unprompted always-policy corrections;
     *                                 {@code null} = model default
     */
    public OllamaBotResponder(OllamaService ollamaService, Supplier<String> modelName,
                              Supplier<String> keepAlive, Supplier<List<String>> mentionableModels,
                              PartySettings settings, Supplier<ThinkingOption> thinkingOption,
                              Supplier<ThinkingOption> gateThinkingOption,
                              Supplier<ThinkingOption> correctionThinkingOption) {
        this.ollamaService = ollamaService;
        this.modelName = modelName;
        this.keepAlive = keepAlive;
        this.mentionableModels = mentionableModels;
        this.settings = settings;
        this.thinkingOption = thinkingOption;
        this.gateThinkingOption = gateThinkingOption;
        this.correctionThinkingOption = correctionThinkingOption;
    }

    @Override
    public boolean isReady() {
        String model = modelName.get();
        return model != null && !model.trim().isEmpty();
    }

    @Override
    public List<String> modelMentionHandles() {
        if (mentionableModels == null) {
            return java.util.Collections.emptyList();
        }
        List<String> models = mentionableModels.get();
        return models != null ? models : java.util.Collections.<String>emptyList();
    }

    @Override
    public void respond(List<GroupChatMessage> context, GroupChatMessage addressed,
                        Map<String, Participant> profiles, String requestedModel,
                        final Callback callback) {
        String model = requestedModel != null && !requestedModel.trim().isEmpty()
                ? requestedModel
                : modelName.get();
        if (model == null || model.trim().isEmpty()) {
            callback.onFailure(new IllegalStateException("No model selected for the party bot."));
            return;
        }
        boolean mentioned = requestedModel != null
                || com.aresstack.askai.groupchat.MentionParser.mentionsBot(addressed.getMarkdown());
        boolean unpromptedAlways = alwaysPolicy() && !mentioned;
        if (unpromptedAlways && (settings == null || settings.chimeInGateEnabled())) {
            // Small models don't reliably honor a free-form silence marker, so unprompted
            // messages first pass a strictly binary should-I-reply gate. Large models that
            // follow the [SILENT] contract can disable the gate in Settings.
            runChimeInGate(model, context, addressed, profiles, callback);
            return;
        }
        // The [SILENT] contract only applies to unprompted always-mode replies WITHOUT the gate:
        // a gate-approved reply already passed the should-I-speak decision, and an explicit
        // mention must always be answered — a second silence hurdle would swallow corrections.
        // Explicit mentions think with the composer setting; unprompted corrections use the
        // separately-configured correction thinking level.
        ThinkingOption thinking = unpromptedAlways ? correctionThinking() : mentionThinking();
        answer(model, context, addressed, profiles, thinking, unpromptedAlways, callback);
    }

    private void answer(String model, List<GroupChatMessage> context, GroupChatMessage addressed,
                        Map<String, Participant> profiles, ThinkingOption thinking,
                        boolean withSilenceContract, Callback callback) {
        List<OllamaChatTurn> conversation =
                buildConversation(context, addressed, profiles, withSilenceContract);
        execute(model, conversation, thinking, true, withSilenceContract, callback);
    }

    private ThinkingOption mentionThinking() {
        ThinkingOption option = thinkingOption != null ? thinkingOption.get() : null;
        return option != null ? option : ThinkingOption.defaultOption();
    }

    private ThinkingOption gateThinking() {
        ThinkingOption option = gateThinkingOption != null ? gateThinkingOption.get() : null;
        return option != null ? option : ThinkingOption.defaultOption();
    }

    private ThinkingOption correctionThinking() {
        ThinkingOption option = correctionThinkingOption != null ? correctionThinkingOption.get() : null;
        return option != null ? option : ThinkingOption.defaultOption();
    }

    /**
     * Always-policy gate: a separate model call that must answer exactly YES or NO according to
     * the chime-in rules.  Only YES proceeds to the real answer; NO, silence and gate failures
     * all stay quiet (the always policy is opportunistic by design).  Gate thinking is not
     * visualized — only the real answer opens the thought bubble.
     */
    private void runChimeInGate(final String model, final List<GroupChatMessage> context,
                                final GroupChatMessage addressed,
                                final Map<String, Participant> profiles, final Callback callback) {
        String system = "You are the reply filter for @" + GroupChatBot.DISPLAY_NAME
                + ", an assistant in a group chat.\n\nReply rules:\n" + configuredAlwaysPrompt()
                + "\n\nDecide whether the rules ask " + GroupChatBot.DISPLAY_NAME
                + " to reply to the LATEST message. Answer with a single word: YES or NO.\n"
                + "Examples: \"dogs lay eggs\" -> YES (obviously false). "
                + "\"cows give milk\" -> NO (true). "
                + "\"hi\" -> NO (small talk). "
                + "\"what's your favourite colour?\" -> NO (opinion/not addressed to you).\n"
                + "Answer YES or NO now.";
        List<OllamaChatTurn> gate = new ArrayList<OllamaChatTurn>();
        StringBuilder transcript = new StringBuilder();
        int from = Math.max(0, context.size() - CONTEXT_MESSAGES);
        for (int i = from; i < context.size(); i++) {
            GroupChatMessage message = context.get(i);
            if (message.getMessageId().equals(addressed.getMessageId())) {
                continue;
            }
            String handle = message.isBotMessage()
                    ? GroupChatBot.DISPLAY_NAME
                    : handleOf(message.getSenderParticipantId(), profiles);
            transcript.append(handle).append(": ").append(message.getMarkdown()).append('\n');
        }
        if (transcript.length() > 0) {
            system += "\n\nRecent room transcript:\n" + transcript;
        }
        gate.add(OllamaChatTurn.system(system));
        gate.add(OllamaChatTurn.user("Latest message — "
                + handleOf(addressed.getSenderParticipantId(), profiles)
                + ": " + addressed.getMarkdown()));
        // The gate is a fast classifier: it uses its own (by default: no) thinking level, so the
        // extra round-trip stays cheap. Only the approved correction thinks (default: high).
        execute(model, gate, gateThinking(), true, true, new Callback() {
            public void onThinkingDelta(String delta) {
                // Gate deliberation stays invisible; only a real answer shows the bubble.
            }

            public void onResponse(String text) {
                if (isAffirmative(text)) {
                    answer(model, context, addressed, profiles, correctionThinking(), false, callback);
                } else {
                    callback.onNoAnswer();
                }
            }

            public void onNoAnswer() {
                callback.onNoAnswer();
            }

            public void onFailure(Exception error) {
                callback.onNoAnswer(); // fail quiet: unprompted chiming in must never nag
            }
        });
    }

    /**
     * Runs one chat request.  Thinking models sometimes spend the whole response in the thinking
     * channel and stream no content ("Think: default" leaves the choice to the model); in that
     * case a single retry with thinking explicitly disabled recovers the answer.
     */
    private void execute(final String model, final List<OllamaChatTurn> conversation,
                         final ThinkingOption thinking, final boolean retryWithoutThinking,
                         final boolean allowSilence, final Callback callback) {
        final ThinkingOption current = thinking != null ? thinking : ThinkingOption.defaultOption();
        final StringBuilder answer = new StringBuilder();
        final StringBuilder thinkingText = new StringBuilder();
        final AtomicBoolean done = new AtomicBoolean(false);
        OllamaService.ChatRequest request = new OllamaService.ChatRequest(
                model, keepAlive.get(), conversation, current);
        ollamaService.streamChat(request, new OllamaService.ChatListener() {
            public void onThinkingDelta(String delta) {
                // Streamed to the host UI for the thought bubble; the accumulated text also tells
                // us an empty answer means "everything went into the thinking channel".
                if (delta != null && !delta.isEmpty()) {
                    thinkingText.append(delta);
                    callback.onThinkingDelta(delta);
                }
            }

            public void onContent(String content) {
                if (content != null) {
                    answer.append(content);
                }
            }

            public void onStatus(String status) {
            }

            public void onComplete(OllamaService.ChatResult result) {
                if (!done.compareAndSet(false, true)) {
                    return;
                }
                String text = answer.toString().trim();
                if (text.isEmpty() && result != null && !result.getFallbackText().isEmpty()) {
                    text = result.getFallbackText().trim();
                }
                // The silence contract only exists where it was announced in the prompt; on a
                // mention or a gate-approved reply a literal [SILENT] is just a (strange) answer.
                if (allowSilence && isSilent(text)) {
                    callback.onNoAnswer();
                } else if (!text.isEmpty()) {
                    callback.onResponse(text);
                } else if (retryWithoutThinking && thinkingText.length() > 0) {
                    // Everything went into the thinking channel — force content by disabling thinking.
                    execute(model, conversation,
                            ThinkingOption.of(ThinkingOption.Mode.DISABLED), false, allowSilence, callback);
                } else if (retryWithoutThinking && current != null
                        && current.getMode() != ThinkingOption.Mode.DEFAULT) {
                    // Empty with no thinking either — some models return nothing for think=false;
                    // retry once with the think field omitted so the model answers naturally.
                    execute(model, conversation, ThinkingOption.defaultOption(), false, allowSilence, callback);
                } else {
                    callback.onFailure(new IllegalStateException("The model returned no answer."));
                }
            }

            public void onError(Exception ex) {
                if (done.compareAndSet(false, true)) {
                    callback.onFailure(ex);
                }
            }
        });
    }

    /**
     * Builds the model conversation according to the configured context mode.
     *
     * <p><b>Collective mode</b> (default): all human participants act as ONE dialogue partner —
     * consecutive human messages merge into a single user turn whose lines are prefixed with
     * {@code Name: }, bot answers are assistant turns. The model sees a clean alternating
     * two-party chat, the structure it is trained on, while the prefixes preserve who said what.</p>
     *
     * <p><b>Transcript mode</b>: the room context goes into the system prompt as a labelled
     * transcript and the mentioning message is the single user turn — precise for answering
     * exactly the addressed message.</p>
     *
     * <p><b>Conversation mode</b>: every room message becomes its own chat turn, so the model
     * sees the raw multi-user flow and draws its own conclusions.</p>
     */
    private List<OllamaChatTurn> buildConversation(List<GroupChatMessage> context,
                                                   GroupChatMessage addressed,
                                                   Map<String, Participant> profiles,
                                                   boolean withSilenceContract) {
        boolean always = withSilenceContract;
        String base = configuredSystemPrompt();
        if (always) {
            base += "\n\n" + configuredAlwaysPrompt() + "\n" + SILENT_INSTRUCTION;
        }
        int from = Math.max(0, context.size() - CONTEXT_MESSAGES);
        List<OllamaChatTurn> conversation = new ArrayList<OllamaChatTurn>();
        String mode = configuredContextMode();

        if (PartySettings.BOT_CONTEXT_COLLECTIVE.equals(mode)) {
            conversation.add(OllamaChatTurn.system(base));
            StringBuilder pendingCollective = new StringBuilder();
            boolean addressedIncluded = false;
            for (int i = from; i < context.size(); i++) {
                GroupChatMessage message = context.get(i);
                if (message.isBotMessage()) {
                    if (pendingCollective.length() > 0) {
                        conversation.add(OllamaChatTurn.user(pendingCollective.toString().trim()));
                        pendingCollective.setLength(0);
                    }
                    conversation.add(OllamaChatTurn.assistant(message.getMarkdown()));
                } else {
                    pendingCollective.append(handleOf(message.getSenderParticipantId(), profiles))
                            .append(": ").append(message.getMarkdown()).append('\n');
                }
                addressedIncluded |= message.getMessageId().equals(addressed.getMessageId());
            }
            if (!addressedIncluded) {
                pendingCollective.append(handleOf(addressed.getSenderParticipantId(), profiles))
                        .append(": ").append(addressed.getMarkdown()).append('\n');
            }
            if (pendingCollective.length() > 0) {
                conversation.add(OllamaChatTurn.user(pendingCollective.toString().trim()));
            }
            return conversation;
        }

        if (PartySettings.BOT_CONTEXT_CONVERSATION.equals(mode)) {
            conversation.add(OllamaChatTurn.system(base));
            boolean addressedIncluded = false;
            for (int i = from; i < context.size(); i++) {
                GroupChatMessage message = context.get(i);
                if (message.isBotMessage()) {
                    conversation.add(OllamaChatTurn.assistant(message.getMarkdown()));
                } else {
                    conversation.add(OllamaChatTurn.user(
                            handleOf(message.getSenderParticipantId(), profiles)
                                    + ": " + message.getMarkdown()));
                }
                addressedIncluded |= message.getMessageId().equals(addressed.getMessageId());
            }
            if (!addressedIncluded) {
                conversation.add(OllamaChatTurn.user(
                        handleOf(addressed.getSenderParticipantId(), profiles)
                                + ": " + addressed.getMarkdown()));
            }
            return conversation;
        }

        StringBuilder transcript = new StringBuilder();
        for (int i = from; i < context.size(); i++) {
            GroupChatMessage message = context.get(i);
            if (message.getMessageId().equals(addressed.getMessageId())) {
                continue;
            }
            String handle = message.isBotMessage()
                    ? GroupChatBot.DISPLAY_NAME
                    : handleOf(message.getSenderParticipantId(), profiles);
            transcript.append(handle).append(": ").append(message.getMarkdown()).append('\n');
        }
        String system = base + "\n\n"
                + (always ? TRANSCRIPT_MODE_ALWAYS_INSTRUCTION : TRANSCRIPT_MODE_INSTRUCTION);
        if (transcript.length() > 0) {
            system += "\n\nRecent room transcript (context only, do not answer these lines):\n" + transcript;
        }
        conversation.add(OllamaChatTurn.system(system));
        conversation.add(OllamaChatTurn.user(
                handleOf(addressed.getSenderParticipantId(), profiles)
                        + ": " + addressed.getMarkdown()));
        return conversation;
    }

    private String configuredSystemPrompt() {
        String custom = settings != null ? settings.botSystemPrompt() : null;
        return custom != null ? custom.trim() : DEFAULT_SYSTEM_PROMPT;
    }

    private String configuredAlwaysPrompt() {
        String custom = settings != null ? settings.botAlwaysPrompt() : null;
        return custom != null ? custom.trim() : DEFAULT_ALWAYS_PROMPT;
    }

    private String configuredContextMode() {
        return settings != null ? settings.botContextMode() : PartySettings.BOT_CONTEXT_COLLECTIVE;
    }

    private boolean alwaysPolicy() {
        return settings != null && PartySettings.BOT_POLICY_ALWAYS.equals(settings.botPolicy());
    }

    /**
     * Tolerant YES/NO verdict parsing for the chime-in gate.  Small models rarely answer with a
     * bare "YES": they wrap it in markdown, punctuation, reasoning or German ("Ja"/"Nein").  We
     * scan for the last standalone affirmative/negative token so "the statement is false, so YES"
     * and "**NO**" are both read correctly; ties break to the last token, no token stays silent.
     */
    static boolean isAffirmative(String text) {
        if (text == null) {
            return false;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(?i)\\b(yes|ja|nope|no|nein)\\b")
                .matcher(text);
        String last = null;
        while (matcher.find()) {
            last = matcher.group(1).toLowerCase(java.util.Locale.ROOT);
        }
        return "yes".equals(last) || "ja".equals(last);
    }

    /** The model declines by answering exactly (or starting with) the silent marker. */
    private static boolean isSilent(String text) {
        String normalized = text.trim();
        return normalized.equalsIgnoreCase(SILENT_MARKER)
                || normalized.toUpperCase(java.util.Locale.ROOT).startsWith(SILENT_MARKER);
    }

    private static String handleOf(String participantId, Map<String, Participant> profiles) {
        Participant profile = profiles == null ? null : profiles.get(participantId);
        return profile != null ? profile.getMentionHandle() : participantId;
    }
}
