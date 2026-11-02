package com.aresstack.askai.groupchat;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public final class HistoryMergerTest {

    private static GroupChatMessage message(String id, String sender, long seq, long createdAt) {
        return new GroupChatMessage.Builder()
                .messageId(id)
                .roomId("room")
                .senderParticipantId(sender)
                .senderSequence(seq)
                .createdAt(createdAt)
                .markdown("body " + id)
                .build();
    }

    @Test
    public void mergeDeduplicatesByMessageIdFirstOccurrenceWins() {
        GroupChatMessage originalM1 = message("m1", "alice", 1, 100);
        GroupChatMessage duplicateM1 = new GroupChatMessage.Builder()
                .messageId("m1").roomId("room").senderParticipantId("alice")
                .senderSequence(1).createdAt(100).markdown("other body").build();
        GroupChatMessage m2 = message("m2", "bob", 1, 200);

        List<GroupChatMessage> merged = HistoryMerger.merge(
                Arrays.asList(originalM1, m2), Arrays.asList(duplicateM1));
        assertEquals(2, merged.size());
        assertSame("First occurrence must win", originalM1, merged.get(0));
        assertSame(m2, merged.get(1));
    }

    @Test
    public void orderSortsByCreatedAtThenSenderThenSequenceThenId() {
        GroupChatMessage late = message("m4", "alice", 9, 300);
        GroupChatMessage sameTimeSenderB = message("m3", "bob", 1, 100);
        GroupChatMessage sameTimeSenderASeq2 = message("m2", "alice", 2, 100);
        GroupChatMessage sameTimeSenderASeq1b = message("m1b", "alice", 1, 100);
        GroupChatMessage sameTimeSenderASeq1a = message("m1a", "alice", 1, 100);

        List<GroupChatMessage> sorted = HistoryMerger.sort(Arrays.asList(
                late, sameTimeSenderB, sameTimeSenderASeq2, sameTimeSenderASeq1b, sameTimeSenderASeq1a));

        assertEquals("m1a", sorted.get(0).getMessageId()); // equal ts/sender/seq → id order
        assertEquals("m1b", sorted.get(1).getMessageId());
        assertEquals("m2", sorted.get(2).getMessageId());  // equal ts/sender → sequence order
        assertEquals("m3", sorted.get(3).getMessageId());  // equal ts → sender order
        assertEquals("m4", sorted.get(4).getMessageId());  // createdAt order
    }

    @Test
    public void mergeToleratesNullAndEmptyInputs() {
        GroupChatMessage m1 = message("m1", "alice", 1, 100);
        assertEquals(1, HistoryMerger.merge(null, Collections.singletonList(m1)).size());
        assertTrue(HistoryMerger.merge(null, null).isEmpty());
        assertTrue(HistoryMerger.sort(null).isEmpty());
    }

    @Test
    public void orderIsADeterministicTotalOrder() {
        GroupChatMessage a = message("a", "alice", 1, 100);
        GroupChatMessage b = message("b", "alice", 1, 100);
        assertTrue(HistoryMerger.ORDER.compare(a, b) < 0);
        assertTrue(HistoryMerger.ORDER.compare(b, a) > 0);
        assertEquals(0, HistoryMerger.ORDER.compare(a, a));
    }
}
