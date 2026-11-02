package com.aresstack.askai.ui.chat;

import com.aresstack.comiccontrols.control.ComicSegmentedToggle;
import org.junit.Test;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** The drawer screens (Audio, Models) and the Text | Audio toggle of the title bar. */
public class DrawerScreenTest {

    @Test
    public void pagesKeepTheirOrderAndSelectingRunsTheirAction() throws Exception {
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                final int[] shown = {0};
                DrawerScreen screen = new DrawerScreen("MODELS");
                screen.addPage("Setup", new JPanel(), null); // placeholder fixes the order
                JLabel models = new JLabel("models");
                screen.addPage("Installed", models, () -> shown[0]++);
                screen.addPage("Running Models", models, () -> shown[0]++);
                screen.addPage("Setup", new JLabel("setup"), null); // the real page replaces the placeholder
                assertEquals(Arrays.asList("Setup", "Installed", "Running Models"), screen.pageNames());
                assertEquals("Setup", screen.selectedPage());
                screen.select("Running Models");
                assertEquals("Running Models", screen.selectedPage());
                assertEquals(1, shown[0]);
                assertTrue("the page content is in the main area",
                        SwingUtilities.isDescendingFrom(models, screen));
                screen.select("unknown");
                assertEquals("Running Models", screen.selectedPage());
            }
        });
    }

    @Test
    public void theModeToggleSwitchesBetweenTextAndAudio() {
        final int[] picked = {-1};
        ComicSegmentedToggle toggle = new ComicSegmentedToggle("Text", "Audio");
        toggle.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.PLAIN, 12));
        toggle.setSelectionListener(index -> picked[0] = index);
        toggle.setSize(toggle.getPreferredSize());
        assertEquals(0, toggle.getSelectedIndex());
        java.awt.event.MouseEvent press = new java.awt.event.MouseEvent(toggle,
                java.awt.event.MouseEvent.MOUSE_PRESSED, 0L, 0, toggle.getWidth() - 5, 10, 1, false);
        for (java.awt.event.MouseListener listener : toggle.getMouseListeners()) {
            listener.mousePressed(press);
        }
        assertEquals("the right segment is Audio", 1, toggle.getSelectedIndex());
        assertEquals(1, picked[0]);
        toggle.setSelectedIndex(0);
        assertEquals("programmatic selection does not notify", 1, picked[0]);
        int width = toggle.getPreferredSize().width;
        toggle.setToggleHeight(12);
        assertEquals("slim: only the height shrinks", new java.awt.Dimension(width, 12), toggle.getPreferredSize());
    }
}
