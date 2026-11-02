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

/** Installed and running models share one list: a running installed model is highlighted with a RUNNING badge. */
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
                ref.set(OllamaModelCard.installed(model, ps, NOOP, NOOP, NOOP, NOOP));
            }
        });
        return ref.get();
    }

    @Test
    public void runningInstalledModelHasBadgeAndKeepsActions() throws Exception {
        OllamaModelCard card = card(true);
        assertTrue(card.isRunningCard());
        assertEquals(1, count(card, "RUNNING"));
        assertEquals(1, count(card, "Delete"));
        assertEquals(1, count(card, "Use in chat"));
    }

    @Test
    public void idleInstalledModelHasNoBadge() throws Exception {
        OllamaModelCard card = card(false);
        assertFalse(card.isRunningCard());
        assertEquals(0, count(card, "RUNNING"));
        assertEquals(0, count(card, "INSTALLED"));
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
