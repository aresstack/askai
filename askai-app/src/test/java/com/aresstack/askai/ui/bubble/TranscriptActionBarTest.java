package com.aresstack.askai.ui.bubble;

import org.junit.Test;

import javax.swing.JButton;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;

/**
 * Exactly ONE action bar is ever visible — the CURRENT possibilities. A new bar replaces the previous one,
 * and a consumed bar is removed entirely, so a live chat matches a restored chat: no stack of stale
 * grayed-out buttons from earlier cards.
 */
public class TranscriptActionBarTest {

    @Test
    public void onlyTheLatestBarIsShownAndAConsumedBarIsRemoved() throws Exception {
        final int[] afterTwoBars = {-1};
        final int[] afterConsume = {-1};
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                BubbleTranscriptPanel transcript = new BubbleTranscriptPanel();
                // First bar: never consumed.
                transcript.appendActionButtons(Arrays.asList("Old-A", "Old-B"), null,
                        new BubbleTranscriptPanel.ActionInvoker() {
                            public boolean invoke(int index) {
                                return false;
                            }
                        });
                // Second bar: replaces the first; a click consumes it.
                transcript.appendActionButtons(Arrays.asList("New-A", "New-B"), null,
                        new BubbleTranscriptPanel.ActionInvoker() {
                            public boolean invoke(int index) {
                                return true;
                            }
                        });
                afterTwoBars[0] = countButtons(transcript);

                JButton current = firstButton(transcript);
                if (current != null) {
                    current.doClick(); // a decision that consumes the card
                }
                afterConsume[0] = countButtons(transcript);
            }
        });
        assertEquals("a new action bar replaces the previous one", 2, afterTwoBars[0]);
        assertEquals("a consumed bar is removed entirely", 0, afterConsume[0]);
    }

    private static int countButtons(BubbleTranscriptPanel transcript) {
        return countButtons((Container) transcript.getScrollPane().getViewport().getView());
    }

    private static int countButtons(Container container) {
        int count = 0;
        for (Component component : container.getComponents()) {
            if (component instanceof JButton) {
                count++;
            } else if (component instanceof Container) {
                count += countButtons((Container) component);
            }
        }
        return count;
    }

    private static JButton firstButton(BubbleTranscriptPanel transcript) {
        return firstButton((Container) transcript.getScrollPane().getViewport().getView());
    }

    private static JButton firstButton(Container container) {
        for (Component component : container.getComponents()) {
            if (component instanceof JButton) {
                return (JButton) component;
            }
            if (component instanceof Container) {
                JButton nested = firstButton((Container) component);
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }
}
