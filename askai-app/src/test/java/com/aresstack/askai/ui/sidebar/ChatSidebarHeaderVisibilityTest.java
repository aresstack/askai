package com.aresstack.askai.ui.sidebar;

import org.junit.Test;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The "Chats durchsuchen" bar lives in the drawer HEADER, which sits above the card layout of ALL
 * panes — it therefore showed on Fragestellung, Visualisierung and every other tab, where it filters
 * nothing. The header belongs to the default Chats pane alone.
 */
public class ChatSidebarHeaderVisibilityTest {

    private static ChatSidebarTab tab(final String title) {
        return new ChatSidebarTab() {
            public String getTitle() {
                return title;
            }

            public javax.swing.JComponent getComponent() {
                return new JPanel();
            }
        };
    }

    @Test
    public void theChatFilterHeaderShowsOnlyOnTheChatsPane() throws Exception {
        if (java.awt.GraphicsEnvironment.isHeadless()) {
            return;
        }
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                ChatSidebarPanel sidebar = new ChatSidebarPanel("Chats", new JPanel());
                sidebar.setHeaderComponent(new JLabel("Chats durchsuchen…"));
                sidebar.setExtraTabsSupplier(new Supplier<List<ChatSidebarTab>>() {
                    public List<ChatSidebarTab> get() {
                        return Arrays.asList(tab("Fragestellung"), tab("Sources"));
                    }
                });
                sidebar.rebuildTabs();

                assertTrue("on the Chats pane the filter is at home", sidebar.isHeaderVisible());

                sidebar.showTab("Fragestellung");
                assertFalse("foreign chrome on every other pane — gone", sidebar.isHeaderVisible());

                sidebar.showTab("Chats");
                assertTrue("and back with the chat list", sidebar.isHeaderVisible());
            }
        });
    }
}
