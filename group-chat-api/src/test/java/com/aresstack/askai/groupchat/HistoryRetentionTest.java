package com.aresstack.askai.groupchat;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Retention (age/size caps) and clear behavior of {@link FileRoomHistoryLog}. */
public class HistoryRetentionTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private static GroupChatMessage message(String id, long createdAt) {
        return new GroupChatMessage.Builder()
                .messageId(id)
                .roomId("room")
                .senderParticipantId("p1")
                .senderSequence(1)
                .createdAt(createdAt)
                .markdown("body-" + id)
                .build();
    }

    @Test
    public void ageCapDropsOldRecordsOnReopen() {
        long now = 1_000_000_000_000L;
        java.io.File dir = folder.getRoot();
        FileRoomHistoryLog log = new FileRoomHistoryLog(dir, "room");
        log.append(message("old", now - 40L * 24 * 60 * 60 * 1000)); // 40 days old
        log.append(message("new", now - 1L * 24 * 60 * 60 * 1000));   // 1 day old
        log.close();

        // Reopen with a 30-day age cap; compaction must drop "old" and keep "new".
        HistoryRetentionPolicy policy = new HistoryRetentionPolicy(
                30L * 24 * 60 * 60 * 1000, 0L, HistoryRetentionPolicy.DEFAULT_MAX_RECORD_BYTES);
        List<GroupChatMessage> kept =
                FileRoomHistoryLog.applyRetention(readAll(dir), policy, now);
        assertEquals(1, kept.size());
        assertEquals("new", kept.get(0).getMessageId());
    }

    @Test
    public void sizeCapKeepsNewestWithinBudget() {
        List<GroupChatMessage> records = new ArrayList<GroupChatMessage>();
        for (int i = 0; i < 10; i++) {
            records.add(message("m" + i, 1000 + i));
        }
        long oneRecord = 4L + GroupChatWire.encodeMessage(records.get(0)).length;
        HistoryRetentionPolicy policy = new HistoryRetentionPolicy(0L, oneRecord * 3, 1024 * 1024);
        List<GroupChatMessage> kept = FileRoomHistoryLog.applyRetention(records, policy, 99999);
        assertTrue("keeps at most the size budget", kept.size() <= 3);
        assertEquals("keeps the newest", "m9", kept.get(kept.size() - 1).getMessageId());
    }

    @Test
    public void clearEmptiesTheLog() {
        java.io.File dir = folder.getRoot();
        FileRoomHistoryLog log = new FileRoomHistoryLog(dir, "room");
        log.append(message("a", 1000));
        log.append(message("b", 1001));
        assertTrue(log.contains("a"));
        log.clear();
        assertFalse(log.contains("a"));
        assertEquals(0, log.readAll().size());
        log.append(message("c", 1002));
        assertEquals(1, log.readAll().size());
        log.close();
    }

    @Test
    public void deleteLogRemovesFile() {
        java.io.File dir = folder.getRoot();
        FileRoomHistoryLog log = new FileRoomHistoryLog(dir, "room");
        log.append(message("a", 1000));
        log.close();
        assertTrue(FileRoomHistoryLog.deleteLog(dir, "room"));
        assertEquals(0, new FileRoomHistoryLog(dir, "room").readAll().size());
    }

    private static List<GroupChatMessage> readAll(java.io.File dir) {
        FileRoomHistoryLog log = new FileRoomHistoryLog(dir, "room");
        try {
            return log.readAll();
        } finally {
            log.close();
        }
    }
}
