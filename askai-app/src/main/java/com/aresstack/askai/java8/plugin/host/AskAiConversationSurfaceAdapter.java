package com.aresstack.askai.java8.plugin.host;

import com.aresstack.askai.java8.ui.bubble.BubbleTranscriptPanel;
import com.aresstack.askai.plugin.api.service.ConversationSurface;

import javax.swing.JComponent;
import java.util.HashMap;
import java.util.Map;

/**
 * Adapts the existing {@link BubbleTranscriptPanel} (user/assistant/thinking/tool bubbles) to the generic
 * {@link ConversationSurface} host service. Activity ids from the plugin are mapped to the panel's opaque
 * thinking/agent handles. The surface stays domain-free; no research concepts leak in.
 */
final class AskAiConversationSurfaceAdapter implements ConversationSurface {

    private final BubbleTranscriptPanel panel;
    private final Map<String, BubbleTranscriptPanel.ThinkingHandle> thinking =
            new HashMap<String, BubbleTranscriptPanel.ThinkingHandle>();
    private final Map<String, BubbleTranscriptPanel.AgentActivityHandle> tools =
            new HashMap<String, BubbleTranscriptPanel.AgentActivityHandle>();

    AskAiConversationSurfaceAdapter(BubbleTranscriptPanel panel) {
        this.panel = panel;
    }

    @Override
    public JComponent getComponent() {
        return panel;
    }

    @Override
    public void addUserMessage(String messageId, String markdown) {
        panel.appendUserMessage(markdown);
    }

    @Override
    public void addAssistantMessage(String messageId, String markdown) {
        panel.startAssistantMessage("Assistant");
        panel.appendAssistantDelta(markdown);
        panel.finishAssistantMessage();
    }

    @Override
    public void startAssistantStreaming(String messageId) {
        panel.startAssistantMessage("Assistant");
    }

    @Override
    public void appendAssistantDelta(String messageId, String delta) {
        panel.appendAssistantDelta(delta);
    }

    @Override
    public void finishAssistantStreaming(String messageId) {
        panel.finishAssistantMessage();
    }

    @Override
    public void startThinking(String activityId, String title) {
        thinking.put(activityId, panel.startAssistantThinking(title));
    }

    @Override
    public void updateThinking(String activityId, String text) {
        BubbleTranscriptPanel.ThinkingHandle handle = thinking.get(activityId);
        if (handle != null) {
            panel.replaceAssistantThinkingText(handle, text);
        }
    }

    @Override
    public void finishThinking(String activityId, String summary) {
        BubbleTranscriptPanel.ThinkingHandle handle = thinking.remove(activityId);
        if (handle != null) {
            panel.completeAssistantThinking(handle, summary);
        }
    }

    @Override
    public void startToolActivity(String activityId, String title, String explanation) {
        tools.put(activityId, panel.startAgentActivity(title, explanation));
    }

    @Override
    public void updateToolActivity(String activityId, String title, String explanation) {
        BubbleTranscriptPanel.AgentActivityHandle handle = tools.get(activityId);
        if (handle != null) {
            panel.updateAgentActivity(handle, title, explanation);
        }
    }

    @Override
    public void markApprovalRequired(String activityId, String explanation) {
        BubbleTranscriptPanel.AgentActivityHandle handle = tools.get(activityId);
        if (handle != null) {
            panel.updateAgentActivity(handle, "Approval required", explanation);
        }
    }

    @Override
    public void completeToolActivity(String activityId, String summary) {
        BubbleTranscriptPanel.AgentActivityHandle handle = tools.remove(activityId);
        if (handle != null) {
            panel.completeAgentActivity(handle, summary);
        }
    }

    @Override
    public void failToolActivity(String activityId, String summary) {
        BubbleTranscriptPanel.AgentActivityHandle handle = tools.remove(activityId);
        if (handle != null) {
            panel.failAgentActivity(handle, summary);
        }
    }

    @Override
    public void cancelActivity(String activityId, String summary) {
        BubbleTranscriptPanel.ThinkingHandle t = thinking.remove(activityId);
        if (t != null) {
            panel.cancelAssistantThinking(t, summary);
        }
        BubbleTranscriptPanel.AgentActivityHandle a = tools.remove(activityId);
        if (a != null) {
            panel.cancelAgentActivity(a, summary);
        }
    }

    @Override
    public void clear() {
        thinking.clear();
        tools.clear();
        panel.clear();
    }

    @Override
    public void dispose() {
        clear();
    }
}
