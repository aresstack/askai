package com.aresstack.askai.java8.ui;

import com.aresstack.askai.java8.ui.bubble.BubbleTranscriptPanel;
import com.aresstack.askai.plugin.api.agent.AgentConversationSink;

import java.util.HashMap;
import java.util.Map;

/**
 * Adapts the generic {@link AgentConversationSink} onto the SHARED chat transcript. There is no second
 * conversation surface: agent user/assistant/thinking/tool/approval activity renders in the exact same bubbles
 * as the normal Ollama chat. Activity ids map to the transcript's opaque handles. A refresh callback lets the
 * composer re-read Send/Stop availability as the run progresses. All calls must be on the EDT.
 */
final class AskAiAgentConversationSink implements AgentConversationSink {

    /** Receives one technical diagnostic line for the chat's collapsed "Technical details" area. */
    interface TechnicalLog {
        void line(String line);
    }

    /**
     * Persists the CONVERSATION bubbles (user + assistant) to the durable chat store, exactly like the normal
     * chat, so an agent conversation survives a restart. Transient run activity (thinking, tools, cards) stays
     * ephemeral and is never persisted.
     */
    interface MessagePersister {
        /** @param messageId the agent's stable id for this message; persisted so plugins can key metadata on it. */
        void persistUser(String messageId, String text);

        void persistAssistant(String messageId, String text);

        /** Persist a muted italic info/system breadcrumb (e.g. "Websuche: …") so it survives a restart. */
        void persistInfo(String messageId, String text);
    }

    private final ChatTranscript transcript;
    private final Runnable afterUpdate;
    private final TechnicalLog technicalLog;
    private final MessagePersister persister;
    private final Map<String, BubbleTranscriptPanel.ThinkingHandle> thinking =
            new HashMap<String, BubbleTranscriptPanel.ThinkingHandle>();
    private final Map<String, BubbleTranscriptPanel.AgentActivityHandle> tools =
            new HashMap<String, BubbleTranscriptPanel.AgentActivityHandle>();

    @Override
    public void setToolActivityClickListener(final ToolActivityClickListener listener) {
        transcript.setAgentActivityClickListener(listener == null ? null
                : new BubbleTranscriptPanel.AgentActivityClickListener() {
                    public void agentActivityClicked(String title, String explanation) {
                        listener.toolActivityClicked(title, explanation);
                    }
                });
    }

    AskAiAgentConversationSink(ChatTranscript transcript, Runnable afterUpdate, TechnicalLog technicalLog) {
        this(transcript, afterUpdate, technicalLog, null);
    }

    AskAiAgentConversationSink(ChatTranscript transcript, Runnable afterUpdate, TechnicalLog technicalLog,
                              MessagePersister persister) {
        this.transcript = transcript;
        this.afterUpdate = afterUpdate;
        this.technicalLog = technicalLog;
        this.persister = persister;
    }

    @Override
    public void appendTechnicalLog(String line) {
        if (technicalLog != null && line != null && !line.isEmpty()) {
            technicalLog.line(line);
        }
    }

    @Override
    public void appendUserMessage(String messageId, String markdown) {
        transcript.appendUser(markdown);
        if (persister != null) {
            persister.persistUser(messageId, markdown);
        }
        refresh();
    }

    @Override
    public void appendAssistantMessage(String messageId, String markdown) {
        transcript.startAssistant("Agent");
        transcript.appendAssistantDelta(markdown);
        transcript.finishAssistant();
        if (persister != null) {
            persister.persistAssistant(messageId, markdown);
        }
        refresh();
    }

    @Override
    public void startThinking(String activityId, String title) {
        thinking.put(activityId, transcript.startAssistantThinking(title));
        refresh();
    }

    @Override
    public void updateThinking(String activityId, String text) {
        BubbleTranscriptPanel.ThinkingHandle handle = thinking.get(activityId);
        if (handle != null) {
            transcript.replaceAssistantThinkingText(handle, text);
        }
    }

    @Override
    public void finishThinking(String activityId, String summary) {
        BubbleTranscriptPanel.ThinkingHandle handle = thinking.remove(activityId);
        if (handle != null) {
            transcript.completeAssistantThinking(handle, summary);
        }
        refresh();
    }

    @Override
    public void startToolActivity(String activityId, String title, String explanation) {
        tools.put(activityId, transcript.startAgentActivity(title, explanation));
        refresh();
    }

    @Override
    public void updateToolActivity(String activityId, String title, String explanation) {
        BubbleTranscriptPanel.AgentActivityHandle handle = tools.get(activityId);
        if (handle != null) {
            transcript.updateAgentActivity(handle, title, explanation);
        }
    }

