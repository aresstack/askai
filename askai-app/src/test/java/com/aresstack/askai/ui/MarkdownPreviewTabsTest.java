package com.aresstack.askai.ui;

import org.junit.Test;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JComponent;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.lang.reflect.InvocationTargetException;
import java.util.function.Function;

import static org.junit.Assert.assertEquals;

/**
 * The bottom tabbed area keeps one fixed tab, reuses a single preview tab across selections, and opens a
 * distinct closable tab per pinned key (focusing an existing one instead of duplicating).
 */
public class MarkdownPreviewTabsTest {

    private static final Function<String, JComponent> FAKE = new Function<String, JComponent>() {
        public JComponent apply(String markdown) {
            return new JTextArea(markdown);
        }
    };

    @Test
    public void startsWithOnlyTheFixedTab() throws Exception {
        onEdt(new Runnable() {
            public void run() {
                MarkdownPreviewTabs tabs = new MarkdownPreviewTabs("Log", new JLabel(), FAKE);
                assertEquals(1, tabs.tabCount());
            }
        });
    }

    @Test
    public void previewReusesASingleTab() throws Exception {
        onEdt(new Runnable() {
            public void run() {
                MarkdownPreviewTabs tabs = new MarkdownPreviewTabs("Log", new JLabel(), FAKE);
                tabs.preview("a.md", "# A");
                assertEquals(2, tabs.tabCount());
                assertEquals("a.md — preview", tabs.selectedTitle());
                tabs.preview("b.md", "# B");
                assertEquals("preview tab is reused, not added", 2, tabs.tabCount());
                assertEquals("b.md — preview", tabs.selectedTitle());
            }
        });
    }

    @Test
    public void pinAddsAClosableTabAndFocusesAnExistingOne() throws Exception {
        onEdt(new Runnable() {
            public void run() {
                MarkdownPreviewTabs tabs = new MarkdownPreviewTabs("Log", new JLabel(), FAKE);
                tabs.pin("k1", "a.md", "# A");
                assertEquals(2, tabs.tabCount());
                tabs.pin("k1", "a.md", "# A again");
                assertEquals("same key focuses the existing pinned tab", 2, tabs.tabCount());
                assertEquals("a.md", tabs.selectedTitle());
                tabs.pin("k2", "b.md", "# B");
                assertEquals(3, tabs.tabCount());
            }
        });
    }

    @Test
    public void closingAPinnedTabRemovesItButNotTheFixedTab() throws Exception {
        onEdt(new Runnable() {
            public void run() {
                MarkdownPreviewTabs tabs = new MarkdownPreviewTabs("Log", new JLabel(), FAKE);
                tabs.pin("k1", "a.md", "# A");
                tabs.preview("c.md", "# C");
                assertEquals(3, tabs.tabCount());
                // Close the pinned tab via its real close button.
                int pinnedIndex = tabs.tabsForTest().indexOfTab("a.md");
                clickCloseButton(tabs, pinnedIndex);
                assertEquals(2, tabs.tabCount());
                // Re-pinning the same key opens a fresh tab (it was really removed).
                tabs.pin("k1", "a.md", "# A");
                assertEquals(3, tabs.tabCount());
            }
        });
    }

    private static void clickCloseButton(MarkdownPreviewTabs tabs, int index) {
        Component tabComponent = tabs.tabsForTest().getTabComponentAt(index);
        JButton close = findButton((Container) tabComponent);
        if (close == null) {
            throw new AssertionError("no close button on tab " + index);
        }
        close.doClick();
    }

    private static JButton findButton(Container container) {
        for (Component child : container.getComponents()) {
            if (child instanceof JButton) {
                return (JButton) child;
            }
            if (child instanceof Container) {
                JButton nested = findButton((Container) child);
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
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
