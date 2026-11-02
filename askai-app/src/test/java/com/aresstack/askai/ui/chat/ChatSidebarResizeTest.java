package com.aresstack.askai.ui.chat;

import com.aresstack.askai.state.ApplicationStateService;
import com.aresstack.askai.ui.sidebar.ChatSidebarTab;
import com.aresstack.comiccontrols.control.ComicSplitPane;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The split pane conversion of the drawer (issue #36): the width is clamped, persisted and
 * restored; opening/closing/pinning and the plugin panes keep working exactly as before.
 */
public class ChatSidebarResizeTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private static ChatWorkspacePanel build() {
        return new ChatWorkspacePanel(new ChatWorkspacePanel.ChatSessionFactory() {
            public ChatSessionComponent create(ChatSessionId id) {
                return new FakeSession(id);
            }
        });
    }

    @Test
    public void sidebarWidthRespectsMinAndMaxBounds() throws Exception {
        onEdt(new Runnable() {
            public void run() {
                ChatWorkspacePanel workspace = build();
                workspace.openSidebarTab("Chats");
                ComicSplitPane split = workspace.sidebarSplitForTest();

                split.setDividerLocation(50);
                assertEquals(split.getMinLeftWidth(), split.getDividerLocation());

                split.setDividerLocation(5000);
                assertEquals(split.getMaxLeftWidth(), split.getDividerLocation());
            }
        });
    }

    @Test
    public void draggedWidthIsPersistedToTheApplicationState() throws Exception {
        final File stateFile = folder.newFile("application-state.json");
        onEdt(new Runnable() {
            public void run() {
                ChatWorkspacePanel workspace = build();
                workspace.setApplicationState(new ApplicationStateService(stateFile));
                workspace.openSidebarTab("Chats");

                workspace.sidebarSplitForTest().setDividerLocation(432);
                workspace.flushSidebarWidthSaveForTest();
            }
        });
        assertEquals("432", new ApplicationStateService(stateFile).get("chat.sidebar.width", null));
    }

    @Test
    public void persistedWidthIsRestoredWhenTheWorkspaceIsRebuilt() throws Exception {
        final File stateFile = folder.newFile("application-state.json");
        new ApplicationStateService(stateFile).putAndSave("chat.sidebar.width", "512");
        onEdt(new Runnable() {
            public void run() {
                ChatWorkspacePanel workspace = build(); // "next startup"
                workspace.setApplicationState(new ApplicationStateService(stateFile));
                workspace.openSidebarTab("Chats");
                assertEquals(512, workspace.sidebarSplitForTest().getDividerLocation());
            }
        });
    }

    @Test
    public void openCloseAndPinningStillWorkAfterTheSplitPaneConversion() throws Exception {
        onEdt(new Runnable() {
            public void run() {
                ChatWorkspacePanel workspace = build();
                ComicSplitPane split = workspace.sidebarSplitForTest();
                assertFalse("drawer starts closed", workspace.sidebarForTest().isVisible());
                assertEquals("no divider while closed", 0, split.getDividerSize());

                workspace.openSidebarTab("Chats"); // opens LATCHED
                assertTrue(workspace.sidebarForTest().isVisible());
                assertTrue("divider is back while open", split.getDividerSize() > 0);

                workspace.burgerForTest().doClick(); // releases the latch and closes
                assertFalse(workspace.sidebarForTest().isVisible());
                assertEquals(0, split.getDividerSize());
                assertEquals(0, split.getDividerLocation());

                workspace.burgerForTest().doClick(); // latches open again
                assertTrue(workspace.sidebarForTest().isVisible());
                assertTrue(split.getDividerSize() > 0);
            }
        });
    }

    @Test
    public void pluginPanesStaySelectableAndResizable() throws Exception {
        onEdt(new Runnable() {
            public void run() {
                ChatWorkspacePanel workspace = build();
                workspace.setSidebarTabContributions(
                        new java.util.function.Supplier<List<ChatSidebarTab>>() {
                            public List<ChatSidebarTab> get() {
                                return Collections.<ChatSidebarTab>singletonList(new ChatSidebarTab() {
                                    public String getTitle() {
                                        return "Sources";
                                    }

                                    public JComponent getComponent() {
                                        return new JPanel();
                                    }
                                });
                            }
                        });

                workspace.openSidebarTab("Sources");
                assertEquals("the contributed pane is active", "Sources",
                        workspace.sidebarForTest().activeTab());
                assertTrue("the default pane is still there",
                        workspace.sidebarForTest().tabTitles().contains("Chats"));

                workspace.sidebarSplitForTest().setDividerLocation(500);
                assertEquals("a plugin pane profits from the resize", 500,
                        workspace.sidebarSplitForTest().getDividerLocation());
            }
        });
    }

    // --- helpers ---

    private static final class FakeSession implements ChatSessionComponent {
        private final ChatSessionId id;
        private final Component component = new JPanel();

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
