package com.aresstack.askai.ui.sidebar;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The chat sidebar (drawer). It hosts one PANE per tab — plain CardLayout panes, deliberately no
 * Swing {@code JTabbedPane}: switching happens through the Java2D {@link SidebarTabRibbon} that
 * unfolds next to the hamburger. The fixed default pane (the app's "Chats" list) comes first; any
 * {@link ChatSidebarTab} contributions supplied at open time follow — the seam through which plugins
 * (e.g. Research) add their own panes later.
 *
 * <p>The drawer has NO pin or close controls of its own: ribbon and drawer are latched open together
 * by clicking the hamburger (pressed-in state) and fold away together on hover-out otherwise — the
 * workspace owns that policy entirely. The header only carries the workspace's component (the
 * New-chat button).</p>
 */
public final class ChatSidebarPanel extends JPanel {

    private final CardLayout paneLayout = new CardLayout();
    private final JPanel panes = new JPanel(paneLayout);
    private final JPanel headerLeft = new JPanel(new BorderLayout());
    /** The whole header strip — visible ONLY on the default pane (its content filters the chat list). */
    private JComponent header;
    private final String defaultTabTitle;
    private final JComponent defaultTabComponent;
    private final List<String> titles = new ArrayList<String>();

    private String activeTitle;
    private Supplier<List<ChatSidebarTab>> extraTabsSupplier;
    /** Told the active pane's title whenever it changes (the workspace swaps its main area). */
    private java.util.function.Consumer<String> tabListener;

    public ChatSidebarPanel(String defaultTabTitle, JComponent defaultTabComponent) {
        super(new BorderLayout());
        this.defaultTabTitle = defaultTabTitle;
        this.defaultTabComponent = defaultTabComponent;
        this.activeTitle = defaultTabTitle;
        setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, getBackground().darker()));
        panes.setOpaque(false);
        add(buildHeader(), BorderLayout.NORTH);
        add(panes, BorderLayout.CENTER);
        rebuildTabs();
        // Wide enough for the agent artifact panes (brief/sources), which used to live in a ~380px
        // right-hand area before they moved into this drawer.
        setPreferredSize(new Dimension(360, 10));
    }

    /** Plugins' panes arrive lazily through this supplier; re-read every time the drawer opens. */
    public void setExtraTabsSupplier(Supplier<List<ChatSidebarTab>> supplier) {
        this.extraTabsSupplier = supplier;
    }

    /** The pane titles in order — the default pane first, then the current contributions. */
    public List<String> tabTitles() {
        return Collections.unmodifiableList(new ArrayList<String>(titles));
    }

    /** The title of the pane currently shown. */
    public String activeTab() {
        return activeTitle;
    }

    public void setTabListener(java.util.function.Consumer<String> listener) {
        this.tabListener = listener;
    }

    /** Show the pane with this title (no-op for unknown titles). */
    public void showTab(String title) {
        if (title == null || !titles.contains(title)) {
            return;
        }
        activeTitle = title;
        paneLayout.show(panes, title);
        // The header carries the CHAT list's controls (the "Chats durchsuchen" filter): on every
        // other pane (Fragestellung, Visualisierung, …) it is foreign chrome and disappears.
        if (header != null) {
            header.setVisible(defaultTabTitle.equals(title));
        }
        if (tabListener != null) {
            tabListener.accept(title);
        }
    }

    /** Whether the header strip (chat filter) is currently shown — pinned by the tab test. */
    boolean isHeaderVisible() {
        return header != null && header.isVisible();
    }

    /**
     * The header's slot — no pane title (the ribbon's colored entry already says which pane is
     * active); the workspace puts its full-width chat search bar here.
     */
    public void setHeaderComponent(JComponent component) {
        headerLeft.removeAll();
        if (component != null) {
            headerLeft.add(component, BorderLayout.CENTER); // full width, e.g. for a search bar
        }
        headerLeft.revalidate();
        headerLeft.repaint();
    }

    /** Rebuild the pane set: the default pane first, then the current contributions. */
    public void rebuildTabs() {
        panes.removeAll();
        titles.clear();
        Map<String, JComponent> byTitle = new LinkedHashMap<String, JComponent>();
        byTitle.put(defaultTabTitle, defaultTabComponent);
        List<ChatSidebarTab> extras = extraTabsSupplier == null
                ? Collections.<ChatSidebarTab>emptyList() : extraTabsSupplier.get();
        if (extras != null) {
            for (ChatSidebarTab tab : extras) {
                if (tab != null && tab.getTitle() != null && !byTitle.containsKey(tab.getTitle())) {
                    byTitle.put(tab.getTitle(), tab.getComponent());
                }
            }
        }
        for (Map.Entry<String, JComponent> entry : byTitle.entrySet()) {
            titles.add(entry.getKey());
            panes.add(entry.getValue(), entry.getKey());
        }
        if (!titles.contains(activeTitle)) {
            activeTitle = defaultTabTitle; // a vanished contribution falls back to the default pane
        }
        showTab(activeTitle);
        panes.revalidate();
        panes.repaint();
    }

    private JComponent buildHeader() {
        JPanel headerPanel = new JPanel(new BorderLayout());
        // The top air is the SHARED slim-bar metric — the sky status bar aligns to the same edge.
        headerPanel.setBorder(BorderFactory.createEmptyBorder(
                com.aresstack.comiccontrols.theme.ResearchUiMetrics.SLIM_BAR_TOP_GAP, 8, 4, 4));
        headerLeft.setOpaque(false);
        headerPanel.add(headerLeft, BorderLayout.CENTER); // the slot spans the drawer width
        this.header = headerPanel;
        return headerPanel;
    }
}
