package com.aresstack.askai.ui.chat;

import org.junit.Test;

import javax.swing.SwingUtilities;
import java.awt.Component;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The workspace WITHOUT a tab strip: sessions are cards addressed by id; the sidebar list is the
 * tab-switch replacement. These tests cover the id-based session semantics.
 */
public class ChatWorkspacePanelTest {

    private final List<FakeSession> created = new ArrayList<FakeSession>();

    private ChatWorkspacePanel build() {
        created.clear();
        return new ChatWorkspacePanel(new ChatWorkspacePanel.ChatSessionFactory() {
            public ChatSessionComponent create(ChatSessionId id) {
                FakeSession session = new FakeSession(id);
                created.add(session);
                return session;
            }
        });
    }

    @Test
    public void startsWithExactlyOneOpenChat() throws Exception {
        onEdt(new Runnable() {
            public void run() {
                ChatWorkspacePanel workspace = build();
                assertEquals(1, workspace.openSessionCount());
                assertNotNull("an active chat exists from the start", workspace.activeSession());
            }
        });
    }

    @Test
    public void theTabSetListenerReportsOpenIdsOnEveryOpenAndClose() throws Exception {
        onEdt(new Runnable() {
            public void run() {
                ChatWorkspacePanel workspace = build();
                final List<List<ChatSessionId>> fires = new ArrayList<List<ChatSessionId>>();
                workspace.setTabSetListener(new ChatWorkspacePanel.TabSetListener() {
                    public void tabSetChanged(List<ChatSessionId> ids) {
                        fires.add(ids);
                    }
                });
                assertFalse("registering fires once with the current set", fires.isEmpty());
                assertEquals(1, fires.get(fires.size() - 1).size());

                ChatSessionComponent a = workspace.openNewChat();
                assertEquals("open reports the grown set", 2, fires.get(fires.size() - 1).size());
                assertTrue(fires.get(fires.size() - 1).contains(a.getSessionId()));

                workspace.closeSession(a.getSessionId());
                assertFalse("a closed chat is immediately dropped from the open set",
                        fires.get(fires.size() - 1).contains(a.getSessionId()));
            }
        });
    }

    @Test
    public void theNewChatIconButtonInTheChatsFooterOpensANewChat() throws Exception {
        onEdt(new Runnable() {
            public void run() {
                ChatWorkspacePanel workspace = build();
                List<com.aresstack.comiccontrols.control.ResearchIconButton> buttons =
                        new ArrayList<com.aresstack.comiccontrols.control.ResearchIconButton>();
                collect(workspace, buttons);
                com.aresstack.comiccontrols.control.ResearchIconButton newChat = null;
                for (com.aresstack.comiccontrols.control.ResearchIconButton button : buttons) {
                    if ("New chat".equals(button.getToolTipText())) {
                        newChat = button;
                    }
                }
                assertNotNull("an icon-only New chat button with tooltip exists", newChat);
                assertEquals("icon only, no label", "", newChat.getText() == null ? "" : newChat.getText());
                newChat.doClick();
                assertEquals(2, workspace.openSessionCount());
                boolean trash = false;
                for (com.aresstack.comiccontrols.control.ResearchIconButton button : buttons) {
                    trash |= "Delete all chats…".equals(button.getToolTipText());
                }
                assertTrue("delete-all is an icon-only trash button", trash);
            }
        });
    }

    @Test
    public void theMainAreaFollowsTheActiveDrawerPane() throws Exception {
        onEdt(new Runnable() {
            public void run() {
                ChatWorkspacePanel workspace = build();
                assertTrue("the Settings pane exists in the drawer",
                        workspace.sidebarTabTitlesForTest().contains(ChatWorkspacePanel.SETTINGS_TAB));
                assertFalse("but it is NO ribbon entry (reached through the gear only)",
                        workspace.ribbonTitlesForTest().contains(ChatWorkspacePanel.SETTINGS_TAB));
                javax.swing.JLabel page = new javax.swing.JLabel("page");
                workspace.setMainContent(ChatWorkspacePanel.SETTINGS_TAB, page);
                assertFalse("the Chats pane is active: the chat stays", workspace.isMainContentShowing());
                workspace.openSidebarTab(ChatWorkspacePanel.SETTINGS_TAB);
                assertTrue("the Settings pane owns the main area", workspace.isMainContentShowing());
                workspace.openSidebarTab("Chats");
                assertFalse("back on Chats: the chat shows again", workspace.isMainContentShowing());
                workspace.openSidebarTab(ChatWorkspacePanel.SETTINGS_TAB);
                workspace.setMainContent(ChatWorkspacePanel.SETTINGS_TAB, null);
                assertFalse("withdrawn content: the chat shows again", workspace.isMainContentShowing());
            }
        });
    }

