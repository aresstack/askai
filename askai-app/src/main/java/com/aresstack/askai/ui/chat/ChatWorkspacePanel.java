package com.aresstack.askai.ui.chat;

import com.aresstack.comiccontrols.control.ComicControls;
import com.aresstack.askai.history.ChatHistoryStore;
import com.aresstack.askai.history.ChatRecord;
import com.aresstack.askai.ui.ChatComposerPanel;
import com.aresstack.askai.ui.OllamaChatPanel;
import com.aresstack.askai.ui.sidebar.ChatSidebarPanel;
import com.aresstack.askai.ui.sidebar.ChatSidebarTab;
import com.aresstack.comiccontrols.control.ComicScrollPane;
import com.aresstack.comiccontrols.control.ComicSplitPane;
import com.aresstack.comiccontrols.control.ResearchIconButton;
import com.aresstack.comiccontrols.control.ResearchPillButton;
import com.aresstack.comiccontrols.theme.ResearchUiMetrics;
import com.aresstack.comiccontrols.theme.ResearchUiPalette;
import com.aresstack.comiccontrols.theme.ResearchUiTypography;
import com.aresstack.comiccontrols.theme.ThemeColor;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingConstants;
import java.awt.AWTEvent;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.event.AWTEventListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Hosts independent chat sessions as full-area cards — there is NO tab strip anymore. Switching,
 * opening and closing chats happens through the sidebar (drawer): its "Chats" tab merges the OPEN
 * sessions with the saved history, so one list is both the tab bar replacement and the history. The
 * drawer spans the workspace's full height on the left, opens on hamburger hover/click, closes when
 * the mouse leaves it (unless pinned), and the top bar carries the hamburger (top-left) plus the
 * agent toolbar slots; New chat lives INSIDE the drawer's Chats pane (icon button in its footer); the settings
 * gear sits in the application menu bar (see {@link #openActiveChatSettings()}). Each chat keeps a stable {@link ChatSessionId}; sessions are addressed
 * by id, never by index. At least one chat stays open.
 */
public final class ChatWorkspacePanel extends JPanel {

    /** Builds the per-tab chat session for a freshly created id (kept out of the host for testability). */
    public interface ChatSessionFactory {
        ChatSessionComponent create(ChatSessionId id);
    }

    /** Notified when the active chat changes (switch, open or close), with the newly active id. */
    public interface ActiveSessionListener {
        void activeSessionChanged(ChatSessionId id);
    }

    /**
     * Notified whenever the SET of open chats changes (open or close), with the current open ids in
     * order. The host persists this immediately so a crash never resurrects a closed chat.
     */
    public interface TabSetListener {
        void tabSetChanged(List<ChatSessionId> openIds);
    }

    private static final int SIDEBAR_CLOSE_DELAY_MS = 300;
    private static final int HOVER_MARGIN_PX = 12;

    private final CardLayout cardLayout = new CardLayout();
    private final JPanel cards = new JPanel(cardLayout);
    private final ChatSessionFactory factory;
    private final ChatHistoryStore historyStore; // may be null (tests) → only open sessions are listed
    private final Map<ChatSessionId, ChatSessionComponent> sessionsById =
            new LinkedHashMap<ChatSessionId, ChatSessionComponent>();
    private ChatSessionId activeId;
    private ActiveSessionListener activeSessionListener;
    private TabSetListener tabSetListener;
    private ChatSessionId lastNotifiedSessionId;

    private final ChatSidebarPanel sidebar;
    private final JButton burger;
    /** Slot for the ACTIVE agent's TRAILING toolbar contributions, far right (may stay empty). */
    private final JPanel agentToolbarSlot = new JPanel(new BorderLayout());
    /** Slot for the ACTIVE agent's Chats-footer control (e.g. the session language pill). */
    private final JPanel agentFooterToolbarSlot = new JPanel(new BorderLayout());
    /** Slot for the ACTIVE agent's LEADING control (right after the burger, before the ribbon). */
    private final JPanel agentLeadingSlot = new JPanel(new BorderLayout());
    /**
     * The TOP-BAR mode pill (Yapping / agent / Partying), right beside the hamburger — the mode
     * IS navigation (and explains the plugin-branded burger icon); it anchors the active tab's
     * mode popup. Sized to the burger's 28px row, not the footer's 34px pills.
     */
    private final ResearchPillButton modePill = new ResearchPillButton("Yapping",
            28, ResearchUiMetrics.RADIUS_CONTROL, 12);
    private com.aresstack.askai.plugin.host.WorkspaceModeController modeController;
    /**
     * The CENTERED top-bar slot (e.g. the research web-search tag): GridBagLayout centers its one
     * child; the unfolding ribbon pushes it right and squeezes it when the width runs out.
     */
    private final JPanel agentCenterSlot = new JPanel(new java.awt.GridBagLayout());
    /** The drawer's chat search bar — filters the chat list live by title/project. */
    private final com.aresstack.comiccontrols.control.ComicSearchBar chatFilter =
            new com.aresstack.comiccontrols.control.ComicSearchBar(
                    "Chats durchsuchen…", "Filtert die Chatliste nach Titel und Projekt");
    private final com.aresstack.askai.ui.sidebar.SidebarTabRibbon ribbon =
            new com.aresstack.askai.ui.sidebar.SidebarTabRibbon();
    private JPanel chatListPanel;
    private java.util.function.Supplier<List<ChatSidebarTab>> sidebarTabsSource;
    private final javax.swing.Timer sidebarCloseTimer;
    private AWTEventListener sidebarMouseWatcher;
    private boolean menuLocked; // clicking the burger latches the menu until the next click
    private com.aresstack.askai.state.ApplicationStateService applicationState;
    private static final String STATE_BURGER_PINNED = "chat.burgerPinned";
    private static final String STATE_SIDEBAR_WIDTH = "chat.sidebar.width";
    private static final int SIDEBAR_MIN_WIDTH = 280;
    private static final int SIDEBAR_MAX_WIDTH = 700;
    private static final int SIDEBAR_DEFAULT_WIDTH = 360;
    private static final int SIDEBAR_WIDTH_SAVE_DELAY_MS = 300;

    /** Sidebar and cards share this pane; the divider only exists while the drawer is open. */
    private final ComicSplitPane sidebarSplit;
    /** Debounces the per-drag divider events into ONE state write after the drag settles. */
    private final javax.swing.Timer sidebarWidthSaveTimer;
    private int pendingSidebarWidth = -1;

    public ChatWorkspacePanel(ChatSessionFactory factory) {
        this(factory, null, null);
    }

    public ChatWorkspacePanel(ChatSessionFactory factory, List<ChatSessionId> restoreIds) {
        this(factory, restoreIds, null);
    }

    /**
     * @param restoreIds   session ids of persisted chats to reopen on startup (most recent first);
     *                     when null/empty, a single fresh chat is opened
     * @param historyStore the saved-chats store backing the sidebar list (null in tests)
     */
    public ChatWorkspacePanel(ChatSessionFactory factory, List<ChatSessionId> restoreIds,
                              ChatHistoryStore historyStore) {
        super(new BorderLayout());
        if (factory == null) {
            throw new IllegalArgumentException("factory must not be null");
        }
        this.factory = factory;
        this.historyStore = historyStore;

        this.burger = ChatComposerPanel.createSidebarToggleButton();
        this.sidebar = new ChatSidebarPanel("Chats", buildChatsSidebarTab());
        // The MAIN area is a deck (pattern of the enterprise client): the chat cards, or the content the
        // ACTIVE drawer pane put there (settings page, source full text, ...).
        mainDeck.add(cards, MAIN_CHAT);
        mainDeck.add(mainContentSlot, MAIN_CONTENT);
        mainContentSlot.setOpaque(false);
        sidebar.setTabListener(title -> {
            if (SETTINGS_TAB.equals(title) && settingsPane.getComponentCount() == 0) {
                fillSettingsFromActiveChat(); // the Settings pane picked directly in the ribbon
            }
            updateMainArea();
        });
        this.sidebarSplit = new ComicSplitPane(sidebar, mainDeck, SIDEBAR_MIN_WIDTH, SIDEBAR_MAX_WIDTH);
        sidebarSplit.setPreferredLeftWidth(SIDEBAR_DEFAULT_WIDTH);
        sidebarSplit.collapseLeft(); // the drawer starts closed, exactly like before the split pane
        this.sidebarWidthSaveTimer = new javax.swing.Timer(SIDEBAR_WIDTH_SAVE_DELAY_MS,
                event -> saveSidebarWidthNow());
        sidebarWidthSaveTimer.setRepeats(false);
        sidebarSplit.setLeftWidthListener(width -> {
            pendingSidebarWidth = width;
            sidebarWidthSaveTimer.restart();
        });
        this.sidebarCloseTimer = new javax.swing.Timer(SIDEBAR_CLOSE_DELAY_MS,
                event -> onPointerLeftSidebarArea());
        sidebarCloseTimer.setRepeats(false);
        buildTopLevelLayout();

        if (restoreIds != null && !restoreIds.isEmpty()) {
            // Reopen persisted chats in oldest-first order (the list is newest-first).
            for (int i = restoreIds.size() - 1; i >= 0; i--) {
                openExistingChat(restoreIds.get(i));
            }
        } else {
            openNewChat(); // never start empty
        }
    }

    // ------------------------------------------------------------------ public session API

    /** Open a persisted chat with a known id (selects it when already open); restores its transcript. */
    public ChatSessionComponent openExistingChat(ChatSessionId id) {
        ChatSessionComponent existing = sessionsById.get(id);
        if (existing != null) {
            selectSession(id);
            return existing;
        }
        return addChat(id, factory.create(id));
    }

    /** Create a brand-new chat and bring it to the foreground. */
    public ChatSessionComponent openNewChat() {
        ChatSessionId id = ChatSessionId.create();
        return addChat(id, factory.create(id));
    }

    /**
     * THE one way a chat enters the workspace. Restored at startup or opened at runtime, the steps must be
     * identical — the two used to be separate copies of the same four lines, which is exactly the kind of
     * duplication where the two paths silently drift apart.
     */
    private ChatSessionComponent addChat(ChatSessionId id, ChatSessionComponent session) {
        sessionsById.put(id, session);
        if (session instanceof OllamaChatPanel) {
            final OllamaChatPanel chat = (OllamaChatPanel) session;
            chat.setSettingsOpener(category -> openSettings(chat, category));
        }
        cards.add(session.getComponent(), id.toString());
        selectSession(id);
        fireTabSetChanged();
        return session;
    }

    /** Bring the chat with this id to the foreground (no-op for unknown ids). */
    public void selectSession(ChatSessionId id) {
        ChatSessionComponent session = sessionsById.get(id);
        if (session == null) {
            return;
        }
        cardLayout.show(cards, id.toString());
        activeId = id;
        fireActiveSessionChanged();
        if (sidebar.isVisible()) {
            refreshChatList(); // update the "current" marker
        }
    }

    /** Close the chat with this id: abort its work, remove its card, and never leave the workspace empty. */
    public void closeSession(ChatSessionId id) {
        ChatSessionComponent session = sessionsById.remove(id);
        if (session == null) {
            return;
        }
        try {
            session.disposeSession();
        } finally {
            cards.remove(session.getComponent());
        }
        if (sessionsById.isEmpty()) {
            openNewChat(); // fires the active-session + tab-set change for the fresh replacement itself
        } else {
            if (id.equals(activeId)) {
                // Fall back to the most recently opened remaining chat.
                ChatSessionId last = null;
                for (ChatSessionId open : sessionsById.keySet()) {
                    last = open;
                }
                selectSession(last);
            }
            fireTabSetChanged(); // persist "this chat is closed" immediately
            if (sidebar.isVisible()) {
                refreshChatList();
            }
        }
    }

    /**
     * Register the active-session listener; fires immediately with the current selection so the host can
     * restore that chat's mode/agent synchronously. Switching, opening or closing chats re-fires it.
     */
    public void setActiveSessionListener(ActiveSessionListener listener) {
        this.activeSessionListener = listener;
        fireActiveSessionChanged();
    }

    /**
     * Register the open-set listener; fires immediately with the currently open ids so the host can
     * persist the restored set. Every later open/close re-fires it so "closed stays closed".
     */
    public void setTabSetListener(TabSetListener listener) {
        this.tabSetListener = listener;
        fireTabSetChanged();
    }

    /** @return the currently selected chat session, or null if none. */
    public ChatSessionComponent activeSession() {
        return activeId == null ? null : sessionsById.get(activeId);
    }

    /** @return a snapshot of all open sessions (e.g. for a global catalog refresh or shutdown). */
    public List<ChatSessionComponent> sessions() {
        return new ArrayList<ChatSessionComponent>(sessionsById.values());
    }

    /** Plugin seam: contributions appear as additional sidebar tabs whenever the drawer opens. */
    public void setSidebarTabContributions(
            java.util.function.Supplier<List<ChatSidebarTab>> source) {
        this.sidebarTabsSource = source;
    }

    /**
     * Bind the application state so the burger's PINNED (latched) drawer survives a restart. Restores the
     * pinned drawer immediately when the last session left it pinned. Safe to call once after construction.
     */
    public void setApplicationState(com.aresstack.askai.state.ApplicationStateService applicationState) {
        this.applicationState = applicationState;
        if (applicationState != null) {
            // Restore the user's drawer width BEFORE a pinned drawer reopens below.
            int width = parseWidth(applicationState.get(STATE_SIDEBAR_WIDTH, null));
            if (width > 0) {
                sidebarSplit.setPreferredLeftWidth(width);
            }
        }
        if (applicationState != null && applicationState.getBoolean(STATE_BURGER_PINNED, false)) {
            menuLocked = true;
            ChatComposerPanel.setToolbarButtonLatched(burger, true);
            openMenuAndSidebar();
            refreshRibbonTabs();
        }
    }

    /** Persist the current pinned/latched state of the burger drawer (no-op before the state is bound). */
    private void persistBurgerPinned() {
        if (applicationState != null) {
            applicationState.putAndSave(STATE_BURGER_PINNED, Boolean.toString(menuLocked));
        }
    }

    /** Write the debounced drawer width to the application state (no-op before the state is bound). */
    private void saveSidebarWidthNow() {
        sidebarWidthSaveTimer.stop();
        if (applicationState != null && pendingSidebarWidth > 0) {
            applicationState.putAndSave(STATE_SIDEBAR_WIDTH, Integer.toString(pendingSidebarWidth));
        }
    }

    private static int parseWidth(String value) {
        if (value == null) {
            return -1;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException invalid) {
            return -1; // a corrupt persisted width falls back to the default
        }
    }

    /**
     * Re-read the chat list in place — used when a chat's PERSISTED state changed (a title appeared, a
     * message was written). Without this the open sidebar keeps showing "(new chat)" for a chat that has
     * long since been titled, e.g. one an agent created with an explicit title.
     */
    public void refreshChatTitles() {
        if (sidebar.isVisible()) {
            refreshChatList();
        }
    }

    /** Refresh the drawer's panes/ribbon in place (e.g. the active agent's tab set changed). */
    public void refreshSidebarTabs() {
        if (sidebar.isVisible()) {
            sidebar.rebuildTabs();
            refreshRibbonTabs();
        }
    }

    /**
     * Open the drawer LATCHED on the pane with this title (e.g. an /open artifact reveal): an
     * explicitly requested pane must not fold away on the next mouse move.
     */
    public void openSidebarTab(String title) {
        menuLocked = true;
        ChatComposerPanel.setToolbarButtonLatched(burger, true);
        openMenuAndSidebar();
        sidebar.showTab(title);
        refreshRibbonTabs();
        persistBurgerPinned();
    }

    /** Show the active agent's LEADING control right after the burger (replaces any previous). */
    public void setAgentLeadingToolbar(javax.swing.JComponent component) {
        agentLeadingSlot.removeAll();
        if (component != null) {
            agentLeadingSlot.add(component, BorderLayout.CENTER);
        }
        syncLeadingCompact();
        agentLeadingSlot.revalidate();
        agentLeadingSlot.repaint();
    }

    public void clearAgentLeadingToolbar() {
        setAgentLeadingToolbar(null);
    }

    /**
     * Tell the leading control whether the unfolded ribbon needs its REDUCED view (client-property
     * convention, see {@code AgentToolbarContribution.COMPACT_MODE_PROPERTY}) — set on the hosted
     * component and its direct children, mirroring the transcript-inset convention.
     */
    private void syncLeadingCompact() {
        Boolean compact = Boolean.valueOf(ribbon.isOpen());
        for (java.awt.Component hosted : agentLeadingSlot.getComponents()) {
            if (hosted instanceof javax.swing.JComponent) {
                ((javax.swing.JComponent) hosted).putClientProperty(
                        com.aresstack.askai.plugin.api.agent.toolbar.AgentToolbarContribution
                                .COMPACT_MODE_PROPERTY, compact);
                for (java.awt.Component child : ((java.awt.Container) hosted).getComponents()) {
                    if (child instanceof javax.swing.JComponent) {
                        ((javax.swing.JComponent) child).putClientProperty(
                                com.aresstack.askai.plugin.api.agent.toolbar
                                        .AgentToolbarContribution.COMPACT_MODE_PROPERTY, compact);
                    }
                }
            }
        }
    }

    /** A plugin's hamburger-replacement glyph (null = plain hamburger); hover always shows ☰. */
    public void setMenuOverrideIcon(javax.swing.Icon icon) {
        ChatComposerPanel.setSidebarToggleOverrideIcon(burger, icon);
    }

    /** Bind the shared mode controller so the footer's mode pill can render and refresh. */
    public void setWorkspaceModeController(
            com.aresstack.askai.plugin.host.WorkspaceModeController controller) {
        this.modeController = controller;
        if (controller != null) {
            controller.addChangeListener(this::refreshModePill);
        }
        refreshModePill();
    }

    /** The footer pill mirrors the ACTIVE tab's mode label (Yapping / agent / Partying). */
    private void refreshModePill() {
        ChatSessionComponent active = activeSession();
        modePill.setText(active instanceof OllamaChatPanel
                ? ((OllamaChatPanel) active).currentModeLabel() : "Yapping");
        modePill.revalidate();
        modePill.repaint();
    }

    /** Show the active agent's control in the Chats-pane footer (replaces any previous one). */
    public void setAgentFooterToolbar(javax.swing.JComponent component) {
        agentFooterToolbarSlot.removeAll();
        if (component != null) {
            agentFooterToolbarSlot.add(component, BorderLayout.CENTER);
        }
        agentFooterToolbarSlot.revalidate();
        agentFooterToolbarSlot.repaint();
    }

    public void clearAgentFooterToolbar() {
        setAgentFooterToolbar(null);
    }

    /** Show the active agent's TRAILING toolbar controls far right (replaces any previous ones). */
    public void setAgentToolbar(javax.swing.JComponent component) {
        agentToolbarSlot.removeAll();
        if (component != null) {
            agentToolbarSlot.add(component, BorderLayout.CENTER);
        }
        agentToolbarSlot.revalidate();
        agentToolbarSlot.repaint();
    }

    public void clearAgentToolbar() {
        setAgentToolbar(null);
    }

    /** Show the active agent's CENTERED top-bar control (e.g. the research web-search tag). */
    public void setAgentCenterToolbar(javax.swing.JComponent component) {
        agentCenterSlot.removeAll();
        if (component != null) {
            agentCenterSlot.add(component); // GridBag default constraints → centered
        }
        agentCenterSlot.revalidate();
        agentCenterSlot.repaint();
    }

    public void clearAgentCenterToolbar() {
        setAgentCenterToolbar(null);
    }

    // ------------------------------------------------------------------ layout + sidebar behavior

    private void buildTopLevelLayout() {
        // Opening/closing is HOVER-only; a click LATCHES the ribbon instead: the burger stays
        // pressed-in until the next click, which releases and closes the menu.
        burger.addActionListener(event -> {
            if (menuLocked) {
                collapseMenuAndSidebar(); // also resets menuLocked and the pressed-in look
            } else {
                menuLocked = true;
                ChatComposerPanel.setToolbarButtonLatched(burger, true);
                openMenuAndSidebar();
            }
            persistBurgerPinned(); // remember the pin across restarts
        });
        burger.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent event) {
                if (!sidebar.isVisible() && !ribbon.isOpen()) {
                    openMenuAndSidebar(); // hovering the burger unfolds the menu + drawer
                }
            }
        });

        ribbon.setListener(title -> {
            if (!sidebar.isVisible()) {
                showSidebar();
            }
            sidebar.showTab(title);
            refreshRibbonTabs(); // re-emphasize the newly active entry
        });

        JPanel topBar = new JPanel(new BorderLayout(4, 0));
        topBar.setOpaque(false);
        topBar.setBorder(BorderFactory.createEmptyBorder(2, 4, 0, 4));
        // LEFT: burger + the ribbon, which now asks only for its unfolded share — so the centered
        // slot owns the middle and gets pushed right/squeezed while the ribbon unfolds.
        JPanel topLeft = new JPanel();
        topLeft.setLayout(new javax.swing.BoxLayout(topLeft, javax.swing.BoxLayout.X_AXIS));
        topLeft.setOpaque(false);
        topLeft.add(burger);
        // The MODE pill (Yapping / agent / Partying) is configured here and placed centered in the
        // Chats footer (between New chat and the trash).
        modePill.setFont(ResearchUiTypography.regular(12.5f));
        modePill.setToolTipText("Chat mode: Yapping, Questing (agent) or Partying");
        modePill.addActionListener(event -> {
            ChatSessionComponent active = activeSession();
            if (active instanceof OllamaChatPanel) {
                ((OllamaChatPanel) active).openModePopupAt(modePill);
            }
        });
        // The MODE pill lives in the Chats footer now (centered between New chat and the trash).
        topLeft.add(javax.swing.Box.createHorizontalStrut(6));
        // The LEADING agent slot stays as generic infrastructure (currently unused by research).
        agentLeadingSlot.setOpaque(false);
        topLeft.add(agentLeadingSlot);
        topLeft.add(ribbon);
        topBar.add(topLeft, BorderLayout.WEST);
        agentCenterSlot.setOpaque(false);
        topBar.add(agentCenterSlot, BorderLayout.CENTER);
        // The agent's TRAILING contributions (e.g. the visualize buttons) sit at the FAR right — the
        // old "+"/gear pair moved into the drawer's Chats pane. The workspace only hosts the slot;
        // components come from the plugin.
        agentToolbarSlot.setOpaque(false);
        topBar.add(agentToolbarSlot, BorderLayout.EAST);

        sidebar.setVisible(false);
        sidebar.setExtraTabsSupplier(() -> {
            List<ChatSidebarTab> tabs = new java.util.ArrayList<ChatSidebarTab>();
            if (sidebarTabsSource != null && sidebarTabsSource.get() != null) {
                tabs.addAll(sidebarTabsSource.get());
            }
            tabs.add(settingsTab); // always last: the general settings beside the agent's panes
            return tabs;
        });
        // No pane title in the header — the ribbon's colored entry already names the active pane.
        // The chat SEARCH BAR takes that spot; it filters live on every keystroke, no Enter needed.
        chatFilter.getTextField().getDocument().addDocumentListener(
                new javax.swing.event.DocumentListener() {
                    public void insertUpdate(javax.swing.event.DocumentEvent e) {
                        refreshChatList();
                    }

                    public void removeUpdate(javax.swing.event.DocumentEvent e) {
                        refreshChatList();
                    }

                    public void changedUpdate(javax.swing.event.DocumentEvent e) {
                        refreshChatList();
                    }
                });
        sidebar.setHeaderComponent(chatFilter);

        add(topBar, BorderLayout.NORTH);
        // Drawer and cards live in the comic split pane: while the drawer is collapsed there is no
        // divider at all, so this looks exactly like the old WEST/CENTER layout — but an open
        // drawer can be resized by mouse (issue #36).
        add(sidebarSplit, BorderLayout.CENTER);
    }

    private void refreshRibbonTabs() {
        // The Settings pane is reached through the gear only — it is never a ribbon entry.
        List<String> titles = new java.util.ArrayList<String>(sidebar.tabTitles());
        titles.remove(SETTINGS_TAB);
        ribbon.setTabs(titles, sidebar.activeTab());
    }

    private void openMenuAndSidebar() {
        showSidebar();
        ribbon.open();
        syncLeadingCompact(); // the leading control shrinks to its short label beside the tabs
    }

    private void collapseMenuAndSidebar() {
        sidebarCloseTimer.stop();
        menuLocked = false;
        ChatComposerPanel.setToolbarButtonLatched(burger, false);
        ribbon.close();
        syncLeadingCompact();
        hideSidebar();
        persistBurgerPinned();
    }

    private void showSidebar() {
        refreshChatList();
        refreshModePill(); // catches tab-local mode changes (Partying) without an own event
        sidebar.rebuildTabs(); // picks up freshly contributed panes
        refreshRibbonTabs();
        sidebar.setVisible(true);
        sidebarSplit.openLeft(); // brings back the divider at the remembered width
        updateMouseWatcher();
        lastBusySignature = busySignature();
        activityRefreshTimer.start();
        revalidate();
        repaint();
    }

    private void hideSidebar() {
        activityRefreshTimer.stop();
        sidebar.setVisible(false);
        sidebarSplit.collapseLeft(); // width 0, divider gone
        updateMouseWatcher();
        revalidate();
        repaint();
    }

    /**
     * While the drawer is open, the activity dots must not go stale: a quiet poll compares the
     * busy SET (from the sessions' authoritative runtime state) and rebuilds the list only when it
     * actually changed — so hover states never flicker on idle ticks.
     */
    private java.util.Set<ChatSessionId> lastBusySignature = java.util.Collections.emptySet();
    private final javax.swing.Timer activityRefreshTimer =
            new javax.swing.Timer(2500, event -> onActivityPollTick());

    private void onActivityPollTick() {
        if (!sidebar.isVisible()) {
            return;
        }
        java.util.Set<ChatSessionId> signature = busySignature();
        if (!signature.equals(lastBusySignature)) {
            lastBusySignature = signature;
            refreshChatList();
        }
    }

    private java.util.Set<ChatSessionId> busySignature() {
        java.util.Set<ChatSessionId> busy = new java.util.HashSet<ChatSessionId>();
        for (ChatSessionId id : sessionsById.keySet()) {
            if (isSessionBusy(id)) {
                busy.add(id);
            }
        }
        return busy;
    }

    /** Ribbon and drawer fold away TOGETHER after hover-out — unless latched by the burger click. */
    private void onPointerLeftSidebarArea() {
        if (!menuLocked) {
            ribbon.close();
            syncLeadingCompact();
            hideSidebar();
        }
        updateMouseWatcher();
    }

    /**
     * While the drawer or the unfolded menu is open, a global mouse watcher tracks the pointer: it
     * stays inside the burger ∪ ribbon ∪ drawer area → nothing happens; it leaves → a short delay
     * (bridging the burger→drawer transit) folds away whatever is neither pinned nor latched.
     */
    private void updateMouseWatcher() {
        boolean needed = sidebar.isVisible() || ribbon.isOpen();
        if (needed) {
            installSidebarMouseWatcher();
        } else {
            removeSidebarMouseWatcher();
        }
    }

    private void installSidebarMouseWatcher() {
        if (sidebarMouseWatcher != null) {
            return;
        }
        sidebarMouseWatcher = new AWTEventListener() {
            public void eventDispatched(AWTEvent event) {
                if (!(event instanceof MouseEvent) || !isShowing()) {
                    return;
                }
                if (menuLocked) {
                    sidebarCloseTimer.stop(); // latched: nothing auto-closes until the next click
                    return;
                }
                MouseEvent mouse = (MouseEvent) event;
                int id = mouse.getID();
                if (id != MouseEvent.MOUSE_MOVED && id != MouseEvent.MOUSE_ENTERED
                        && id != MouseEvent.MOUSE_DRAGGED) {
                    return;
                }
                Point onScreen = new Point(mouse.getXOnScreen(), mouse.getYOnScreen());
                boolean inside = screenBounds(burger, HOVER_MARGIN_PX).contains(onScreen)
                        || (ribbon.isOpen() && screenBounds(ribbon, HOVER_MARGIN_PX).contains(onScreen))
                        || (sidebar.isVisible() && screenBounds(sidebar, HOVER_MARGIN_PX).contains(onScreen));
                if (inside) {
                    sidebarCloseTimer.stop();
                } else if (!sidebarCloseTimer.isRunning()) {
                    sidebarCloseTimer.restart();
                }
            }
        };
        try {
            Toolkit.getDefaultToolkit().addAWTEventListener(sidebarMouseWatcher,
                    AWTEvent.MOUSE_MOTION_EVENT_MASK | AWTEvent.MOUSE_EVENT_MASK);
        } catch (SecurityException restricted) {
            sidebarMouseWatcher = null; // hover-close degrades gracefully; pin/close still work
        }
    }

    private void removeSidebarMouseWatcher() {
        if (sidebarMouseWatcher != null) {
            try {
                Toolkit.getDefaultToolkit().removeAWTEventListener(sidebarMouseWatcher);
            } catch (SecurityException ignore) {
            }
            sidebarMouseWatcher = null;
        }
    }

    private static Rectangle screenBounds(Component component, int margin) {
        if (!component.isShowing()) {
            return new Rectangle(0, 0, 0, 0);
        }
        Point location = component.getLocationOnScreen();
        return new Rectangle(location.x - margin, location.y - margin,
                component.getWidth() + 2 * margin, component.getHeight() + 2 * margin);
    }

    // ------------------------------------------------------------------ the "Chats" sidebar tab

    /**
     * The default sidebar tab REPLACES the old bottom tab strip: one list that merges the open
     * sessions (in workspace order) with the remaining saved history, plus New-chat on top and the
     * confirmed delete-all at the bottom.
     */
    private JComponent buildChatsSidebarTab() {
        chatListPanel = new JPanel();
        chatListPanel.setLayout(new javax.swing.BoxLayout(chatListPanel, javax.swing.BoxLayout.Y_AXIS));
        chatListPanel.setOpaque(false);
        JScrollPane scroll = new ComicScrollPane(chatListPanel,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        scroll.getVerticalScrollBar().setUnitIncrement(16);

        JPanel tab = new JPanel(new BorderLayout());
        tab.setOpaque(false);
        tab.add(scroll, BorderLayout.CENTER);
        tab.add(buildChatsFooter(), BorderLayout.SOUTH);
        return tab;
    }

    /**
     * The Chats-pane FOOTER: [new chat] [agent slot] ... [trash = delete all chats] in ONE row, pinned to the
     * bottom of the pane (it never scrolls with the list). The language control is the active
     * agent's SIDEBAR_FOOTER toolbar contribution; delete-all lost its own full-width row — it is a
     * small quiet control now that only turns red on hover (the confirmation logic is unchanged).
     * The settings gear moved to the application menu bar ({@link #openActiveChatSettings()}).
     */
    private JComponent buildChatsFooter() {
        JPanel footer = new JPanel(new BorderLayout(8, 0));
        footer.setOpaque(false);
        footer.setBorder(BorderFactory.createEmptyBorder(
                ResearchUiMetrics.FOOTER_PADDING_V, ResearchUiMetrics.FOOTER_PADDING_H,
                ResearchUiMetrics.FOOTER_PADDING_V, ResearchUiMetrics.FOOTER_PADDING_H));
        footer.setPreferredSize(new Dimension(10, ResearchUiMetrics.FOOTER_HEIGHT));

        agentFooterToolbarSlot.setOpaque(false);
        // Left group: the New-chat icon button (moved down from above the list, icon + tooltip only),
        // then the agent's footer contributions (if any); the MODE pill sits centered in this footer.
        ResearchIconButton newChat = new ResearchIconButton(
                ChatComposerPanel.createNewChatGlyphIcon(), "New chat");
        newChat.addActionListener(event -> {
            openNewChat();
            if (menuLocked) {
                refreshChatList();
            } else {
                collapseMenuAndSidebar();
            }
        });
        JPanel left = new JPanel();
        left.setLayout(new javax.swing.BoxLayout(left, javax.swing.BoxLayout.X_AXIS));
        left.setOpaque(false);
        left.add(newChat);
        left.add(javax.swing.Box.createHorizontalStrut(8));
        left.add(agentFooterToolbarSlot);
        footer.add(left, BorderLayout.WEST);

        // Delete-all: just a trash icon, right-aligned; quiet at rest, danger red only on hover — a
        // rarely used destructive action must not draw attention (the confirmation is unchanged).
        ResearchIconButton deleteAll = new ResearchIconButton(
                ChatComposerPanel.createTrashGlyphIcon(), "Delete all chats…");
        deleteAll.setHoverColors(ResearchUiPalette.DANGER_RED, ThemeColor.of("bubble.assistantForeground"));
        deleteAll.addActionListener(event -> deleteAllChats());
        footer.add(deleteAll, BorderLayout.EAST);
        // The chat MODE pill (Yapping / Research Agent / Partying), centered between New chat and the trash.
        JPanel modeWrap = new JPanel(new java.awt.GridBagLayout());
        modeWrap.setOpaque(false);
        modeWrap.add(modePill);
        footer.add(modeWrap, BorderLayout.CENTER);
        return footer;
    }

    /** Opens the ACTIVE chat's settings (drawer pane + main area); no-op without an active chat panel. */
    public void openActiveChatSettings() {
        ChatSessionComponent active = activeSession();
        if (active instanceof OllamaChatPanel) {
            openSettings((OllamaChatPanel) active, null);
        }
    }

    // ------------------------------------------------------------------ main area (deck)

    /** The drawer pane that holds the settings category list; its page shows in the main area. */
    public static final String SETTINGS_TAB = "Settings";
    private static final String MAIN_CHAT = "chat";
    private static final String MAIN_CONTENT = "content";
    private final CardLayout mainLayout = new CardLayout();
    private final JPanel mainDeck = new JPanel(mainLayout);
    private final JPanel mainContentSlot = new JPanel(new BorderLayout());
    /** The main-area content each drawer pane owns (pane title -> content). */
    private final Map<String, JComponent> mainContentByTab = new LinkedHashMap<String, JComponent>();
    private final JPanel settingsPane = new JPanel(new BorderLayout());
    private final ChatSidebarTab settingsTab = new ChatSidebarTab() {
        public String getTitle() {
            return SETTINGS_TAB;
        }

        public JComponent getComponent() {
            return settingsPane;
        }
    };

    /**
     * Put {@code content} into the main area while the drawer pane {@code tabTitle} is active ({@code null}
     * withdraws it). The main area always follows the active pane: its content, or the chat.
     */
    public void setMainContent(String tabTitle, JComponent content) {
        if (tabTitle == null) {
            return;
        }
        if (content == null) {
            mainContentByTab.remove(tabTitle);
        } else {
            mainContentByTab.put(tabTitle, content);
        }
        updateMainArea();
    }

    /** The drawer's pane titles (tests). */
    List<String> sidebarTabTitlesForTest() {
        sidebar.rebuildTabs();
        return sidebar.tabTitles();
    }

    /** The ribbon's entries (tests). */
    List<String> ribbonTitlesForTest() {
        refreshRibbonTabs();
        return ribbon.titles();
    }

    /** Whether the main area currently shows a pane's content instead of the chat (tests). */
    boolean isMainContentShowing() {
        return mainContentSlot.isVisible() && mainContentSlot.getComponentCount() > 0
                && mainContentByTab.containsKey(sidebar.activeTab());
    }

    private void updateMainArea() {
        JComponent content = mainContentByTab.get(sidebar.activeTab());
        if (content == null) {
            mainLayout.show(mainDeck, MAIN_CHAT);
            return;
        }
        if (mainContentSlot.getComponentCount() != 1 || mainContentSlot.getComponent(0) != content) {
            refreshThemeIfStale(content);
            mainContentSlot.removeAll();
            mainContentSlot.add(content, BorderLayout.CENTER);
            mainContentSlot.revalidate();
        }
        mainLayout.show(mainDeck, MAIN_CONTENT);
        mainContentSlot.repaint();
    }

    /** Theme switches seen so far; content that was off-screen during one gets its delegates renewed. */
    private static volatile int themeGeneration;

    static {
        com.aresstack.comiccontrols.theme.AskAiTheme.addChangeListener(() -> themeGeneration++);
    }

    private static void refreshThemeIfStale(JComponent content) {
        Object seen = content.getClientProperty("askai.themeGeneration");
        if (seen != null && ((Integer) seen).intValue() != themeGeneration) {
            javax.swing.SwingUtilities.updateComponentTreeUI(content);
        }
        content.putClientProperty("askai.themeGeneration", Integer.valueOf(themeGeneration));
    }

    /**
     * The settings live in the workspace (no dialog): the category list in the drawer's Settings pane, the
     * selected page in the main area. {@code category} null keeps the current page.
     */
    private void fillSettingsFromActiveChat() {
        ChatSessionComponent active = activeSession();
        if (active instanceof OllamaChatPanel) {
            OllamaChatPanel chat = (OllamaChatPanel) active;
            renderSettingsRows(chat);
            mainContentByTab.put(SETTINGS_TAB, chat.settingsPagesComponent());
        }
    }

    private void openSettings(OllamaChatPanel chat, String category) {
        if (category != null) {
            chat.selectSettingsCategory(category);
        }
        renderSettingsRows(chat);
        setMainContent(SETTINGS_TAB, chat.settingsPagesComponent());
        refreshSidebarTabs();
        openSidebarTab(SETTINGS_TAB);
    }

    /**
     * The settings categories in the drawer's OWN design — the same rows and group headings as the chat
     * list (SETTINGS, then the active agent's pages); the selected category is the highlighted row.
     */
    private void renderSettingsRows(final OllamaChatPanel chat) {
        JPanel rows = new JPanel();
        rows.setLayout(new javax.swing.BoxLayout(rows, javax.swing.BoxLayout.Y_AXIS));
        rows.setOpaque(false);
        String selected = chat.selectedSettingsCategory();
        boolean agentHeading = false;
        rows.add(alignedLeft(groupHeader("SETTINGS")));
        for (final String category : chat.settingsCategories()) {
            if (!agentHeading && !OllamaChatPanel.isGeneralSettingsCategory(category)) {
                rows.add(alignedLeft(groupHeader("AGENT")));
                agentHeading = true;
            }
            rows.add(alignedLeft(new ChatHistoryRow(category, "", "", false, category.equals(selected), () -> {
                chat.selectSettingsCategory(category);
                renderSettingsRows(chat); // move the highlight
            }, null)));
        }
        JScrollPane scroll = new ComicScrollPane(rows,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        settingsPane.removeAll();
        settingsPane.setOpaque(false);
        settingsPane.add(scroll, BorderLayout.CENTER);
        settingsPane.add(buildSettingsFooter(), BorderLayout.SOUTH);
        settingsPane.revalidate();
        settingsPane.repaint();
    }

    /**
     * The Settings pane's FOOTER — the drawer's usual footer row (same height and padding as the Chats
     * footer) with the way back: "Back to chat" returns to the Chats pane and the chat.
     */
    private JComponent buildSettingsFooter() {
        JPanel footer = new JPanel(new BorderLayout(8, 0));
        footer.setOpaque(false);
        footer.setBorder(BorderFactory.createEmptyBorder(
                ResearchUiMetrics.FOOTER_PADDING_V, ResearchUiMetrics.FOOTER_PADDING_H,
                ResearchUiMetrics.FOOTER_PADDING_V, ResearchUiMetrics.FOOTER_PADDING_H));
        footer.setPreferredSize(new Dimension(10, ResearchUiMetrics.FOOTER_HEIGHT));
        com.aresstack.comiccontrols.control.ComicButton back = new com.aresstack.comiccontrols.control.ComicButton(
                "Back to chat", ChatComposerPanel.createBackGlyphIcon());
        back.setIconTextGap(8);
        back.setFocusable(false);
        back.setToolTipText("Leave the settings and return to the chat");
        back.addActionListener(event -> leaveSettings());
        JPanel backWrap = new JPanel(new java.awt.GridBagLayout());
        backWrap.setOpaque(false);
        backWrap.add(back);
        footer.add(backWrap, BorderLayout.WEST);
        return footer;
    }

    /** Back from the settings to the Chats pane (the main area shows the chat again). */
    void leaveSettings() {
        sidebar.showTab(sidebar.tabTitles().get(0)); // the default pane: Chats
        refreshRibbonTabs();
    }

    private static JComponent alignedLeft(JComponent component) {
        component.setAlignmentX(LEFT_ALIGNMENT);
        return component;
    }

    /** One chat-list entry: an open session ({@code openId != null}) and/or its persisted record. */
    private static final class ChatListEntry {
        final ChatSessionId openId;
        final ChatRecord record;

        ChatListEntry(ChatSessionId openId, ChatRecord record) {
            this.openId = openId;
            this.record = record;
        }
    }

    private static final long DAY_MS = 24L * 60L * 60L * 1000L;

    /**
     * Rebuild the list, grouped for the drawer's two-line history rows: AKTIV holds ONLY chats in
     * which processing is actually running right now (authoritative runtime state, see {@link
     * OllamaChatPanel#isProcessingBusy()}) — an idle open session is ordinary history. Then the
     * PROJECT groups (recency order of first appearance), then everything else by age — HEUTE,
     * GESTERN, LETZTE 7 TAGE, ÄLTER. Empty groups are simply absent (including AKTIV). The search
     * bar filters every section live by title and project name.
     */
    private void refreshChatList() {
        chatListPanel.removeAll();
        List<ChatRecord> saved = historyStore != null ? historyStore.list()
                : java.util.Collections.<ChatRecord>emptyList();
        Map<String, ChatRecord> savedById = new LinkedHashMap<String, ChatRecord>();
        for (ChatRecord record : saved) {
            savedById.put(record.getId(), record);
        }
        String filter = chatFilter.getText().trim().toLowerCase(java.util.Locale.ROOT);
        long startOfToday = startOfToday();

        List<ChatListEntry> entries = new ArrayList<ChatListEntry>();
        for (ChatSessionId id : sessionsById.keySet()) {
            ChatRecord record = savedById.remove(id.toString());
            if (record == null) {
                record = deletedChats.get(id.toString()); // deleted but open: keep title/time
            }
            entries.add(new ChatListEntry(id, record));
        }
        for (ChatRecord record : savedById.values()) {
            entries.add(new ChatListEntry(null, record));
        }
        // Soft-deleted CLOSED chats stay listed for this run (marked "Deleted", undo-able);
        // a restart drops them for good.
        for (Map.Entry<String, ChatRecord> deleted : deletedChats.entrySet()) {
            boolean open = false;
            for (ChatSessionId id : sessionsById.keySet()) {
                open |= id.toString().equals(deleted.getKey());
            }
            if (!open) {
                entries.add(new ChatListEntry(null, deleted.getValue()));
            }
        }

        Map<String, List<ChatListEntry>> byProject = new LinkedHashMap<String, List<ChatListEntry>>();
        List<ChatListEntry> active = new ArrayList<ChatListEntry>();
        List<ChatListEntry> today = new ArrayList<ChatListEntry>();
        List<ChatListEntry> yesterday = new ArrayList<ChatListEntry>();
        List<ChatListEntry> lastWeek = new ArrayList<ChatListEntry>();
        List<ChatListEntry> older = new ArrayList<ChatListEntry>();
        for (ChatListEntry entry : entries) {
            String project = entry.record == null ? null : entry.record.getProject();
            if (!matchesFilter(entry, project, filter)) {
                continue;
            }
            if (isSessionBusy(entry.openId)) {
                active.add(entry); // ACTUALLY working right now — busy wins over project/time
            } else if (project != null) {
                List<ChatListEntry> group = byProject.get(project);
                if (group == null) {
                    group = new ArrayList<ChatListEntry>();
                    byProject.put(project, group);
                }
                group.add(entry);
            } else {
                // Idle chats (open or not) are ordinary history, bucketed by their last change;
                // a freshly opened chat without a persisted record belongs to today.
                long at = entry.record == null ? startOfToday : entry.record.getModifiedAt();
                if (at >= startOfToday) {
                    today.add(entry);
                } else if (at >= startOfToday - DAY_MS) {
                    yesterday.add(entry);
                } else if (at >= startOfToday - 6 * DAY_MS) {
                    lastWeek.add(entry);
                } else {
                    older.add(entry);
                }
            }
        }

        addChatGroup("AKTIV", active, startOfToday);
        for (Map.Entry<String, List<ChatListEntry>> group : byProject.entrySet()) {
            chatListPanel.add(projectHeader(group.getKey()));
            for (ChatListEntry entry : group.getValue()) {
                chatListPanel.add(buildRow(entry.openId, entry.record, startOfToday));
            }
        }
        addChatGroup("HEUTE", today, startOfToday);
        addChatGroup("GESTERN", yesterday, startOfToday);
        addChatGroup("LETZTE 7 TAGE", lastWeek, startOfToday);
        addChatGroup("ÄLTER", older, startOfToday);
        if (chatListPanel.getComponentCount() == 0) {
            JLabel none = new JLabel(filter.isEmpty() ? "No chats" : "No matching chats");
            none.setEnabled(false);
            none.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
            chatListPanel.add(none);
        }
        chatListPanel.revalidate();
        chatListPanel.repaint();
    }

    /** One time/state group: quiet header + its rows; an empty group renders nothing at all. */
    private void addChatGroup(String title, List<ChatListEntry> group, long startOfToday) {
        if (group.isEmpty()) {
            return;
        }
        chatListPanel.add(groupHeader(title));
        for (ChatListEntry entry : group) {
            chatListPanel.add(buildRow(entry.openId, entry.record, startOfToday));
        }
    }

    /** Midnight of the local calendar day — the boundary for HEUTE/GESTERN/… bucketing. */
    private static long startOfToday() {
        java.util.Calendar calendar = java.util.Calendar.getInstance();
        calendar.set(java.util.Calendar.HOUR_OF_DAY, 0);
        calendar.set(java.util.Calendar.MINUTE, 0);
        calendar.set(java.util.Calendar.SECOND, 0);
        calendar.set(java.util.Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis();
    }

    private static boolean matchesFilter(ChatListEntry entry, String project, String filter) {
        if (filter.isEmpty()) {
            return true;
        }
        String title = rowTitle(entry.openId, entry.record).toLowerCase(java.util.Locale.ROOT);
        return title.contains(filter)
                || (project != null && project.toLowerCase(java.util.Locale.ROOT).contains(filter));
    }

    /** A project group's heading — the quiet group typography, petrol so projects stay distinct. */
    private JComponent projectHeader(String project) {
        JLabel header = new JLabel("▪ " + project);
        header.setFont(ResearchUiTypography.semiBold(11f));
        header.setForeground(ThemeColor.of("comic.agentPetrol")); // the design language's petrol role
        header.setBorder(BorderFactory.createEmptyBorder(14, 10, 4, 8));
        return header;
    }

    /** A time/state group heading (AKTIV, HEUTE, …): 11px Semi Bold, muted, extra top spacing. */
    private JComponent groupHeader(String title) {
        JLabel header = new JLabel(title);
        header.setFont(ResearchUiTypography.semiBold(11f));
        header.setForeground(com.aresstack.comiccontrols.theme.ResearchUiPalette.LIGHT_TEXT_MUTED);
        header.setBorder(BorderFactory.createEmptyBorder(14, 10, 4, 8));
        return header;
    }

    private static String rowTitle(ChatSessionId openId, ChatRecord record) {
        return record != null && record.getTitle() != null && !record.getTitle().trim().isEmpty()
                ? record.getTitle().trim()
                : (openId != null ? "(new chat)" : "(untitled)");
    }

    /**
     * One two-line history row ({@link ChatHistoryRow}): status dot, title, time, quiet metadata.
     * Clicking the row brings the chat to the foreground (opening it first when needed); ALL other
     * actions (close, delete, project) live behind the hover-only {@code …} / right-click menu —
     * the same existing functions the old inline ✕/🗑 buttons and the right-click menu called.
     */
    private JComponent buildRow(final ChatSessionId openId, final ChatRecord record,
                                long startOfToday) {
        final String chatId = openId != null ? openId.toString() : record.getId();
        final String title = rowTitle(openId, record);
        if (chatId.equals(renamingChatId)) {
            return buildRenameRow(openId, record, title);
        }
        Runnable open = () -> {
            try {
                ChatSessionId target = openId != null ? openId
                        : new ChatSessionId(java.util.UUID.fromString(chatId));
                openExistingChat(target); // selects when already open — the tab-switch replacement
            } catch (IllegalArgumentException ignored) {
                return;
            }
            if (!menuLocked) {
                collapseMenuAndSidebar();
            }
        };
        boolean busy = isSessionBusy(openId);
        final boolean deleted = deletedChats.containsKey(chatId);
        return new ChatHistoryRow(title,
                deleted ? "Deleted" : rowMeta(openId, record, busy, startOfToday),
                rowTime(record, startOfToday),
                busy, openId != null && openId.equals(activeId), deleted,
                deleted && openId == null ? null : open, // a deleted closed chat is not openable
                () -> buildRowMenu(openId, record, title),
                deleted ? () -> undoDelete(chatId) : null);
    }

    /** True when this chat's session ACTUALLY processes something right now (never "it exists"). */
    private boolean isSessionBusy(ChatSessionId openId) {
        ChatSessionComponent session = openId == null ? null : sessionsById.get(openId);
        return session instanceof OllamaChatPanel
                && ((OllamaChatPanel) session).isProcessingBusy();
    }

    /** The hover/right-click menu of one row — existing functions only, nothing new. */
    private javax.swing.JPopupMenu buildRowMenu(final ChatSessionId openId, final ChatRecord record,
                                                final String title) {
        javax.swing.JPopupMenu menu = new javax.swing.JPopupMenu();
        final String menuChatId = openId != null ? openId.toString()
                : record != null ? record.getId() : null;
        if (menuChatId != null && deletedChats.containsKey(menuChatId)) {
            // A deleted row offers exactly its way back — the arrow's twin for menu users.
            javax.swing.JMenuItem restore = new javax.swing.JMenuItem("Restore");
            restore.addActionListener(event -> undoDelete(menuChatId));
            menu.add(restore);
            if (openId != null) {
                javax.swing.JMenuItem close = new javax.swing.JMenuItem("Close this chat");
                close.addActionListener(event -> closeSession(openId));
                menu.add(close);
            }
            return menu;
        }
        // Rename morphs the ROW into an inline field (like the sky's + Add), no dialog.
        javax.swing.JMenuItem rename = new javax.swing.JMenuItem("Rename…");
        rename.addActionListener(event -> {
            renamingChatId = openId != null ? openId.toString() : record.getId();
            refreshChatList();
        });
        menu.add(rename);
        if (openId != null) {
            javax.swing.JMenuItem close = new javax.swing.JMenuItem("Close this chat");
            close.addActionListener(event -> closeSession(openId));
            menu.add(close);
        }
        if (record != null) {
            javax.swing.JMenuItem assign = new javax.swing.JMenuItem(
                    record.getProject() == null ? "Assign to project…" : "Move to project…");
            assign.addActionListener(event -> openAssignProjectDialog(openId, record));
            menu.add(assign);
            if (record.getProject() != null) {
                javax.swing.JMenuItem remove = new javax.swing.JMenuItem(
                        "Remove from \"" + record.getProject() + "\"");
                remove.addActionListener(event -> applyProject(openId, record, null));
                menu.add(remove);
            }
            javax.swing.JMenuItem delete = new javax.swing.JMenuItem("Delete");
            delete.setToolTipText("Soft delete — undo stays available until the app restarts");
            delete.addActionListener(event -> deletePersistedChat(record, title));
            menu.add(delete);
        }
        return menu;
    }

    /** The chat currently morphing into an inline rename field (its id), or {@code null}. */
    private String renamingChatId;

    /**
     * The rename "Verwandlung": the row becomes [field | comic ✕] — Enter applies, Escape or the
     * shared close chip (the same one the sky's add field uses) cancels. No dialog.
     */
    private JComponent buildRenameRow(final ChatSessionId openId, final ChatRecord record,
                                      String title) {
        final javax.swing.JTextField field = ComicControls.textField(title);
        field.setFont(ResearchUiTypography.regular(13f));
        field.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(ThemeColor.mix(
                        ResearchUiPalette.ACCENT_BLUE, ThemeColor.of("comic.fieldBg"), 0.5f)),
                BorderFactory.createEmptyBorder(4, 8, 4, 8)));
        final Runnable cancel = () -> {
            renamingChatId = null;
            refreshChatList();
        };
        field.addActionListener(event -> {
            String value = field.getText();
            renamingChatId = null;
            applyRename(openId, record, value);
        });
        field.addKeyListener(new java.awt.event.KeyAdapter() {
            @Override
            public void keyPressed(java.awt.event.KeyEvent event) {
                if (event.getKeyCode() == java.awt.event.KeyEvent.VK_ESCAPE) {
                    cancel.run();
                }
            }
        });
        JPanel row = new JPanel(new BorderLayout(4, 0));
        row.setOpaque(false);
        row.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        row.add(field, BorderLayout.CENTER);
        com.aresstack.comiccontrols.control.ComicOverlayPanel.CloseButton close =
                new com.aresstack.comiccontrols.control.ComicOverlayPanel.CloseButton(
                        com.aresstack.comiccontrols.theme.ComicPalette.defaultPalette(),
                        cancel);
        close.setToolTipText("Cancel");
        JPanel closeWrap = new JPanel(new java.awt.GridBagLayout());
        closeWrap.setOpaque(false);
        closeWrap.add(close);
        row.add(closeWrap, BorderLayout.EAST);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
        javax.swing.SwingUtilities.invokeLater(() -> {
            field.requestFocusInWindow();
            field.selectAll();
        });
        return row;
    }

    /**
     * Persist the new title. An OPEN chat owns its live record (the panel autosaves it), so the
     * change must go through the panel — exactly like {@link #applyProject}. Blank keeps the old
     * title (a rename to nothing is a cancel, not a deletion).
     */
    void applyRename(ChatSessionId openId, ChatRecord record, String newTitle) {
        String trimmed = newTitle == null ? "" : newTitle.trim();
        if (!trimmed.isEmpty()) {
            ChatSessionComponent session = openId == null ? null : sessionsById.get(openId);
            if (session instanceof OllamaChatPanel) {
                ((OllamaChatPanel) session).setChatTitle(trimmed);
            } else if (record != null && historyStore != null) {
                record.setTitle(trimmed);
                historyStore.save(record);
            }
        }
        refreshChatList();
    }

    /**
     * SOFT delete: the record leaves the store immediately, but the row stays visible for this
     * app run marked "Deleted" with an UNDO arrow — the undo replaces the old blocking confirm
     * dialog. A restart forgets deleted items for good (this map is memory-only on purpose).
     */
    private final Map<String, ChatRecord> deletedChats = new LinkedHashMap<String, ChatRecord>();

    void deletePersistedChat(ChatRecord record, String title) {
        if (historyStore == null || record == null) {
            return;
        }
        historyStore.delete(record.getId());
        detachOpenPanelFromDeletedChat(record.getId());
        deletedChats.put(record.getId(), record);
        refreshChatList();
    }

    /** Bring a soft-deleted chat back: persist the kept record and re-attach an open panel. */
    void undoDelete(String chatId) {
        ChatRecord record = deletedChats.remove(chatId);
        if (record == null || historyStore == null) {
            return;
        }
        historyStore.save(record);
        for (Map.Entry<ChatSessionId, ChatSessionComponent> entry : sessionsById.entrySet()) {
            if (entry.getKey().toString().equals(chatId)
                    && entry.getValue() instanceof OllamaChatPanel) {
                ((OllamaChatPanel) entry.getValue()).reattachToPersistedChat(record);
            }
        }
        refreshChatList();
    }

    /**
     * Line 2: what is actually known and useful — never a repetitive "Aktive Sitzung". Busy chats
     * say so ("Research Agent · arbeitet"); idle open chats show agent · phase when available;
     * everything else falls back to the quiet time metadata.
     */
    private String rowMeta(ChatSessionId openId, ChatRecord record, boolean busy,
                           long startOfToday) {
        ChatSessionComponent session = openId == null ? null : sessionsById.get(openId);
        if (session instanceof OllamaChatPanel) {
            OllamaChatPanel panel = (OllamaChatPanel) session;
            String agent = panel.describeActiveAgentForList();
            if (busy) {
                return agent == null || agent.trim().isEmpty() ? "arbeitet"
                        : agent + " · arbeitet";
            }
            String phase = titleCase(panel.describeAgentPhaseForList());
            if (agent != null && !agent.trim().isEmpty()) {
                return phase.isEmpty() ? agent : agent + " · " + phase;
            }
        }
        return savedMeta(record, startOfToday);
    }

    /** "SCOPING" → "Scoping" (the list speaks quietly, not in enum shouting). */
    private static String titleCase(String label) {
        if (label == null || label.trim().isEmpty()) {
            return "";
        }
        String trimmed = label.trim();
        return Character.toUpperCase(trimmed.charAt(0))
                + trimmed.substring(1).toLowerCase(java.util.Locale.ROOT);
    }

    /** Line 2 for a saved chat: Heute / Gestern / the date. */
    private static String savedMeta(ChatRecord record, long startOfToday) {
        if (record == null) {
            return "";
        }
        long at = record.getModifiedAt();
        if (at >= startOfToday) {
            return "Heute";
        }
        if (at >= startOfToday - DAY_MS) {
            return "Gestern";
        }
        return new java.text.SimpleDateFormat("dd.MM.yyyy").format(new java.util.Date(at));
    }

    /** The right-aligned time: HH:mm for today/yesterday (older rows carry the date in line 2). */
    private static String rowTime(ChatRecord record, long startOfToday) {
        if (record == null) {
            return "";
        }
        long at = record.getModifiedAt();
        if (at >= startOfToday - DAY_MS) {
            return new java.text.SimpleDateFormat("HH:mm").format(new java.util.Date(at));
        }
        return "";
    }

    private void openAssignProjectDialog(ChatSessionId openId, ChatRecord record) {
        java.util.Set<String> known = new java.util.TreeSet<String>();
        if (historyStore != null) {
            for (ChatRecord other : historyStore.list()) {
                if (other.getProject() != null) {
                    known.add(other.getProject());
                }
            }
        }
        javax.swing.JComboBox<String> combo =
                ComicControls.<String>comboBox(known.toArray(new String[0]));
        combo.setEditable(true); // free naming creates a new project on the spot
        combo.setSelectedItem(record.getProject() == null ? "" : record.getProject());
        int choice = JOptionPane.showConfirmDialog(this, combo, "Assign chat to project",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (choice == JOptionPane.OK_OPTION) {
            Object typed = combo.getEditor().getItem();
            applyProject(openId, record, typed == null ? null : typed.toString());
        }
    }

    /**
     * Persist the assignment. An OPEN chat owns its live record (the panel autosaves it), so the
     * change must go through the panel — a store-side write on a loaded copy would be overwritten.
     */
    private void applyProject(ChatSessionId openId, ChatRecord record, String project) {
        ChatSessionComponent session = openId == null ? null : sessionsById.get(openId);
        if (session instanceof OllamaChatPanel) {
            ((OllamaChatPanel) session).setChatProject(project);
        } else if (historyStore != null) {
            record.setProject(project);
            historyStore.save(record);
        }
        refreshChatList();
    }

    /**
     * Delete EVERY chat — only after an explicit confirmation. "All" means what the drawer shows: the
     * saved records AND the open chats (open-but-unsaved "(new chat)" rows included). Open chats are
     * closed; {@link #closeSession} leaves exactly one fresh, empty chat so the workspace is never empty.
     */
    private void deleteAllChats() {
        int count = deletableChatCount();
        if (count == 0) {
            return;
        }
        int choice = JOptionPane.showConfirmDialog(this,
                "Delete ALL " + count + " chats? Open chats are closed. This cannot be undone.",
                "Delete all chats", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (choice != JOptionPane.OK_OPTION) {
            return;
        }
        deleteAllChatsConfirmed();
    }

    /** Number of distinct chats the drawer lists (saved, open, soft-deleted) — the delete-all scope. */
    private int deletableChatCount() {
        java.util.Set<String> ids = new java.util.HashSet<String>();
        if (historyStore != null) {
            for (ChatRecord record : historyStore.list()) {
                ids.add(record.getId());
            }
        }
        for (ChatSessionId id : sessionsById.keySet()) {
            ids.add(id.toString());
        }
        ids.addAll(deletedChats.keySet());
        return ids.size();
    }

    /** The confirmed delete-all (package-private for tests: no dialog). */
    void deleteAllChatsConfirmed() {
        if (historyStore != null) {
            for (ChatRecord chat : historyStore.list()) {
                detachOpenPanelFromDeletedChat(chat.getId()); // before closing: no last autosave
                historyStore.delete(chat.getId());
            }
        }
        deletedChats.clear(); // soft-deleted rows are gone for good too, no undo left
        for (ChatSessionId id : new ArrayList<ChatSessionId>(sessionsById.keySet())) {
            ChatSessionComponent session = sessionsById.get(id);
            if (session instanceof OllamaChatPanel) {
                ((OllamaChatPanel) session).detachFromPersistedChat();
            }
            closeSession(id); // the last close opens one fresh chat
        }
        refreshChatList();
    }

    /** Stop an open chat panel from re-saving its just-deleted persisted record. */
    private void detachOpenPanelFromDeletedChat(String chatId) {
        for (Map.Entry<ChatSessionId, ChatSessionComponent> entry : sessionsById.entrySet()) {
            if (entry.getKey().toString().equals(chatId)
                    && entry.getValue() instanceof OllamaChatPanel) {
                ((OllamaChatPanel) entry.getValue()).detachFromPersistedChat();
            }
        }
    }

    // ------------------------------------------------------------------ listener plumbing

    private void fireTabSetChanged() {
        if (tabSetListener != null) {
            tabSetListener.tabSetChanged(new ArrayList<ChatSessionId>(sessionsById.keySet()));
        }
    }

    private void fireActiveSessionChanged() {
        refreshModePill(); // the footer pill always mirrors the newly active tab's mode
        if (activeSessionListener == null) {
            return;
        }
        ChatSessionComponent active = activeSession();
        ChatSessionId id = active == null ? null : active.getSessionId();
        if (id == null || id.equals(lastNotifiedSessionId)) {
            return;
        }
        lastNotifiedSessionId = id;
        activeSessionListener.activeSessionChanged(id);
    }

    // ------------------------------------------------------------------ test accessors (package-private)

    int openSessionCount() {
        return sessionsById.size();
    }

    boolean hasSession(ChatSessionId id) {
        return sessionsById.containsKey(id);
    }

    List<ChatSessionId> openSessionIds() {
        return new ArrayList<ChatSessionId>(sessionsById.keySet());
    }

    ComicSplitPane sidebarSplitForTest() {
        return sidebarSplit;
    }

    ChatSidebarPanel sidebarForTest() {
        return sidebar;
    }

    JButton burgerForTest() {
        return burger;
    }

    /** Runs the debounced width save immediately — tests must not wait on the timer. */
    void flushSidebarWidthSaveForTest() {
        saveSidebarWidthNow();
    }

    com.aresstack.comiccontrols.control.ComicSearchBar chatFilterForTest() {
        return chatFilter;
    }

    /** The visible chat-list texts in order (group/project headers, row titles) — for tests. */
    java.util.List<String> chatListEntriesForTest() {
        refreshChatList();
        java.util.List<String> texts = new ArrayList<String>();
        for (java.awt.Component component : chatListPanel.getComponents()) {
            if (component instanceof ChatHistoryRow) {
                texts.add(((ChatHistoryRow) component).titleForTest());
            } else if (component instanceof JLabel) {
                texts.add(((JLabel) component).getText());
            }
        }
        return texts;
    }
}
