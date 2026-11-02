package com.aresstack.askai.ui.bubble;

import org.junit.Test;

import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.lang.reflect.InvocationTargetException;

import static org.junit.Assert.assertTrue;

/**
 * Regression for two user-reported chat defects, both on the plain SPEECH bubbles (user turns and agent
 * replies — the Markdown answer bubble has its own test):
 * <ul>
 * <li>a long message was cut off after a number of lines,</li>
 * <li>bubbles stayed in a narrow column while the chat window was wide.</li>
 * </ul>
 */
public class SpeechBubbleWidthAndClippingTest {

    private static final String LONG_MESSAGE =
            "Die fünf Profile HABE ich bereits vollständig angegeben. Jeder Block „Beispiel 1“ bis "
            + "„Beispiel 5“ beschreibt jeweils den unbekannten Begriff durch seine Nähe zu den genannten "
            + "Ankern. Bitte werte ausschließlich diese Angaben aus und antworte mit genau einem Begriff "
            + "je Profil, ohne Rückfrage und ohne zusätzliche Recherche, damit der Vergleich zwischen den "
            + "Profilen auswertbar bleibt.";

    @Test
    public void aLongMessageIsNeverCutOffAtTheBubbleBottom() throws Exception {
        final int[] widths = {420, 560, 720, 900, 1300};
        onEdt(new Runnable() {
            public void run() {
                for (int width : widths) {
                    BubbleTranscriptPanel transcript = new BubbleTranscriptPanel();
                    transcript.appendUserMessage(LONG_MESSAGE);
                    transcript.setSize(width, 4000);
                    layoutOnce(transcript);

                    SpeechBubblePanel bubble = firstSpeechBubble(transcript);
                    JTextArea text = firstTextArea(bubble);
                    int textBottom = yRelativeTo(bubble, text) + text.getHeight();
                    assertTrue("width=" + width + ": text bottom " + textBottom + " + padding "
                                    + bubble.getInsets().bottom + " exceeds bubble height "
                                    + bubble.getHeight(),
                            textBottom + bubble.getInsets().bottom <= bubble.getHeight());
                    // The text must also have room for every wrapped line, not just for the first few.
                    int lineHeight = text.getFontMetrics(text.getFont()).getHeight();
                    assertTrue("width=" + width + ": text area far too short (h=" + text.getHeight() + ")",
                            text.getHeight() >= lineHeight * 2);
                }
            }
        });
    }

    /**
     * The height must be measured at the width the row is REALLY laid out at. A scrollbar appearing
     * narrows the row after the fact; measuring against the parent's width then reports a height for a
     * wider layout, and the extra wrapped lines fall out of the bubble.
     */
    @Test
    public void theRowMeasuresItsHeightAtTheWidthItIsActuallyLaidOutAt() throws Exception {
        onEdt(new Runnable() {
            public void run() {
                SpeechBubblePanel bubble = new SpeechBubblePanel(BubbleSide.RIGHT,
                        java.awt.Color.BLUE, java.awt.Color.WHITE, "You", LONG_MESSAGE);
                BubbleMessageRow row = new BubbleMessageRow(bubble, BubbleSide.RIGHT);
                Container parent = new Container();
                parent.setSize(1200, 800);
                parent.add(row);

                // The row is narrower than its parent — exactly the scrollbar situation.
                row.setSize(600, row.getPreferredSize().height);
                row.doLayout();

                assertTrue("the row must be at least as tall as the bubble it lays out ("
                                + row.getPreferredSize().height + " < " + bubble.getHeight() + ")",
                        row.getPreferredSize().height >= bubble.getHeight());
            }
        });
    }

    /**
     * The width is PROPORTIONAL to what the chat has. A fixed pixel cap would satisfy this at 900px and
     * quietly turn a 2560px window back into a narrow column with huge empty margins — which is why the
     * widest viewport here is deliberately far beyond any constant in the code.
     */
    @Test
    public void bubblesUseTheAvailableWidthInsteadOfStayingInANarrowColumn() throws Exception {
        onEdt(new Runnable() {
            public void run() {
                for (int width : new int[]{900, 1300, 1920, 2560}) {
                    BubbleTranscriptPanel transcript = new BubbleTranscriptPanel();
                    transcript.appendUserMessage(LONG_MESSAGE);
                    transcript.setSize(width, 4000);
                    layoutOnce(transcript);

                    SpeechBubblePanel bubble = firstSpeechBubble(transcript);
                    assertTrue("width=" + width + ": bubble only " + bubble.getWidth()
                                    + "px wide — a long message must not stay in a narrow column",
                            bubble.getWidth() >= (int) (width * 0.7));
                    assertTrue("width=" + width + ": a moderate margin must remain",
                            bubble.getWidth() < width);
                }
            }
        });
    }

    @Test
    public void bubblesStayAnchoredAtTheirOwnSide() throws Exception {
        onEdt(new Runnable() {
            public void run() {
                BubbleTranscriptPanel transcript = new BubbleTranscriptPanel();
                transcript.appendUserMessage("kurz");
                transcript.setSize(900, 2000);
                layoutOnce(transcript);

                SpeechBubblePanel userBubble = firstSpeechBubble(transcript);
                Container row = userBubble.getParent();
                int leftGap = userBubble.getX();
                int rightGap = row.getWidth() - (userBubble.getX() + userBubble.getWidth());
                assertTrue("a user message belongs to the right edge (left=" + leftGap
                        + ", right=" + rightGap + ")", rightGap < leftGap);
                assertTrue("the side margin stays moderate (" + rightGap + "px)", rightGap <= 40);
            }
        });
    }

    // ------------------------------------------------------------------ helpers

    private static void onEdt(Runnable body) throws InterruptedException, InvocationTargetException {
        if (java.awt.GraphicsEnvironment.isHeadless()) {
            return; // no font metrics without a graphics environment
        }
        SwingUtilities.invokeAndWait(body);
    }

    private static void layoutOnce(Container container) {
        container.doLayout();
        for (Component child : container.getComponents()) {
            if (child instanceof Container) {
                layoutOnce((Container) child);
            }
        }
    }

    private static SpeechBubblePanel firstSpeechBubble(Container container) {
        for (Component child : container.getComponents()) {
            if (child instanceof SpeechBubblePanel) {
                return (SpeechBubblePanel) child;
            }
            if (child instanceof JScrollPane) {
                SpeechBubblePanel found = firstSpeechBubble(
                        (Container) ((JScrollPane) child).getViewport().getView());
                if (found != null) {
                    return found;
                }
            }
            if (child instanceof Container) {
                SpeechBubblePanel found = firstSpeechBubble((Container) child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static JTextArea firstTextArea(Container container) {
        for (Component child : container.getComponents()) {
            if (child instanceof JTextArea) {
                return (JTextArea) child;
            }
            if (child instanceof Container) {
                JTextArea found = firstTextArea((Container) child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static int yRelativeTo(Container ancestor, Component component) {
        int y = 0;
        Component current = component;
        while (current != null && current != ancestor) {
            y += current.getY();
            current = current.getParent();
        }
        return y;
    }
}