    @Test
    public void backToChatLeavesTheSettings() throws Exception {
        onEdt(new Runnable() {
            public void run() {
                ChatWorkspacePanel workspace = build();
                workspace.setMainContent(ChatWorkspacePanel.SETTINGS_TAB, new javax.swing.JLabel("page"));
                workspace.openSidebarTab(ChatWorkspacePanel.SETTINGS_TAB);
                assertTrue(workspace.isMainContentShowing());
                workspace.leaveSettings();
                assertFalse("the chat is back", workspace.isMainContentShowing());
            }
        });
    }

    private static void collect(java.awt.Container root,
                                List<com.aresstack.comiccontrols.control.ResearchIconButton> into) {
        for (java.awt.Component child : root.getComponents()) {
            if (child instanceof com.aresstack.comiccontrols.control.ResearchIconButton) {
                into.add((com.aresstack.comiccontrols.control.ResearchIconButton) child);
            }
            if (child instanceof java.awt.Container) {
                collect((java.awt.Container) child, into);
            }
        }
    }

    @Test
    public void openingANewChatMakesItTheActiveOne() throws Exception {
        onEdt(new Runnable() {
            public void run() {
                ChatWorkspacePanel workspace = build();
                ChatSessionComponent second = workspace.openNewChat();
                List<ChatSessionId> ids = workspace.openSessionIds();
                assertEquals(2, ids.size());
                assertNotEquals(ids.get(0), ids.get(1));
                assertEquals("active session is the freshly opened one",
                        second.getSessionId(), workspace.activeSession().getSessionId());
            }
        });
    }

    @Test
    public void selectSessionBringsAnOpenChatToTheForeground() throws Exception {
        onEdt(new Runnable() {
            public void run() {
                ChatWorkspacePanel workspace = build();
                ChatSessionId first = workspace.openSessionIds().get(0);
                workspace.openNewChat();
                assertNotEquals(first, workspace.activeSession().getSessionId());

                workspace.selectSession(first);
                assertEquals(first, workspace.activeSession().getSessionId());
            }
        });
    }

    @Test
    public void openExistingChatSelectsAnAlreadyOpenSessionInsteadOfDuplicating() throws Exception {
        onEdt(new Runnable() {
            public void run() {
                ChatWorkspacePanel workspace = build();
                ChatSessionComponent first = workspace.activeSession();
                workspace.openNewChat();
                int before = workspace.openSessionCount();

                ChatSessionComponent reopened = workspace.openExistingChat(first.getSessionId());
                assertEquals("no duplicate session", before, workspace.openSessionCount());
                assertEquals(first.getSessionId(), reopened.getSessionId());
                assertEquals("it became the active chat",
                        first.getSessionId(), workspace.activeSession().getSessionId());
            }
        });
    }

    @Test
    public void closingRemovesExactlyThatSessionAndAbortsItsWork() throws Exception {
        onEdt(new Runnable() {
            public void run() {
                ChatWorkspacePanel workspace = build();
                ChatSessionComponent a = workspace.openNewChat(); // there is already an initial one
                ChatSessionComponent b = workspace.openNewChat();
                ChatSessionComponent c = workspace.openNewChat();

                workspace.closeSession(b.getSessionId());

                assertFalse(workspace.hasSession(b.getSessionId()));
                assertTrue(workspace.hasSession(a.getSessionId()));
                assertTrue(workspace.hasSession(c.getSessionId()));
                assertTrue("closed session was disposed", ((FakeSession) b).disposed);
                assertFalse("other sessions untouched", ((FakeSession) a).disposed);
                assertFalse(((FakeSession) c).disposed);
            }
        });
    }

