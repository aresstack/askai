package com.aresstack.askai.groupchat;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.util.List;

import static org.junit.Assert.*;

public final class FileRoomHistoryLogTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private static GroupChatMessage message(String id, String sender, long seq, long createdAt) {
        return new GroupChatMessage.Builder()
                .messageId(id)
                .roomId("room-1")
                .senderParticipantId(sender)
                .senderSequence(seq)
                .createdAt(createdAt)
                .markdown("body " + id)
                .build();
    }

    @Test
    public void appendAndReadAllRoundTripAcrossReopen() {
        File dir = folder.getRoot();
        FileRoomHistoryLog log = new FileRoomHistoryLog(dir, "room-1");
        log.append(message("m2", "bob", 1, 200));
        log.append(message("m1", "alice", 1, 100));
        assertTrue(log.contains("m1"));
        log.close();

        FileRoomHistoryLog reopened = new FileRoomHistoryLog(dir, "room-1");
        List<GroupChatMessage> all = reopened.readAll();
        assertEquals(2, all.size());
        assertEquals("m1", all.get(0).getMessageId()); // HistoryMerger order by createdAt
        assertEquals("m2", all.get(1).getMessageId());
        assertTrue("contains() must survive reopen", reopened.contains("m2"));
        reopened.close();
    }

    @Test
    public void duplicateAppendIgnored() {
        FileRoomHistoryLog log = new FileRoomHistoryLog(folder.getRoot(), "room-1");
        log.append(message("m1", "alice", 1, 100));
        log.append(message("m1", "alice", 1, 100));
        assertEquals(1, log.readAll().size());
        log.close();
    }

    @Test
    public void readSinceFiltersByCreatedAt() {
        FileRoomHistoryLog log = new FileRoomHistoryLog(folder.getRoot(), "room-1");
        log.append(message("m1", "alice", 1, 100));
        log.append(message("m2", "alice", 2, 200));
        log.append(message("m3", "alice", 3, 300));
        List<GroupChatMessage> since = log.readSince(200);
        assertEquals(2, since.size());
        assertEquals("m2", since.get(0).getMessageId()); // inclusive bound
        assertEquals("m3", since.get(1).getMessageId());
        log.close();
    }

    @Test
    public void corruptTailBytesAreTolerated() throws Exception {
        File dir = folder.getRoot();
        FileRoomHistoryLog log = new FileRoomHistoryLog(dir, "room-1");
        log.append(message("m1", "alice", 1, 100));
        log.close();

        // Simulate a crash mid-write: a length prefix promising more bytes than exist.
        File file = new File(dir, "room-1.log");
        assertTrue(file.exists());
        FileOutputStream tail = new FileOutputStream(file, true);
        try {
            tail.write(new byte[]{0, 0, 0, 50, 1, 2, 3});
        } finally {
            tail.close();
        }

        FileRoomHistoryLog reopened = new FileRoomHistoryLog(dir, "room-1");
        List<GroupChatMessage> all = reopened.readAll();
        assertEquals("Corrupt tail must be skipped", 1, all.size());
        assertEquals("m1", all.get(0).getMessageId());

        // The log stays usable after the corrupt tail was detected.
        reopened.append(message("m2", "bob", 1, 200));
        assertTrue(reopened.contains("m2"));
        assertEquals("Appends after tail repair must be readable", 2, reopened.readAll().size());
        reopened.close();
    }

    @Test
    public void roomIdIsSanitizedForFileName() {
        FileRoomHistoryLog log = new FileRoomHistoryLog(folder.getRoot(), "room:1/evil id");
        log.append(message("m1", "alice", 1, 100));
        log.close();
        assertTrue(new File(folder.getRoot(), "room_1_evil_id.log").exists());
    }
}
