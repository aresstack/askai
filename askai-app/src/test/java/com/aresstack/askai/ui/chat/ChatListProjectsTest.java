package com.aresstack.askai.ui.chat;

import com.aresstack.askai.history.ChatHistoryStore;
import com.aresstack.askai.history.ChatMessageRecord;
import com.aresstack.askai.history.ChatRecord;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.swing.SwingUtilities;
import java.awt.Component;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The chat list's project grouping and search (issue #36 line): chats carry an optional PROJECT,
 * project groups render at the TOP of the drawer list, and the header search bar filters every
 * section live by title and project name.
 */
public class ChatListProjectsTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private ChatHistoryStore store;

    private ChatWorkspacePanel build() throws Exception {
        store = new ChatHistoryStore(folder.newFolder("chats"));
        saveChat("Oldtimer Preise", "Autos");
        saveChat("Kaufberatung W124", "Autos");
        saveChat("Rezept Lasagne", null);
        return new ChatWorkspacePanel(new ChatWorkspacePanel.ChatSessionFactory() {
            public ChatSessionComponent create(ChatSessionId id) {
                return new FakeSession(id);
            }
        }, null, store);
    }

    private void saveChat(String title, String project) {
        ChatRecord record = new ChatRecord(UUID.randomUUID().toString(), System.currentTimeMillis());
        record.setTitle(title);
        record.setProject(project);
        record.getMessages().add(new ChatMessageRecord(null, ChatMessageRecord.ROLE_USER,
                "hi", System.currentTimeMillis(), null, null));
        store.save(record);
    }

    @Test
    public void projectPersistsThroughTheStoreRoundTrip() throws Exception {
        store = new ChatHistoryStore(folder.newFolder("roundtrip"));
        saveChat("Oldtimer Preise", "Autos");
        ChatRecord reloaded = store.list().get(0);
        assertEquals("Autos", reloaded.getProject());

        reloaded.setProject("   "); // blank clears the assignment
        assertEquals(null, reloaded.getProject());
    }

    @Test
    public void projectGroupsRenderBeforeTheTimeGroupedHistoryAndIdleChatsAreNotAktiv() throws Exception {
        final ChatWorkspacePanel workspace = build();
        onEdt(new Runnable() {
            public void run() {
                List<String> entries = workspace.chatListEntriesForTest();
                // The open FakeSession does no work — AKTIV holds only ACTUALLY busy chats, so
                // the group must be absent entirely (a merely existing session is not activity).
                assertFalse("idle sessions never form an AKTIV group",
                        indexContaining(entries, "AKTIV") >= 0);
                int projectHeader = indexContaining(entries, "Autos");
                int projectChat = indexContaining(entries, "Oldtimer Preise");
                int timeHeader = indexContaining(entries, "HEUTE"); // records were saved just now
                int looseChat = indexContaining(entries, "Rezept Lasagne");
                int openChat = indexContaining(entries, "(new chat)");
                assertTrue("the project heading exists", projectHeader >= 0);
                assertTrue("project chats sit under their heading, before the history groups",
                        projectHeader < projectChat && projectChat < timeHeader);
                assertTrue("unassigned saved chats land in their time group", timeHeader < looseChat);
                assertTrue("the idle open chat is ordinary history under HEUTE", timeHeader < openChat);
            }
        });
    }

    @Test
    public void theSearchBarFiltersByTitleAndProjectName() throws Exception {
        final ChatWorkspacePanel workspace = build();
        onEdt(new Runnable() {
            public void run() {
                workspace.chatFilterForTest().setText("lasagne");
                List<String> byTitle = workspace.chatListEntriesForTest();
                assertTrue(indexContaining(byTitle, "Rezept Lasagne") >= 0);
                assertFalse("non-matching project chats are hidden",
                        indexContaining(byTitle, "Oldtimer Preise") >= 0);

                workspace.chatFilterForTest().setText("autos");
                List<String> byProject = workspace.chatListEntriesForTest();
                assertTrue("the project NAME matches every chat in the project",
                        indexContaining(byProject, "Oldtimer Preise") >= 0
                                && indexContaining(byProject, "Kaufberatung W124") >= 0);
                assertFalse(indexContaining(byProject, "Rezept Lasagne") >= 0);

                workspace.chatFilterForTest().setText("zzz-nothing");
                assertTrue(indexContaining(workspace.chatListEntriesForTest(),
                        "No matching chats") >= 0);
            }
        });
    }

    @Test
    public void renamePersistsThroughTheStoreForSavedChats() throws Exception {
        final ChatWorkspacePanel workspace = build();
        onEdt(new Runnable() {
            public void run() {
                ChatRecord lasagne = null;
                for (ChatRecord record : store.list()) {
                    if ("Rezept Lasagne".equals(record.getTitle())) {
                        lasagne = record;
                    }
                }
                assertTrue(lasagne != null);
                workspace.applyRename(null, lasagne, "Lasagne Deluxe");
                assertTrue("the list shows the new title",
                        indexContaining(workspace.chatListEntriesForTest(), "Lasagne Deluxe") >= 0);
                assertFalse("the old title is gone",
                        indexContaining(workspace.chatListEntriesForTest(), "Rezept Lasagne") >= 0);
            }
        });
        boolean persisted = false;
        for (ChatRecord record : store.list()) {
            persisted |= "Lasagne Deluxe".equals(record.getTitle());
        }
        assertTrue("the rename reached the persistent store", persisted);
    }

    @Test
    public void softDeleteKeepsTheRowVisibleAndUndoRestoresTheStore() throws Exception {
        final ChatWorkspacePanel workspace = build();
        final String[] deletedId = new String[1];
        onEdt(new Runnable() {
            public void run() {
                ChatRecord lasagne = null;
                for (ChatRecord record : store.list()) {
                    if ("Rezept Lasagne".equals(record.getTitle())) {
                        lasagne = record;
                    }
                }
                deletedId[0] = lasagne.getId();
                workspace.deletePersistedChat(lasagne, lasagne.getTitle());
                assertTrue("the deleted row STAYS visible for this run",
                        indexContaining(workspace.chatListEntriesForTest(), "Rezept Lasagne") >= 0);
            }
        });
        boolean inStore = false;
        for (ChatRecord record : store.list()) {
            inStore |= "Rezept Lasagne".equals(record.getTitle());
        }
        assertFalse("…but it is really gone from the store", inStore);
        onEdt(new Runnable() {
            public void run() {
                workspace.undoDelete(deletedId[0]);
            }
        });
        boolean restored = false;
        for (ChatRecord record : store.list()) {
            restored |= "Rezept Lasagne".equals(record.getTitle());
        }
        assertTrue("undo brings the record back into the store", restored);
    }

    @Test
    public void aBlankRenameKeepsTheOldTitle() throws Exception {
        final ChatWorkspacePanel workspace = build();
        onEdt(new Runnable() {
            public void run() {
                ChatRecord lasagne = null;
                for (ChatRecord record : store.list()) {
                    if ("Rezept Lasagne".equals(record.getTitle())) {
                        lasagne = record;
                    }
                }
                workspace.applyRename(null, lasagne, "   ");
                assertTrue("blank rename = cancel, never a deletion",
                        indexContaining(workspace.chatListEntriesForTest(), "Rezept Lasagne") >= 0);
            }
        });
    }

    private static int indexContaining(List<String> entries, String needle) {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i) != null && entries.get(i).contains(needle)) {
                return i;
            }
        }
        return -1;
    }

    @Test
    public void deleteAllEmptiesTheDrawerIncludingOpenChats() throws Exception {
        final ChatWorkspacePanel workspace = build();
        onEdt(new Runnable() {
            public void run() {
                workspace.openNewChat();
                workspace.openNewChat(); // several open "(new chat)" rows, as in the bug report
                workspace.deleteAllChatsConfirmed();
                List<String> entries = workspace.chatListEntriesForTest();
                assertFalse(indexContaining(entries, "Oldtimer Preise") >= 0);
                assertFalse(indexContaining(entries, "Rezept Lasagne") >= 0);
                int newChats = 0;
                for (String entry : entries) {
                    newChats += entry.contains("(new chat)") ? 1 : 0;
                }
                assertEquals("only the one fresh replacement chat remains", 1, newChats);
                assertEquals(1, workspace.openSessionCount());
            }
        });
        assertTrue("the store is empty", store.list().isEmpty());
    }

    // --- helpers ---

    private static final class FakeSession implements ChatSessionComponent {
        private final ChatSessionId id;
        private final Component component = new javax.swing.JPanel();

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
