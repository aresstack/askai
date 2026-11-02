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
 * The real transcript path: an assistant Markdown answer inside a {@code MarkdownAnswerRow} must render a
 * long paragraph at its full height after a single validate/doLayout cycle, so the following heading does
 * not overlap it. No invalidate/layout loop is used — this reproduces the actual first-display behaviour.
 */
public class BubbleParagraphHeightTest {

    private static final String PARAGRAPH =
            "This is a deliberately long paragraph that must wrap onto several lines when the chat bubble "
            + "is narrow, so that its measured height reflects the real number of wrapped lines and not a "
            + "single line height computed before the final width was known to the layout manager.";

    private static final String DOCUMENT =
            "# First Heading\n\n" + PARAGRAPH + "\n\n## Second Heading\n\nShort tail.";

    @Test
    public void aMarkdownAnswerLaysOutTheParagraphFullHeightInOnePass() throws Exception {
        onEdt(new Runnable() {
            public void run() {
                BubbleTranscriptPanel transcript = new BubbleTranscriptPanel();
                transcript.startAssistantMessage("Assistant");
                transcript.appendAssistantDelta(DOCUMENT);
                transcript.finishAssistantMessage();

                transcript.setSize(560, 4000);
                layoutOnce(transcript); // exactly one top-down layout pass

                JTextPane paragraph = paneContaining(transcript, "deliberately long paragraph");
                JTextPane secondHeading = paneContaining(transcript, "Second Heading");

                int lineHeight = paragraph.getFontMetrics(paragraph.getFont()).getHeight();
                assertTrue("paragraph is not clipped to one line (h=" + paragraph.getHeight()
                                + ", line=" + lineHeight + ")",
                        paragraph.getHeight() >= lineHeight * 3);

                int paragraphBottom = yRelativeTo(transcript, paragraph) + paragraph.getHeight();
                assertTrue("second heading must start below the fully rendered paragraph (no overlap)",
                        yRelativeTo(transcript, secondHeading) >= paragraphBottom);
            }
        });
    }

    // --- helpers ---

    private static void layoutOnce(Container container) {
        container.doLayout();
        for (Component child : container.getComponents()) {
            if (child instanceof Container) {
                layoutOnce((Container) child);
            }
        }
    }

    /** The Markdown renderer's paragraph/heading component is a JTextPane subclass; match by text. */
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
