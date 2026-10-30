package com.aresstack.askai.java8.ui.bubble;

import org.junit.Test;

import javax.swing.SwingUtilities;
import java.awt.Color;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BubbleComponentsTest {

    @Test
    public void pointsEveryBubbleTowardTranscriptCenter() {
        assertTrue(BubbleSide.LEFT.pointsRight());
        assertFalse(BubbleSide.LEFT.pointsLeft());
        assertTrue(BubbleSide.RIGHT.pointsLeft());
        assertFalse(BubbleSide.RIGHT.pointsRight());
    }

    @Test
    public void appendsStreamingTextWithoutReplacingExistingContent() throws Exception {
        final AtomicReference<SpeechBubblePanel> bubbleReference = new AtomicReference<SpeechBubblePanel>();
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                SpeechBubblePanel bubble = new SpeechBubblePanel(
                        BubbleSide.LEFT,
                        Color.BLUE,
                        Color.WHITE,
                        "Assistant",
                        "Hello");
                bubble.appendText(" world");
                bubbleReference.set(bubble);
            }
        });

        assertEquals("Hello world", bubbleReference.get().getText());
    }

    @Test
    public void createsAndUpdatesAgentActivityOnEventDispatchThread() throws Exception {
        final AtomicReference<AgentActivityBubblePanel> activityReference =
                new AtomicReference<AgentActivityBubblePanel>();
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                AgentActivityBubblePanel activity = new AgentActivityBubblePanel(
                        BubbleSide.LEFT, BubblePalette.windowsPhoneInspired(),
                        "Open website", "Verify the official product data.");
                activity.updateActivity("Read website", "Compare the documented capabilities.");
                activityReference.set(activity);
                activity.stopAnimation();
            }
        });

        AgentActivityBubblePanel activity = activityReference.get();
        assertEquals("Read website", activity.getTitle());
        assertEquals("Compare the documented capabilities.", activity.getExplanation());
        assertEquals(AgentActivityBubblePanel.VisualState.RUNNING, activity.getVisualState());
        assertFalse(activity.isAnimationRunning());
    }

    @Test(expected = IllegalStateException.class)
    public void rejectsTranscriptMutationOutsideEventDispatchThread() {
        new BubbleTranscriptPanel().appendUserMessage("Hello");
    }

    @Test
    public void thinkingUsesAssistantColoursAndActivityUsesAmber() {
        BubblePalette palette = BubblePalette.windowsPhoneInspired();
        assertEquals(palette.getAssistantBackground(), ThoughtBubbleTheme.assistant(palette).getBackground());
        assertEquals(palette.getAssistantForeground(), ThoughtBubbleTheme.assistant(palette).getForeground());
        assertEquals(palette.getActivityBackground(), ThoughtBubbleTheme.activity(palette).getBackground());
        assertEquals(palette.getActivityAccent(), ThoughtBubbleTheme.activity(palette).getAccent());
    }

    @Test
    public void assistantThinkingBubbleStreamsReasoningIntoItsOwnBody() throws Exception {
        final AtomicReference<AssistantThinkingBubblePanel> ref =
                new AtomicReference<AssistantThinkingBubblePanel>();
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                AssistantThinkingBubblePanel bubble = new AssistantThinkingBubblePanel(
                        BubbleSide.LEFT, BubblePalette.windowsPhoneInspired(), "AskAI", "");
                bubble.appendBodyText("Let me ");
                bubble.appendBodyText("think.");
                ref.set(bubble);
            }
        });
        assertEquals("Let me think.", ref.get().getExplanation());
        assertTrue(ref.get() instanceof AnimatedThoughtBubblePanel);
    }

    @Test
    public void thinkingBubbleBodyCanBeReplacedInPlace() throws Exception {
        final AtomicReference<AssistantThinkingBubblePanel> ref = new AtomicReference<AssistantThinkingBubblePanel>();
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                AssistantThinkingBubblePanel bubble = new AssistantThinkingBubblePanel(
                        BubbleSide.LEFT, BubblePalette.windowsPhoneInspired(), "AskAI", "");
                bubble.setBodyText("• Preparing topic areas …");
                bubble.setBodyText("• Concept extended – 2 topic areas added: A, B");
                ref.set(bubble);
            }
        });
        // A status bubble shows the latest full text — never the earlier lines again.
        assertEquals("• Concept extended – 2 topic areas added: A, B", ref.get().getExplanation());
    }

    @Test
    public void transcriptThinkingHandleStreamsWithoutError() throws Exception {
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                BubbleTranscriptPanel transcript = new BubbleTranscriptPanel();
                BubbleTranscriptPanel.ThinkingHandle handle = transcript.startAssistantThinking("AskAI");
                transcript.appendAssistantThinkingDelta(handle, "reasoning...");
                transcript.clear(); // stops timers cleanly
            }
        });
    }
}
