package com.aresstack.askai.ui;

import com.aresstack.askai.client.OllamaModelDetails;
import com.aresstack.askai.client.OllamaModelInfo;
import com.aresstack.askai.client.OllamaRunningModelInfo;
import org.junit.Test;

import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Installed and running models share one list: a running installed model is highlighted
 * and its Load toggle turns into Unload (no RUNNING badge). */
public class OllamaModelCardRunningTest {

    private static final Runnable NOOP = new Runnable() {
        public void run() {
        }
    };

    private OllamaModelCard card(final boolean running) throws Exception {
        final AtomicReference<OllamaModelCard> ref = new AtomicReference<OllamaModelCard>();
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                OllamaModelInfo model = new OllamaModelInfo("m", "m", "", 0L, "", OllamaModelDetails.empty());
                OllamaRunningModelInfo ps = running
                        ? new OllamaRunningModelInfo("m", "m", "", 1024L, 0L, OllamaModelDetails.empty()) : null;
                ref.set(OllamaModelCard.installed(model, ps, NOOP, NOOP, NOOP, NOOP)
                        .withLoadToggle(c -> { }));
            }
        });
        return ref.get();
    }

    @Test
    public void runningInstalledModelOffersUnloadAndKeepsActions() throws Exception {
        OllamaModelCard card = card(true);
        assertTrue(card.isRunningCard());
        assertEquals(0, count(card, "RUNNING"));
        assertEquals(1, count(card, "Unload"));
        assertEquals(0, count(card, "Load"));
        assertEquals(1, count(card, "Delete"));
    }

    @Test
    public void idleInstalledModelOffersLoad() throws Exception {
        OllamaModelCard card = card(false);
        assertFalse(card.isRunningCard());
        assertEquals(1, count(card, "Load"));
        assertEquals(0, count(card, "Unload"));
        assertEquals(0, count(card, "RUNNING"));
        assertEquals(0, count(card, "INSTALLED"));
    }

    @Test
    public void clickDisablesToggleUntilResetOnFailure() throws Exception {
        final OllamaModelCard[] holder = new OllamaModelCard[1];
        final int[] calls = new int[1];
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                OllamaModelInfo model = new OllamaModelInfo("m", "m", "", 0L, "", OllamaModelDetails.empty());
                holder[0] = OllamaModelCard.installed(model, null, NOOP, NOOP, NOOP, NOOP)
                        .withLoadToggle(c -> calls[0]++);
                holder[0].loadToggleButton().doClick();
            }
        });
        assertEquals(1, calls[0]);
        assertFalse(holder[0].loadToggleButton().isEnabled());
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                holder[0].resetLoadToggle();
            }
        });
        assertTrue(holder[0].loadToggleButton().isEnabled());
        assertEquals("Load", holder[0].loadToggleButton().getText());
    }

    private static int count(Container root, String text) {
        int found = 0;
        for (Component child : root.getComponents()) {
            if (child instanceof JLabel && text.equals(((JLabel) child).getText())) {
                found++;
            } else if (child instanceof javax.swing.AbstractButton
                    && text.equals(((javax.swing.AbstractButton) child).getText())) {
                found++;
            }
            if (child instanceof Container) {
                found += count((Container) child, text);
            }
        }
        return found;
    }
}
