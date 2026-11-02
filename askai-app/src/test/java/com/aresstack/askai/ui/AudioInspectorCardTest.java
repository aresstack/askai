package com.aresstack.askai.ui;

import org.junit.Test;

import javax.swing.JLabel;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The block-width inspector card starts rolled up and reveals its content once a block is selected. */
public class AudioInspectorCardTest {

    @Test
    public void startsCollapsedWithNoHeightAndHiddenContent() {
        AudioInspectorCard card = new AudioInspectorCard(new JLabel("inspector"), 168);
        // Collapsed: it must not occupy any vertical space and its content must be hidden.
        assertEquals(0, card.getPreferredSize().height);
        assertFalse(card.isContentShown());
    }

    @Test
    public void keepsTheRequestedBlockWidth() {
        AudioInspectorCard card = new AudioInspectorCard(new JLabel("inspector"), 168);
        assertEquals(168, card.cardWidth());
        assertEquals(168, card.getPreferredSize().width);
    }

    @Test
    public void expandingRevealsTheContentImmediately() {
        AudioInspectorCard card = new AudioInspectorCard(new JLabel("inspector"), 168);
        card.setExpanded(true);
        // The roll-down animation drives the height over time, but the content is shown right away.
        assertTrue(card.isContentShown());
    }
}
