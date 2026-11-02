package com.aresstack.askai.ui.bubble;

import org.junit.Test;

import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Rectangle;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The live-test signature: messages appended to a RUNNING chat laid out wrongly, while the very same
 * messages laid out correctly after a restart — i.e. when the transcript was built first and laid out
 * afterwards.
 * <p>
 * That difference is a LIFECYCLE difference, not a width constant: every existing bubble test builds the
 * transcript and only then lays it out, which is the restore order. This test does it the other way round —
 * realize the scroll pane at a real size FIRST, then append — and requires both to end up identical.
 */
public class LiveAppendMatchesReplayTest {

    private static final String LONG_USER_MESSAGE =
            "Die fünf Profile habe ich bereits vollständig angegeben. Jeder Block beschreibt den "
            + "unbekannten Begriff durch seine Nähe zu den genannten Ankern, und ich möchte, dass du "
            + "ausschließlich diese Angaben auswertest. ENDE-DER-TESTNACHRICHT";

    private static final String LONG_AGENT_MESSAGE =
            "Basierend auf dem Ähnlichkeitsprofil, das du bereitgestellt hast, deutet die höchste "
            + "semantische Nähe stark auf ein tragbares elektronisches Display hin. Eine plausible "
            + "Alternative wäre eine Smartwatch.";

    @Test
    public void appendingToARunningChatLaysOutExactlyLikeAReplayOfTheSameMessages() throws Exception {
        onEdt(new Runnable() {
            public void run() {
                for (int width : new int[]{700, 1000, 1600}) {
                    // LIVE: realize at a real size first, then append (a running conversation).
                    BubbleTranscriptPanel live = new BubbleTranscriptPanel();
                    Container liveHost = realize(live, width);
                    live.appendUserMessage(LONG_USER_MESSAGE);
                    appendAssistant(live, LONG_AGENT_MESSAGE);
                    settle(liveHost);

                    // REPLAY: build everything first, then lay out (what a restart does).
                    BubbleTranscriptPanel replay = new BubbleTranscriptPanel();
                    replay.appendUserMessage(LONG_USER_MESSAGE);
                    appendAssistant(replay, LONG_AGENT_MESSAGE);
                    Container replayHost = realize(replay, width);
                    settle(replayHost);

                    List<Component> liveBubbles = bubbles(live);
                    List<Component> replayBubbles = bubbles(replay);
                    assertEquals("width=" + width + ": both must hold the same bubbles",
                            replayBubbles.size(), liveBubbles.size());

                    for (int index = 0; index < liveBubbles.size(); index++) {
                        Rectangle liveBounds = liveBubbles.get(index).getBounds();
                        Rectangle replayBounds = replayBubbles.get(index).getBounds();
                        String where = "width=" + width + ", bubble " + index + " ("
                                + liveBubbles.get(index).getClass().getSimpleName() + ": live="
                                + liveBounds + ", replay=" + replayBounds + ")";
                        assertEquals(where + ": width", replayBounds.width, liveBounds.width);
                        assertEquals(where + ": height", replayBounds.height, liveBounds.height);
                        assertEquals(where + ": x", replayBounds.x, liveBounds.x);
                    }
                }
            }
        });
    }

    /** The live path must also honour the proportional width, not a fallback measured at no width at all. */
    @Test
    public void aLiveAppendedBubbleUsesTheAvailableWidthRightAway() throws Exception {
        onEdt(new Runnable() {
            public void run() {
                BubbleTranscriptPanel live = new BubbleTranscriptPanel();
                Container host = realize(live, 1600);
                live.appendUserMessage(LONG_USER_MESSAGE);
                settle(host);

                Component bubble = bubbles(live).get(0);
                Container row = bubble.getParent();
                assertTrue("a live-appended bubble must not fall back to a narrow default width (got "
                                + bubble.getWidth() + " in a row of " + row.getWidth() + ")",
                        bubble.getWidth() >= (int) (row.getWidth() * 0.7));
                int rightGap = row.getWidth() - (bubble.getX() + bubble.getWidth());
                assertTrue("the user bubble stays on the right (bubble=" + bubble.getBounds()
                                + ", row=" + row.getWidth() + ", leftGap=" + bubble.getX()
                                + ", rightGap=" + rightGap + ")",
                        rightGap < bubble.getX());
            }
        });
    }