    @Test
    public void closingTheActiveChatFallsBackToAnotherOpenOne() throws Exception {
        onEdt(new Runnable() {
            public void run() {
                ChatWorkspacePanel workspace = build();
                ChatSessionComponent second = workspace.openNewChat();
                assertEquals(second.getSessionId(), workspace.activeSession().getSessionId());

                workspace.closeSession(second.getSessionId());
                assertNotNull("a surviving chat inherited the selection", workspace.activeSession());
                assertNotEquals(second.getSessionId(), workspace.activeSession().getSessionId());
            }
        });
    }

    @Test
    public void closingTheLastChatOpensAFreshOne() throws Exception {
        onEdt(new Runnable() {
            public void run() {
                ChatWorkspacePanel workspace = build();
                for (ChatSessionId id : workspace.openSessionIds()) {
                    workspace.closeSession(id);
                }
                assertEquals("never empty", 1, workspace.openSessionCount());
                assertFalse("the new chat has a fresh id", workspace.hasSession(created.get(0).id));
            }
        });
    }

    @Test
    public void closeIsResolvedByIdNotByOrder() throws Exception {
        onEdt(new Runnable() {
            public void run() {
                ChatWorkspacePanel workspace = build();
                ChatSessionComponent a = workspace.openNewChat();
                ChatSessionComponent b = workspace.openNewChat();
                ChatSessionComponent c = workspace.openNewChat();
                workspace.closeSession(a.getSessionId());
                workspace.closeSession(c.getSessionId());
                assertTrue(workspace.hasSession(b.getSessionId()));
                assertFalse(workspace.hasSession(a.getSessionId()));
                assertFalse(workspace.hasSession(c.getSessionId()));
            }
        });
    }

    @Test
    public void theActiveSessionListenerFiresWithEachSelectionChange() throws Exception {
        onEdt(new Runnable() {
            public void run() {
                ChatWorkspacePanel workspace = build();
                final List<ChatSessionId> notified = new ArrayList<ChatSessionId>();
                workspace.setActiveSessionListener(new ChatWorkspacePanel.ActiveSessionListener() {
                    public void activeSessionChanged(ChatSessionId id) {
                        notified.add(id);
                    }
                });
                // Registering fires immediately with the current selection.
                assertEquals(1, notified.size());
                ChatSessionId first = notified.get(0);

                ChatSessionComponent second = workspace.openNewChat(); // selecting it fires again
                assertEquals(second.getSessionId(), notified.get(notified.size() - 1));

                workspace.selectSession(first); // back to the first chat
                assertEquals(first, notified.get(notified.size() - 1));
            }
        });
    }

    @Test
    public void closingTheLastTabNotifiesTheFreshReplacementId() throws Exception {
        onEdt(new Runnable() {
            public void run() {
                ChatWorkspacePanel workspace = build();
                final List<ChatSessionId> notified = new ArrayList<ChatSessionId>();
                workspace.setActiveSessionListener(new ChatWorkspacePanel.ActiveSessionListener() {
                    public void activeSessionChanged(ChatSessionId id) {
                        notified.add(id);
                    }
                });
                ChatSessionId original = workspace.openSessionIds().get(0);

                for (ChatSessionId id : workspace.openSessionIds()) {
                    workspace.closeSession(id); // closing the last one opens a fresh replacement
                }

                ChatSessionId replacement = workspace.openSessionIds().get(0);
                assertNotEquals("the replacement chat has a new id", original, replacement);
                assertEquals("the listener was told about the fresh chat",
                        replacement, notified.get(notified.size() - 1));
            }
        });
    }

    // --- helpers ---

    private static final class FakeSession implements ChatSessionComponent {
        private final ChatSessionId id;
        private final Component component = new javax.swing.JPanel();
        private boolean disposed;

        FakeSession(ChatSessionId id) {
            this.id = id;
        }

        public ChatSessionId getSessionId() {
            return id;
        }

        public Component getComponent() {
            return component;
        }

        public void disposeSession() {
            disposed = true;
        }
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
