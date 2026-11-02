package com.aresstack.askai.ui.sidebar;

import org.junit.Test;

import javax.swing.AbstractButton;
import java.awt.Component;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The unfolding ribbon: entries per tab, reveal progress, and overflow arrows per side. */
public class SidebarTabRibbonTest {

    private static List<AbstractButton> entriesOf(SidebarTabRibbon ribbon) {
        List<AbstractButton> entries = new ArrayList<AbstractButton>();
        for (Component child : ribbon.contentForTest().getComponents()) {
            if (child instanceof AbstractButton) {
                entries.add((AbstractButton) child);
            }
        }
        return entries;
    }

    @Test
    public void oneEntryPerTabAndClicksReportTheTitle() {
        SidebarTabRibbon ribbon = new SidebarTabRibbon();
        final List<String> selected = new ArrayList<String>();
        ribbon.setListener(selected::add);
        ribbon.setTabs(Arrays.asList("Chats", "Research"), "Chats");

        List<AbstractButton> entries = entriesOf(ribbon);
        assertEquals(2, entries.size());
        assertEquals("Chats", entries.get(0).getText());
        assertEquals("Research", entries.get(1).getText());

        entries.get(1).doClick();
        assertEquals(Arrays.asList("Research"), selected);
    }

    @Test
    public void theRibbonStartsFoldedAndUnfoldsOnOpen() {
        SidebarTabRibbon ribbon = new SidebarTabRibbon();
        ribbon.setTabs(Arrays.asList("Chats"), "Chats");
        assertFalse("starts folded away", ribbon.isOpen());

        ribbon.open();
        ribbon.finishAnimationForTest();
        assertTrue(ribbon.isOpen());

        ribbon.close();
        ribbon.finishAnimationForTest();
        assertFalse(ribbon.isOpen());
    }

    @Test
    public void overflowShowsTheArrowExactlyOnTheSideWithMoreEntries() {
        SidebarTabRibbon ribbon = new SidebarTabRibbon();
        ribbon.setTabs(Arrays.asList("Alpha", "Beta", "Gamma", "Delta", "Epsilon", "Zeta"), "Alpha");
        ribbon.open();
        ribbon.finishAnimationForTest();
        ribbon.setSize(120, 28); // far narrower than the entries
        ribbon.doLayout();

        assertFalse("nothing hidden on the left yet", ribbon.scrollLeftForTest().isVisible());
        assertTrue("more entries follow on the right", ribbon.scrollRightForTest().isVisible());

        // Scroll to the far end: now the left arrow appears and the right one disappears.
        for (int i = 0; i < 200; i++) {
            ribbon.scrollRightForTest().doClick();
            ribbon.doLayout();
        }
        assertTrue(ribbon.scrollLeftForTest().isVisible());
        assertFalse(ribbon.scrollRightForTest().isVisible());
        assertTrue("offset clamped to the content", ribbon.scrollOffsetForTest() > 0);
    }

    @Test
    public void aWideEnoughRibbonNeedsNoArrows() {
        SidebarTabRibbon ribbon = new SidebarTabRibbon();
        ribbon.setTabs(Arrays.asList("Chats"), "Chats");
        ribbon.open();
        ribbon.finishAnimationForTest();
        ribbon.setSize(600, 28);
        ribbon.doLayout();
        assertFalse(ribbon.scrollLeftForTest().isVisible());
        assertFalse(ribbon.scrollRightForTest().isVisible());
        assertEquals(0, ribbon.scrollOffsetForTest());
    }
}
