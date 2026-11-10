package com.aresstack.askai.research.agent;

import com.aresstack.askai.research.sources.InMemoryResearchSourceRepository;
import com.aresstack.askai.research.sources.ResearchSourcesView;
import com.aresstack.comiccontrols.control.ComicLayoutAudit;
import com.aresstack.comiccontrols.control.ComicSearchBar;
import org.junit.Test;

import javax.swing.AbstractButton;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The sidebar tabs (Concept, Sources, Outline) keep the Chats tab's shape: nothing sits above the search
 * bar, the buttons live at the bottom as icon buttons with tooltips, and the tabs fit a narrow sidebar.
 */
public class SidebarTabChromeTest {

    /** The sidebar's minimum width (ChatWorkspacePanel) and the widest window the fit tests use. */
    private static final int[] WIDTHS = {280, 540, 1600};
    private static final int HEIGHT = 900;
    /** An icon button shows a glyph, not a word. */
    private static final int MAX_GLYPH_LENGTH = 3;

    @Test
    public void conceptStartsWithTheSearchRowAndTheMissionSitsBelow() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ConceptPaperView view = new ConceptPaperView();
            view.renderMission("Plugin isolation", "Frozen at revision 1. Reopen Concept to change it.");
            Component north = ((BorderLayout) view.getLayout()).getLayoutComponent(BorderLayout.NORTH);
            assertNotNull(north);
            assertNotNull("the search bar is in the topmost row", find((Container) north, ComicSearchBar.class));
            ComicLayoutAudit.overflows(view, WIDTHS[1], HEIGHT);
            assertEquals(0, yIn(view, north));
            assertTrue("the mission sits below the search row",
                    yIn(view, view.missionFieldForTest()) > yIn(view, find(view, ComicSearchBar.class)));
        });
    }

    @Test
    public void outlineHasNothingOnTopAndOnlyIconButtons() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            OutlineEditorView view = new OutlineEditorView();
            assertNull("nothing sits above the outline",
                    ((BorderLayout) view.getLayout()).getLayoutComponent(BorderLayout.NORTH));
            List<AbstractButton> buttons = new ArrayList<AbstractButton>();
            for (JButton button : view.allButtons()) {
                buttons.add(button);
            }
            assertIconButtons(buttons);
            assertFits(view);
        });
    }

    @Test
    public void theModeToggleStaysReachableInJsonMode() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            OutlineEditorView view = new OutlineEditorView();
            JButton toggle = view.button("Show the raw JSON");
            toggle.doClick();
            ComicLayoutAudit.overflows(view, WIDTHS[0], HEIGHT);
            assertTrue("the toggle is laid out outside the hidden tree card", toggle.getWidth() > 0);
            assertTrue(toggle.getParent().isVisible());
        });
    }

    @Test
    public void sourcesKeepTheSearchPlanBelowTheSearchBar() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ResearchSourcesView view = new ResearchSourcesView(new InMemoryResearchSourceRepository(),
                    ResearchSourcesView.demoKnownSections());
            JPanel checklist = new JPanel();
            view.setPlanChecklist(checklist);
            assertNull("nothing sits above the sources search bar",
                    ((BorderLayout) view.getLayout()).getLayoutComponent(BorderLayout.NORTH));
            assertTrue(SwingUtilities.isDescendingFrom(checklist, view));
            ComicLayoutAudit.overflows(view, WIDTHS[1], HEIGHT);
            ComicSearchBar search = find(view, ComicSearchBar.class);
            assertNotNull(search);
            assertTrue("the search plan is below the search bar", yIn(view, checklist) > yIn(view, search));
        });
    }

    private static void assertIconButtons(List<AbstractButton> buttons) {
        assertFalse(buttons.isEmpty());
        for (AbstractButton button : buttons) {
            String text = button.getText() == null ? "" : button.getText();
            assertTrue("\"" + text + "\" is a text button", text.length() <= MAX_GLYPH_LENGTH);
            assertTrue("\"" + text + "\" has no tooltip",
                    button.getToolTipText() != null && !button.getToolTipText().isEmpty());
        }
    }

    private static void assertFits(JComponent view) {
        for (int width : WIDTHS) {
            List<String> overflows = ComicLayoutAudit.overflows(view, width, HEIGHT);
            assertTrue("At " + width + " px the tab runs out of the sidebar:\n"
                    + String.join("\n", overflows), overflows.isEmpty());
        }
    }

    private static <T> T find(Container container, Class<T> type) {
        for (Component child : container.getComponents()) {
            if (type.isInstance(child)) {
                return type.cast(child);
            }
            if (child instanceof Container) {
                T nested = find((Container) child, type);
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }

    private static int yIn(Component root, Component component) {
        return SwingUtilities.convertPoint(component.getParent(), component.getLocation(), root).y;
    }
}
