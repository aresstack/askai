package com.aresstack.askai.research.host;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

public class WorkerUserContextStateStoreTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void statesAreKeptPerChatOwnerOnlyAndCanBeForgotten() throws Exception {
        Path root = tmp.newFolder("worker-state").toPath();
        WorkerUserContextStateStore chatA = new WorkerUserContextStateStore(root, "chat-a");
        WorkerUserContextStateStore chatB = new WorkerUserContextStateStore(root, "chat-b");
        assertNotEquals(chatA.chatDirectory(), chatB.chatDirectory());
        assertNull(chatA.load("account"));

        chatA.save("account", "{\"cookies\":[1]}");
        chatA.save("account", "{\"cookies\":[2]}"); // replaced atomically
        assertEquals("{\"cookies\":[2]}", chatA.load("account"));
        assertNull("chat B never sees chat A's sign-in", chatB.load("account"));
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            assertEquals("rw-------", PosixFilePermissions.toString(
                    Files.getPosixFilePermissions(chatA.chatDirectory().resolve("account.json"))));
        }
        try (java.util.stream.Stream<Path> left = Files.list(chatA.chatDirectory())) {
            assertEquals("no temp files remain", 1L, left.count());
        }
        chatA.delete("account");
        assertNull(chatA.load("account"));
    }

    @Test
    public void keysCannotEscapeTheChatDirectory() throws Exception {
        WorkerUserContextStateStore store = new WorkerUserContextStateStore(tmp.newFolder().toPath(), "chat");
        for (String bad : new String[]{"../x", "a/b", "..", ".hidden", ""}) {
            try {
                store.save(bad, "x");
                fail("must reject " + bad);
            } catch (IllegalArgumentException expected) {
                // rejected
            }
        }
    }
}
