package com.aresstack.askai.ui;

import com.aresstack.askai.plugin.api.agent.AgentSession;
import com.aresstack.askai.plugin.api.agent.search.AgentSearchContribution;
import com.aresstack.comiccontrols.control.ComicSearchTag;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** The own title bar: title left, the active agent's search as a host-owned field, hidden without one. */
public class AskAiTitleBarTest {

    private static final Runnable NOTHING = new Runnable() {
        @Override
        public void run() {
        }
    };

    @Test
    public void showsTheWindowTitle() {
        AskAiTitleBar bar = new AskAiTitleBar("AskAI", NOTHING, NOTHING);
        assertEquals("AskAI", bar.getTitle());
        bar.setTitle("AskAI – Chat");
        assertEquals("AskAI – Chat", bar.getTitle());
    }

    @Test
    public void navigationAndTrailingControlsHaveTheirSlots() {
        AskAiTitleBar bar = new AskAiTitleBar("AskAI", NOTHING, NOTHING);
        javax.swing.JButton chat = new javax.swing.JButton("Chat");
        javax.swing.JButton help = new com.aresstack.comiccontrols.control.ComicHelpButton(24, "Help");
        bar.addNavigation(chat);
        bar.addTrailingControl(help);
        assertTrue("navigation sits in the bar", javax.swing.SwingUtilities.isDescendingFrom(chat, bar));
        assertTrue("trailing control sits in the bar", javax.swing.SwingUtilities.isDescendingFrom(help, bar));
        assertEquals(new java.awt.Dimension(24, 24), help.getPreferredSize());
    }

    @Test
    public void helpAndCloseCirclesShareSizeAndHeight() {
        AskAiTitleBar bar = new AskAiTitleBar("AskAI", NOTHING, NOTHING);
        javax.swing.JButton help = new com.aresstack.comiccontrols.control.ComicHelpButton(24, "Help");
        bar.addTrailingControl(help);
        bar.setSize(800, 40);
        layoutTree(bar);
        java.awt.Component close = find(bar, com.aresstack.comiccontrols.control.ComicWindowCloseButton.class);
        assertEquals(close.getSize(), help.getSize());
        assertEquals("? and X sit on exactly the same height",
                javax.swing.SwingUtilities.convertPoint(close.getParent(), close.getLocation(), bar).y,
                javax.swing.SwingUtilities.convertPoint(help.getParent(), help.getLocation(), bar).y);
    }

    private static void layoutTree(java.awt.Container container) {
        container.doLayout();
        for (java.awt.Component child : container.getComponents()) {
            if (child instanceof java.awt.Container) {
                layoutTree((java.awt.Container) child);
            }
        }
    }

    private static java.awt.Component find(java.awt.Container container, Class<?> type) {
        for (java.awt.Component child : container.getComponents()) {
            if (type.isInstance(child)) {
                return child;
            }
            if (child instanceof java.awt.Container) {
                java.awt.Component found = find((java.awt.Container) child, type);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    @Test
    public void theSearchIsHiddenWithoutAnAgentContribution() {
        AskAiTitleBar bar = new AskAiTitleBar("AskAI", NOTHING, NOTHING);
        assertNull(bar.searchField());
        bar.bindSearch(null, null);
        assertNull(bar.searchField());
    }

    @Test
    public void theAgentDefinesWhatTheSearchDoes() {
        final List<String> searched = new ArrayList<String>();
        final AgentSession session = (AgentSession) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{AgentSession.class}, (proxy, method, args) -> null);
        AgentSearchContribution search = new AgentSearchContribution() {
            public boolean supports(AgentSession s) {
                return true;
            }

            public String getPlaceholder() {
                return "Find documents…";
            }

            public String getTooltip() {
                return "Search my documents";
            }

            public void search(AgentSession s, String query) {
                assertTrue(s == session);
                searched.add(query);
            }
        };
        AskAiTitleBar bar = new AskAiTitleBar("AskAI", NOTHING, NOTHING);
        Supplier<AgentSession> sessions = () -> session;
        bar.bindSearch(search, sessions);
        ComicSearchTag field = (ComicSearchTag) bar.searchField();
        assertNotNull("the agent's search is shown", field);

        field.getTextField().setText("  wearables  ");
        field.getTextField().postActionEvent(); // Enter
        assertEquals("[wearables]", searched.toString());
        assertEquals("the host clears the field after a search", "", field.getText());

        bar.bindSearch(search, sessions);
        assertTrue("rebinding the same contribution keeps the field", field == bar.searchField());
        bar.bindSearch(null, sessions);
        assertNull("no active agent search: hidden", bar.searchField());
    }

    @Test
    public void theSearchIsCenteredOnTheWholeBarNotOnTheFreeSpace() {
        final AgentSession session = (AgentSession) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{AgentSession.class}, (proxy, method, args) -> null);
        AgentSearchContribution search = new AgentSearchContribution() {
            public boolean supports(AgentSession s) {
                return true;
            }

            public String getPlaceholder() {
                return "Search";
            }

            public String getTooltip() {
                return "Search";
            }

            public void search(AgentSession s, String query) {
            }
        };
        AskAiTitleBar bar = new AskAiTitleBar("AskAI", NOTHING, NOTHING);
        // many controls on the right: the free space is far left of the bar's center
        for (int i = 0; i < 6; i++) {
            javax.swing.JButton control = new javax.swing.JButton("control " + i);
            // font-independent width: with the platform font the six buttons got so wide on Linux CI
            // that the (intended) no-overlap clamp moved the search — the test must pin centering only
            control.setPreferredSize(new java.awt.Dimension(70, 24));
            bar.addTrailingControl(control);
        }
        bar.bindSearch(search, () -> session);
        bar.setSize(1400, AskAiTitleBar.HEIGHT);
        layoutAll(bar);
        java.awt.Component field = bar.searchField();
        java.awt.Rectangle r = javax.swing.SwingUtilities.convertRectangle(field.getParent(), field.getBounds(), bar);
        assertEquals("centered on the bar", 700, r.x + r.width / 2, 2);
    }

    private static void layoutAll(java.awt.Container c) {
        c.doLayout();
        for (java.awt.Component child : c.getComponents()) {
            if (child instanceof java.awt.Container) {
                layoutAll((java.awt.Container) child);
            }
        }
    }
}
