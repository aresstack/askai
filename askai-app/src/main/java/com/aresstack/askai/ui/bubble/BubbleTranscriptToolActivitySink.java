package com.aresstack.askai.ui.bubble;

import com.aresstack.askai.tool.ToolActivitySink;

/**
 * Drives amber tool-activity bubbles on a {@link BubbleTranscriptPanel} for a
 * {@link com.aresstack.askai.tool.ToolActivityCoordinator}. All calls must be made on the Swing
 * Event Dispatch Thread (the panel enforces this).
 */
public final class BubbleTranscriptToolActivitySink
        implements ToolActivitySink<BubbleTranscriptPanel.AgentActivityHandle> {

    private final BubbleTranscriptPanel transcript;

    public BubbleTranscriptToolActivitySink(BubbleTranscriptPanel transcript) {
        if (transcript == null) {
            throw new IllegalArgumentException("transcript must not be null");
        }
        this.transcript = transcript;
    }

    public BubbleTranscriptPanel.AgentActivityHandle start(String title, String explanation) {
        return transcript.startAgentActivity(title, explanation);
    }

    public void update(BubbleTranscriptPanel.AgentActivityHandle handle, String title, String explanation) {
        transcript.updateAgentActivity(handle, title, explanation);
    }

    public void complete(BubbleTranscriptPanel.AgentActivityHandle handle, String summary) {
        transcript.completeAgentActivity(handle, summary);
    }

    public void fail(BubbleTranscriptPanel.AgentActivityHandle handle, String summary) {
        transcript.failAgentActivity(handle, summary);
    }

    public void cancel(BubbleTranscriptPanel.AgentActivityHandle handle, String summary) {
        transcript.cancelAgentActivity(handle, summary);
    }
}
