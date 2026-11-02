package com.aresstack.askai.ui.bubble;

import org.junit.Test;

import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Every entry of the transcript list must carry the same alignmentX.
 * <p>
 * A BoxLayout on the Y axis lines its children up on a shared alignment point; mixing the values splits the
 * container at that point, and LEFT-aligned entries then get only the part right of it. A NEW chat opens
 * with a centered "New conversation…" hint — which is why new chats laid every following row out at half
 * the width in the right half, while restored chats (no hint) were fine and no resize ever helped.
 */
public class TranscriptEntryAlignmentTest {

    @Test
    public void aLeadingInfoLineDoesNotHalveTheRowsThatFollowIt() throws Exception {
        onEdt(new Runnable() {
            public void run() {
                BubbleTranscriptPanel transcript = new BubbleTranscriptPanel();
                transcript.appendInfo("New conversation. Type a message below and press Enter.");
                transcript.appendUserMessage("autos");
                transcript.setSize(784, 600);
                layoutDeep(transcript);

                Container row = rowOf(transcript);
                assertEquals("the row must fill the transcript width, not half of it",
                        listWidth(transcript), row.getWidth());
                assertEquals("and it must start at the left edge, not at the alignment point",
                        0, row.getX());
            }
        });
    }

    @Test
    public void everyEntryOfTheListSharesOneAlignment() throws Exception {
        onEdt(new Runnable() {
            public void run() {
                BubbleTranscriptPanel transcript = new BubbleTranscriptPanel();
                transcript.appendInfo("hint");
                transcript.appendUserMessage("user");
                transcript.startAssistantMessage("Agent");
                transcript.appendAssistantDelta("answer");
                transcript.finishAssistantMessage();
                transcript.setSize(784, 600);
                layoutDeep(transcript);

                Container list = messageList(transcript);
                for (Component entry : list.getComponents()) {
                    assertEquals(entry.getClass().getSimpleName() + " breaks the shared alignment",
                            Component.LEFT_ALIGNMENT, entry.getAlignmentX(), 0.0001f);
                }
                assertTrue("the test needs entries to be meaningful", list.getComponentCount() > 2);
            }
        });
    }

    // ------------------------------------------------------------------ helpers

    private static Container messageList(Container transcript) {
        for (Component child : allComponents(transcript)) {
            if (child instanceof Container && child.getClass().getSimpleName().equals("WidthTrackingList")) {
                return (Container) child;
            }
        }
        throw new IllegalStateException("no message list");
    }

    private static int listWidth(Container transcript) {
        return messageList(transcript).getWidth();
    }

    private static Container rowOf(Container transcript) {
        for (Component child : allComponents(transcript)) {
            if (child instanceof BubbleMessageRow) {
                return (Container) child;
            }
        }
        throw new IllegalStateException("no bubble row");
    }

    private static List<Component> allComponents(Container container) {
        List<Component> found = new ArrayList<Component>();
        collect(container, found);
        return found;
    }

    private static void collect(Container container, List<Component> found) {
        for (Component child : container.getComponents()) {
            found.add(child);
            if (child instanceof javax.swing.JScrollPane) {
                Component view = ((javax.swing.JScrollPane) child).getViewport().getView();
                found.add(view); // the view itself, not only its children
                if (view instanceof Container) {
                    collect((Container) view, found);
                }
            } else if (child instanceof Container) {
                collect((Container) child, found);
            }
        }
    }

    private static void layoutDeep(Container container) {
        for (int pass = 0; pass < 3; pass++) {
            invalidateDeep(container);
            doLayoutDeep(container);
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

    private static void doLayoutDeep(Container container) {
        container.doLayout();
        for (Component child : container.getComponents()) {
            if (child instanceof Container) {
                doLayoutDeep((Container) child);
            }
        }
    }

    private static void onEdt(Runnable body) throws Exception {
        if (java.awt.GraphicsEnvironment.isHeadless()) {
            return;
        }
        SwingUtilities.invokeAndWait(body);
    }

}
