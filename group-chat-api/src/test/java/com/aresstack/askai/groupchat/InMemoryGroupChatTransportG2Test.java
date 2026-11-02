package com.aresstack.askai.groupchat;

import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/**
 * Tests for the G2 feature parity of {@link InMemoryGroupChatTransport}: replicated color map,
 * bot claim fan-out and message deduplication.
 */
public final class InMemoryGroupChatTransportG2Test {

    private static final String ROOM_ID = "g2-room";

    private final GroupChatRoom room = new GroupChatRoom(ROOM_ID, "G2 Room", "secret");

    @After
    public void cleanUp() {
        InMemoryGroupChatTransport.clearRoom(ROOM_ID);
    }

    /** Listener recording color maps and bot claims. */
    private static final class RecordingListener implements GroupChatListener {
        final List<GroupChatMessage> messages = new ArrayList<GroupChatMessage>();
        final AtomicReference<ColorMap> colorMap = new AtomicReference<ColorMap>();
        final AtomicInteger colorMapChanges = new AtomicInteger();
        final AtomicReference<BotClaim> botClaim = new AtomicReference<BotClaim>();
        final AtomicReference<List<Participant>> participants = new AtomicReference<List<Participant>>();

        public void onMessage(GroupChatMessage message) { messages.add(message); }
        public void onParticipantJoined(Participant participant) {}
        public void onParticipantLeft(Participant participant) {}
        public void onParticipantsChanged(List<Participant> ps) { participants.set(ps); }
        public void onConnectionStateChanged(GroupChatConnectionState state) {}
        public void onColorMapChanged(ColorMap map) { this.colorMap.set(map); colorMapChanges.incrementAndGet(); }
        public void onBotClaim(BotClaim claim) { this.botClaim.set(claim); }
    }

    @Test
    public void colorMapIsRecomputedAndBroadcastOnJoinAndLeave() {
        InMemoryGroupChatTransport t1 = new InMemoryGroupChatTransport();
        InMemoryGroupChatTransport t2 = new InMemoryGroupChatTransport();
        RecordingListener l1 = new RecordingListener();
        RecordingListener l2 = new RecordingListener();

        t1.join(room, new Participant("alice", "Alice", null, "green"), l1);
        assertNotNull("Joiner must receive the initial color map", l1.colorMap.get());
        assertEquals("green", l1.colorMap.get().colorOf("alice"));
        assertEquals("green", t1.getColorMap().colorOf("alice"));

        t2.join(room, new Participant("bob", "Bob", null, null), l2);
        assertNotNull(l2.colorMap.get());
        assertEquals("Both peers see the same map version",
                l1.colorMap.get().getVersion(), l2.colorMap.get().getVersion());
        assertEquals("green", l2.colorMap.get().colorOf("alice"));
        assertNotNull(l2.colorMap.get().colorOf("bob"));

        long versionBeforeLeave = l1.colorMap.get().getVersion();
        t2.leave();
        assertTrue("Remaining peer must see a new map version after a leave",
                l1.colorMap.get().getVersion() > versionBeforeLeave);
        assertNull("Departed peer loses its assignment", l1.colorMap.get().colorOf("bob"));
        assertNotNull("Departed peer's color is leased",
                l1.colorMap.get().getLeases().get(l2.colorMap.get().colorOf("bob")));

        t1.leave();
    }

    @Test
    public void updateSelfBroadcastsParticipantsAndRecomputesColors() {
        InMemoryGroupChatTransport t1 = new InMemoryGroupChatTransport();
        InMemoryGroupChatTransport t2 = new InMemoryGroupChatTransport();
        RecordingListener l1 = new RecordingListener();
        RecordingListener l2 = new RecordingListener();

        t1.join(room, new Participant("alice", "Alice", null, null), l1);
        t2.join(room, new Participant("bob", "Bob", null, null), l2);

        t1.updateSelf(new Participant("alice", "Alicia", null, null, true, true));

        List<Participant> seenByBob = l2.participants.get();
        assertNotNull(seenByBob);
        boolean found = false;
        for (Participant p : seenByBob) {
            if ("alice".equals(p.getParticipantId())) {
                assertEquals("Alicia", p.getDisplayName());
                assertTrue(p.isBotReady());
                found = true;
            }
        }
        assertTrue("Bob must see Alice's updated profile", found);

        t1.leave();
        t2.leave();
    }

    @Test
    public void botClaimFansOutToAllListenersIncludingSender() {
        InMemoryGroupChatTransport t1 = new InMemoryGroupChatTransport();
        InMemoryGroupChatTransport t2 = new InMemoryGroupChatTransport();
        RecordingListener l1 = new RecordingListener();
        RecordingListener l2 = new RecordingListener();

        t1.join(room, new Participant("alice", "Alice", null, null, true, true), l1);
        t2.join(room, new Participant("bob", "Bob", null, null), l2);

        BotClaim claim = new BotClaim("c1", "m1", "alice,bob", "alice", System.currentTimeMillis());
        t1.publishBotClaim(claim);

        assertNotNull("Sender's own listener must receive the claim", l1.botClaim.get());
        assertNotNull("Other peers must receive the claim", l2.botClaim.get());
        assertEquals("c1", l2.botClaim.get().getClaimId());

        t1.leave();
        t2.leave();
    }

    @Test
    public void duplicateMessageIdsAreDroppedByTheBus() {
        InMemoryGroupChatTransport t1 = new InMemoryGroupChatTransport();
        InMemoryGroupChatTransport t2 = new InMemoryGroupChatTransport();
        RecordingListener l1 = new RecordingListener();
        RecordingListener l2 = new RecordingListener();

        t1.join(room, new Participant("alice", "Alice", null, null), l1);
        t2.join(room, new Participant("bob", "Bob", null, null), l2);

        GroupChatMessage message = new GroupChatMessage.Builder()
                .messageId("dup-1").roomId(ROOM_ID).senderParticipantId("alice")
                .senderSequence(1).markdown("hello").build();
        t1.send(message);
        t1.send(message); // duplicate delivery

        assertEquals(1, l1.messages.size());
        assertEquals(1, l2.messages.size());
        assertEquals("dup-1", l2.messages.get(0).getMessageId());

        t1.leave();
        t2.leave();
    }
}
