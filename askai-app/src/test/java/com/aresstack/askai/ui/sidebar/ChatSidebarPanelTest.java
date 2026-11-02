package com.aresstack.askai.ui.sidebar;

import org.junit.Test;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

/**
 * The drawer's pane composition (plain CardLayout panes, no JTabbedPane): default pane first,
 * contributions appended on rebuild, switching by title.
 */
public class ChatSidebarPanelTest {

    private static ChatSidebarTab tab(final String title) {
        return new ChatSidebarTab() {
            public String getTitle() {
                return title;
            }

            public JComponent getComponent() {
                return new JLabel(title);
            }
        };
    }

    @Test
    public void theDefaultPaneIsAlwaysFirstAndActive() {
        ChatSidebarPanel panel = new ChatSidebarPanel("Chats", new JPanel());
        assertEquals(Arrays.asList("Chats"), panel.tabTitles());
        assertEquals("Chats", panel.activeTab());
    }

    @Test
    public void contributedPanesAppearAfterTheDefaultOnRebuild() {
        ChatSidebarPanel panel = new ChatSidebarPanel("Chats", new JPanel());
        final List<ChatSidebarTab> extras = Arrays.asList(tab("Research"), tab("Notes"));
        panel.setExtraTabsSupplier(() -> extras);
        panel.rebuildTabs();
        assertEquals(Arrays.asList("Chats", "Research", "Notes"), panel.tabTitles());

        // A later rebuild with fewer contributions does not accumulate stale panes.
        panel.setExtraTabsSupplier(() -> Arrays.asList(tab("Research")));
        panel.rebuildTabs();
        assertEquals(Arrays.asList("Chats", "Research"), panel.tabTitles());
    }

    @Test
    public void showTabSwitchesTheActivePaneAndIgnoresUnknownTitles() {
        ChatSidebarPanel panel = new ChatSidebarPanel("Chats", new JPanel());
        panel.setExtraTabsSupplier(() -> Arrays.asList(tab("Research")));
        panel.rebuildTabs();

        panel.showTab("Research");
        assertEquals("Research", panel.activeTab());

        panel.showTab("Nope");
        assertEquals("still on the last valid pane", "Research", panel.activeTab());
    }

    @Test
    public void aVanishedContributionFallsBackToTheDefaultPane() {
        ChatSidebarPanel panel = new ChatSidebarPanel("Chats", new JPanel());
        panel.setExtraTabsSupplier(() -> Arrays.asList(tab("Research")));
        panel.rebuildTabs();
        panel.showTab("Research");

        panel.setExtraTabsSupplier(null);
        panel.rebuildTabs();
        assertEquals(Arrays.asList("Chats"), panel.tabTitles());
        assertEquals("Chats", panel.activeTab());
    }

    @Test
    public void nullEntriesAreTolerated() {
        ChatSidebarPanel panel = new ChatSidebarPanel("Chats", new JPanel());
        panel.setExtraTabsSupplier(() -> Arrays.asList(tab("One"), null));
        panel.rebuildTabs();
        assertEquals(Arrays.asList("Chats", "One"), panel.tabTitles());
    }
}
