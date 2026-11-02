package com.aresstack.askai.ui.chat;

import org.junit.Test;

import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * A chat opened while the workspace is ALREADY on screen must be laid out exactly like one that was
 * restored at startup.
 * <p>
 * The two differ only in timing: chats restored in the constructor are added before the window's first
 * validation, so that validation sizes them. A chat opened later is added to the card container afterwards —
 * if nothing validates then, its panel stays at zero size and everything inside it measures itself against
 * a fallback width instead of the real one.
 */
public class NewChatCardIsLaidOutTest {

    /** A minimal chat component: just something with a measurable size inside the card container. */
    private static final class StubChat extends JPanel implements ChatSessionComponent {
        private final ChatSessionId id;

        private StubChat(ChatSessionId id) {
            this.id = id;
        }

        public ChatSessionId getSessionId() {
            return id;
        }

        public Component getComponent() {
            return this;
        }

        public void disposeSession() {
        }
    }

    private static ChatWorkspacePanel workspace(List<ChatSessionId> restoreIds) {
        return new ChatWorkspacePanel(new ChatWorkspacePanel.ChatSessionFactory() {
            public ChatSessionComponent create(ChatSessionId id) {
                return new StubChat(id);
            }
        }, restoreIds, null);
    }

    @Test
    public void aChatOpenedAfterTheWorkspaceIsOnScreenGetsTheSameSizeAsARestoredOne() throws Exception {
        onEdt(new Runnable() {
            public void run() {
                ChatSessionId restoredId = ChatSessionId.create();
                ChatWorkspacePanel workspace = workspace(Arrays.asList(restoredId));
                workspace.setSize(1200, 800);
                layoutDeep(workspace);

                int restoredWidth = componentOf(workspace, restoredId).getWidth();
                assertTrue("the restored chat must have a real size to compare against (was "
                        + restoredWidth + ")", restoredWidth > 0);

                // Now open a chat the way the user does at runtime. Swing validates after a change, so the
                // test does too — otherwise it would only prove that nothing laid anything out.
                ChatSessionComponent opened = workspace.openNewChat();
                layoutDeep(workspace);

                assertTrue("a chat opened while the workspace is on screen must be laid out too — it was "
                                + opened.getComponent().getWidth() + "px wide while the restored one is "
                                + restoredWidth + "px",
                        opened.getComponent().getWidth() > 0);
                assertEquals("both chats live in the same card container and must get the same size",
                        restoredWidth, opened.getComponent().getWidth());
            }
        });
    }

    // ------------------------------------------------------------------ helpers

    private static Component componentOf(Container container, ChatSessionId id) {
        List<Component> found = new ArrayList<Component>();
        collectStubs(container, found);
        for (Component candidate : found) {
            if (((StubChat) candidate).getSessionId().equals(id)) {
                return candidate;
            }
        }
        throw new IllegalStateException("chat " + id + " is not in the workspace");
    }

    private static void collectStubs(Container container, List<Component> found) {
        for (Component child : container.getComponents()) {
            if (child instanceof StubChat) {
                found.add(child);
            } else if (child instanceof Container) {
                collectStubs((Container) child, found);
            }
        }
    }

    private static void layoutDeep(Container container) {
        container.doLayout();
        for (Component child : container.getComponents()) {
            if (child instanceof Container) {
                layoutDeep((Container) child);
            }
        }
    }

    private static void onEdt(Runnable body) throws Exception {
        if (java.awt.GraphicsEnvironment.isHeadless()) {
            return;
        }
        SwingUtilities.invokeAndWait(body);
    }
}
