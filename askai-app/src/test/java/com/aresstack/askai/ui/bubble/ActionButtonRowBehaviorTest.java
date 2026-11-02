package com.aresstack.askai.ui.bubble;

import org.junit.Test;

import javax.swing.JButton;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The action-button row of a card: NAVIGATION buttons never consume the card (the decision buttons stay
 * usable), a DECISION consumes the row only when the invoker reports it as accepted — a rejected/failed
 * decision re-enables the row instead of leaving it dead.
 */
public class ActionButtonRowBehaviorTest {

    @Test
    public void navigationKeepsDecisionsUsableAndOnlyAcceptedDecisionsConsumeTheRow() throws Exception {
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                BubbleTranscriptPanel panel = new BubbleTranscriptPanel();
                final List<Integer> invoked = new ArrayList<Integer>();
                final boolean[] consume = {false};
                panel.appendActionButtons(Arrays.asList("Continue", "Sources"),
                        Arrays.asList(Boolean.FALSE, Boolean.TRUE),
                        new BubbleTranscriptPanel.ActionInvoker() {
                            public boolean invoke(int index) {
                                invoked.add(index);
                                return consume[0];
                            }
                        });
                JButton continueButton = findButton(panel, "Continue");
                JButton sourcesButton = findButton(panel, "Sources");
                assertNotNull(continueButton);
                assertNotNull(sourcesButton);

                sourcesButton.doClick();
                assertEquals("navigation invoked", Arrays.asList(1), invoked);
                assertTrue("navigation must not consume the card", continueButton.isEnabled());
                assertTrue(sourcesButton.isEnabled());

                continueButton.doClick(); // rejected/failed decision (consume=false)
                assertTrue("a non-accepted decision re-enables the row", continueButton.isEnabled());
                assertTrue(sourcesButton.isEnabled());

                consume[0] = true;
                continueButton.doClick(); // accepted decision
                assertFalse("an accepted decision consumes the row", continueButton.isEnabled());
                assertFalse(sourcesButton.isEnabled());
                assertEquals(Arrays.asList(1, 0, 0), invoked);
            }
        });
    }

    private static JButton findButton(Container root, String label) {
        for (Component component : root.getComponents()) {
            if (component instanceof JButton && label.equals(((JButton) component).getText())) {
                return (JButton) component;
            }
            if (component instanceof Container) {
                JButton nested = findButton((Container) component, label);
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }
}
