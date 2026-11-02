package com.aresstack.askai.ui.bubble;

import org.junit.Test;

import javax.swing.JTextPane;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertTrue;

/**
 * Regression for the user-reported clipping of the research greeting: the LAST line
 * ("Also: Was möchtest du herausfinden?") was cut off at the bubble's bottom edge. The bubble must be
 * tall enough after a single layout pass that the final paragraph's bottom (plus the bubble's bottom
 * padding) lies INSIDE the bubble — at several realistic widths.
 */
public class GreetingBubbleClippingTest {

    private static final String GERMAN_GREETING =
            "Hallo! Ich unterstütze dich bei einer strukturierten Recherche: Wir klären zuerst, "
            + "WAS du herausfinden willst, dann schlage ich dir eine Gliederung zur Freigabe "
            + "vor, und danach recherchiere ich echte Webquellen und sammle die Belege für "
            + "dich.\n\n"
            + "Also: Was möchtest du herausfinden?";

    @Test
    public void theLastGreetingLineIsNotClippedAtTheBubbleBottom() throws Exception {
        final int[] widths = {480, 560, 720, 900, 1300};
        onEdt(new Runnable() {
            public void run() {
                for (int width : widths) {
                    BubbleTranscriptPanel transcript = new BubbleTranscriptPanel();
                    transcript.startAssistantMessage("Agent");
                    transcript.appendAssistantDelta(GERMAN_GREETING);
                    transcript.finishAssistantMessage();

                    transcript.setSize(width, 4000);
                    layoutOnce(transcript);

                    JTextPane last = paneContaining(transcript, "herausfinden");
                    Container bubble = bubbleOf(last);
                    int lastBottomInBubble = yRelativeTo(bubble, last) + last.getHeight();
                    int bottomPadding = bubble.getInsets().bottom;
                    assertTrue("width=" + width + ": last line bottom (" + lastBottomInBubble
                                    + " + padding " + bottomPadding + ") exceeds bubble height "
                                    + bubble.getHeight(),
                            lastBottomInBubble + bottomPadding <= bubble.getHeight());
                    // And the line itself must have a full line height (not squashed to zero).
                    int lineHeight = last.getFontMetrics(last.getFont()).getHeight();
                    assertTrue("width=" + width + ": last pane too small (h=" + last.getHeight() + ")",
                            last.getHeight() >= lineHeight);
                }
            }
        });
    }

    // --- helpers ---

    private static Container bubbleOf(Component component) {
        Component current = component;
        while (current != null && !(current instanceof AssistantMarkdownBubble)) {
            current = current.getParent();
        }
        if (current == null) {
            throw new AssertionError("no AssistantMarkdownBubble ancestor");
        }
        return (Container) current;
    }

    private static void layoutOnce(Container container) {
        container.doLayout();
        for (Component child : container.getComponents()) {
            if (child instanceof Container) {
                layoutOnce((Container) child);
            }
        }
    }

    private static JTextPane paneContaining(Component root, String needle) {
        List<JTextPane> panes = new ArrayList<JTextPane>();
        collect(root, panes);
        for (JTextPane pane : panes) {
            if (pane.getText().contains(needle)) {
                return pane;
            }
        }
        throw new AssertionError("no JTextPane containing '" + needle + "'");
    }

    private static void collect(Component component, List<JTextPane> found) {
        if (component instanceof JTextPane) {
            found.add((JTextPane) component);
        }
        if (component instanceof Container) {
            for (Component child : ((Container) component).getComponents()) {
                collect(child, found);
            }
        }
    }

    private static int yRelativeTo(Component ancestor, Component component) {
        int y = 0;
        Component current = component;
        while (current != null && current != ancestor) {
            y += current.getY();
            current = current.getParent();
        }
        return y;
    }

    private static void onEdt(Runnable runnable) throws Exception {
        try {
            SwingUtilities.invokeAndWait(runnable);
        } catch (InvocationTargetException ex) {
            if (ex.getCause() instanceof RuntimeException) {
                throw (RuntimeException) ex.getCause();
            }
            if (ex.getCause() instanceof Error) {
                throw (Error) ex.getCause();
            }
            throw ex;
        }
    }
}