    @Override
    public void completeToolActivity(String activityId, String summary) {
        BubbleTranscriptPanel.AgentActivityHandle handle = tools.remove(activityId);
        if (handle != null) {
            transcript.completeAgentActivity(handle, summary);
        }
        refresh();
    }

    @Override
    public void failToolActivity(String activityId, String summary) {
        BubbleTranscriptPanel.AgentActivityHandle handle = tools.remove(activityId);
        if (handle != null) {
            transcript.failAgentActivity(handle, summary);
        }
        refresh();
    }

    @Override
    public void requestApproval(String approvalId, String prompt) {
        // Clearly recognizable, actionable via the shared composer's slash commands (/approve, /request-changes).
        transcript.startAssistant("Agent · approval");
        transcript.appendAssistantDelta(prompt
                + "\n\n_Type_ `/approve` _or_ `/request-changes` _to respond._");
        transcript.finishAssistant();
        if (persister != null && prompt != null && !prompt.trim().isEmpty()) {
            persister.persistAssistant(approvalId, prompt);
        }
        refresh();
    }

    @Override
    public void appendInfoMessage(String messageId, String markdown) {
        transcript.appendInfo(messageId, markdown);
        if (persister != null && markdown != null && !markdown.trim().isEmpty()) {
            persister.persistInfo(messageId, markdown); // survives a restart as a muted italic line (not a bubble)
        }
        refresh();
    }

    @Override
    public void markInfoStatus(String messageId, boolean success, Runnable retry) {
        transcript.markInfoStatus(messageId, success, retry);
        refresh();
    }

    @Override
    public void showProblem(String problemId, String publicMessage) {
        transcript.appendInfo("⚠ " + publicMessage);
        if (persister != null && publicMessage != null && !publicMessage.trim().isEmpty()) {
            // Persist as INFO so the restored line looks exactly like the live one (muted italic),
            // not like an agent bubble per problem.
            persister.persistInfo(problemId, "⚠ " + publicMessage);
        }
        refresh();
    }

    @Override
    public void showActionCard(String cardId, String markdown, java.util.List<ActionOption> actions,
                               ActionHandler handler) {
        renderActionCard(markdown, actions, handler);
        if (persister != null && markdown != null && !markdown.trim().isEmpty()) {
            // Persist the CARD TEXT (outline, run outcome/error, …) so it survives a restart. The buttons are
            // tied to the live state and are not persisted — the restored card is static content.
            persister.persistAssistant(cardId, markdown);
        }
    }

    @Override
    public void showLiveActionCard(String cardId, String markdown, java.util.List<ActionOption> actions,
                                   ActionHandler handler) {
        // Re-derived from the live state on restore (e.g. a pending approval): render the interactive card
        // but NEVER persist it — its content is already in the restored transcript, so persisting would
        // duplicate and accumulate it across restarts.
        renderActionCard(markdown, actions, handler);
    }

    private void renderActionCard(String markdown, final java.util.List<ActionOption> actions,
                                  final ActionHandler handler) {
        transcript.startAssistant("Agent");
        transcript.appendAssistantDelta(markdown);
        transcript.finishAssistant();
        java.util.List<String> labels = new java.util.ArrayList<String>();
        java.util.List<Boolean> navigation = new java.util.ArrayList<Boolean>();
        for (ActionOption option : actions) {
            labels.add(option.getLabel());
            navigation.add(option.getKind() == ActionKind.NAVIGATION);
        }
        transcript.appendActionButtons(labels, navigation,
                new BubbleTranscriptPanel.ActionInvoker() {
                    public boolean invoke(int index) {
                        if (handler == null || index < 0 || index >= actions.size()) {
                            return false;
                        }
                        ActionOption option = actions.get(index);
                        ActionExecutionResult result = handler.onAction(option.getId());
                        if (option.getKind() == ActionKind.NAVIGATION) {
                            return false; // navigation never consumes the card
                        }
                        if (result == ActionExecutionResult.ACCEPTED) {
                            return true;
                        }
                        if (result == ActionExecutionResult.REJECTED
                                || result == ActionExecutionResult.FAILED) {
                            transcript.appendInfo("⚠ The action could not be applied — the card stays "
                                    + "active, please try again.");
                        }
                        return false;
                    }
                });
        refresh();
    }

    @Override
    public void turnActivityChanged() {
        refresh(); // no bubble, no persistence — only the composer re-reads Send/Stop
    }

    private void refresh() {
        if (afterUpdate != null) {
            afterUpdate.run();
        }
    }
}
