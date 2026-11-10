package com.aresstack.askai.research.agent;

import com.aresstack.askai.research.sources.InMemoryResearchSourceRepository;
import com.aresstack.askai.research.sources.ResearchSourcesView;
import com.aresstack.comiccontrols.control.ComicLayoutAudit;
import com.aresstack.comiccontrols.control.ComicSearchBar;
import org.junit.Test;

import javax.swing.AbstractButton;
import javax.swing.JButton;
import javax.swing.JComponent;
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
 * The sidebar tabs (Concept, Sources, Outline, Document) keep the Chats tab's shape: nothing sits above the search
 * bar, the buttons live at the bottom as icon buttons with tooltips, and the tabs fit a narrow sidebar.
 */
public class SidebarTabChromeTest {

    /** The sidebar's minimum width (ChatWorkspacePanel) and the widest window the fit tests use. */
    private static final int[] WIDTHS = {280, 540, 1600};
    private static final int HEIGHT = 900;

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
    public void outlineStartsWithTheSearchBarAndHasOnlyIconButtons() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            OutlineEditorView view = new OutlineEditorView();
            assertNull("nothing sits above the outline's search bar",
                    ((BorderLayout) view.getLayout()).getLayoutComponent(BorderLayout.NORTH));
            ComicLayoutAudit.overflows(view, WIDTHS[1], HEIGHT);
            ComicSearchBar search = find(view, ComicSearchBar.class);
            assertNotNull(search);
            javax.swing.JTable table = find(view, javax.swing.JTable.class);
            assertTrue("the list is below the search bar", yIn(view, table) > yIn(view, search));
            List<AbstractButton> buttons = new ArrayList<AbstractButton>();
            collectButtons(view, buttons);
            assertIconButtons(buttons);
            for (JButton button : view.allButtons()) {
                assertTrue(button.getAccessibleContext().getAccessibleName() + " is laid out in the tab",
                        buttons.contains(button));
            }
            assertFits(view);
        });
    }

    @Test
    public void theOutlineSwitchStaysReachableOnEveryCard() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            OutlineEditorView view = new OutlineEditorView();
            com.aresstack.comiccontrols.control.ComicSegmentedToggle toggle =
                    find(view, com.aresstack.comiccontrols.control.ComicSegmentedToggle.class);
            assertNotNull(toggle);
            for (int card : new int[] {OutlineEditorView.CARD_TEXT, OutlineEditorView.CARD_JSON,
                    OutlineEditorView.CARD_OUTLINE}) {
                view.selectCard(card);
                assertTrue(ComicLayoutAudit.overflows(view, WIDTHS[0], HEIGHT).isEmpty());
                assertEquals(card, view.shownCard());
                assertTrue("the switch is laid out on every card", toggle.getWidth() > 0);
                assertTrue("the switch sits below the search bar",
                        yIn(view, toggle) > yIn(view, find(view, ComicSearchBar.class)));
            }
        });
    }

    @Test
    public void sourcesStartWithTheSearchBarAndFitTheSidebar() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ResearchSourcesView view = new ResearchSourcesView(new InMemoryResearchSourceRepository(),
                    ResearchSourcesView.demoKnownSections());
            assertNull("nothing sits above the sources search bar",
                    ((BorderLayout) view.getLayout()).getLayoutComponent(BorderLayout.NORTH));
            ComicLayoutAudit.overflows(view, WIDTHS[1], HEIGHT);
            ComicSearchBar search = find(view, ComicSearchBar.class);
            assertNotNull(search);
            javax.swing.JTable table = find(view, javax.swing.JTable.class);
            assertTrue("the list is below the search bar", yIn(view, table) > yIn(view, search));
            List<AbstractButton> buttons = new ArrayList<AbstractButton>();
            collectButtons(view, buttons);
            assertIconButtons(buttons);
            assertFits(view);
        });
    }

    @Test
    public void documentStartsWithTheSearchBarAndFitsTheSidebar() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            com.aresstack.askai.research.document.DocumentSectionsView view =
                    new com.aresstack.askai.research.document.DocumentSectionsView();
            assertNull("nothing sits above the document's search bar",
                    ((BorderLayout) view.getLayout()).getLayoutComponent(BorderLayout.NORTH));
            ComicLayoutAudit.overflows(view, WIDTHS[1], HEIGHT);
            ComicSearchBar search = find(view, ComicSearchBar.class);
            assertNotNull(search);
            javax.swing.JTable table = find(view, javax.swing.JTable.class);
            assertTrue("the list is below the search bar", yIn(view, table) > yIn(view, search));
            List<AbstractButton> buttons = new ArrayList<AbstractButton>();
            collectButtons(view, buttons);
            assertIconButtons(buttons);
            assertFits(view);
        });
    }

    private static void collectButtons(Container container, List<AbstractButton> found) {
        for (Component child : container.getComponents()) {
            if (child instanceof ComicSearchBar || child instanceof javax.swing.JScrollBar) {
                continue; // their own inner buttons (search arrow, scroll arrows) are not actions of the tab
            }
            if (child instanceof JButton) {
                found.add((AbstractButton) child);
            } else if (child instanceof Container) {
                collectButtons((Container) child, found);
            }
        }
    }

    private static void assertIconButtons(List<AbstractButton> buttons) {
        assertFalse(buttons.isEmpty());
        for (AbstractButton button : buttons) {
            String name = button.getAccessibleContext().getAccessibleName();
            assertNotNull(name + " shows an icon", button.getIcon());
            assertTrue(name + " shows no text", button.getText() == null || button.getText().isEmpty());
            assertTrue(name + " has a tooltip",
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