    /** And the row must be tall enough for what it lays out — no clipping on the live path either. */
    @Test
    public void aLiveAppendedRowIsTallEnoughForItsBubble() throws Exception {
        onEdt(new Runnable() {
            public void run() {
                BubbleTranscriptPanel live = new BubbleTranscriptPanel();
                Container host = realize(live, 700);
                live.appendUserMessage(LONG_USER_MESSAGE);
                appendAssistant(live, LONG_AGENT_MESSAGE);
                settle(host);

                for (Component bubble : bubbles(live)) {
                    Container row = bubble.getParent();
                    assertTrue("row " + row.getHeight() + " must contain bubble bottom "
                                    + (bubble.getY() + bubble.getHeight()),
                            bubble.getY() + bubble.getHeight() <= row.getHeight());
                }
            }
        });
    }

    // ------------------------------------------------------------------ helpers

    /**
     * A real scroll-pane hierarchy WITHOUT window peers: the viewport is what sizes the view (the transcript
     * tracks the viewport width), so laying out from the scroll pane down reproduces the mechanism under
     * test. Sizing the transcript by hand would make the test measure itself, and a JFrame that is never
     * shown has no peer, so validate() on it does nothing at all — both would produce false greens.
     */
    private static Container realize(BubbleTranscriptPanel transcript, int width) {
        // The transcript OWNS its scroll pane internally — wrapping it in another one would test a
        // hierarchy the app does not have (and produced a phantom 720px view width).
        javax.swing.JPanel host = new javax.swing.JPanel(new java.awt.BorderLayout());
        host.add(transcript, java.awt.BorderLayout.CENTER);
        host.setSize(width, 600);
        settle(host);
        return host;
    }

    /**
     * Lay out until stable: one pass can change a width, which changes the wrapping and thus the heights.
     * Each pass INVALIDATES first — BoxLayout caches its children's size requirements, so a plain doLayout
     * would happily re-apply stale numbers and hide exactly the difference we are looking for.
     */
    private static void settle(Container container) {
        for (int pass = 0; pass < 4; pass++) {
            invalidateDeep(container);
            layoutDeep(container);
        }
    }

    private static void invalidateDeep(Container container) {
        for (Component child : container.getComponents()) {
            if (child instanceof Container) {
                invalidateDeep((Container) child);
            }
        }
        container.invalidate();
    }

    private static void layoutDeep(Container container) {
        container.doLayout();
        for (Component child : container.getComponents()) {
            if (child instanceof Container) {
                layoutDeep((Container) child);
            }
        }
    }

    /** The assistant path is a lifecycle (start/delta/finish), not a single call. */
    private static void appendAssistant(BubbleTranscriptPanel transcript, String text) {
        transcript.startAssistantMessage("Agent");
        transcript.appendAssistantDelta(text);
        transcript.finishAssistantMessage();
    }

    /** Every message bubble, whatever kind of row holds it — the row's single laid-out child. */
    private static List<Component> bubbles(Container container) {
        List<Component> found = new ArrayList<Component>();
        collect(container, found);
        return found;
    }

    private static void collect(Container container, List<Component> found) {
        for (Component child : container.getComponents()) {
            if (child instanceof Container
                    && container.getClass().getSimpleName().endsWith("Row")) {
                found.add(child);
                continue;
            }
            if (child instanceof JScrollPane) {
                collect((Container) ((JScrollPane) child).getViewport().getView(), found);
            } else if (child instanceof Container) {
                collect((Container) child, found);
            }
        }
    }

    private static void onEdt(Runnable body) throws InterruptedException, InvocationTargetException {
        if (java.awt.GraphicsEnvironment.isHeadless()) {
            return; // no font metrics without a graphics environment
        }
        SwingUtilities.invokeAndWait(body);
    }
}
