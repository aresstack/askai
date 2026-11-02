package com.aresstack.askai.ui;

import org.junit.Test;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Rectangle;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** The uniform screen footer: Back left, the center component exactly centered, the trailing one right. */
public class FooterScreenTest {

    @Test
    public void backLeftCenterCenteredTrailingRight() {
        final int[] back = {0};
        JButton copy = new com.aresstack.comiccontrols.control.ComicButton("Copy diagnostics");
        JLabel copyright = new JLabel("(c) 2026 AresStack");
        FooterScreen screen = new FooterScreen(new JLabel("content"), () -> back[0]++)
                .withCenter(copy).withTrailing(copyright);
        screen.setSize(1200, 800);
        layoutAll(screen);

        Rectangle backBounds = in(screen, screen.backButton());
        Rectangle copyBounds = in(screen, copy);
        Rectangle rightBounds = in(screen, copyright);
        assertEquals("Back on the left padding", FooterScreen.FOOTER_PADDING, backBounds.x);
        assertEquals("centered", 600, copyBounds.x + copyBounds.width / 2, 1);
        assertEquals("right-aligned (GridLayout rounds the thirds)", 1200 - FooterScreen.FOOTER_PADDING,
                rightBounds.x + rightBounds.width, 2);
        assertTrue("the footer sits below the content", backBounds.y > 700);

        screen.backButton().doClick();
        assertEquals(1, back[0]);
    }

    private static Rectangle in(Container root, Component c) {
        return SwingUtilities.convertRectangle(c.getParent(), c.getBounds(), root);
    }

    private static void layoutAll(Container c) {
        c.doLayout();
        for (Component child : c.getComponents()) {
            if (child instanceof Container) {
                layoutAll((Container) child);
            }
        }
    }
}
