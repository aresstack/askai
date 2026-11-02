package com.aresstack.askai.ui.bubble;

import org.junit.Test;

import javax.swing.JScrollPane;
import javax.swing.Scrollable;
import javax.swing.SwingUtilities;
import java.awt.Component;

import static org.junit.Assert.assertTrue;

/**
 * The transcript body must track the viewport width so bubbles reflow when the window is made narrower
 * instead of keeping their old width and forcing a horizontal scrollbar.
 */
public class TranscriptWidthTrackingTest {

    @Test
    public void messageListTracksViewportWidth() throws Exception {
        final boolean[] tracks = {false};
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                BubbleTranscriptPanel transcript = new BubbleTranscriptPanel();
                transcript.appendUserMessage("A fairly long user message that would otherwise stay wide.");
                JScrollPane scrollPane = transcript.getScrollPane();
                Component view = scrollPane.getViewport().getView();
                tracks[0] = view instanceof Scrollable
                        && ((Scrollable) view).getScrollableTracksViewportWidth();
            }
        });
        assertTrue("the transcript body must track the viewport width", tracks[0]);
    }
}
